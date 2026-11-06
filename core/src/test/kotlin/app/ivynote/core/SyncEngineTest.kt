package app.ivynote.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted server: records pushes and answers with queued responses. */
class FakeSync : SyncTransport {
    val snapshots = ArrayDeque<PullResponse>()
    val pulls = ArrayDeque<PullResponse>()
    var pushHandler: (List<Change>) -> PushResponse = { changes ->
        PushResponse(changes.mapIndexed { i, c ->
            ChangeResult(c.type, c.id, ok = true, seq = (i + 1).toLong(),
                rev = if (c.type == EntityType.NOTE && c.fields?.containsKey("body") == true) (c.baseRev ?: 0) + 1 else null)
        }, changes.size.toLong())
    }
    val pushed = mutableListOf<List<Change>>()
    val calls = mutableListOf<String>()

    override suspend fun snapshot(): PullResponse { calls += "snapshot"; return snapshots.removeFirstOrNull() ?: PullResponse(0) }
    override suspend fun pull(since: Long): PullResponse { calls += "pull:$since"; return pulls.removeFirstOrNull() ?: PullResponse(since) }
    override suspend fun push(changes: List<Change>): PushResponse { pushed += changes; calls += "push:${changes.size}"; return pushHandler(changes) }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private val clock = 1_800_000_000_000L

    private fun engine(fake: SyncTransport?, scope: CoroutineScope, store: DocumentStore = MemoryStore(), now: () -> Long = { clock }) =
        SyncEngine(store, fake, "test-1", scope, debounceMs = 3_600_000, persistDelayMs = 3_600_000, now = now)

    private fun note(id: String, title: String = "", body: String = "", rev: Long = 1, seq: Long = 1, deleted: Boolean = false) =
        Note(id = id, title = title, body = body, rev = rev, seq = seq, deleted = deleted, createdAt = 1, updatedAt = 1)

