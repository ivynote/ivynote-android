package app.ivynote.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A server that takes uploads, or refuses them, or cannot be reached. */
class FakeUploads : SyncTransport {
    val sync = FakeSync()
    var mode: (File, String) -> UploadResult = { f, name -> UploadResult(Attachment("srv" + f.length().toString().padStart(8, '0'), name = name, kind = "image", size = f.length())) }
    val uploaded = mutableListOf<String>()

    override suspend fun snapshot() = sync.snapshot()
    override suspend fun pull(since: Long) = sync.pull(since)
    override suspend fun push(changes: List<Change>) = sync.push(changes)
    override suspend fun upload(file: File, name: String, mime: String): UploadResult {
        uploaded += name
        return mode(file, name)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AttachmentsTest {
    private val dir: File = Files.createTempDirectory("ivy-files").toFile()
    private val src: File = Files.createTempFile("ivy-src", ".png").toFile().apply { writeBytes(ByteArray(1234) { it.toByte() }) }

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
        src.delete()
    }

    private fun engine(t: SyncTransport?, scope: CoroutineScope, store: DocumentStore = MemoryStore()) =
        SyncEngine(store, t, "test-1", scope, debounceMs = 3_600_000, persistDelayMs = 3_600_000, files = dir)

    @Test fun uploadsAtOnceWhenOnline() = runTest {
        val fake = FakeUploads()
        val e = engine(fake, backgroundScope)
        var cached: Attachment? = null
        e.onUploaded = { a, _ -> cached = a }
        val added = e.addFile(src, "cat.png", "image/png")
        assertFalse(added.queued)
        assertEquals("![cat.png](ivy://res/${added.id})", added.markdown)
        assertNotNull(e.attachments[added.id])
        assertEquals(added.id, cached?.id)
        assertTrue(e.uploads.isEmpty())
    }

    @Test fun queuesOfflineAndRewritesBodiesWhenBack() = runTest {
        val fake = FakeUploads()
        val online = fake.mode
        val store = MemoryStore()
        val e = engine(fake, backgroundScope, store)
        fake.sync.snapshots += PullResponse(5)
        e.sync() // the first sync downloads a snapshot
        fake.mode = { _, _ -> throw ApiError.Unreachable("offline") }
        val added = e.addFile(src, "cat.png", "image/png")
        assertTrue(added.queued)
        assertTrue(added.id.startsWith("local-"))
        assertNotNull(e.localFile(added.id))
        assertEquals("image", e.attachmentKind(added.id))
        val n = e.createNote(body = "see\n${added.markdown}\n")
        assertEquals(2, e.state.value.pending) // the note and the file

        // Survives a restart.
        val again = engine(fake, backgroundScope, store).apply { load() }
        assertEquals(1, again.uploads.size)

        fake.mode = online
        e.sync()
        val real = "srv00001234"
        assertEquals("see\n![cat.png](ivy://res/$real)\n", e.notes[n.id]?.body)
        assertTrue(e.uploads.isEmpty())
        assertNull(e.localFile(added.id))
        assertEquals(0, e.state.value.pending)
        // The rewritten body went out with the note.
        assertTrue(fake.sync.pushed.flatten().any { it.fields?.get("body")?.toString()?.contains(real) == true })
    }

    @Test fun aRefusedFileDoesNotHoldBackTheRest() = runTest {
        val fake = FakeUploads()
        fake.mode = { _, _ -> throw ApiError.Unreachable("offline") }
        val e = engine(fake, backgroundScope)
        e.addFile(src, "huge.mov", "video/quicktime")
        e.createNote(title = "x")
        fake.mode = { _, _ -> throw ApiError.Server(413, "too large") }
        e.sync()
        assertEquals("huge.mov", e.uploadFailure.value?.name)
        assertEquals(1, e.state.value.blocked)
        assertEquals(0, e.state.value.pending)
        assertTrue(fake.sync.pushed.isNotEmpty())
        // Not retried in this run.
        e.sync()
        assertEquals(1, fake.uploaded.size - 1)
    }

    @Test fun serverErrorsStopTheRound() = runTest {
        val fake = FakeUploads()
        fake.mode = { _, _ -> throw ApiError.Unreachable("offline") }
        val e = engine(fake, backgroundScope)
        e.addFile(src, "a.png", "image/png")
        e.createNote(title = "x")
        fake.mode = { _, _ -> throw ApiError.Server(503, "") }
        e.sync()
        assertEquals(SyncState.Status.OFFLINE, e.state.value.status)
        assertTrue(fake.sync.pushed.isEmpty())
        assertEquals(1, e.uploads.size)
    }

    @Test fun localLibraryKeepsFilesAndDeduplicates() = runTest {
        val e = engine(null, backgroundScope)
        val a = e.addFile(src, "cat.png", "image/png")
        assertFalse(a.queued)
        val att = e.attachments.getValue(a.id)
        assertEquals("device", att.storage)
        assertEquals(1234, att.size)
        assertEquals(64, att.sha256.length)
        assertNotNull(e.localFile(a.id))
        val b = e.addFile(src, "again.png", "image/png")
        assertTrue(b.duplicate)
        assertEquals(a.id, b.id)
        assertEquals(1, e.attachments.size)
        e.removeAttachment(a.id)
        assertNull(e.localFile(a.id))
    }

    @Test fun mediaLibraryCountsReferencesLocally() = runTest {
        val e = engine(null, backgroundScope)
        val a = e.addFile(src, "cat.png", "image/png")
        val other = Files.createTempFile("ivy", ".txt").toFile().apply { writeText("hello") }
        val b = e.addFile(other, "notes.txt", "text/plain")
        other.delete()
        val n = e.createNote(title = "Pets", body = a.markdown)
        val media = MediaLibrary(e, null)
        val all = media.list(MediaLibrary.Filter.ALL)
        assertEquals(2, all.items.size)
        assertEquals(1, all.counts[MediaLibrary.Filter.IMAGE])
        assertEquals(1, all.counts[MediaLibrary.Filter.ORPHANED])
        assertEquals(listOf(b.id), media.list(MediaLibrary.Filter.ORPHANED).items.map { it.id })
        assertEquals(listOf(n.id), media.detail(a.id).notes.map { it.id })
        media.rename(b.id, "readme.txt")
        assertEquals(listOf(b.id), media.list(MediaLibrary.Filter.ALL, "READ").items.map { it.id })
        media.trash(b.id)
        assertEquals(listOf(b.id), media.list(MediaLibrary.Filter.TRASH).items.map { it.id })
        assertEquals(1, media.list(MediaLibrary.Filter.ALL).items.size)
        media.purge(b.id)
        assertNull(e.attachments[b.id])
    }

    @Test fun tagsRenameRecolorAndAttach() = runTest {
        val e = engine(FakeUploads(), backgroundScope)
        val t = e.createTag("#工作 ")
        assertEquals("工作", t.name)
        assertEquals(t.id, e.tagNamed(" 工作")?.id)
        e.updateTag(t.id, name = "Work", color = "green")
        assertEquals("Work", e.tags[t.id]?.name)
        assertEquals("green", e.tags[t.id]?.color)
        assertEquals(t.id, e.tagNamed("work")?.id)
        val n = e.createNote(title = "x")
        e.setNoteTag(n.id, t.id, true)
        e.setNoteTag(n.id, t.id, true)
        assertEquals(listOf(t.id), e.notes[n.id]?.tags)
        e.setNoteTag(n.id, t.id, false)
        assertEquals(emptyList(), e.notes[n.id]?.tags)
    }
}

class LinksTest {
    private fun n(id: String, title: String, body: String = "", updated: Long = 1, trashed: Long = 0) =
        Note(id = id, title = title, body = body, updatedAt = updated, trashedAt = trashed)

