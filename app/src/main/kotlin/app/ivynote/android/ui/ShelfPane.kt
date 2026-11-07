package app.ivynote.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.IvyApp
import app.ivynote.core.Folder
import app.ivynote.core.Note
import app.ivynote.core.SessionStore
import app.ivynote.core.Search
import app.ivynote.core.Shelf
import app.ivynote.core.SyncEngine
import app.ivynote.core.displayTitle
import kotlinx.coroutines.launch

private const val SORT_KEY = "ivy.shelf.sort"

/** The shelf (guide 11.2): the library header, search, and the tree of notebooks and notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfPane(ui: LibraryUiState, session: SessionStore?, wide: Boolean, modifier: Modifier = Modifier) {
    val lib = ui.lib
    val engine = lib.engine
    val revision by engine.revision.collectAsState()
    val state by engine.state.collectAsState()
    val prefs = IvyApp.instance.prefs
    var sortBy by remember { mutableStateOf(prefs.getString(SORT_KEY)?.let { runCatching { Shelf.SortBy.valueOf(it) }.getOrNull() } ?: Shelf.SortBy.UPDATED) }
    val limits = remember { mutableStateMapOf<String, Int>() }
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Dialogs
    var newFolderParent by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var deletingFolder by remember { mutableStateOf<Folder?>(null) }
    var renamingNote by remember { mutableStateOf<Note?>(null) }
    var movingNote by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<app.ivynote.core.Tag?>(null) }
    val s = LocalStrings.current

    // The labels (置顶, 笔记, 回收站…) are in the interface language.
    val rows = remember(revision, ui.expanded, sortBy, limits.toMap(), s) {
        Shelf.build(engine.folders.values, engine.notes.values, engine.tags.values, ui.expanded, sortBy, limits)
    }
    val actions = ShelfActions(
        newNote = { folder -> query = ""; ui.newNote(folder) },
        newFolder = { parent -> newFolderParent = parent },
        renameFolder = { renamingFolder = it },
        deleteFolder = { deletingFolder = it },
        renameNote = { renamingNote = it },
        moveNote = { movingNote = it },
        emptyTrash = { confirmEmpty = true },
        editTag = { editingTag = it },
    )

    Box(modifier) {
        Column(Modifier.fillMaxSize().then(if (wide) Modifier else Modifier.statusBarsPadding())) {
            LibraryHeader(ui, session, state = state)
            SearchField(query, onChange = { query = it })
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        try { engine.sync() } finally { refreshing = false }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (query.isBlank()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp),
                    ) {
                        items(rows, key = { it.key }, contentType = { it.kind }) { row ->
                            ShelfRow(row, ui, engine, sortBy, onSort = { sortBy = it; prefs.putString(SORT_KEY, it.name) },
                                actions = actions, onMore = { limits[row.parent] = (limits[row.parent] ?: Shelf.PAGE) + Shelf.PAGE })
                        }
                    }
                } else {
                    SearchResults(engine, revision, query) { id -> ui.reveal(id) }
                }
            }
        }
        // 新建笔记: where the thumb is (guide 11.1: the phone's round ink button).
        FloatingActionButton(
            onClick = { actions.newNote("") },
            containerColor = Ivy.colors.ink,
            contentColor = Ivy.colors.canvas,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Icon(Icons.Outlined.Edit, contentDescription = s.newNote) }
    }

    newFolderParent?.let { parent ->
        TextInputDialog(title = if (parent.isEmpty()) s.newFolder else s.newSubfolder, confirm = s.create, onDismiss = { newFolderParent = null }) { name ->
            val n = name.trim().take(100)
            if (n.isNotEmpty()) {
                engine.createFolder(n, parent)
                if (parent.isNotEmpty()) ui.updateExpanded(ui.expanded + parent)
            }
        }
    }
    renamingFolder?.let { f ->
        TextInputDialog(title = s.renameFolder, initial = f.name, onDismiss = { renamingFolder = null }) { name ->
            val n = name.trim().take(100)
            if (n.isNotEmpty()) engine.renameFolder(f.id, n)
        }
    }
    deletingFolder?.let { f ->
        ConfirmDialog(s.deleteFolderTitle, s.deleteFolderMessage(f.name), s.delete, onDismiss = { deletingFolder = null }) {
            engine.deleteFolder(f.id)
        }
    }
    renamingNote?.let { n ->
        TextInputDialog(title = s.renameNote, initial = n.title, placeholder = s.untitled, onDismiss = { renamingNote = null }) { title ->
            engine.updateNote(n.id, SyncEngine.NotePatch(title = cleanTitle(title)))
        }
    }
    movingNote?.let { n ->
        MoveDialog(engine, n, onDismiss = { movingNote = null })
    }
    editingTag?.let { t -> TagEditDialog(engine, t, onDismiss = { editingTag = null }) }
    if (confirmEmpty) {
        ConfirmDialog(s.emptyTrashTitle, s.emptyTrashMessage, s.emptyTrash, onDismiss = { confirmEmpty = false }) {
            if (ui.selectedNote?.let { engine.notes[it]?.isTrashed } == true) ui.selection = null
            engine.emptyTrash()
        }
    }
}

/** Titles: one line, at most 300 characters (guide 4.3). */
fun cleanTitle(s: String): String = s.replace('\n', ' ').replace('\r', ' ').trim().take(300)

