package app.ivynote.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.IvyApp
import app.ivynote.android.LibrarySession
import app.ivynote.core.L10n
import app.ivynote.core.SessionStore
import app.ivynote.core.Shelf
import app.ivynote.core.displayTitle

/** What the shelf and the note pane share: selection and expansion of one open library. */
class LibraryUiState(val lib: LibrarySession) {
    /** The selected row's key; a note can show in several places (pinned, tags, its notebook). */
    var selection by mutableStateOf<String?>(null)
    var expanded by mutableStateOf(IvyApp.instance.catalog.expanded(lib.ref.id))
    var showAccount by mutableStateOf(false)
    var showMedia by mutableStateOf(false)

    val selectedNote: String? get() = selection?.let(Shelf::noteId)

    fun updateExpanded(keys: Set<String>) {
        expanded = keys
        IvyApp.instance.catalog.saveExpanded(lib.ref.id, keys)
    }

    fun toggle(key: String) = updateExpanded(if (key in expanded) expanded - key else expanded + key)

    /** Opens a note where it lives in the tree, expanding the path to it. */
    fun reveal(id: String) {
        val engine = lib.engine
        val note = engine.notes[id] ?: return
        updateExpanded(expanded + Shelf.revealKeys(note, engine.folders))
        selection = Shelf.homeKey(note, engine.folders)
    }

    /** A followed `[[wiki link]]`. A missing note is created, like a wiki, in the notebook of the note the link is in (guide 3.2). */
    fun openLink(id: String, title: String) {
        val engine = lib.engine
        if (id.isNotEmpty() && engine.notes[id] != null) {
            reveal(id)
            return
        }
        val name = title.substringBefore('#').substringBefore('|').trim()
        if (name.isEmpty()) return
        val folder = selectedNote?.let { engine.notes[it]?.folderId } ?: ""
        reveal(engine.createNote(title = name, folderId = folder).id)
    }

    fun newNote(folder: String) {
        if (folder.isNotEmpty()) updateExpanded(expanded + folder)
        reveal(lib.engine.createNote(folderId = folder).id)
    }
}

/**
 * The bookshelf and the paper (guide 11.1): the shelf tree beside the open
 * note on wide screens; on phones one at a time, the note pushed over the shelf.
 */
@Composable
fun LibraryScreen(lib: LibrarySession, session: SessionStore?) {
    val ui = remember(lib) { LibraryUiState(lib) }
    val engine = lib.engine
    val revision by engine.revision.collectAsState()
    val conflict by engine.conflict.collectAsState()

    DisposableEffect(ui) {
        lib.editor.onOpenNote = { id, title -> ui.openLink(id, title) }
        onDispose { lib.editor.onOpenNote = null }
    }
    // A selected note that is gone (deleted elsewhere) closes.
    LaunchedEffect(revision) {
        val id = ui.selectedNote
        if (id != null && engine.notes[id] == null) ui.selection = null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ivy.colors.canvas)) {
        val wide = maxWidth >= 720.dp
        val noteId = ui.selectedNote?.takeIf { engine.notes[it] != null }
        if (wide) {
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                ShelfPane(ui, session, wide = true, modifier = Modifier.width(320.dp).fillMaxHeight())
                // The paper: a sheet on the desk, 8px from the edges (guide 11.1).
                Box(
                    Modifier.fillMaxSize().padding(top = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Ivy.colors.surface),
                ) {
                    if (noteId != null) {
                        NoteScreen(ui, noteId, wide = true, onBack = { ui.selection = null })
                    } else {
                        EmptyPaper()
                    }
                }
            }
        } else {
            BackHandler(enabled = noteId != null) { ui.selection = null }
            AnimatedContent(
                targetState = noteId,
                transitionSpec = {
                    if (targetState != null && initialState == null) {
                        slideInHorizontally(tween(320)) { it } togetherWith slideOutHorizontally(tween(320)) { -it / 5 } + fadeOut(tween(320), 0.6f)
                    } else if (targetState == null) {
                        slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(280), 0.6f) togetherWith slideOutHorizontally(tween(280)) { it }
                    } else {
                        fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                    }
                },
                label = "pane",
            ) { id ->
                if (id != null) {
                    Box(Modifier.fillMaxSize().background(Ivy.colors.surface)) {
                        NoteScreen(ui, id, wide = false, onBack = { ui.selection = null })
                    }
                } else {
                    ShelfPane(ui, session, wide = false, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    if (ui.showMedia) {
        MediaScreen(lib, onOpenNote = { id -> ui.showMedia = false; ui.reveal(id) }, onDismiss = { ui.showMedia = false })
    }

    // A queued file the server refused (guide 7.2): say so; the rest keeps syncing.
    val refused by engine.uploadFailure.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(refused) {
        val f = refused ?: return@LaunchedEffect
        toast(context, L10n.s.uploadRefused(f.name, f.message), long = true)
        engine.clearUploadFailure()
    }

    if (ui.showAccount && session != null) {
        AccountSheet(lib, session, onDismiss = { ui.showAccount = false })
    }

    val s = LocalStrings.current
    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = { engine.clearConflict() },
            title = { Text(s.conflictTitle) },
            text = {
                val name = engine.notes[c.noteId]?.let(::displayTitle) ?: s.note
                Text(s.conflictMessage(name))
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.reveal(c.copyId)
                    engine.clearConflict()
                }) { Text(s.openCopy) }
            },
            dismissButton = { TextButton(onClick = { engine.clearConflict() }) { Text(s.ok) } },
        )
    }
}

/** The paper with no note: the leaf and the motto (guide 11.6). */
@Composable
fun EmptyPaper() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IvyLeaf(64.dp, alpha = 0.7f)
        Text(LocalStrings.current.tagline, color = Ivy.colors.muted, fontSize = 14.sp)
    }
}
