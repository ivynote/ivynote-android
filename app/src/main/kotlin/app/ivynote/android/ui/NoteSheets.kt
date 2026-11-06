package app.ivynote.android.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ivynote.android.IvyApp
import app.ivynote.android.LibrarySession
import app.ivynote.core.ApiError
import app.ivynote.core.L10n
import app.ivynote.core.Links
import app.ivynote.core.Note
import app.ivynote.core.Revision
import app.ivynote.core.Share
import app.ivynote.core.SyncEngine
import app.ivynote.core.displayTitle
import kotlinx.coroutines.launch
import java.io.File

// Backlinks (guide 3.2: computed locally, so they work offline)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklinksSheet(engine: SyncEngine, note: Note, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val revision by engine.revision.collectAsState()
    val links = remember(revision, note.id) { Links.backlinks(note.id, engine.notes.values) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = c.surface) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.backlinks, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink)
            Spacer(Modifier.width(6.dp))
            Text("${links.size}", fontSize = 13.sp, color = c.faint)
        }
        if (links.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text(s.noBacklinks, color = c.muted, fontWeight = FontWeight.Medium)
                Text(s.noBacklinksHint(displayTitle(note)), color = c.faint, fontSize = 13.sp)
            }
        } else {
            HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(links, key = { it.id }) { n ->
                    val context = Links.linkContext(n.body, note.title, note.id)
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(n.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, tint = c.muted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(displayTitle(n), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (n.title.isBlank()) c.muted else c.ink, modifier = Modifier.weight(1f))
                            Text(shortDate(n.updatedAt), fontSize = 12.sp, color = c.faint)
                        }
                        if (context.isNotEmpty()) Text(context, fontSize = 13.5.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// History (guide 11.6)

/**
 * A note's saved versions, newest first; a tap previews one, 恢复 writes it
 * back through the normal local edit, so the restore syncs like any edit and
 * the replaced text stays in the history.
 */
@Composable
fun HistoryScreen(lib: LibrarySession, note: Note, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val context = LocalContext.current
    val api = lib.api
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Revision>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Revision?>(null) }
    var preview by remember { mutableStateOf<Revision?>(null) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(note.id) {
        if (api == null) return@LaunchedEffect
        try {
            // The newest edits are on the server first, so the list ends with them.
            lib.engine.sync()
            list = api.history(note.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }
    LaunchedEffect(picked?.rev) {
        val p = picked ?: return@LaunchedEffect
        preview = null
        preview = try { api?.revision(note.id, p.rev) } catch (e: ApiError) { error = errorText(e); null }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (picked != null) picked = null else onDismiss() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back, tint = c.ink2)
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(s.history, style = SerifTitle, fontSize = 18.sp, color = c.ink)
                    Text(displayTitle(note), fontSize = 12.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val p = picked
                if (p != null && p.rev != list?.firstOrNull()?.rev) {
                    TextButton(enabled = preview != null, onClick = { confirm = true }) { Text(s.restoreVersion, color = c.ivy, fontWeight = FontWeight.SemiBold) }
                }
            }
            val l = list
            when {
                api == null -> EmptyState(Icons.Outlined.History, s.localNoHistory, s.localNoHistoryHint)
                error != null && l == null -> EmptyState(Icons.Outlined.WifiOff, s.historyLoadFailed, error ?: "")
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.muted) }
                l.isEmpty() -> EmptyState(Icons.Outlined.History, s.noHistory, s.noHistoryHint)
                picked != null -> {
                    val r = preview
                    if (r == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.muted) }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp).navigationBarsPadding()) {
                            Text(revisionLabel(r, l.first().rev == r.rev), fontSize = 12.5.sp, color = c.faint)
                            Spacer(Modifier.height(8.dp))
                            Text(r.title.ifBlank { s.untitled }, style = SerifTitle, fontSize = 22.sp, color = if (r.title.isBlank()) c.muted else c.ink)
                            Spacer(Modifier.height(12.dp))
                            SelectionContainer {
                                Text(r.body.orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, color = c.ink2, lineHeight = 20.sp)
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 32.dp)) {
                    items(l, key = { it.rev }) { r ->
                        val current = r.rev == l.first().rev
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable { picked = r }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fullDate(r.createdAt), fontSize = 15.sp, color = c.ink, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                                Text(listOf(r.device.ifEmpty { s.unknownDevice }, formatSize(context, r.size), r.title.ifBlank { s.untitled }).joinToString(" · "),
                                    fontSize = 12.5.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (current) {
                                Text(s.currentVersion, fontSize = 12.sp, color = c.ivyInk,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.ivySoft).padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        val r = preview
        ConfirmDialog(s.restoreVersionTitle, s.restoreVersionMessage, s.restore, destructive = false, onDismiss = { confirm = false }) {
            if (r != null) {
                lib.engine.updateNote(note.id, SyncEngine.NotePatch(title = r.title, body = r.body.orEmpty()))
                toast(context, s.restoredVersion)
                scope.launch { lib.engine.sync() }
                onDismiss()
            }
        }
    }
}

private fun revisionLabel(r: Revision, current: Boolean): String =
    fullDate(r.createdAt) + (if (r.device.isNotEmpty()) " · ${r.device}" else "") + if (current) " · " + L10n.s.currentVersion else ""

/** 2026年11月6日 16:20 / Nov 6, 2026 16:20, for lists where the exact moment matters. */
fun fullDate(at: Long): String =
    java.text.SimpleDateFormat(L10n.s.dateTimePattern, L10n.s.locale).format(java.util.Date(at))

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String) {
    val c = Ivy.colors
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.faint, modifier = Modifier.size(32.dp))
        Text(title, color = c.muted, fontWeight = FontWeight.Medium)
        if (hint.isNotEmpty()) Text(hint, color = c.faint, fontSize = 13.sp)
    }
}

// Sharing (guide 11.6, web ShareDialog)

/**
 * The note's public link (password required, expiry, views) and export:
 * copy Markdown, save `.md`, or send to another app. Online the export comes
 * from the server with attachments published; offline only the local text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(lib: LibrarySession, note: Note, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = lib.api
    val state by lib.engine.state.collectAsState()
    var share by remember { mutableStateOf<Share?>(null) }
    var loading by remember { mutableStateOf(api != null) }
    var offline by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(note.id) {
        if (api == null) return@LaunchedEffect
        try {
            share = api.share(note.id)
            if (share?.enabled == true && share?.hasPassword == false) {
                password = Share.generatePassword()
                asking = true
            }
        } catch (e: ApiError) {
            if (e.isTransient) offline = true else toast(context, errorText(e))
        } finally {
            loading = false
        }
    }

    fun save(enabled: Boolean? = null, newPassword: String? = null, days: Int? = null) {
        val a = api ?: return
        saving = true
        scope.launch {
            try {
                val cur = share
                var expiresAt = cur?.expiresAt ?: 0
                if (days != null) expiresAt = if (days > 0) System.currentTimeMillis() + days * 86_400_000L else 0
                share = a.setShare(note.id, enabled = enabled ?: cur?.enabled ?: true, expiresAt = expiresAt, password = newPassword)
                if (newPassword != null) {
                    password = ""
                    asking = false
                    changing = false
                }
            } catch (e: ApiError) {
                toast(context, errorText(e))
            } finally {
                saving = false
            }
        }
    }

    /** The note with public attachment links; offline, the local text as is. */
    suspend fun markdown(): String {
        val local = (if (note.title.isNotBlank()) "# ${note.title}\n\n" else "") + note.body
        val a = api ?: return local
        return try {
            // The server renders the export, so it needs the latest edits first.
            lib.engine.sync()
            a.exportNote(note.id, public = true)
        } catch (e: ApiError) {
            if (!e.isTransient) throw e
            toast(context, s.offlineNoPublicLinks)
            local
        }
    }

    fun export(block: (String) -> Unit) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                block(markdown())
            } catch (e: ApiError) {
                toast(context, errorText(e))
            } finally {
                exporting = false
            }
        }
    }

    val link = share?.let { api?.shareLink(it.id) }.orEmpty()
    val live = share?.live == true
    val title = displayTitle(note)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(s.shareNote, style = SerifTitle, fontSize = 19.sp, color = c.ink)

            when {
                api == null -> Text(s.localNoShare, fontSize = 13.sp, color = c.muted)
                loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.muted) }
                offline -> Text(s.shareNeedsNetwork, fontSize = 13.sp, color = c.muted)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.publicLink, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.ink)
                            Text(s.publicLinkHint, fontSize = 12.5.sp, color = c.muted)
                        }
                        Switch(
                            checked = live || asking && !changing,
                            enabled = !saving,
                            onCheckedChange = { on ->
                                if (!on) {
                                    asking = false
                                    changing = false
                                    if (share?.enabled == true) save(enabled = false)
                                } else if (share?.hasPassword == true) {
                                    // A link that already has a password simply reopens.
                                    save(enabled = true)
                                } else {
                                    password = Share.generatePassword()
                                    asking = true
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.ivy),
                        )
                    }
                    AnimatedVisibility(asking || changing) {
                        val err = if (password.isEmpty()) null else Share.passwordError(password)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.sunken).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row {
                                Icon(Icons.Outlined.Lock, null, tint = c.ivy, modifier = Modifier.size(15.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (share?.enabled == true && share?.hasPassword == false) s.legacyShare
                                    else s.needPassword,
                                    fontSize = 12.5.sp, color = c.muted,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = password, onValueChange = { password = it.take(72) }, singleLine = true,
                                    placeholder = { Text(s.newPasswordPlaceholder) },
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                                    isError = err != null,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { password = Share.generatePassword() }) { Text(s.generate) }
                            }
                            if (err != null) Text(err, color = c.danger, fontSize = 12.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (changing) TextButton(onClick = { changing = false; password = "" }) { Text(s.cancel) }
                                InkButton(if (changing) s.save else s.createLink, busy = saving, enabled = Share.passwordError(password) == null,
                                    onClick = { save(enabled = true, newPassword = password) })
                            }
                        }
                    }
                    val sh = share
                    AnimatedVisibility(live && sh != null) {
                        if (sh == null) return@AnimatedVisibility
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(link, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { copyText(context, link) }) { Text(s.copyLink) }
                            }
                            if (sh.expired) Text(s.shareExpired, color = c.danger, fontSize = 12.sp)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Lock, null, tint = c.ivy, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                if (sh.password.isNotEmpty()) {
                                    Text(sh.password, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { copyText(context, sh.password) }) { Icon(Icons.Outlined.ContentCopy, contentDescription = s.copyPassword, tint = c.ink2, modifier = Modifier.size(16.dp)) }
                                } else {
                                    Text(s.passwordSet, fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f))
                                }
                                if (!changing) TextButton(onClick = { changing = true; password = Share.generatePassword() }) { Text(s.changePassword, fontSize = 12.5.sp) }
                            }
                            if (sh.password.isNotEmpty()) {
                                InkButton(s.copyLinkAndPassword, modifier = Modifier.fillMaxWidth(), onClick = { copyText(context, Share.message(title, link, sh.password)) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.expires, fontSize = 13.sp, color = c.muted)
                                Spacer(Modifier.width(8.dp))
                                ExpiryPicker(sh, enabled = !saving) { days -> save(days = days) }
                                Spacer(Modifier.weight(1f))
                                Text(s.views(sh.views), fontSize = 12.5.sp, color = c.faint)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = c.line)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.copyOrExport, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.ink)
                Text(
                    if (api != null && !offline && state.status != app.ivynote.core.SyncState.Status.OFFLINE) s.copyAsHint
                    else s.copyAsOffline,
                    fontSize = 12.5.sp, color = c.muted,
                )
            }
            if (exporting) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.muted) }
            SheetAction(Icons.Outlined.ContentCopy, s.copyMarkdown, enabled = !exporting) { export { md -> copyText(context, md) } }
            SheetAction(Icons.Outlined.Share, s.sendToApp, enabled = !exporting) {
                export { md ->
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, md)
                    context.startActivity(Intent.createChooser(intent, title))
                }
            }
            SheetAction(Icons.Outlined.FileDownload, s.exportMd, enabled = !exporting) {
                export { md ->
                    val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "-") + ".md"
                    val tmp = File(IvyApp.instance.transferDir(), "export-" + System.nanoTime()).apply { writeText(md) }
                    try {
                        shareFile(context, tmp, name, "text/markdown")
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpiryPicker(share: Share, enabled: Boolean, onPick: (Int) -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    val label = when {
        share.expiresAt == 0L -> s.neverExpires
        else -> s.until(java.text.SimpleDateFormat(s.monthDayPattern, s.locale).format(java.util.Date(share.expiresAt)))
    }
    Box {
        Text(label, fontSize = 13.sp, color = if (enabled) c.ivy else c.faint,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { open = true }.padding(horizontal = 6.dp, vertical = 4.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((days, text) in listOf(1 to s.days(1), 7 to s.days(7), 30 to s.days(30), -1 to s.neverExpires)) {
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(days) })
            }
        }
    }
}
