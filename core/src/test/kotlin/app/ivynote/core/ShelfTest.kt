package app.ivynote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShelfTest {
    private fun note(id: String, title: String, folder: String = "", pinned: Boolean = false, updated: Long = 1, trashed: Long = 0, tags: List<String> = emptyList()) =
        Note(id = id, folderId = folder, title = title, tags = tags, pinned = pinned, trashedAt = trashed, createdAt = updated, updatedAt = updated)

    private fun folder(id: String, name: String, parent: String = "", sort: Double = 0.0) = Folder(id = id, parentId = parent, name = name, sort = sort)
    private fun keys(rows: List<Shelf.Row>) = rows.map { it.key }

    private val folders = listOf(folder("fwork", "Work", sort = 1.0), folder("fsub", "Sub", "fwork", 1.0), folder("fhome", "Home", sort = 2.0))

    @Test fun rootNotesSitBelowNotebooks() {
        val notes = listOf(note("n1", "Root", updated = 5), note("n2", "In work", "fwork"))
        val rows = Shelf.build(folders, notes, emptyList(), emptySet(), Shelf.SortBy.UPDATED)
        assertEquals(listOf("h:notes", "fwork", "fhome", "/n1", "v:trash"), keys(rows))
        assertTrue(rows[1].count == 1 && rows[1].expandable && !rows[1].open)
        assertTrue(rows[2].count == 0 && !rows[2].expandable)
    }

    @Test fun openNotebooksListSubNotebooksBeforeNotes() {
        val notes = listOf(note("n2", "In work", "fwork"), note("n3", "Deep", "fsub"))
        val rows = Shelf.build(folders, notes, emptyList(), setOf("fwork", "fsub"), Shelf.SortBy.UPDATED)
        assertEquals(listOf("h:notes", "fwork", "fsub", "fsub/n3", "fwork/n2", "fhome", "v:trash"), keys(rows))
        assertEquals(2, rows.first { it.key == "fwork" }.count)
        assertEquals(2, rows.first { it.key == "fsub/n3" }.depth)
        assertEquals("fwork", rows.first { it.key == "fwork/n2" }.folderId)
    }

    @Test fun orphansFallToTheTop() {
        val rows = Shelf.build(listOf(folder("fa", "A", "gone")), listOf(note("n1", "Lost", "gone")), emptyList(), emptySet(), Shelf.SortBy.UPDATED)
        assertEquals(listOf("h:notes", "fa", "/n1", "v:trash"), keys(rows))
        assertEquals(0, rows[1].depth)
    }

    @Test fun pinnedTagsAndTrashGroups() {
        val tag = Tag("t1", "idea")
        val notes = listOf(note("n1", "P", pinned = true, tags = listOf("t1")), note("n2", "T", trashed = 9))
        val first = Shelf.build(emptyList(), notes, listOf(tag), emptySet(), Shelf.SortBy.UPDATED)
        assertEquals(listOf("v:pinned", "v:pinned/n1", "h:notes", "h:create", "/n1", "v:tags", "v:trash"), keys(first))
        assertTrue(first[0].kind == Shelf.Kind.SECTION && first[0].open && first[0].count == 1)
        assertEquals(0, first[1].depth)
        assertEquals(1, first.last().count)
        val shut = Shelf.build(emptyList(), notes, listOf(tag), setOf(Shelf.closedKey("v:pinned")), Shelf.SortBy.UPDATED)
        assertEquals(listOf("v:pinned", "h:notes", "h:create", "/n1", "v:tags", "v:trash"), keys(shut))
        val open = Shelf.build(emptyList(), notes, listOf(tag), setOf("v:tags", "t:t1", "v:trash"), Shelf.SortBy.UPDATED)
        assertEquals(listOf("v:pinned", "v:pinned/n1", "h:notes", "h:create", "/n1", "v:tags", "t:t1", "t:t1/n1", "v:trash", "v:trash/n2"), keys(open))
    }

    @Test fun longBranchesArePaged() {
        val notes = (0 until 130).map { note("n$it", "N$it", updated = it.toLong()) }
        val rows = Shelf.build(emptyList(), notes, emptyList(), emptySet(), Shelf.SortBy.UPDATED)
        assertEquals(100, rows.count { it.kind == Shelf.Kind.NOTE })
        assertEquals(30, rows.first { it.kind == Shelf.Kind.MORE }.count)
        val more = Shelf.build(emptyList(), notes, emptyList(), emptySet(), Shelf.SortBy.UPDATED, mapOf("" to 200))
        assertEquals(130, more.count { it.kind == Shelf.Kind.NOTE })
    }

    @Test fun sorting() {
        val notes = listOf(note("a", "乙", updated = 1), note("b", "", updated = 3), note("c", "甲", updated = 2), note("d", "丙", pinned = true))
        assertEquals(listOf("d", "b", "c", "a"), Shelf.sortNotes(notes, Shelf.SortBy.UPDATED).map { it.id })
        assertEquals(listOf("丙", "甲", "乙", ""), Shelf.sortNotes(notes, Shelf.SortBy.TITLE).map { it.title })
    }

    @Test fun revealAndHome() {
        val map = folders.associateBy { it.id }
        val deep = note("n3", "Deep", "fsub")
        assertEquals(listOf("fwork", "fsub"), Shelf.revealKeys(deep, map))
        assertEquals("fsub/n3", Shelf.homeKey(deep, map))
        assertEquals("/n1", Shelf.homeKey(note("n1", "R"), map))
        assertEquals(listOf("v:trash"), Shelf.revealKeys(note("n2", "T", trashed = 1), map))
        assertEquals("n3", Shelf.noteId("fsub/n3"))
        assertEquals(null, Shelf.noteId("fsub/more"))
        assertEquals(null, Shelf.noteId("fsub"))
    }

    @Test fun flatFoldersDepthFirst() {
        assertEquals(listOf("fwork" to 0, "fsub" to 1, "fhome" to 0), Shelf.flatFolders(folders).map { it.first.id to it.second })
    }
}