    @Test fun firstSyncDownloadsSnapshotThenPulls() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(7, folders = listOf(Folder("f1234567", name = "Work", seq = 1)), notes = listOf(note("n0000001", title = "Hello")))
        val e = engine(fake, backgroundScope)
        e.sync()
        assertEquals("Hello", e.notes["n0000001"]?.title)
        assertEquals("Work", e.folders["f1234567"]?.name)
        assertEquals(7, e.cursor)
        assertEquals(SyncState.Status.IDLE, e.state.value.status)
        assertTrue(e.state.value.lastSyncAt > 0)
        e.sync()
        assertEquals(listOf("snapshot", "pull:7"), fake.calls)
    }

    @Test fun localEditsArePushedWithBaseRev() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(1, notes = listOf(note("n0000001", body = "a", rev = 3)))
        val e = engine(fake, backgroundScope)
        e.sync()
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "b"))
        assertEquals(1, e.state.value.pending)
        e.sync()
        val change = fake.pushed.last().first()
        assertEquals(3, change.baseRev)
        assertEquals(JsonPrimitive("b"), change.fields?.get("body"))
        assertEquals("test-1", change.device)
        assertEquals(4, e.notes["n0000001"]?.rev)
        assertEquals(0, e.state.value.pending)
    }

    @Test fun newNotesPushAllFieldsWithBaseRevZero() = runTest {
        val fake = FakeSync()
        val e = engine(fake, backgroundScope)
        val n = e.createNote(title = "T", body = "B", folderId = "f1234567")
        e.sync()
        val c = fake.pushed.first().first()
        assertEquals(n.id, c.id)
        assertEquals(Op.UPSERT, c.op)
        assertEquals(0, c.baseRev)
        assertEquals(JsonPrimitive("T"), c.fields?.get("title"))
        assertEquals(JsonPrimitive("f1234567"), c.fields?.get("folderId"))
        assertEquals(JsonPrimitive(clock), c.fields?.get("createdAt"))
    }

    @Test fun wireFormatLeavesOutNulls() {
        val delete = OutboxEntry(key = "note:n1", type = EntityType.NOTE, id = "n1", op = Op.DELETE, hlc = "h", device = "d").change
        val json = IvyJson.encodeToString(Change.serializer(), delete)
        assertEquals("""{"type":"note","id":"n1","op":"delete","hlc":"h","device":"d"}""", json)
        val title = OutboxEntry(key = "note:n1", type = EntityType.NOTE, id = "n1", op = Op.UPSERT, hlc = "h", device = "d",
            fields = mapOf("title" to JsonPrimitive("x")), baseRev = 4).change
        assertNull(title.baseRev) // only sent with a body
    }

    @Test fun pendingFieldsSurviveAPull() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(1, notes = listOf(note("n0000001", title = "old", body = "x")))
        val e = engine(fake, backgroundScope)
        e.sync()
        e.updateNote("n0000001", SyncEngine.NotePatch(title = "mine"))
        e.applyPull(PullResponse(2, notes = listOf(note("n0000001", title = "theirs", body = "y", rev = 2, seq = 2))), snapshot = false)
        assertEquals("mine", e.notes["n0000001"]?.title)
        assertEquals("y", e.notes["n0000001"]?.body)
    }

    @Test fun tombstonesWinOverPendingEdits() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(1, notes = listOf(note("n0000001")))
        val e = engine(fake, backgroundScope)
        e.sync()
        e.updateNote("n0000001", SyncEngine.NotePatch(title = "edit"))
        e.applyPull(PullResponse(2, notes = listOf(note("n0000001", seq = 2, deleted = true))), snapshot = false)
        assertNull(e.notes["n0000001"])
        assertEquals(0, e.state.value.pending)
    }

    @Test fun snapshotDropsWhatTheServerNoLongerHas() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        e.applyPull(PullResponse(1, notes = listOf(note("n0000001"), note("n0000002"))), snapshot = true)
        val mine = e.createNote(title = "unpushed")
        e.applyPull(PullResponse(2, notes = listOf(note("n0000002"))), snapshot = true)
        assertNull(e.notes["n0000001"])
        assertNotNull(e.notes["n0000002"])
        assertNotNull(e.notes[mine.id]) // still in the outbox
    }

    @Test fun conflictCopyArrivingBeforeItsPushKeepsLocalFields() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        val n = e.createNote(title = "local", body = "draft")
        // A pull brings the entity while its creation is still queued.
        e.applyPull(PullResponse(3, notes = listOf(note(n.id, title = "server", body = "old"))), snapshot = false)
        assertEquals("local", e.notes[n.id]?.title)
        assertEquals("draft", e.notes[n.id]?.body)
    }

    @Test fun editsDuringAPushStayQueued() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(1, notes = listOf(note("n0000001", body = "a", rev = 1)))
        val e = engine(fake, backgroundScope)
        e.sync()
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "b"))
        val sent = e.outbox.getValue("note:n0000001")
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "c")) // typed while "b" was in flight
        e.applyResult(sent, ChangeResult(EntityType.NOTE, "n0000001", ok = true, seq = 2, rev = 2))
        val cur = e.outbox.getValue("note:n0000001")
        assertEquals(JsonPrimitive("c"), cur.fields["body"])
        assertEquals(2, cur.baseRev) // builds on the accepted "b"
        assertEquals(2, e.notes["n0000001"]?.rev)
    }

    @Test fun mergedBodyReplacesLocalText() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(1, notes = listOf(note("n0000001", body = "A\nB\n", rev = 1)))
        fake.pushHandler = { c -> PushResponse(listOf(ChangeResult(EntityType.NOTE, c[0].id, ok = true, seq = 3, rev = 3, merged = true, body = "A\nb\nC\n")), 3) }
        val e = engine(fake, backgroundScope)
        e.sync()
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "A\nb\n"))
        e.sync()
        assertEquals("A\nb\nC\n", e.notes["n0000001"]?.body)
        assertEquals(3, e.notes["n0000001"]?.rev)
    }

    @Test fun mergedWhileTypingKeepsTheOldBase() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        e.applyPull(PullResponse(1, notes = listOf(note("n0000001", body = "A\n", rev = 1))), snapshot = false)
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "A\nb\n"))
        val sent = e.outbox.getValue("note:n0000001")
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "A\nb\nc\n"))
        e.applyResult(sent, ChangeResult(EntityType.NOTE, "n0000001", ok = true, seq = 2, rev = 2, merged = true, body = "Z\nA\nb\n"))
        assertEquals("A\nb\nc\n", e.notes["n0000001"]?.body) // newer local text is not overwritten
        assertEquals(1, e.outbox.getValue("note:n0000001").baseRev)
    }

    @Test fun conflictCopyIsReportedAndNewTypingMovesToIt() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        e.applyPull(PullResponse(1, notes = listOf(note("n0000001", body = "a", rev = 1))), snapshot = false)
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "mine"))
        val sent = e.outbox.getValue("note:n0000001")
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "mine and more"))
        e.applyResult(sent, ChangeResult(EntityType.NOTE, "n0000001", ok = true, seq = 4, rev = 2, conflictCopy = "copy0001"))
        assertEquals(ConflictNotice("n0000001", "copy0001"), e.conflict.value)
        assertNull(e.outbox["note:n0000001"])
        val copy = e.outbox.getValue("note:copy0001")
        assertEquals(JsonPrimitive("mine and more"), copy.fields["body"])
        assertEquals(1, copy.baseRev)
    }

    @Test fun conflictCopyIsReported() = runTest {
        val fake = FakeSync()
        fake.snapshots += PullResponse(1, notes = listOf(note("n0000001", body = "a", rev = 1)))
        fake.pushHandler = { c -> PushResponse(listOf(ChangeResult(EntityType.NOTE, c[0].id, ok = true, seq = 4, rev = 2, conflictCopy = "copy0001")), 4) }
        val e = engine(fake, backgroundScope)
        e.sync()
        e.updateNote("n0000001", SyncEngine.NotePatch(body = "mine"))
        e.sync()
        assertEquals(ConflictNotice("n0000001", "copy0001"), e.conflict.value)
    }

    @Test fun refusedChangesAreBlockedNotLost() = runTest {
        val fake = FakeSync()
        fake.pushHandler = { c -> PushResponse(listOf(ChangeResult(c[0].type, c[0].id, ok = false, error = "invalid hlc")), 0) }
        val e = engine(fake, backgroundScope)
        e.createFolder("X")
        e.sync()
        assertEquals(1, e.state.value.blocked)
        assertEquals(0, e.state.value.pending)
        e.sync()
        assertEquals(1, fake.pushed.size) // not sent again until edited or restarted
    }

    @Test fun badBatchesAreSplitToFindTheCulprit() = runTest {
        val fake = FakeSync()
        fake.pushHandler = { changes ->
            if (changes.any { it.fields?.get("name") == JsonPrimitive("bad") }) throw ApiError.Server(400, "name too long")
            PushResponse(changes.map { ChangeResult(it.type, it.id, ok = true, seq = 1) }, 1)
        }
        val e = engine(fake, backgroundScope)
        e.createFolder("a"); e.createFolder("bad"); e.createFolder("c")
        e.sync()
        assertEquals(1, e.state.value.blocked)
        assertEquals(0, e.state.value.pending)
        assertEquals("name too long", e.outbox.values.single().blocked)
    }

    @Test fun pushesInTheOrderEntitiesWereTouched() = runTest {
        val fake = FakeSync()
        var t = 1000L
        val e = engine(fake, backgroundScope, now = { ++t })
        val f = e.createFolder("New")
        val n = e.createNote(title = "in it", folderId = f.id)
        e.renameFolder(f.id, "Renamed") // later edit, but the folder still goes first
        e.sync()
        assertEquals(listOf(f.id, n.id), fake.pushed[0].map { it.id })
    }

    @Test fun deletingAFolderTrashesItsNotesAndSubfolders() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        val top = e.createFolder("Top")
        val sub = e.createFolder("Sub", top.id)
        val n = e.createNote(title = "x", folderId = sub.id)
        e.deleteFolder(top.id)
        assertTrue(e.folders.isEmpty())
        assertEquals(true, e.notes[n.id]?.isTrashed)
        assertEquals("", e.notes[n.id]?.folderId)
        assertEquals(Op.DELETE, e.outbox["folder:${top.id}"]?.op)
    }

    @Test fun deletingATagRemovesItFromNotes() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        val tag = e.createTag("#idea")
        assertEquals("idea", tag.name)
        val n = e.createNote(title = "x", tags = listOf(tag.id))
        e.deleteTag(tag.id)
        assertEquals(emptyList(), e.notes[n.id]?.tags)
        assertEquals(Op.DELETE, e.outbox["tag:${tag.id}"]?.op)
    }

    @Test fun folderCyclesAreRefused() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        val a = e.createFolder("A")
        val b = e.createFolder("B", a.id)
        assertFalse(e.canMoveFolder(a.id, b.id))
        e.moveFolder(a.id, b.id)
        assertEquals("", e.folders[a.id]?.parentId)
    }

    @Test fun duplicateCopiesBodyAndPlace() = runTest {
        val e = engine(FakeSync(), backgroundScope)
        val n = e.createNote(title = "Plan", body = "text", folderId = "f1")
        val copy = e.duplicateNote(n.id)!!
        assertEquals("Plan 副本", copy.title)
        assertEquals("text", copy.body)
        assertEquals("f1", copy.folderId)
    }

    @Test fun networkFailureMeansOfflineAndKeepsChanges() = runTest {
        val fake = FakeSync()
        fake.pushHandler = { throw ApiError.Unreachable("offline") }
        val e = engine(fake, backgroundScope)
        e.createNote(title = "offline note")
        e.sync()
        assertEquals(SyncState.Status.OFFLINE, e.state.value.status)
        assertEquals(1, e.state.value.pending)
    }

    @Test fun unauthorizedIsReported() = runTest {
        val fake = FakeSync()
        fake.pushHandler = { throw ApiError.Server(401, "") }
        val e = engine(fake, backgroundScope)
        var told = false
        e.onUnauthorized = { told = true }
        e.createNote()
        e.sync()
        assertTrue(told)
        assertEquals(1, e.state.value.pending) // kept for after signing in again
    }

    @Test fun everythingSurvivesARestart() = runTest {
        val store = MemoryStore()
        val fake = FakeSync()
        fake.snapshots += PullResponse(5, notes = listOf(note("n0000001", title = "synced")))
        run {
            val e = engine(fake, backgroundScope, store)
            e.sync()
            e.createNote(title = "draft")
            e.persistNow()
        }
        val e = engine(fake, backgroundScope, store)
        e.load()
        assertEquals(5, e.cursor)
        assertEquals(setOf("synced", "draft"), e.notes.values.map { it.title }.toSet())
        assertEquals(1, e.state.value.pending)
    }

    @Test fun localLibrariesSaveButNeverQueue() = runTest {
        val store = MemoryStore()
        run {
            val e = engine(null, backgroundScope, store)
            assertTrue(e.isLocal)
            val f = e.createFolder("Inbox")
            val n = e.createNote(title = "local", folderId = f.id)
            e.updateNote(n.id, SyncEngine.NotePatch(body = "hello"))
            val gone = e.createNote(title = "gone")
            e.deleteNote(gone.id)
            e.sync()
            assertEquals(0, e.state.value.pending)
            assertEquals(SyncState.Status.IDLE, e.state.value.status)
            e.persistNow()
        }
        val e = engine(null, backgroundScope, store)
        e.load()
        assertEquals(listOf("local"), e.notes.values.map { it.title })
        assertEquals("hello", e.notes.values.first().body)
        assertEquals(1, e.folders.size)
        assertTrue(e.outbox.isEmpty())
    }

    @Test fun debouncedSyncRunsAfterEdits() = runTest {
        val fake = FakeSync()
        val e = SyncEngine(MemoryStore(), fake, "d", backgroundScope, debounceMs = 800, persistDelayMs = 300, now = { clock })
        e.createNote(title = "x")
        testScheduler.advanceTimeBy(900)
        testScheduler.runCurrent()
        assertEquals(1, fake.pushed.size)
        assertEquals(0, e.state.value.pending)
    }
}