class ShelfActions(
    val newNote: (String) -> Unit,
    val newFolder: (String) -> Unit,
    val renameFolder: (Folder) -> Unit,
    val deleteFolder: (Folder) -> Unit,
    val renameNote: (Note) -> Unit,
    val moveNote: (Note) -> Unit,
    val emptyTrash: () -> Unit,
    val editTag: (app.ivynote.core.Tag) -> Unit = {},
)

// Header

/**
 * The open library at the head of the shelf: its name and sync status; a tap
 * opens a menu to switch libraries, open a synced library's account, or the list.
 */
@Composable
private fun LibraryHeader(ui: LibraryUiState, session: SessionStore?, state: app.ivynote.core.SyncState) {
    val catalog = IvyApp.instance.catalog
    val libraries by catalog.libraries.collectAsState()
    val ref = ui.lib.ref
    var open by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    val s = LocalStrings.current
    if (choosingLanguage) LanguageDialog(onDismiss = { choosingLanguage = false })
    var about by remember { mutableStateOf(false) }
    if (about) AboutSheet(onDismiss = { about = false })
    Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIcon(ref, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ref.name, style = SerifTitle, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ivy.colors.ink)
                SyncStatusLine(ui.lib.engine, state)
            }
            Icon(Icons.Outlined.UnfoldMore, contentDescription = s.switchLibrary, tint = Ivy.colors.faint, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Caption(s.libraries, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for (l in libraries) {
                DropdownMenuItem(
                    text = { Text(l.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { if (l.id == ref.id) Icon(Icons.Outlined.Check, null, tint = Ivy.colors.ivy) else LibraryIcon(l, 24.dp) },
                    onClick = {
                        open = false
                        if (l.id != ref.id) catalog.open(l.id)
                    },
                )
            }
            HorizontalDivider()
            if (!ref.isLocal && session != null) {
                DropdownMenuItem(text = { Text(s.accountAndSync) }, leadingIcon = { Icon(Icons.Outlined.ManageAccounts, null) },
                    onClick = { open = false; ui.showAccount = true })
            } else if (catalog.library(ref.id)?.canTurnOnSync == true) {
                DropdownMenuItem(text = { Text(s.turnOnSyncEllipsis) }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) },
                    onClick = { open = false; catalog.turnOnSync(ref.id) })
            }
            DropdownMenuItem(text = { Text(s.mediaLibrary) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                onClick = { open = false; ui.showMedia = true })
            DropdownMenuItem(text = { Text(s.language) }, leadingIcon = { Icon(Icons.Outlined.Language, null) },
                onClick = { open = false; choosingLanguage = true })
            DropdownMenuItem(text = { Text(s.manageLibraries) }, leadingIcon = { Icon(Icons.Outlined.Book, null) },
                onClick = { open = false; catalog.open(null) })
            DropdownMenuItem(text = { Text(s.aboutAndUpdates) }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                onClick = { open = false; about = true })
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val c = Ivy.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(LocalStrings.current.searchNotes, color = c.faint, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                cursorBrush = SolidColor(c.ivy),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Outlined.Close, contentDescription = LocalStrings.current.clear, tint = c.faint,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") })
        }
    }
}

// Rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfRow(
    row: Shelf.Row,
    ui: LibraryUiState,
    engine: SyncEngine,
    sortBy: Shelf.SortBy,
    onSort: (Shelf.SortBy) -> Unit,
    actions: ShelfActions,
    onMore: () -> Unit,
) {
    val c = Ivy.colors
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val selected = row.kind == Shelf.Kind.NOTE && ui.selection == row.key
    val tap: () -> Unit = {
        when (row.kind) {
            Shelf.Kind.SECTION -> ui.toggle(Shelf.closedKey(row.key))
            Shelf.Kind.GROUP, Shelf.Kind.FOLDER, Shelf.Kind.TAG -> if (row.expandable) ui.toggle(row.key)
            Shelf.Kind.MORE -> onMore()
            Shelf.Kind.CREATE -> actions.newFolder("")
            Shelf.Kind.NOTE -> ui.selection = row.key
            Shelf.Kind.HEADING -> menu = true
        }
    }
    val hasMenu = row.kind in setOf(Shelf.Kind.FOLDER, Shelf.Kind.NOTE, Shelf.Kind.HEADING, Shelf.Kind.TAG) ||
        (row.key == Shelf.TRASH_KEY && row.count > 0)
    Box(Modifier.padding(top = if (row.gap) 14.dp else 0.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) c.ink.copy(alpha = 0.09f) else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = tap,
                    onLongClick = if (hasMenu) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) else null,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            RowContent(row, engine, onHeadingMenu = { menu = true })
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            RowMenu(row, ui, engine, sortBy, onSort, actions) { menu = false }
        }
    }
}

