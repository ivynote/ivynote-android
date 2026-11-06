package app.ivynote.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The official region addresses in effect, as the desktop and web clients do
 * (`client/packages/core/src/regions.ts` in ivynote).
 *
 * The addresses are built into [SyncRegion], and the website can override
 * them: the client asks `GET /api/v1/regions` on ivynote.net, then on
 * ivynote.app, keeps the answer for next time, and falls back to the last
 * answer and then to the built-in addresses. This lets a region move to a
 * temporary domain (say, while an ICP filing is pending) and back without a
 * release. A region can also list aliases, other addresses of the same
 * service, so a library made on an earlier address is still that region.
 *
 * Answer: `{ "regions": [{ "id": "cn", "url": "https://…", "aliases": ["https://…"] }] }`.
 */
object OfficialRegions {
    data class Entry(val server: ServerAddress, val aliases: List<ServerAddress> = emptyList())

    /** Websites that publish the region addresses, asked in order. */
    val SITES = listOf("https://ivynote.net", "https://ivynote.app")

    const val CACHE_KEY = "ivy.regions"
    private const val FETCH_TIMEOUT_MS = 6_000L

    val defaults: Map<SyncRegion, Entry> get() = SyncRegion.entries.associateWith { Entry(it.defaultServer) }

    private val state = MutableStateFlow(defaults)

    /** The regions in effect; collect it to follow a late answer from the website. */
    val regions: StateFlow<Map<SyncRegion, Entry>> = state.asStateFlow()

    val current: Map<SyncRegion, Entry> get() = state.value

    @Volatile private var cache: KeyValueStore? = null
    @Volatile private var refreshed = false
    private val lock = Mutex()

    /** Uses [prefs] to keep the website's answer, and starts from the last one kept there. */
    fun install(prefs: KeyValueStore) {
        cache = prefs
        prefs.getString(CACHE_KEY)?.let(::parse)?.let { state.value = it }
    }

    /** Use these regions (tests). Also forgets that the website was asked. */
    fun set(regions: Map<SyncRegion, Entry>) {
        state.value = regions
        refreshed = false
    }

    fun regionOf(server: ServerAddress): SyncRegion? {
        for (list in listOf(current, defaults)) {
            list.entries.firstOrNull { (_, e) -> e.server == server || server in e.aliases }?.let { return it.key }
        }
        return null
    }

    /**
     * Asks the websites for the current addresses, once per app start after
     * a good answer ([force] asks again). Never throws: on failure the last
     * known addresses stay, and the next call asks again.
     */
    suspend fun refresh(
        force: Boolean = false,
        transport: HttpTransport = OkHttpTransport(),
        sites: List<String> = SITES,
    ): Map<SyncRegion, Entry> = lock.withLock {
        if (refreshed && !force) return@withLock current
        for (site in sites) {
            try {
                val res = withTimeout(FETCH_TIMEOUT_MS) {
                    transport.send(HttpRequest("GET", "$site/api/v1/regions", mapOf("Accept" to "application/json", "User-Agent" to IvyHttp.userAgent)))
                }
                if (res.status !in 200..299) continue
                val body = res.body.toString(Charsets.UTF_8)
                val parsed = parse(body) ?: continue
                cache?.putString(CACHE_KEY, body)
                state.value = parsed
                refreshed = true
                return@withLock parsed
            } catch (e: CancellationException) {
                // Our own timeout: try the next site. The caller's cancellation goes on up.
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) {
                // Offline, refused, not JSON: try the next site.
            }
        }
        current
    }

    /**
     * Reads the website's answer. Regions it leaves out, or gives no valid
     * address for, keep the built-in address; ids this client does not know
     * are ignored. Null when nothing is usable.
     */
    fun parse(body: String): Map<SyncRegion, Entry>? {
        val list = try {
            (IvyJson.parseToJsonElement(body) as? JsonObject)?.get("regions") as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return null
        var any = false
        val out = SyncRegion.entries.associateWith { region ->
            val item = list.firstOrNull { (it as? JsonObject)?.string("id") == region.id } as? JsonObject
            val server = cleanOrigin(item?.get("url")) ?: return@associateWith Entry(region.defaultServer)
            any = true
            val aliases = ((item?.get("aliases") as? JsonArray).orEmpty()).mapNotNull(::cleanOrigin)
                .filter { it != server }.distinct().toMutableList()
            // The built-in address always stays recognised.
            if (region.defaultServer != server && region.defaultServer !in aliases) aliases += region.defaultServer
            Entry(server, aliases)
        }
        return if (any) out else null
    }

    /** A bare https origin (no path, query, fragment or user), or null. */
    private fun cleanOrigin(v: JsonElement?): ServerAddress? {
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.encodedPath != "/" || url.query != null || url.fragment != null) return null
        return ServerAddress.parseOrNull(text)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