    @Test fun parsesWikiLinks() {
        assertEquals(Links.WikiLink("A", "b", "c"), Links.parseWiki(" A#c|b"))
        assertEquals(setOf("hello world"), Links.wikiTargets("[[Hello   World]] and [[hello world#x|y]] `[[code]]`"))
    }

    @Test fun backlinksSkipSelfTrashCodeAndShadowedTitles() {
        val target = n("t0000001", "Target", updated = 5)
        val notes = listOf(
            target,
            n("a0000001", "A", "see [[target]]", updated = 3),
            n("b0000001", "B", "```\n[[Target]]\n```"),
            n("c0000001", "C", "[[Target]]", trashed = 9),
            n("d0000001", "D", "by id [x](ivy://note/t0000001)", updated = 4),
            n("t0000001".replace('1', '2'), "Self", "[[Self]]"),
        )
        assertEquals(listOf("d0000001", "a0000001"), Links.backlinks("t0000001", notes).map { it.id })
        // A newer note of the same title takes the wiki links.
        val newer = notes + n("t0000009", "target", updated = 9)
        assertEquals(listOf("d0000001"), Links.backlinks("t0000001", newer).map { it.id })
        assertEquals("see Target", Links.linkContext("- see [[Target]]", "target"))
        assertEquals("alias here", Links.linkContext("[[Target|alias]] here", "target"))
    }

    @Test fun rewritesAndEmbedsResources() {
        assertEquals("![a](ivy://res/real0001) ivy://res/keep0001",
            Links.rewriteResources("![a](ivy://res/local-abcdefgh) ivy://res/keep0001", mapOf("local-abcdefgh" to "real0001")))
        assertEquals("[ab.pdf](ivy://res/x1234567)", Links.embed("x1234567", "[a]b.pdf", "file"))
        assertEquals("![p.png](ivy://res/x1234567)", Links.embed("x1234567", "p.png", "image"))
    }
}

class MigratorFilesTest {
    @Test fun uploadsFilesAndRewritesBodies() = runTest {
        val dir = Files.createTempDirectory("ivy-mig").toFile()
        try {
            File(dir, "loc00001").writeText("png")
            val content = LibraryMigrator.Content(
                folders = emptyList(), tags = emptyList(),
                notes = listOf(Note("note0001", body = "![a](ivy://res/loc00001) [b](ivy://res/gone0001)")),
                attachments = listOf(Attachment("loc00001", name = "a.png", mime = "image/png", sha256 = "h1"), Attachment("gone0001", name = "b.pdf", sha256 = "h2")),
                files = dir,
            )
            val fake = FakeUploads()
            fake.mode = { _, name -> UploadResult(Attachment("srv00001", name = name, sha256 = "h1")) }
            val r = LibraryMigrator(fake, "d").run(content)
            assertEquals(1, r.files)
            assertEquals(listOf("b.pdf"), r.failedFiles)
            val body = fake.sync.pushed.flatten().single().fields?.get("body")?.toString()
            assertEquals("\"![a](ivy://res/srv00001) [b](ivy://res/gone0001)\"", body)
            // An interrupted run left only our own file: still empty enough.
            fake.sync.pulls += PullResponse(3, attachments = listOf(Attachment("srv00001", sha256 = "h1")))
            LibraryMigrator(fake, "d").run(content)
            fake.sync.pulls += PullResponse(3, attachments = listOf(Attachment("other001", sha256 = "zz")))
            kotlin.test.assertFailsWith<LibraryMigrator.AccountNotEmpty> { LibraryMigrator(fake, "d").run(content) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