@Composable
private fun RowContent(row: Shelf.Row, engine: SyncEngine, onHeadingMenu: () -> Unit) {
    val c = Ivy.colors
    when (row.kind) {
        Shelf.Kind.SECTION -> Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
            Spacer(Modifier.width(6.dp))
            Text("${row.count}", fontSize = 12.5.sp, color = c.faint)
            Spacer(Modifier.weight(1f))
            Chevron(row.open, Modifier.padding(end = 14.dp))
        }
        Shelf.Kind.HEADING -> Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f))
            IconButton(onClick = onHeadingMenu) { Icon(Icons.Outlined.MoreHoriz, contentDescription = LocalStrings.current.sortAndNew, tint = c.muted) }
        }
        Shelf.Kind.CREATE -> Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, null, tint = c.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(row.label, color = c.muted, fontSize = 15.sp)
        }
        Shelf.Kind.MORE -> Row(Modifier.padding(start = indent(row.depth) + 30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(LocalStrings.current.showMore(minOf(row.count, Shelf.PAGE), row.count), color = c.ivy, fontSize = 13.5.sp)
        }
        else -> Row(Modifier.fillMaxWidth().padding(start = 4.dp + indent(row.depth), end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                if (row.expandable) Chevron(row.open)
            }
            val tint = when {
                row.kind == Shelf.Kind.NOTE -> c.muted
                row.kind == Shelf.Kind.TAG && row.tag?.color.orEmpty().isNotEmpty() -> tagColor(row.tag!!.color)
                else -> c.ivy
            }
            Icon(rowIcon(row), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            val untitled = row.kind == Shelf.Kind.NOTE && row.label.isBlank()
            Text(
                when {
                    untitled -> LocalStrings.current.untitled
                    row.kind == Shelf.Kind.TAG -> "#" + row.label
                    else -> row.label
                },
                fontSize = 15.sp,
                color = if (untitled) c.muted else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val note = row.note
            if (note != null && engine.hasPending(note.id) && !engine.isLocal) {
                Spacer(Modifier.width(6.dp))
                Dot(c.warn)
            }
            if (note != null && note.pinned && !note.isTrashed && row.parent != Shelf.PINNED_KEY) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.PushPin, contentDescription = LocalStrings.current.pinned, tint = c.warn, modifier = Modifier.size(13.dp))
            }
            if (row.count > 0) {
                Spacer(Modifier.width(6.dp))
                Text("${row.count}", fontSize = 13.sp, color = c.faint)
            }
        }
    }
}

