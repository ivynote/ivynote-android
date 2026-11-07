package app.ivynote.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * New versions of this app, as the desktop client does it
 * (`client/desktop/src-tauri/src/updater.rs` in ivynote).
 *
 * The app asks the Ivynote website, never GitHub (slow from mainland China and
 * rate-limited); ivynote.net first, then ivynote.app:
 *
 * ```text
 * GET https://ivynote.net/api/v1/update?platform=android&arch=arm64&version=0.1.0&kind=apk&reason=startup
 * ```
 *
 * Each check says why it runs (startup, periodic, manual) and, unless the
 * person turned anonymous statistics off, carries the install id in the
 * `X-Ivynote-Install` header: a random UUID made on first start, not tied to
 * any account, so the website can count installs rather than requests.
 *
 * The answer names the APK, its size and, when GitHub reported one, its
 * SHA-256. After the person confirms, it is downloaded (resuming a partial
 * download, falling back to the other site) and checked against both before
 * the app hands it to the system installer.
 */
object AppUpdates {
    /** Websites that answer update checks, asked in order. */
    val SITES = OfficialRegions.SITES
    const val INSTALL_HEADER = "X-Ivynote-Install"
    private const val CHECK_TIMEOUT_MS = 12_000L
    private const val MAX_ANSWER = 1 shl 20

    // --- versions ---

    /** "1.2.3", "v1.2.3" or "1.2.3-beta.1" as numbers and a pre-release flag; null when not a version. */
    fun parseVersion(v: String): Pair<List<Long>, Boolean>? {
        val s = v.trim().trimStart('v', 'V')
        val cut = s.indexOfFirst { it == '-' || it == '+' }
        val core = if (cut >= 0) s.substring(0, cut) else s
        val pre = cut >= 0 && s[cut] == '-' && cut + 1 < s.length
        val parts = core.split('.')
        if (parts.size != 3) return null
        val nums = parts.map { p -> p.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return null }
        return nums to pre
    }

