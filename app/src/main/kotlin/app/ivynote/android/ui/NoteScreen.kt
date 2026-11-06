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
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.History
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
    val linkCount = remember(revision, noteId) { Links.backlinks(noteId, engine.notes.values).size }

    // Picked files land in the note they were picked for, even if another is open by the time they are ready.
    var pickTarget by remember { mutableStateOf(noteId) }
    val picker = rememberAttachmentPicker(lib) { added -> insertAttachment(lib, pickTarget, added.markdown) }
    fun pick(open: () -> Unit) {
        pickTarget = noteId
        open()
    }

    // Open the note in the editor once per note; afterwards only changes from elsewhere go in.
    LaunchedEffect(noteId) {
        host.setTheme(dark)
        val n = engine.notes[noteId] ?: return@LaunchedEffect
        host.open(n, focus = n.body.isEmpty() && n.title.isEmpty() && !n.isTrashed)
    }
    LaunchedEffect(dark) { host.setTheme(dark) }
    LaunchedEffect(note.body) { host.bodyChanged(noteId, note.body) }
    LaunchedEffect(note.isTrashed) { host.setReadonly(note.isTrashed) }
    // New and uploaded files: embeds render as their kind.
    LaunchedEffect(revision) { host.refreshKinds() }

    Column(Modifier.fillMaxSize().background(c.surface).then(if (wide) Modifier else Modifier.statusBarsPadding())) {
        // The bar: back, title, outline, more.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) {
                IconButton(onClick = { host.hideKeyboard(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = c.ink2)
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
                TextButton(onClick = { host.hideKeyboard() }) { Text("完成", color = c.ivy, fontWeight = FontWeight.SemiBold) }
            }
            if (!note.isTrashed) {
                IconButton(onClick = { sharing = true }) { Icon(Icons.Outlined.IosShare, contentDescription = "分享", tint = c.ink2) }
            }
            IconButton(onClick = { outline = true }) { Icon(Icons.Outlined.Toc, contentDescription = "大纲", tint = c.ink2) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = c.ink2) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!note.isTrashed) {
                        DropdownMenuItem(text = { Text("标签…") }, leadingIcon = { Icon(Icons.Outlined.Sell, null) },
                            onClick = { menu = false; tagging = true })
                    }
                    DropdownMenuItem(text = { Text(if (linkCount > 0) "反向链接（$linkCount）" else "反向链接") }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                        onClick = { menu = false; backlinks = true })
                    if (!engine.isLocal) {
                        DropdownMenuItem(text = { Text("历史版本") }, leadingIcon = { Icon(Icons.Outlined.History, null) },
                            onClick = { menu = false; history = true })
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
                Text("这篇笔记在回收站中", color = c.warnInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { engine.updateNote(note.id, SyncEngine.NotePatch(trashed = false)) }) { Text("恢复", color = c.warnInk) }
                TextButton(onClick = {
                    ui.selection = null
                    engine.deleteNote(note.id)
                }) { Text("永久删除", color = c.danger) }
            }
        }

        // Tags and backlinks under the title (guide 11.3); hidden while typing, to leave room for the text.
        if (!focused || wide) {
            val hasTags = note.tags.any { engine.tags[it] != null }
            if (hasTags || !note.isTrashed || linkCount > 0) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NoteTagsRow(note, engine, editable = !note.isTrashed, onEdit = { tagging = true }, modifier = Modifier.weight(1f))
                    if (linkCount > 0) {
                        Text("$linkCount 条反向链接", fontSize = 12.5.sp, color = c.ivy,
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
        TextInputDialog(title = "重命名笔记", initial = note.title, placeholder = "无标题", onDismiss = { renaming = false }) { t ->
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
    val files: List<Triple<String, ImageVector, String>> = listOf(
        Triple("photo", Icons.Outlined.Image, "图片"),
        Triple("camera", Icons.Outlined.PhotoCamera, "拍照"),
        Triple("file", Icons.Outlined.AttachFile, "附件"),
    )
    val items: List<Triple<String, ImageVector, String>> = listOf(
        Triple("h1", Icons.Outlined.Title, "标题"),
        Triple("bold", Icons.Outlined.FormatBold, "加粗"),
        Triple("italic", Icons.Outlined.FormatItalic, "斜体"),
        Triple("task", Icons.Outlined.CheckBox, "待办"),
        Triple("list", Icons.AutoMirrored.Filled.FormatListBulleted, "列表"),
        Triple("ordered", Icons.Outlined.FormatListNumbered, "编号"),
        Triple("quote", Icons.Outlined.FormatQuote, "引用"),
        Triple("code", Icons.Outlined.Code, "代码"),
        Triple("wiki", Icons.Outlined.AddLink, "双链"),
        Triple("link", Icons.Outlined.Link, "链接"),
        Triple("strike", Icons.Outlined.StrikethroughS, "删除线"),
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
            IconButton(onClick = { host.command("undo") }) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销", tint = c.ink2) }
            IconButton(onClick = { host.hideKeyboard() }) { Icon(Icons.Outlined.KeyboardHide, contentDescription = "收起键盘", tint = c.ink2) }
        }
    }
}

/** The note's headings: tap to scroll there; the section in view is marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(body: String, topLine: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val headings = remember(body) { Outline.headings(body) }
    val current = Outline.current(headings, topLine)
    val top = headings.minOfOrNull { it.level } ?: 1
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = c.surface) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("大纲", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink)
            if (headings.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text("${headings.size}", fontSize = 13.sp, color = c.faint)
            }
        }
        if (headings.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text("还没有标题", color = c.muted, fontWeight = FontWeight.Medium)
                Text("以 # 开头的行会出现在这里。", color = c.faint, fontSize = 13.sp)
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
