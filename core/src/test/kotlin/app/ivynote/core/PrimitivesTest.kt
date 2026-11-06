package app.ivynote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HLCTest {
    @Test fun formatsLikeTheServer() {
        assertEquals("1793503505537-0000-desk-1", HLC.format(1793503505537, 0, "desk-1"))
        assertEquals("0000000000005-00ff-n", HLC.format(5, 255, "n"))
    }

    @Test fun parsesNodesWithDashes() {
        val p = HLC.parse("1793503505537-00ff-ios-a-b")!!
        assertEquals(1793503505537, p.wall)
        assertEquals(255, p.counter)
        assertEquals("ios-a-b", p.node)
        assertNull(HLC.parse("garbage"))
    }

    @Test fun ticksMonotonically() {
        val c = HLC("n")
        val a = c.tick(1000); val b = c.tick(1000); val d = c.tick(999)
        assertTrue(a < b && b < d)
        assertEquals("0000000002000-0000-n", c.tick(2000))
    }

    @Test fun observeMovesForward() {
        val c = HLC("n")
        c.observe("0000000005000-0003-x")
        assertEquals("0000000005000-0004-n", c.tick(1000))
    }
}

class SseParseTest {
    @Test fun readsSeqEvents() {
        val p = SyncEvents.Parser()
        val seqs = listOf("retry: 3000", "event: seq", "data: 0", "", ": ping", "event: seq", "data: 12", "").mapNotNull(p::feed)
        assertEquals(listOf(0L, 12L), seqs)
    }

    @Test fun worksWithoutBlankLines() {
        val p = SyncEvents.Parser()
        assertEquals(listOf(3L, 4L), listOf("event: seq", "data: 3", "event: seq", "data: 4").mapNotNull(p::feed))
    }

    @Test fun ignoresOtherEvents() {
        val p = SyncEvents.Parser()
        assertTrue(listOf("event: other", "data: 9").mapNotNull(p::feed).isEmpty())
    }
}

class ModelsTest {
    @Test fun decodesServerNotesWithMissingZeroValues() {
        val n = IvyJson.decodeFromString(Note.serializer(), """{"id":"n1234567","title":"Hi","rev":2,"seq":5,"createdAt":1,"updatedAt":2,"extra":{"a":1},"tags":null}""")
        assertEquals("", n.body)
        assertTrue(n.tags.isEmpty())
        assertFalse(n.pinned)
        assertEquals(0, n.trashedAt)
        assertFalse(n.deleted)
    }

    @Test fun decodesPullWithFoldersSortedByIntegers() {
        val r = IvyJson.decodeFromString(PullResponse.serializer(), """{"seq":3,"folders":[{"id":"f1234567","name":"A","sort":2}],"attachments":[{"id":"a1234567","kind":"image","size":10}]}""")
        assertEquals(2.0, r.folders[0].sort)
        assertEquals("image", r.attachments[0].kind)
        assertTrue(r.notes.isEmpty())
    }

    @Test fun entityIds() {
        val id = newEntityId()
        assertTrue(Regex("[a-z0-9]{16}").matches(id))
        assertEquals(12, LibraryCatalog.newId().length)
    }
}

class ServerAddressTest {
    @Test fun normalizes() {
        val cases = listOf(
            "notes.example.com" to "https://notes.example.com",
            "https://notes.example.com/" to "https://notes.example.com",
            "  HTTPS://Notes.Example.com/api/v1/ " to "https://notes.example.com",
            "https://example.com/ivy/" to "https://example.com/ivy",
            "https://example.com:8443/ivy/api/v1" to "https://example.com:8443/ivy",
            "localhost:8080" to "http://localhost:8080",
            "192.168.1.20:8080" to "http://192.168.1.20:8080",
            "http://nas.local:8080" to "http://nas.local:8080",
            "nas:8080" to "http://nas:8080",
            "https://notes.example.com/?x=1#y" to "https://notes.example.com",
        )
        for ((input, expected) in cases) assertEquals(expected, ServerAddress.parse(input).toString(), input)
    }

    @Test fun apiBase() {
        assertEquals("https://example.com/ivy/api/v1", ServerAddress.parse("https://example.com/ivy").apiBase)
    }