/** Narrow screens: deep branches indent less, so titles keep their room. */
private fun indent(depth: Int) = (minOf(depth, 4) * 16 + maxOf(depth - 4, 0) * 8).dp

@Composable
private fun Chevron(open: Boolean, modifier: Modifier = Modifier) {
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = if (open) LocalStrings.current.collapse else LocalStrings.current.expand,
        tint = Ivy.colors.faint, modifier = modifier.size(18.dp).rotate(angle))
}

private fun rowIcon(row: Shelf.Row): ImageVector = when (row.kind) {
    Shelf.Kind.NOTE -> Icons.Outlined.Description
    Shelf.Kind.FOLDER -> if (row.open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
    Shelf.Kind.TAG -> Icons.Outlined.Tag
    Shelf.Kind.GROUP -> when (row.key) {
        Shelf.PINNED_KEY -> Icons.Outlined.PushPin
        Shelf.TAGS_KEY -> Icons.Outlined.Tag
        else -> Icons.Outlined.Delete
    }
    else -> Icons.Outlined.Description
}

@Composable
private fun RowMenu(
    row: Shelf.Row,
    ui: LibraryUiState,
    engine: SyncEngine,
    sortBy: Shelf.SortBy,
    onSort: (Shelf.SortBy) -> Unit,
    actions: ShelfActions,
    close: () -> Unit,
) {
    fun item(text: String, icon: ImageVector, danger: Boolean = false, action: () -> Unit) = @Composable {
        DropdownMenuItem(
            text = { Text(text, color = if (danger) Ivy.colors.danger else Ivy.colors.ink) },
            leadingIcon = { Icon(icon, null, tint = if (danger) Ivy.colors.danger else Ivy.colors.ink2) },
            onClick = { close(); action() },
        )
    }
    when (row.kind) {
        Shelf.Kind.HEADING -> {
            val s = LocalStrings.current
            Caption(s.sort, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for (option in Shelf.SortBy.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { if (option == sortBy) Icon(Icons.Outlined.Check, null, tint = Ivy.colors.ivy) else Spacer(Modifier.size(24.dp)) },
                    onClick = { close(); onSort(option) },
                )
            }
            HorizontalDivider()
            item(s.newNote, Icons.Outlined.Edit) { actions.newNote("") }()
            item(s.newFolder, Icons.Outlined.CreateNewFolder) { actions.newFolder("") }()
        }
        Shelf.Kind.FOLDER -> row.folder?.let { f ->
            val s = LocalStrings.current
            item(s.newNote, Icons.Outlined.Edit) { actions.newNote(f.id) }()
            item(s.newSubfolder, Icons.Outlined.CreateNewFolder) { actions.newFolder(f.id) }()
            item(s.rename, Icons.Outlined.Edit) { actions.renameFolder(f) }()
            item(s.delete, Icons.Outlined.Delete, danger = true) { actions.deleteFolder(f) }()
        }
        Shelf.Kind.NOTE -> row.note?.let { n -> NoteMenuItems(n, ui, engine, actions, close) }
        Shelf.Kind.TAG -> row.tag?.let { t ->
            item(LocalStrings.current.editTagEllipsis, Icons.Outlined.Edit) { actions.editTag(t) }()
        }
        Shelf.Kind.GROUP -> if (row.key == Shelf.TRASH_KEY) {
            item(LocalStrings.current.emptyTrash, Icons.Outlined.DeleteForever, danger = true) { actions.emptyTrash() }()
        }
        else -> Unit
    }
}

/** The actions on a note, shared by the tree and the note's own menu. */
@Composable
fun NoteMenuItems(n: Note, ui: LibraryUiState, engine: SyncEngine, actions: ShelfActions?, close: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    fun clearIfSelected() {
        if (ui.selectedNote == n.id) ui.selection = null
    }
    if (n.isTrashed) {
        DropdownMenuItem(text = { Text(s.restore) }, leadingIcon = { Icon(Icons.Outlined.Restore, null) },
            onClick = { close(); engine.updateNote(n.id, SyncEngine.NotePatch(trashed = false)) })
        DropdownMenuItem(text = { Text(s.deleteForever, color = c.danger) }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null, tint = c.danger) },
            onClick = { close(); clearIfSelected(); engine.deleteNote(n.id) })
    } else {
        DropdownMenuItem(text = { Text(if (n.pinned) s.unpin else s.pin) }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
            onClick = { close(); engine.updateNote(n.id, SyncEngine.NotePatch(pinned = !n.pinned)) })
        if (actions != null) {
            DropdownMenuItem(text = { Text(s.rename) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { close(); actions.renameNote(n) })
            DropdownMenuItem(text = { Text(s.moveToEllipsis) }, leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                onClick = { close(); actions.moveNote(n) })
        }
        DropdownMenuItem(text = { Text(s.duplicateNote) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
            onClick = { close(); engine.duplicateNote(n.id)?.let { ui.reveal(it.id) } })
        DropdownMenuItem(text = { Text(s.moveToTrash, color = c.danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = c.danger) },
            onClick = { close(); clearIfSelected(); engine.updateNote(n.id, SyncEngine.NotePatch(trashed = true)) })
    }
}

/** Picks a notebook for a note (guide 11.2 "移动"). */
@Composable
fun MoveDialog(engine: SyncEngine, note: Note, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val revision by engine.revision.collectAsState()
    val folders = remember(revision) { Shelf.flatFolders(engine.folders.values) }
    val s = LocalStrings.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.moveTo) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    FolderOption(s.noNotebook, 0, note.folderId.isEmpty() || engine.folders[note.folderId] == null) {
                        engine.updateNote(note.id, SyncEngine.NotePatch(folderId = "")); onDismiss()
                    }
                }
                items(folders, key = { it.first.id }) { (f, depth) ->
                    FolderOption(f.name, depth + 1, note.folderId == f.id) {
                        engine.updateNote(note.id, SyncEngine.NotePatch(folderId = f.id)); onDismiss()
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(s.cancel, color = c.ink2) } },
    )
}

