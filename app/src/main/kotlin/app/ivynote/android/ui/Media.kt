package app.ivynote.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.ivynote.android.IvyApp
import app.ivynote.android.LibrarySession
import app.ivynote.android.editor.AttachmentLoader
import app.ivynote.core.AddedFile
import app.ivynote.core.ApiError
import app.ivynote.core.Attachment
import app.ivynote.core.AttachmentDetail
import app.ivynote.core.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Small helpers shared by the note's sheets

fun toast(context: Context, text: String, long: Boolean = false) =
    Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun copyText(context: Context, text: String, label: String = "ivynote") {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) toast(context, "已复制")
}

fun errorText(e: Throwable): String = when (e) {
    is ApiError.Unreachable -> "需要联网"
    is ApiError.Server -> if (e.code == 413) "文件超过服务器的上传上限" else e.message ?: "请求失败"
    else -> e.message ?: e.javaClass.simpleName
}

/** A shareable `content://` address for a file in the transfer folder. */
fun shareUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

/** Hands a file to another app (save, send). The copy lives in the transfer folder. */
fun shareFile(context: Context, source: File, name: String, mime: String) {
    val dir = File(IvyApp.instance.transferDir(), "out-" + System.nanoTime()).apply { mkdirs() }
    val file = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "-").ifBlank { "file" })
    source.copyTo(file, overwrite = true)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime.ifEmpty { "application/octet-stream" })
        .putExtra(Intent.EXTRA_STREAM, shareUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, name))
}

fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

// Adding files (guide 3.2: 图片、拍照、附件)

/** Opens the photo picker, the camera or the file picker; each added file comes back through [onAdded]. */
class AttachmentPicker(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit)