    @Test fun rejects() {
        assertFailsWith<ServerAddress.ParseError.Empty> { ServerAddress.parse("  ") }
        assertEquals(ServerAddress.ParseError.UnsupportedScheme("ftp"), runCatching { ServerAddress.parse("ftp://example.com") }.exceptionOrNull())
        assertEquals(ServerAddress.ParseError.InsecureRemote("notes.example.com"), runCatching { ServerAddress.parse("http://notes.example.com") }.exceptionOrNull())
    }

    @Test fun insecureWarning() {
        assertTrue(ServerAddress.parse("http://192.168.1.2").isInsecure)
        assertFalse(ServerAddress.parse("http://127.0.0.1:8080").isInsecure)
        assertFalse(ServerAddress.parse("localhost").isInsecure)
        assertFalse(ServerAddress.parse("notes.example.com").isInsecure)
    }

    @Test fun serializesAsAString() {
        val a = StoredAccount(ServerAddress.parse("notes.example.com"), "t", User("u1", "ivy"))
        val json = IvyJson.encodeToString(StoredAccount.serializer(), a)
        assertTrue(json.contains("\"server\":\"https://notes.example.com\""))
        assertEquals(a, IvyJson.decodeFromString(StoredAccount.serializer(), json))
    }

    @Test fun regions() {
        assertEquals(SyncRegion.CN, SyncRegion.of(ServerAddress.parse("web.ivynote.net")))
        assertNull(SyncRegion.of(ServerAddress.parse("notes.example.com")))
        assertEquals(SyncRegion.CN, SyncRegion.preferred("CN", null))
        assertEquals(SyncRegion.GLOBAL, SyncRegion.preferred("HK", "Asia/Shanghai"))
        assertEquals(SyncRegion.CN, SyncRegion.preferred(null, "Asia/Shanghai"))
        assertNotNull(SyncRegion.GLOBAL.server)
    }
}

class ValidationTest {
    @Test fun username() {
        assertNull(Validation.username("ivy"))
        assertNull(Validation.username("a.b-c_d"))
        assertNotNull(Validation.username("a"))
        assertNotNull(Validation.username("_ivy"))
        assertNotNull(Validation.username("有中文"))
    }

    @Test fun password() {
        assertNotNull(Validation.password("1234567"))
        assertNull(Validation.password("12345678"))
        assertNotNull(Validation.password("密".repeat(25))) // 75 bytes
    }
}

class OutlineTest {
    @Test fun headingsOutsideCode() {
        val body = "# One\ntext\n```\n# not\n```\n## Two ##\n####### seven\n  ### Three"
        val h = Outline.headings(body)
        assertEquals(listOf(Heading(1, "One", 0), Heading(2, "Two", 5)), h)
        assertEquals(0, Outline.current(h, 3))
        assertEquals(5, Outline.current(h, 9))
    }
}

class SearchTest {
    private fun n(id: String, title: String, body: String, updated: Long = 0) = Note(id = id, title = title, body = body, updatedAt = updated)

    @Test fun allTermsMustMatchAndTitlesWeighMore() {
        val notes = listOf(n("a", "常青笔记", "同步协议"), n("b", "随笔", "常青 笔记 同步"), n("c", "other", "nothing"))
        val hits = Search.run(notes, "常青 同步", now = 0)
        assertEquals(listOf("a", "b"), hits.map { it.note.id })
        assertTrue(Search.run(notes, "常青 absent", now = 0).isEmpty())
    }

    @Test fun snippetsMarkHits() {
        val (text, ranges) = Search.snippet("x".repeat(40) + " hello world " + "y".repeat(100), listOf("hello"))
        assertTrue(text.startsWith("…") && text.endsWith("…"))
        assertEquals("hello", text.substring(ranges[0]))
    }

    @Test fun excerptAndWords() {
        assertEquals("Title some bold link and wiki", Excerpt.of("# Title\n\nsome **bold** [link](http://x) and [[wiki|alias]]\n```\ncode\n```"))
        assertEquals(6, Excerpt.words("你好 hello world 世界"))
    }
}
