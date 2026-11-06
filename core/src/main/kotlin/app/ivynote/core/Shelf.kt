package app.ivynote.core

import java.text.Collator
import java.util.Locale

/**
 * The bookshelf (guide 11.2): one tree of notebooks and notes, flattened into
 * rows. A port of the web client's `packages/core/src/tree.ts` (and the Apple
 * client's `Shelf.swift`), so every client shows the same shelf.
 *
 * Order: the pinned section (when any), the "notes" heading, the notebook tree
 * (sub-notebooks before notes), notes at the root, tags (when any), trash.
 */
object Shelf {
    /** Notes shown per branch before a "show more" row. */
    const val PAGE = 100

    enum class SortBy(val label: String) {
        UPDATED("更新时间"), CREATED("创建时间"), TITLE("标题");
    }

    enum class Kind { GROUP, HEADING, SECTION, CREATE, FOLDER, TAG, NOTE, MORE }

    data class Row(
        /** Unique in the tree; folders and groups use it as their expansion key. */
        val key: String,
        val kind: Kind,
        val depth: Int,
        /** Key of the enclosing row, empty at the top level. */
        val parent: String,
        val label: String,
        val count: Int = 0,
        val expandable: Boolean = false,
        val open: Boolean = false,
        /** Sets the row apart from the one above. */
        val gap: Boolean = false,
        /** The notebook a new note made from this row goes into. */
        val folderId: String? = null,
        val folder: Folder? = null,
        val tag: Tag? = null,
        val note: Note? = null,
    )

    // Expansion keys of the groups.
    const val PINNED_KEY = "v:pinned"
    const val TAGS_KEY = "v:tags"
    const val TRASH_KEY = "v:trash"

    /** Sections (pinned) start open, so their key in the expansion set records that one was closed. */
    fun closedKey(key: String) = "$key:closed"

    private val collator: Collator by lazy { Collator.getInstance(Locale.CHINESE).apply { strength = Collator.SECONDARY } }

    /** Chinese collation, as the web's `localeCompare('zh')`. */
    fun compareTitles(a: String, b: String): Int = collator.compare(a, b)

    fun sortNotes(list: Collection<Note>, by: SortBy, trash: Boolean = false): List<Note> =
        list.sortedWith { a, b ->
            if (!trash && a.pinned != b.pinned) return@sortedWith if (a.pinned) -1 else 1
            when (by) {
                SortBy.TITLE -> {
                    // Untitled last.
                    if (a.title.isEmpty() != b.title.isEmpty()) return@sortedWith if (a.title.isEmpty()) 1 else -1
                    val o = compareTitles(a.title, b.title)
                    if (o != 0) o else a.id.compareTo(b.id)
                }
                SortBy.CREATED -> if (a.createdAt != b.createdAt) b.createdAt.compareTo(a.createdAt) else a.id.compareTo(b.id)
                SortBy.UPDATED -> {
                    if (trash && a.trashedAt != b.trashedAt) return@sortedWith b.trashedAt.compareTo(a.trashedAt)
                    if (a.updatedAt != b.updatedAt) b.updatedAt.compareTo(a.updatedAt) else a.id.compareTo(b.id)
                }
            }
        }

    /** Live notes in each notebook, sub-notebooks included. */
    fun folderTotals(folders: Collection<Folder>, notes: Collection<Note>): Map<String, Int> {
        val byId = folders.associateBy { it.id }
        val direct = HashMap<String, Int>()
        for (n in notes) if (!n.isTrashed) direct[n.folderId] = (direct[n.folderId] ?: 0) + 1
        val out = HashMap<String, Int>()
        for (f in folders) {
            val own = direct[f.id] ?: 0
            var p: Folder? = f
            var depth = 0
            while (p != null && depth < 64) {
                out[p.id] = (out[p.id] ?: 0) + own
                p = byId[p.parentId]
                depth++
            }
        }
        return out
    }

    private val folderOrder = Comparator<Folder> { a, b ->
        when {
            a.sort != b.sort -> a.sort.compareTo(b.sort)
            a.name != b.name -> a.name.compareTo(b.name)
            else -> a.id.compareTo(b.id)
        }
    }

