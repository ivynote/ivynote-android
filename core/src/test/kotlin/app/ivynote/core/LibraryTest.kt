package app.ivynote.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCatalogTest {
    private val server = ServerAddress.parse("https://notes.example.com")
    private val prefs = MemoryKeyValueStore()
    private val root: File = Files.createTempDirectory("ivy-cat").toFile()
    private val creds = HashMap<String, InMemoryCredentialStore>()

    private fun catalog() = LibraryCatalog(prefs, root, { id -> creds.getOrPut(id) { InMemoryCredentialStore() } })

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    @Test fun createdLibrariesPersistAndOpen() {
        val c = catalog()
        assertTrue(c.libraries.value.isEmpty() && c.active == null)
        val local = c.createLocal("  ")
        assertEquals("我的笔记", local.name)
        assertTrue(local.isLocal)
        assertEquals(local.id, c.activeId.value)
        val synced = c.createServer(server, "")
        assertEquals("notes.example.com", synced.name)
        assertEquals(server, synced.server)
        assertEquals(synced.id, c.activeId.value)

        val again = catalog()
        assertEquals(listOf(local.id, synced.id), again.libraries.value.map { it.id })
        assertEquals(synced.id, again.activeId.value)
        assertTrue(again.storeFile(local.id) != again.storeFile(synced.id))
    }

    @Test fun openNullGoesBackToTheList() {
        val c = catalog()
        c.createLocal("A")
        c.open(null)
        assertNull(c.active)
        assertNull(catalog().active)
        c.open("missing")
        assertNull(c.active)
    }

    @Test fun removeDeletesTheDatabaseAndSignIn() {
        val c = catalog()
        val ref = c.createServer(server, "Work")
        val file = c.storeFile(ref.id)
        file.parentFile.mkdirs(); file.writeText("db")
        c.credentials(ref.id).save(StoredAccount(server, "t", User("u1", "ivy")))
        c.saveExpanded(ref.id, setOf("x"))
        c.remove(ref.id)
        assertTrue(c.libraries.value.isEmpty() && c.active == null)
        assertFalse(file.exists())
        assertNull(c.credentials(ref.id).load())
        assertTrue(c.expanded(ref.id).isEmpty())
    }

    @Test fun signedInRecordsTheAccount() {
        val c = catalog()
        val ref = c.createServer(server, "Work")
        c.signedIn(ref.id, User("u1", "ivy"))
        assertEquals("ivy", c.library(ref.id)?.account)
        assertEquals("u1", c.library(ref.id)?.userId)
    }

    @Test fun createSignedInSavesTheToken() {
        val c = catalog()
        val ref = c.createSignedIn(server, "", "tok", User("u1", "ivy"))
        assertEquals("tok", c.credentials(ref.id).load()?.token)
        assertEquals("ivy", ref.account)
        assertEquals(server.base, prefs.getString(SessionStore.LAST_SERVER_KEY))
    }

    @Test fun turnOnSyncLeavesTheLibrary() {
        val c = catalog()
        val ref = c.createLocal("Mine")
        c.turnOnSync(ref.id)
        assertNull(c.activeId.value)
        assertEquals(ref.id, c.pendingSync.value)
        c.markMigrated(ref.id, "other")
        assertFalse(c.library(ref.id)!!.canTurnOnSync)
    }
}

class MigratorTest {
    private val content = LibraryMigrator.Content(
        folders = listOf(Folder("child000", "parent00", "Child"), Folder("parent00", "", "Parent")),
        tags = listOf(Tag("tag00001", "idea")),
        notes = listOf(Note("note0001", folderId = "child000", title = "T", body = "B", tags = listOf("tag00001"), pinned = true, createdAt = 5)),
    )

    @Test fun uploadsParentsFirstIntoAnEmptyAccount() = runTest {
        val fake = FakeSync()
        val r = LibraryMigrator(fake, "d", now = { 1000 }).run(content)
        assertEquals(1, r.notes)
        assertTrue(r.rejected.isEmpty())
        val ids = fake.pushed.flatten().map { it.id }
        assertEquals(listOf("parent00", "child000", "tag00001", "note0001"), ids)
        val n = fake.pushed.flatten().last()
        assertEquals(0, n.baseRev)
        assertEquals(JsonPrimitive(true), n.fields?.get("pinned"))
        assertEquals(JsonPrimitive(5L), n.fields?.get("createdAt"))
    }

    @Test fun refusesAnAccountWithOtherNotes() = runTest {
        val fake = FakeSync()
        fake.pulls += PullResponse(3, notes = listOf(Note("someone1")))
        assertFailsWith<LibraryMigrator.AccountNotEmpty> { LibraryMigrator(fake, "d").run(content) }
    }

    @Test fun resumesWhenTheAccountHoldsOnlyItsOwnIds() = runTest {
        val fake = FakeSync()
        fake.pulls += PullResponse(3, notes = listOf(Note("note0001")))
        assertEquals(1, LibraryMigrator(fake, "d").run(content).notes)
    }
}
