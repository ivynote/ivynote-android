package app.ivynote.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialRegionsTest {
    @AfterTest fun reset() = OfficialRegions.set(OfficialRegions.defaults)

    private fun answer(body: String, status: Int = 200) = HttpTransport { HttpResponse(status, body.toByteArray()) }
    private fun s(v: String) = ServerAddress.parse(v)

    @Test fun builtInAddresses() {
        assertEquals(s("https://web.ivynote.net"), SyncRegion.CN.server)
        assertEquals(SyncRegion.GLOBAL, SyncRegion.of(s("https://web.ivynote.app/")))
        assertEquals(SyncRegion.CN, SyncRegion.of(s("HTTPS://WEB.IVYNOTE.NET")))
        assertNull(SyncRegion.of(s("https://notes.example.com")))
    }

    @Test fun readsTheAnswerKeepingBuiltInsForWhatItLeavesOut() {
        val r = OfficialRegions.parse("""{"regions":[{"id":"cn","url":"https://Temp.Example.com/","aliases":["https://old.example","nope"]},{"id":"mars","url":"https://mars.example"}]}""")!!
        assertEquals(OfficialRegions.Entry(s("https://temp.example.com"), listOf(s("https://old.example"), s("https://web.ivynote.net"))), r[SyncRegion.CN])
        assertEquals(OfficialRegions.Entry(s("https://web.ivynote.app")), r[SyncRegion.GLOBAL])
        assertNull(OfficialRegions.parse("""{"regions":[{"id":"cn","url":"http://insecure.example"}]}"""))
        assertNull(OfficialRegions.parse("""{"regions":[{"id":"cn","url":"https://x.example/path"}]}"""))
        assertNull(OfficialRegions.parse("""{"regions":[{"id":"cn","url":"https://x.example/?a=1"}]}"""))
        assertNull(OfficialRegions.parse("null"))
        assertNull(OfficialRegions.parse("""{"regions":"x"}"""))
        assertNull(OfficialRegions.parse("<html>"))
    }

    @Test fun usesTheWebsiteAndStillRecognisesBuiltInAndAliases() = runTest {
        val prefs = MemoryKeyValueStore()
        OfficialRegions.install(prefs)
        OfficialRegions.refresh(force = true, transport = answer("""{"regions":[{"id":"cn","url":"https://temp.example.com"},{"id":"global","url":"https://web.ivynote.app"}]}"""))
        assertEquals(s("https://temp.example.com"), SyncRegion.CN.server)
        assertEquals(SyncRegion.CN, SyncRegion.of(s("https://temp.example.com")))
        assertEquals(SyncRegion.CN, SyncRegion.of(s("https://web.ivynote.net")))
        assertTrue(prefs.getString(OfficialRegions.CACHE_KEY)!!.contains("temp.example.com"))

        // The next start begins from the kept answer.
        OfficialRegions.set(OfficialRegions.defaults)
        OfficialRegions.install(prefs)
        assertEquals(s("https://temp.example.com"), SyncRegion.CN.server)

        // Back to the filed domain: the temporary one, listed as an alias, stays official.
        OfficialRegions.refresh(force = true, transport = answer("""{"regions":[{"id":"cn","url":"https://web.ivynote.net","aliases":["https://temp.example.com"]}]}"""))
        assertEquals(s("https://web.ivynote.net"), SyncRegion.CN.server)
        assertEquals(SyncRegion.CN, SyncRegion.of(s("https://temp.example.com")))
    }

    @Test fun fallsBackToTheNextSiteThenKeepsWhatItHad() = runTest {
        val asked = mutableListOf<String>()
        val flaky = HttpTransport { req ->
            asked += req.url
            if (req.url.startsWith("https://ivynote.net")) throw IOException("offline")
            HttpResponse(200, """{"regions":[{"id":"cn","url":"https://cn.example"}]}""".toByteArray())
        }
        OfficialRegions.refresh(force = true, transport = flaky)
        assertEquals(listOf("https://ivynote.net/api/v1/regions", "https://ivynote.app/api/v1/regions"), asked)
        assertEquals(s("https://cn.example"), SyncRegion.CN.server)

        // Asked once per start after a good answer.
        OfficialRegions.refresh(transport = { error("not asked again") })

        OfficialRegions.refresh(force = true, transport = answer("""{"error":"x"}""", 500))
        assertEquals(s("https://cn.example"), SyncRegion.CN.server)

        OfficialRegions.set(OfficialRegions.defaults)
        OfficialRegions.refresh(force = true, transport = { throw IOException("offline") })
        assertEquals(s("https://web.ivynote.net"), SyncRegion.CN.server)
    }
}