    /** True when [latest] is newer than [current]. A release is newer than its own pre-release. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return true
        for (i in 0 until 3) {
            if (l.first[i] != c.first[i]) return l.first[i] > c.first[i]
        }
        return c.second && !l.second
    }

    // --- check ---

    /** Asks the websites in turn; throws [UpdateException] with every site's error when none answers. */
    suspend fun check(
        current: String,
        reason: Reason,
        installId: String?,
        arch: String = "",
        transport: HttpTransport = OkHttpTransport(),
        sites: List<String> = SITES,
    ): UpdateInfo {
        val query = listOf(
            "platform" to "android",
            "arch" to arch,
            "version" to current,
            "kind" to "apk",
            "reason" to reason.wire,
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val headers = buildMap {
            put("Accept", "application/json")
            put("User-Agent", IvyHttp.userAgent)
            if (installId != null) put(INSTALL_HEADER, installId)
        }
        val errors = mutableListOf<String>()
        for (site in sites) {
            try {
                val res = withTimeout(CHECK_TIMEOUT_MS) { transport.send(HttpRequest("GET", "$site/api/v1/update?$query", headers)) }
                if (res.status !in 200..299) {
                    errors += "${host(site)}: HTTP ${res.status}"
                    continue
                }
                if (res.body.size > MAX_ANSWER) {
                    errors += "${host(site)}: answer too large"
                    continue
                }
                val info = parseAnswer(res.body.toString(Charsets.UTF_8), current, site)
                if (info == null) {
                    errors += "${host(site)}: bad answer"
                    continue
                }
                return info
            } catch (e: CancellationException) {
                // Our own timeout: try the next site. The caller's cancellation goes on up.
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                errors += "${host(site)}: timed out"
            } catch (e: IOException) {
                errors += "${host(site)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw UpdateException(errors.joinToString("; ").ifEmpty { "no website to ask" })
    }

    /** Reads the website's answer; null when it names no version. */
    fun parseAnswer(body: String, current: String, site: String): UpdateInfo? {
        val o = try {
            IvyJson.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val latest = o.str("latest") ?: return null
        if (parseVersion(latest) == null) return null
        val available = (o["update_available"] as? JsonPrimitive)?.booleanOrNull == true && isNewer(latest, current)
        val d = o["download"] as? JsonObject
        // Only an HTTPS APK named like a plain file.
        val asset = d?.let {
            val name = it.str("name") ?: return@let null
            val url = it.str("url") ?: return@let null
            val size = (it["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (!url.startsWith("https://") || !safeName(name) || !name.endsWith(".apk", ignoreCase = true)) return@let null
            UpdateAsset(name, size, url, it.str("sha256")?.lowercase()?.takeIf { s -> s.length == 64 && s.all { c -> c in "0123456789abcdef" } })
        }
        return UpdateInfo(
            current = current,
            latest = latest.trim().trimStart('v', 'V'),
            available = available,
            notes = o.str("notes").orEmpty(),
            publishedAt = o.str("published_at").orEmpty(),
            site = site,
            asset = asset?.takeIf { available },
        )
    }

    /**
     * GitHub release notes as plain lines for the dialog (as the website's
     * download page shows them): headings keep a leading "#", list markers,
     * bold and "by @someone in https://…" go, the changelog link is dropped.
     */
    fun noteLines(md: String, max: Int = 14): List<String> = md.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog**") }
        .map { l ->
            val heading = l.startsWith("#")
            val text = l.replace(Regex("^#+\\s*"), "").replace(Regex("^[*-]\\s+"), "")
                .replace(Regex("\\s+by @\\S+ in https://\\S+"), "").replace("**", "").trim()
            if (heading) "# $text" else text
        }
        .filter { it.isNotEmpty() && it != "#" }
        .take(max)
        .toList()

    // --- download ---

    /** The APK's URL, then the same /dl/ path on the other websites. */
    fun downloadUrls(asset: UpdateAsset, site: String, sites: List<String> = SITES): List<String> {
        val urls = mutableListOf(asset.url)
        val path = asset.url.toHttpUrlOrNull()?.encodedPath
        if (path != null && path.startsWith("/dl/")) {
            for (s in listOf(site) + sites) {
                val candidate = s.trimEnd('/') + path
                if (candidate !in urls) urls += candidate
            }
        }
        return urls
    }

    /**
     * Downloads [asset] to [dest] and verifies it, or returns at once when
     * [dest] is already the verified file. A `.part` file next to it keeps
     * what was received, so a dropped connection resumes; each URL is tried
     * twice. Cancel the coroutine to stop (the partial file stays).
     */
    suspend fun download(
        asset: UpdateAsset,
        site: String,
        dest: File,
        client: OkHttpClient = IvyHttp.client,
        sites: List<String> = SITES,
        progress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        if (verified(dest, asset)) return@withContext dest
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val errors = mutableListOf<String>()
        val urls = downloadUrls(asset, site, sites)
        for (url in urls + urls) {
            ensureActive()
            try {
                fetch(client, url, part, asset.size, progress)
                if (verified(part, asset)) {
                    dest.delete()
                    if (!part.renameTo(dest)) throw IOException("cannot save the download")
                    return@withContext dest
                }
                part.delete()
                errors += "checksum mismatch"
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        throw UpdateException(errors.distinct().joinToString("; "))
    }

    private suspend fun fetch(client: OkHttpClient, url: String, part: File, expected: Long, progress: (Long, Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        // Complete or longer than expected: start over rather than trust it.
        if (expected > 0 && have >= expected) have = 0
        val req = Request.Builder().url(url).header("User-Agent", IvyHttp.userAgent)
        if (have > 0) req.header("Range", "bytes=$have-")
        client.newCall(req.build()).await().use { res ->
            if (res.code == 416) {
                part.delete()
                throw IOException("HTTP 416")
            }
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val append = res.code == 206 && have > 0
            if (append) {
                // Only append what continues exactly where the file stops.
                val range = res.header("Content-Range").orEmpty()
                if (!range.startsWith("bytes $have-")) {
                    part.delete()
                    throw IOException("unexpected range: $range")
                }
            } else {
                have = 0
            }
            val body = res.body ?: throw IOException("empty answer")
            val total = if (expected > 0) expected else body.contentLength().takeIf { it >= 0 }?.plus(have) ?: 0L
            progress(have, total)
            var last = System.currentTimeMillis()
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (total > 0 && have > total) {
                            out.close()
                            part.delete()
                            throw IOException("download larger than announced")
                        }
                        val now = System.currentTimeMillis()
                        if (now - last >= 150) {
                            last = now
                            progress(have, total)
                        }
                    }
                }
            }
            progress(have, total)
            if (total > 0 && have != total) throw IOException("download incomplete ($have of $total bytes)")
        }
    }

    /** True when [file] has the announced size and, when one is known, digest. */
    fun verified(file: File, asset: UpdateAsset): Boolean {
        if (!file.isFile) return false
        if (asset.size > 0 && file.length() != asset.size) return false
        val want = asset.sha256 ?: return true
        return sha256(file) == want
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A plain file name: no path, no leading dot. */
    fun safeName(s: String): Boolean =
        s.isNotEmpty() && s.length <= 200 && !s.startsWith(".") && s.all { it.isLetterOrDigit() || it in "._-+" }

    private fun host(site: String) = site.removePrefix("https://").removePrefix("http://")

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Why a check runs. */
    enum class Reason(val wire: String) { STARTUP("startup"), PERIODIC("periodic"), MANUAL("manual") }
}

/** One APK offered by the website. */
data class UpdateAsset(val name: String, val size: Long, val url: String, val sha256: String? = null)

/** The website's answer to "is there something newer than [current]?". */
data class UpdateInfo(
    val current: String,
    val latest: String,
    val available: Boolean,
    val notes: String,
    val publishedAt: String,
    /** The website that answered (downloads fall back to the others). */
    val site: String,
    /** The APK to install; null when there is none for Android (then: the download page). */
    val asset: UpdateAsset?,
)

class UpdateException(message: String) : IOException(message)

/**
 * What the person chose about updates, kept on the device: a skipped version,
 * "later" (ask again tomorrow), anonymous statistics, and the install id.
 */
class UpdatePrefs(private val prefs: KeyValueStore, private val now: () -> Long = System::currentTimeMillis) {
    /** Automatic checks stay quiet about this version (a manual check still offers it). */
    fun skip(version: String) = prefs.putString(SKIP_KEY, version)

    /** Automatic checks wait a day before asking about this version again. */
    fun snooze(version: String) = prefs.putString(SNOOZE_KEY, "$version@${now() + SNOOZE_MS}")

    /** True when an automatic check should not interrupt for [version]. */
    fun muted(version: String): Boolean {
        if (prefs.getString(SKIP_KEY) == version) return true
        val (v, until) = prefs.getString(SNOOZE_KEY)?.split('@')?.takeIf { it.size == 2 } ?: return false
        return v == version && (until.toLongOrNull() ?: 0) > now()
    }

    /** Anonymous statistics: on unless the person turned them off. */
    var statsEnabled: Boolean
        get() = prefs.getString(STATS_KEY) != "off"
        set(on) {
            if (on) prefs.remove(STATS_KEY) else prefs.putString(STATS_KEY, "off")
        }

    /** The install id, made and kept the first time; null while statistics are off. */
    fun installId(): String? {
        if (!statsEnabled) return null
        prefs.getString(INSTALL_KEY)?.takeIf(::validInstallId)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.putString(INSTALL_KEY, id)
        return id
    }

    companion object {
        const val SKIP_KEY = "ivy.update.skip"
        const val SNOOZE_KEY = "ivy.update.snooze"
        const val STATS_KEY = "ivy.stats"
        const val INSTALL_KEY = "ivy.install"
        const val SNOOZE_MS = 24 * 3600_000L

        fun validInstallId(s: String) = s.length in 16..64 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
    }
}