@Composable
fun rememberAttachmentPicker(lib: LibrarySession, onAdded: (AddedFile) -> Unit): AttachmentPicker {
    val context = LocalContext.current
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun add(uris: List<Uri>, name: String? = null, cleanup: (() -> Unit)? = null) {
        if (uris.isEmpty()) return
        // In the library's scope: an upload keeps going when the sheet or screen closes.
        lib.launch {
            try {
                for (u in uris) {
                    try {
                        val added = lib.addFile(u, name)
                        onAdded(added)
                        if (added.queued) toast(context, "离线中，「${added.name}」将在联网后上传")
                        else if (added.duplicate) toast(context, "重复文件已合并")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(context, "无法添加文件：${errorText(e)}", long = true)
                    }
                }
            } finally {
                cleanup?.invoke()
            }
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (ok && f != null && f.length() > 0) {
            add(listOf(Uri.fromFile(f)), f.name) { f.delete() }
        } else {
            f?.delete()
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    return remember(lib) {
        AttachmentPicker(
            photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            camera = {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                val f = File(IvyApp.instance.transferDir(), "image-$stamp.jpg")
                cameraFile = f
                try {
                    camera.launch(shareUri(context, f))
                } catch (_: android.content.ActivityNotFoundException) {
                    toast(context, "没有可用的相机应用")
                }
            },
            files = {
                try {
                    files.launch(arrayOf("*/*"))
                } catch (_: android.content.ActivityNotFoundException) {
                    toast(context, "没有可用的文件选择器")
                }
            },
        )
    }
}

// Showing files

fun kindIcon(kind: String): ImageVector = when (kind) {
    "image" -> Icons.Outlined.Image
    "video" -> Icons.Outlined.Movie
    "audio" -> Icons.Outlined.AudioFile
    else -> Icons.Outlined.InsertDriveFile
}

/** An image attachment, decoded to about [maxPx] on its long side; an icon for other kinds or while loading. */
@Composable
fun AttachmentImage(loader: AttachmentLoader, a: Attachment, thumb: Boolean, modifier: Modifier = Modifier, maxPx: Int = 480, crop: Boolean = true) {
    val c = Ivy.colors
    val bitmap by produceState<Bitmap?>(null, a.id, thumb) {
        value = if (a.kind != "image") null else withContext(Dispatchers.IO) {
            runCatching { loader.fetch(a.id, thumb && a.hasThumb)?.let { decodeSampled(it, maxPx) } }.getOrNull()
        }
    }
    Box(modifier.background(c.sunken), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = a.name, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Icon(kindIcon(a.kind), contentDescription = null, tint = c.faint, modifier = Modifier.size(28.dp))
        }
    }
}

private fun decodeSampled(file: File, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

// The media library (guide 11.6)

/** Every file of the library: filters, search, a grid; a tap shows the details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(lib: LibrarySession, onOpenNote: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val context = LocalContext.current
    val revision by lib.engine.revision.collectAsState()
    val adding by lib.adding.collectAsState()
    var filter by remember { mutableStateOf(MediaLibrary.Filter.ALL) }
    var query by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<MediaLibrary.Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Attachment?>(null) }
    val picker = rememberAttachmentPicker(lib) { reload++ }

    LaunchedEffect(filter, query, reload, revision) {
        if (query.isNotEmpty()) kotlinx.coroutines.delay(250)
        try {
            listing = lib.media.list(filter, query)
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = c.ink2) }
                Text("媒体库", style = SerifTitle, fontSize = 19.sp, color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (adding > 0) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.muted)
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Upload, contentDescription = "上传", tint = c.ink2) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("图片或视频") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { menu = false; picker.photos() })
                        DropdownMenuItem(text = { Text("拍照") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { menu = false; picker.camera() })
                        DropdownMenuItem(text = { Text("文件") }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, onClick = { menu = false; picker.files() })
                    }
                }
            }
            // Search
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(40.dp)
                    .clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.faint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索文件名", color = c.faint, fontSize = 15.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ivy), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "清除", tint = c.faint, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { query = "" })
            }
            // Filters
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in MediaLibrary.Filter.entries) {
                    val n = listing?.counts?.get(f)?.takeIf { it >= 0 }
                    FilterChip(
                        selected = f == filter,
                        onClick = { filter = f },
                        label = { Text(if (n != null) "${f.label} $n" else f.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.ivySoft, selectedLabelColor = c.ivyInk),
                    )
                }
            }
            val l = listing
            if (l?.offline == true) {
                Banner(Icons.Outlined.WifiOff, "离线中，显示的是本机同步下来的文件信息，修改需要联网。")
            } else if (filter == MediaLibrary.Filter.ORPHANED && (l?.items?.isNotEmpty() == true)) {
                val days = l.orphanDays
                Banner(Icons.Outlined.Delete, if (days > 0) "${l.items.size} 个文件没有被任何笔记引用，将在 $days 天后自动移入回收站。" else "${l.items.size} 个文件没有被任何笔记引用。")
            }
            when {
                error != null && l == null -> Centered(error ?: "")
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.muted) }
                l.items.isEmpty() -> Centered(
                    when {
                        query.isNotBlank() -> "没有找到「${query.trim()}」"
                        filter == MediaLibrary.Filter.TRASH -> "回收站是空的"
                        else -> "还没有文件。在笔记中插入图片或附件即可自动加入媒体库。"
                    },
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(l.items, key = { it.id }) { a -> MediaCell(lib, a) { open = a } }
                }
            }
        }
        open?.let { a ->
            MediaDetailSheet(lib, a, onChanged = { reload++ }, onOpenNote = { id -> open = null; onOpenNote(id) }, onDismiss = { open = null })
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String) {
    val c = Ivy.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warnSoft).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.warnInk, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.warnInk, fontSize = 13.sp)
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ivy.colors.muted, fontSize = 14.sp)
    }
}

@Composable
private fun MediaCell(lib: LibrarySession, a: Attachment, onClick: () -> Unit) {
    val c = Ivy.colors
    val context = LocalContext.current
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box {
            AttachmentImage(lib.editor.media, a, thumb = true, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
            if (a.public) {
                Icon(Icons.Outlined.Public, contentDescription = "已公开", tint = c.surface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(c.ivy).padding(3.dp))
            }
        }
        Text(a.name.ifEmpty { "未命名" }, fontSize = 13.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
        Text(
            formatSize(context, a.size) + " · " + if (a.refs > 0) "被 ${a.refs} 篇引用" else "未引用",
            fontSize = 11.5.sp, color = if (a.refs > 0) c.faint else c.warn, maxLines = 1, modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** One file: preview, references, public link, download, rename, trash (guide 11.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(lib: LibrarySession, initial: Attachment, onChanged: () -> Unit, onOpenNote: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<AttachmentDetail?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    val a = detail?.attachment ?: initial
    val media = lib.media

    LaunchedEffect(initial.id) {
        detail = runCatching { media.detail(initial.id) }.getOrNull()
    }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                onChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(context, errorText(e))
            } finally {
                busy = false
            }
        }
    }

    fun withFile(block: (File) -> Unit) {
        scope.launch {
            val f = withContext(Dispatchers.IO) { lib.editor.media.fetch(a.id, thumb = false) }
            if (f == null) toast(context, "无法取得文件，请检查网络") else block(f)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (a.kind == "image") {
                AttachmentImage(lib.editor.media, a, thumb = false, maxPx = 1600, crop = false,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(
                        if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) (a.width!!.toFloat() / a.height!!).coerceIn(0.5f, 2.5f) else 4f / 3f,
                    ).clip(RoundedCornerShape(16.dp)))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(c.sunken), contentAlignment = Alignment.Center) {
                        Icon(kindIcon(a.kind), null, tint = c.muted, modifier = Modifier.size(28.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name.ifEmpty { "未命名" }, style = SerifTitle, fontSize = 18.sp, color = c.ink, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.muted)
                if (!a.isTrashed) IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = "重命名", tint = c.ink2) }
            }
            InfoLine("类型", a.mime.ifEmpty { a.kind })
            InfoLine("大小", formatSize(context, a.size) + if ((a.width ?: 0) > 0) " · ${a.width}×${a.height}" else "")
            if (a.createdAt > 0) InfoLine("上传于", shortDate(a.createdAt))
            if (a.public) InfoLine("公网链接", a.publicUrl.ifEmpty { "已公开（/r/…）" })
            if (a.isTrashed) InfoLine("回收站", if (a.trashReason == "orphan") "未引用，已自动移入" else "已移入")

            // The notes that reference it.
            val notes = detail?.notes.orEmpty()
            Text(if (notes.isEmpty()) "没有笔记引用它" else "被 ${notes.size} 篇笔记引用", fontSize = 13.sp, color = c.muted, fontWeight = FontWeight.Medium)
            for (n in notes) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = lib.engine.notes[n.id] != null) { onOpenNote(n.id) }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Description, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(n.title.ifBlank { "无标题" }, color = if (n.title.isBlank()) c.muted else c.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = c.line)

            // Actions
            SheetAction(Icons.Outlined.ContentCopy, "复制引用") {
                copyText(context, app.ivynote.core.Links.embed(a.id, a.name, a.kind))
            }
            if (!a.isTrashed && media.canPublish) {
                SheetAction(Icons.Outlined.Link, if (a.public) "复制公网链接" else "生成公网链接") {
                    act { copyText(context, media.publish(a.id)); detail = detail?.copy(attachment = a.copy(public = true)) ?: AttachmentDetail(a.copy(public = true)) }
                }
                if (a.public) SheetAction(Icons.Outlined.LinkOff, "撤销公网链接") {
                    act { val u = media.unpublish(a.id); detail = detail?.copy(attachment = u) ?: AttachmentDetail(u) }
                }
            }
            SheetAction(Icons.Outlined.Share, "分享或保存原文件") {
                withFile { f -> shareFile(context, f, a.name.ifEmpty { a.id }, a.mime) }
            }
            if (a.isTrashed) {
                SheetAction(Icons.Outlined.Restore, "恢复") { act { val u = media.restore(a.id); detail = detail?.copy(attachment = u) ?: AttachmentDetail(u) } }
                SheetAction(Icons.Outlined.DeleteForever, "永久删除", danger = true) { confirmPurge = true }
            } else {
                SheetAction(Icons.Outlined.Delete, "移到回收站", danger = true) {
                    act { media.trash(a.id); onDismiss() }
                }
            }
            if (!media.canPublish) {
                Text("本地笔记库的文件只保存在这台设备上。", fontSize = 12.5.sp, color = c.faint)
            } else if (a.public) {
                Text("公开的文件不再受登录保护，拿到链接的人都能打开。", fontSize = 12.5.sp, color = c.faint)
            }
        }
    }
    if (renaming) {
        TextInputDialog(title = "重命名文件", initial = a.name, onDismiss = { renaming = false }) { name ->
            act { val u = media.rename(a.id, name); detail = detail?.copy(attachment = u) ?: AttachmentDetail(u) }
        }
    }
    if (confirmPurge) {
        ConfirmDialog("永久删除文件？", "「${a.name}」将被永久删除，引用它的笔记会显示为缺失。此操作无法撤销。", "永久删除", onDismiss = { confirmPurge = false }) {
            act { media.purge(a.id); onDismiss() }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val c = Ivy.colors
    Row {
        Text(label, fontSize = 13.sp, color = c.muted, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 13.sp, color = c.ink2, fontFamily = if (label == "公网链接") FontFamily.Monospace else null)
    }
}

@Composable
fun SheetAction(icon: ImageVector, text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Ivy.colors
    val tint = when {
        !enabled -> c.faint
        danger -> c.danger
        else -> c.ink2
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = if (!enabled) c.faint else if (danger) c.danger else c.ink, fontSize = 15.sp)
    }
}