    fun build(
        folders: Collection<Folder>,
        notes: Collection<Note>,
        tags: Collection<Tag>,
        expanded: Set<String>,
        sortBy: SortBy,
        limits: Map<String, Int> = emptyMap(),
    ): List<Row> {
        val rows = ArrayList<Row>()
        val live = notes.filter { !it.isTrashed }
        val trashed = notes.filter { it.isTrashed }
        val ids = folders.mapTo(HashSet()) { it.id }

        val kids = HashMap<String, MutableList<Folder>>()
        // A notebook whose parent is gone shows at the top level.
        for (f in folders) kids.getOrPut(if (f.parentId in ids) f.parentId else "") { mutableListOf() } += f
        for (list in kids.values) list.sortWith(folderOrder)
        // A note whose notebook is gone must still be somewhere: it falls to the root.
        val filed = HashMap<String, MutableList<Note>>()
        for (n in live) filed.getOrPut(if (n.folderId in ids) n.folderId else "") { mutableListOf() } += n
        val totals = folderTotals(folders, notes)

        fun noteRows(list: List<Note>, parent: String, depth: Int, trash: Boolean = false, folderId: String? = null) {
            val sorted = sortNotes(list, sortBy, trash)
            val shown = limits[parent] ?: PAGE
            for (note in sorted.take(shown)) {
                rows += Row(key = "$parent/${note.id}", kind = Kind.NOTE, depth = depth, parent = parent, label = note.title,
                    folderId = folderId, note = note)
            }
            if (sorted.size > shown) {
                rows += Row(key = "$parent/more", kind = Kind.MORE, depth = depth, parent = parent, label = "", count = sorted.size - shown)
            }
        }

        fun group(key: String, label: String, list: List<Note>, trash: Boolean = false, gap: Boolean = false) {
            val open = key in expanded && list.isNotEmpty()
            rows += Row(key = key, kind = Kind.GROUP, depth = 0, parent = "", label = label, count = list.size,
                expandable = list.isNotEmpty(), open = open, gap = gap)
            if (open) noteRows(list, key, 1, trash)
        }

        fun folderRows(parent: String, depth: Int) {
            for (folder in kids[parent].orEmpty()) {
                val subs = kids[folder.id].orEmpty()
                val own = filed[folder.id].orEmpty()
                val expandable = subs.isNotEmpty() || own.isNotEmpty()
                val open = folder.id in expanded && expandable
                rows += Row(key = folder.id, kind = Kind.FOLDER, depth = depth, parent = parent, label = folder.name,
                    count = totals[folder.id] ?: 0, expandable = expandable, open = open, folderId = folder.id, folder = folder)
                if (!open) continue
                // Sub-notebooks first, as in a file manager that sorts folders on top.
                if (depth < 64) folderRows(folder.id, depth + 1)
                noteRows(own, folder.id, depth + 1, folderId = folder.id)
            }
        }

        // Pinned is a section of its own above the tree, open unless closed.
        val pinned = live.filter { it.pinned }
        if (pinned.isNotEmpty()) {
            val open = closedKey(PINNED_KEY) !in expanded
            rows += Row(key = PINNED_KEY, kind = Kind.SECTION, depth = 0, parent = "", label = "置顶", count = pinned.size,
                expandable = true, open = open)
            if (open) noteRows(pinned, PINNED_KEY, 0)
        }

        rows += Row(key = "h:notes", kind = Kind.HEADING, depth = 0, parent = "", label = "笔记", folderId = "")
        if (folders.isNotEmpty()) {
            folderRows("", 0)
        } else {
            rows += Row(key = "h:create", kind = Kind.CREATE, depth = 0, parent = "", label = "新建笔记本")
        }
        // Notes outside any notebook sit below them, like files at the root of a drive.
        noteRows(filed[""].orEmpty(), "", 0, folderId = "")

        if (tags.isNotEmpty()) {
            val open = TAGS_KEY in expanded
            rows += Row(key = TAGS_KEY, kind = Kind.GROUP, depth = 0, parent = "", label = "标签", expandable = true, open = open, gap = true)
            if (open) {
                for (tag in tags.sortedWith { a, b -> compareTitles(a.name, b.name) }) {
                    val key = "t:" + tag.id
                    val list = live.filter { tag.id in it.tags }
                    val isOpen = key in expanded && list.isNotEmpty()
                    rows += Row(key = key, kind = Kind.TAG, depth = 1, parent = TAGS_KEY, label = tag.name, count = list.size,
                        expandable = list.isNotEmpty(), open = isOpen, tag = tag)
                    if (isOpen) noteRows(list, key, 2)
                }
            }
        }
        group(TRASH_KEY, "回收站", trashed, trash = true, gap = tags.isEmpty())
        return rows
    }

    /** The branches to open so that a note shows up where it lives. */
    fun revealKeys(note: Note, folders: Map<String, Folder>): List<String> {
        if (note.isTrashed) return listOf(TRASH_KEY)
        val path = ArrayList<String>()
        var id = note.folderId
        var depth = 0
        while (depth < 64) {
            val f = folders[id] ?: break
            path.add(0, f.id)
            id = f.parentId
            depth++
        }
        return path
    }

    /** The key of the row where a note lives (not its pinned or tag copies). */
    fun homeKey(note: Note, folders: Map<String, Folder>): String {
        if (note.isTrashed) return "$TRASH_KEY/${note.id}"
        val parent = if (folders.containsKey(note.folderId)) note.folderId else ""
        return "$parent/${note.id}"
    }

    /** Row keys are `parent/noteId` for notes. */
    fun noteId(key: String): String? {
        val slash = key.lastIndexOf('/')
        if (slash < 0) return null
        val id = key.substring(slash + 1)
        return if (id == "more" || id.isEmpty()) null else id
    }

    /** Notebooks depth-first, for "move to" pickers. */
    fun flatFolders(folders: Collection<Folder>): List<Pair<Folder, Int>> {
        val byParent = folders.groupBy { it.parentId }
        val ids = folders.mapTo(HashSet()) { it.id }
        val out = ArrayList<Pair<Folder, Int>>()
        fun walk(parent: String, depth: Int) {
            if (depth >= 64) return
            val list = if (parent.isEmpty()) folders.filter { it.parentId.isEmpty() || it.parentId !in ids } else byParent[parent].orEmpty()
            for (f in list.sortedWith(folderOrder)) {
                out += f to depth
                walk(f.id, depth + 1)
            }
        }
        walk("", 0)
        return out
    }
}

fun displayTitle(n: Note): String = n.title.ifBlank { "无标题" }
