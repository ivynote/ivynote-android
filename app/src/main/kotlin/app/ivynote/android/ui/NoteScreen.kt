package app.ivynote.android.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.ivynote.core.L10n
import app.ivynote.core.Links
import app.ivynote.core.Outline
import app.ivynote.core.SyncEngine
import app.ivynote.core.displayTitle

/**
 * A note: the title in the bar (tap it to rename), the body in the web
 * client's CodeMirror editor with live preview. Every change is saved locally
 * at once and synced in the background; there is no save button (guide 3.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(ui: LibraryUiState, noteId: String, wide: Boolean, onBack: () -> Unit) {
    val lib = ui.lib
    val engine = lib.engine
    val host = lib.editor
    val c = Ivy.colors
    val s = LocalStrings.current
    val dark = isSystemInDarkTheme()
    val revision by engine.revision.collectAsState()
    val focused by host.focused.collectAsState()
    val note = remember(revision, noteId) { engine.notes[noteId] } ?: return

    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var backlinks by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val adding by lib.adding.collectAsState()
    val protection by host.protection.collectAsState()
    val linkCount = remember(revision, noteId) { Links.backlinks(noteId, engine.notes.values).size }

    // Picked files land in the note they were picked for, even if another is open by the time they are ready.
    var pickTarget by remember { mutableStateOf(noteId) }
    val picker = rememberAttachmentPicker(lib) { added -> insertAttachment(lib, pickTarget, added.markdown) }
    fun pick(open: () -> Unit) {
        pickTarget = noteId
        open()
    }
    // Photos and files chosen from the editor's insert menu ("/" or ＋).
    DisposableEffect(host, noteId) {
        host.onPick = { kind -> if (kind == "image") pick(picker.photos) else pick(picker.files) }
        onDispose { host.onPick = null }
    }

    // Open the note in the editor once per note; afterwards only changes from elsewhere go in.
    LaunchedEffect(noteId) {
        host.setTheme(dark)
        val n = engine.notes[noteId] ?: return@LaunchedEffect
        host.open(n, focus = n.body.isEmpty() && n.title.isEmpty() && !n.isTrashed)
        // Opened from the agenda: show the task.
        ui.pendingLine?.takeIf { it.first == noteId }?.let { (_, line) ->
            ui.pendingLine = null
            host.scrollToLine(line)
        }
    }
    LaunchedEffect(dark) { host.setTheme(dark) }
    LaunchedEffect(s) { host.setLanguage(L10n.language) }
    LaunchedEffect(note.body) { host.bodyChanged(noteId, note.body) }
    LaunchedEffect(note.isTrashed) { host.setReadonly(note.isTrashed) }
    // New and uploaded files: embeds render as their kind.
    LaunchedEffect(revision) { host.refreshKinds() }

    Column(Modifier.fillMaxSize().background(c.surface).then(if (wide) Modifier else Modifier.statusBarsPadding())) {
        // The bar: back, title, outline, more.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) {
                IconButton(onClick = { host.hideKeyboard(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back, tint = c.ink2)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !note.isTrashed) { renaming = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(displayTitle(note), style = SerifTitle, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (note.title.isBlank()) c.muted else c.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val folder = engine.folders[note.folderId]?.name
                    Text(listOfNotNull(folder, shortDate(note.updatedAt)).joinToString(" · "), fontSize = 12.sp, color = c.faint, maxLines = 1)
                    if (!engine.isLocal && engine.hasPending(note.id)) {
                        Spacer(Modifier.width(6.dp))
                        Dot(c.warn)
                    }
                    if (adding > 0) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = c.muted)
                    }
                }
            }
            if (focused && !wide) {
                TextButton(onClick = { host.hideKeyboard() }) { Text(s.done, color = c.ivy, fontWeight = FontWeight.SemiBold) }
            }
            if (protection.protected) {
                Icon(Icons.Outlined.Lock, contentDescription = s.protectedNote, tint = c.faint, modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
            }
            // A protected note is never shared: the server cannot read it.
            if (!note.isTrashed && !protection.protected) {
                IconButton(onClick = { sharing = true }) { Icon(Icons.Outlined.IosShare, contentDescription = s.share, tint = c.ink2) }
            }
            IconButton(onClick = { outline = true }) { Icon(Icons.Outlined.Toc, contentDescription = s.outline, tint = c.ink2) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = s.more, tint = c.ink2) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!note.isTrashed) {
                        DropdownMenuItem(text = { Text(s.tagsEllipsis) }, leadingIcon = { Icon(Icons.Outlined.Sell, null) },
                            onClick = { menu = false; tagging = true })
                    }
                    DropdownMenuItem(text = { Text(if (linkCount > 0) s.backlinksMenu(linkCount) else s.backlinks) }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                        onClick = { menu = false; backlinks = true })
                    if (!engine.isLocal) {
                        DropdownMenuItem(text = { Text(s.history) }, leadingIcon = { Icon(Icons.Outlined.History, null) },
                            onClick = { menu = false; history = true })
                    }
                    if (!note.isTrashed && !protection.locked) {
                        DropdownMenuItem(text = { Text(s.dateAndReminder) }, leadingIcon = { Icon(Icons.Outlined.Event, null) },
                            onClick = { menu = false; host.command("due") })
                    }
                    if (!note.isTrashed && !protection.locked) {
                        HorizontalDivider()
                        if (protection.protected) {
                            DropdownMenuItem(text = { Text(s.lockNoteNow) }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                                onClick = { menu = false; host.command("lock") })
                            DropdownMenuItem(text = { Text(s.changeNotePassword) }, leadingIcon = { Icon(Icons.Outlined.VpnKey, null) },
                                onClick = { menu = false; host.command("changePassword") })
                            DropdownMenuItem(text = { Text(s.removeNotePassword) }, leadingIcon = { Icon(Icons.Outlined.LockOpen, null) },
                                onClick = { menu = false; host.command("unprotect") })
                        } else {
                            DropdownMenuItem(text = { Text(s.protectNote) }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                                onClick = { menu = false; host.command("protect") })
                        }
                    }
                    HorizontalDivider()
                    NoteMenuItems(note, ui, engine, ShelfActions(
                        newNote = ui::newNote, newFolder = {}, renameFolder = {}, deleteFolder = {},
                        renameNote = { renaming = true }, moveNote = { moving = true }, emptyTrash = {},
                    )) { menu = false }
                }
            }
        }

        if (note.isTrashed) {
            Row(
                Modifier.fillMaxWidth().background(c.warnSoft).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Delete, null, tint = c.warnInk, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.noteInTrash, color = c.warnInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { engine.updateNote(note.id, SyncEngine.NotePatch(trashed = false)) }) { Text(s.restore, color = c.warnInk) }
                TextButton(onClick = {
                    ui.selection = null
                    engine.deleteNote(note.id)
                }) { Text(s.deleteForever, color = c.danger) }
            }
        }

        // Tags and backlinks under the title (guide 11.3); hidden while typing, to leave room for the text.
        if (!focused || wide) {
            val hasTags = note.tags.any { engine.tags[it] != null }
            if (hasTags || !note.isTrashed || linkCount > 0) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NoteTagsRow(note, engine, editable = !note.isTrashed, onEdit = { tagging = true }, modifier = Modifier.weight(1f))
                    if (linkCount > 0) {
                        Text(s.backlinksCount(linkCount), fontSize = 12.5.sp, color = c.ivy,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { backlinks = true }.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            EditorView(lib.editor, Modifier.fillMaxSize())
            if (focused && !note.isTrashed) {
                FormatBar(host, Modifier.align(Alignment.BottomCenter).padding(10.dp), attach = { which ->
                    when (which) {
                        "photo" -> pick(picker.photos)
                        "camera" -> pick(picker.camera)
                        else -> pick(picker.files)
                    }
                })
            }
        }
        if (!focused) Spacer(Modifier.navigationBarsPadding())
    }

    if (renaming) {
        TextInputDialog(title = s.renameNote, initial = note.title, placeholder = s.untitled, onDismiss = { renaming = false }) { t ->
            engine.updateNote(note.id, SyncEngine.NotePatch(title = cleanTitle(t)))
        }
    }
    if (moving) MoveDialog(engine, note, onDismiss = { moving = false })
    if (tagging) TagPickerSheet(engine, note.id, onDismiss = { tagging = false })
    if (backlinks) BacklinksSheet(engine, note, onOpen = { id -> backlinks = false; ui.reveal(id) }, onDismiss = { backlinks = false })
    if (history) HistoryScreen(lib, note, onDismiss = { history = false })
    if (sharing) ShareSheet(lib, note, onDismiss = { sharing = false })
    if (outline) {
        OutlineSheet(note.body, host.topLine.collectAsState().value, onPick = { line ->
            outline = false
            host.scrollToLine(line)
        }, onDismiss = { outline = false })
    }
}

/**
 * Puts an attachment's embed into a note: at the cursor when the note is the
 * one in the editor, otherwise on a new line at its end.
 */
