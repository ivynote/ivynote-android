package app.ivynote.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatesTest {
    @Test fun versions() {
        assertTrue(AppUpdates.isNewer("0.1.1", "0.1.0"))
        assertTrue(AppUpdates.isNewer("v1.0.0", "0.9.9"))
        assertTrue(AppUpdates.isNewer("0.10.0", "0.9.0"))
        assertTrue(AppUpdates.isNewer("0.1.0", "0.1.0-debug"))
        assertTrue(AppUpdates.isNewer("0.1.0", "garbage"))
        assertFalse(AppUpdates.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppUpdates.isNewer("0.1.0-beta.1", "0.1.0"))
        assertFalse(AppUpdates.isNewer("0.1.0", "0.2.0"))
        assertFalse(AppUpdates.isNewer("1.2", "1.0.0"))
        assertNull(AppUpdates.parseVersion("1.2.3.4"))
    }

    private val answer = """{"current":"0.1.0","latest":"0.2.0","tag":"v0.2.0","update_available":true,
        "published_at":"2026-11-06T03:00:00Z","notes":"## New",
        "download":{"name":"ivynote-0.2.0.apk","platform":"android","arch":"universal","kind":"apk","size":4,
        "url":"https://ivynote.net/dl/android/v0.2.0/ivynote-0.2.0.apk","sha256":"ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"}}"""

    @Test fun readsTheAnswer() {
        val info = assertNotNull(AppUpdates.parseAnswer(answer, "0.1.0", "https://ivynote.net"))
        assertTrue(info.available)
        assertEquals("0.2.0", info.latest)
        assertEquals("## New", info.notes)
        assertEquals(UpdateAsset("ivynote-0.2.0.apk", 4, "https://ivynote.net/dl/android/v0.2.0/ivynote-0.2.0.apk", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"), info.asset)

        // Already on it: nothing to install.
        val same = assertNotNull(AppUpdates.parseAnswer(answer, "0.2.0", "s"))
        assertFalse(same.available)
        assertNull(same.asset)

        // No APK (the website has no Android build): the download page instead.
        assertNull(AppUpdates.parseAnswer(answer.replace(".apk", ".exe"), "0.1.0", "s")!!.asset)
        assertNull(AppUpdates.parseAnswer(answer.replace("https://ivynote.net/dl", "http://ivynote.net/dl"), "0.1.0", "s")!!.asset)
        assertNull(AppUpdates.parseAnswer(answer.replace("\"ivynote-0.2.0.apk\"", "\"../x.apk\""), "0.1.0", "s")!!.asset)

        assertNull(AppUpdates.parseAnswer("""{"latest":"soon"}""", "0.1.0", "s"))
        assertNull(AppUpdates.parseAnswer("<html>", "0.1.0", "s"))
    }

    @Test fun notes() {
        val md = "## What's Changed\r\n* Offline search by @someone in https://github.com/o/r/pull/3\n- **Faster** sync\n\n**Full Changelog**: https://github.com/o/r/compare/v0.1.0...v0.2.0"
        assertEquals(listOf("# What's Changed", "Offline search", "Faster sync"), AppUpdates.noteLines(md))
        assertEquals(1, AppUpdates.noteLines(md, max = 1).size)
    }

    @Test fun asksTheSitesInTurn() = runTest {
        val asked = mutableListOf<HttpRequest>()
        val flaky = HttpTransport { req ->
            asked += req
            if (req.url.startsWith("https://ivynote.net")) throw IOException("offline")
            HttpResponse(200, answer.toByteArray())
        }
        val info = AppUpdates.check("0.1.0", AppUpdates.Reason.STARTUP, "0b5f0c1e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", "arm64", flaky)
        assertEquals("https://ivynote.app", info.site)
        assertEquals(2, asked.size)
        assertEquals("https://ivynote.app/api/v1/update?platform=android&arch=arm64&version=0.1.0&kind=apk&reason=startup", asked[1].url)
        assertEquals("0b5f0c1e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", asked[1].headers[AppUpdates.INSTALL_HEADER])

        val anon = mutableListOf<HttpRequest>()
        AppUpdates.check("0.1.0", AppUpdates.Reason.MANUAL, null, transport = { anon += it; HttpResponse(200, answer.toByteArray()) })
        assertNull(anon.single().headers[AppUpdates.INSTALL_HEADER])

        val e = assertFailsWith<UpdateException> {
            AppUpdates.check("0.1.0", AppUpdates.Reason.MANUAL, null, transport = { HttpResponse(503, ByteArray(0)) })
        }
        assertEquals("ivynote.net: HTTP 503; ivynote.app: HTTP 503", e.message)
    }

    @Test fun downloadUrlsFallBackToTheOtherSites() {
        val a = UpdateAsset("x.apk", 1, "https://cdn.example/dl/android/v1.0.0/x.apk")
        assertEquals(
            listOf(
                "https://cdn.example/dl/android/v1.0.0/x.apk",
                "https://ivynote.app/dl/android/v1.0.0/x.apk",
                "https://ivynote.net/dl/android/v1.0.0/x.apk",
            ),
            AppUpdates.downloadUrls(a, "https://ivynote.app"),
        )
        assertEquals(listOf("https://elsewhere/x.apk"), AppUpdates.downloadUrls(a.copy(url = "https://elsewhere/x.apk"), "https://ivynote.net"))
    }

    @Test fun prefs() {
        var now = 1_000L
        val p = UpdatePrefs(MemoryKeyValueStore()) { now }
        assertFalse(p.muted("0.2.0"))
        p.snooze("0.2.0")
        assertTrue(p.muted("0.2.0"))
        assertFalse(p.muted("0.3.0"))
        now += UpdatePrefs.SNOOZE_MS + 1
        assertFalse(p.muted("0.2.0"))
        p.skip("0.2.0")
        assertTrue(p.muted("0.2.0"))

        val id = assertNotNull(p.installId())
        assertTrue(UpdatePrefs.validInstallId(id))
        assertEquals(id, p.installId())
        p.statsEnabled = false
        assertNull(p.installId())
        p.statsEnabled = true
        assertEquals(id, p.installId())
    }

    /** A file server that honours Range, optionally cutting the first answer short. */
    private class FileServer(val data: ByteArray, var cutAfter: Int = -1) : AutoCloseable {
        val ranges = mutableListOf<String?>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dl/") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                ranges += range
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val body = data.copyOfRange(from, data.size)
                if (from > 0) ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(if (from > 0) 206 else 200, body.size.toLong())
                ex.responseBody.use { out ->
                    if (cutAfter in 0 until body.size) {
                        out.write(body, 0, cutAfter)
                        cutAfter = -1
                        out.flush()
                        ex.close()
                        return@use
                    }
                    out.write(body)
                }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/dl/android/v1.0.0/x.apk"
        override fun close() = server.stop(0)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun downloadsResumesAndVerifies() = runTest {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val dir = Files.createTempDirectory("upd").toFile()
        FileServer(data, cutAfter = 100_000).use { srv ->
            val asset = UpdateAsset("x.apk", data.size.toLong(), srv.url, sha(data))
            val dest = File(dir, "x.apk")
            var last = 0L
            val got = AppUpdates.download(asset, "https://unused", dest, sites = emptyList()) { d, _ -> last = d }
            assertTrue(got.readBytes().contentEquals(data))
            assertEquals(data.size.toLong(), last)
            assertFalse(File(dir, "x.apk.part").exists())
            // The dropped first answer was resumed, not restarted.
            assertEquals(null, srv.ranges[0])
            assertTrue(srv.ranges.drop(1).any { it != null && it.startsWith("bytes=") && it != "bytes=0-" }, "ranges: ${srv.ranges}")

            // Already downloaded and verified: not fetched again.
            val before = srv.ranges.size
            AppUpdates.download(asset, "https://unused", dest, sites = emptyList())
            assertEquals(before, srv.ranges.size)

            // A wrong digest is never accepted.
            val bad = asset.copy(sha256 = "0".repeat(64))
            dest.delete()
            assertFailsWith<UpdateException> { AppUpdates.download(bad, "https://unused", dest, sites = emptyList()) }
            assertFalse(dest.exists())
        }
        dir.deleteRecursively()
    }
}