@Composable
private fun FolderOption(name: String, depth: Int, current: Boolean, onClick: () -> Unit) {
    val c = Ivy.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(start = (depth * 14).dp + 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = if (depth == 0) c.muted else c.ivy, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.ink)
        if (current) Icon(Icons.Outlined.Check, null, tint = c.ivy, modifier = Modifier.size(18.dp))
    }
}

// Search (guide 11.5)

@Composable
private fun SearchResults(engine: SyncEngine, revision: Long, query: String, onOpen: (String) -> Unit) {
    val c = Ivy.colors
    val hits = remember(revision, query) { Search.run(engine.notes.values, query) }
    if (hits.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(LocalStrings.current.noResults(query.trim()), color = c.muted)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp)) {
        items(hits, key = { it.note.id }) { hit ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(hit.note.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(displayTitle(hit.note), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (hit.note.title.isBlank()) c.muted else c.ink)
                if (hit.snippet.isNotBlank()) {
                    Text(
                        buildAnnotatedString {
                            append(hit.snippet)
                            for (r in hit.ranges) {
                                if (r.last < hit.snippet.length) addStyle(SpanStyle(background = c.warn.copy(alpha = 0.25f)), r.first, r.last + 1)
                            }
                        },
                        fontSize = 13.5.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shortDate(hit.note.updatedAt), fontSize = 12.sp, color = c.faint)
                    engine.folders[hit.note.folderId]?.let {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Folder, null, tint = c.faint, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(it.name, fontSize = 12.sp, color = c.faint, maxLines = 1)
                    }
                }
            }
        }
    }
}