private fun insertAttachment(lib: app.ivynote.android.LibrarySession, noteId: String, markdown: String) {
    val host = lib.editor
    host.refreshKinds()
    if (host.noteId == noteId) {
        host.insert(markdown)
        return
    }
    val n = lib.engine.notes[noteId] ?: return
    val body = if (n.body.isEmpty() || n.body.endsWith("\n")) n.body + markdown + "\n" else n.body + "\n" + markdown + "\n"
    lib.engine.updateNote(noteId, SyncEngine.NotePatch(body = body))
}

/** The one editor web view of the library, moved into whichever note screen shows it. */
@Composable
private fun EditorView(host: app.ivynote.android.editor.EditorHost, modifier: Modifier) {
    val web by host.webView.collectAsState()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { frame ->
            if (web.parent !== frame) {
                frame.removeAllViews()
                frame.addView(host.attach(frame.context), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { frame ->
            // Only if it is still here: another screen may have taken it already.
            val current = host.webView.value
            if (current.parent === frame) host.detach(current)
        },
    )
}

/** The phone's insert bar above the keyboard (guide 3.2, 11.3): formatting the soft keyboard lacks. */
@Composable
private fun FormatBar(host: app.ivynote.android.editor.EditorHost, modifier: Modifier = Modifier, attach: (String) -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val files: List<Triple<String, ImageVector, String>> = listOf(
        Triple("photo", Icons.Outlined.Image, s.formatPhoto),
        Triple("camera", Icons.Outlined.PhotoCamera, s.formatCamera),
        Triple("file", Icons.Outlined.AttachFile, s.formatAttachment),
    )
    val items: List<Triple<String, ImageVector, String>> = listOf(
        Triple("insert", Icons.Outlined.Add, s.formatInsert),
        Triple("h1", Icons.Outlined.Title, s.formatHeading),
        Triple("bold", Icons.Outlined.FormatBold, s.formatBold),
        Triple("italic", Icons.Outlined.FormatItalic, s.formatItalic),
        Triple("task", Icons.Outlined.CheckBox, s.formatTask),
        Triple("due", Icons.Outlined.Event, s.formatDue),
        Triple("list", Icons.AutoMirrored.Filled.FormatListBulleted, s.formatList),
        Triple("ordered", Icons.Outlined.FormatListNumbered, s.formatOrdered),
        Triple("quote", Icons.Outlined.FormatQuote, s.formatQuote),
        Triple("code", Icons.Outlined.Code, s.formatCode),
        Triple("wiki", Icons.Outlined.AddLink, s.formatWikiLink),
        Triple("link", Icons.Outlined.Link, s.formatLink),
        Triple("strike", Icons.Outlined.StrikethroughS, s.formatStrike),
    )
    androidx.compose.material3.Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        color = c.surface,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).padding(start = 4.dp)) {
                for ((which, icon, label) in files) {
                    IconButton(onClick = { attach(which) }) { Icon(icon, contentDescription = label, tint = c.ink2) }
                }
                for ((cmd, icon, label) in items) {
                    IconButton(onClick = { host.command(cmd) }) { Icon(icon, contentDescription = label, tint = c.ink2) }
                }
            }
            IconButton(onClick = { host.command("undo") }) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = s.undo, tint = c.ink2) }
            IconButton(onClick = { host.hideKeyboard() }) { Icon(Icons.Outlined.KeyboardHide, contentDescription = s.hideKeyboard, tint = c.ink2) }
        }
    }
}

/** The note's headings: tap to scroll there; the section in view is marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(body: String, topLine: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val headings = remember(body) { Outline.headings(body) }
    val current = Outline.current(headings, topLine)
    val top = headings.minOfOrNull { it.level } ?: 1
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = c.surface) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.outline, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink)
            if (headings.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text("${headings.size}", fontSize = 13.sp, color = c.faint)
            }
        }
        if (headings.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text(s.noHeadings, color = c.muted, fontWeight = FontWeight.Medium)
                Text(s.noHeadingsHint, color = c.faint, fontSize = 13.sp)
            }
        } else {
            HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(headings, key = { it.line }) { h ->
                    val depth = minOf(h.level - top, 4)
                    val active = h.line == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (active) c.ivySoft else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(h.line) }
                            .padding(start = (12 + depth * 16).dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(h.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal,
                            color = when {
                                active -> c.ivyInk
                                depth == 0 -> c.ink
                                else -> c.ink2
                            })
                    }
                }
            }
        }
    }
}
