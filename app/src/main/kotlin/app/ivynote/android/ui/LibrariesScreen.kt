package app.ivynote.android.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.IvyApp
import app.ivynote.core.LibraryRef

/**
 * The libraries on this device, as on the desktop and Apple clients. Cloud
 * sync leads: sign in or sign up (the library is created once the account
 * exists); a local library is the quiet alternative and can turn on sync later.
 */
@Composable
fun LibrariesScreen() {
    val catalog = IvyApp.instance.catalog
    val libraries by catalog.libraries.collectAsState()
    val pendingSync by catalog.pendingSync.collectAsState()
    /** null: the list; "": signing in to the cloud; an id: turning on sync for that local library. */
    var cloud by remember { mutableStateOf<String?>(null) }
    var creatingLocal by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibraryRef?>(null) }
    var removing by remember { mutableStateOf<LibraryRef?>(null) }
    var about by remember { mutableStateOf(false) }
    val update by IvyApp.instance.updater.state.collectAsState()

    // "Turn on sync" chosen inside a local library: continue here once it has closed.
    LaunchedEffect(pendingSync) {
        val id = pendingSync ?: return@LaunchedEffect
        catalog.pendingSync.value = null
        if (catalog.library(id)?.canTurnOnSync == true) cloud = id
    }

    val route = cloud
    if (route != null) {
        BackHandler { cloud = null }
        CloudSignInScreen(promoting = route.takeIf { it.isNotEmpty() }?.let(catalog::library), onBack = { cloud = null })
        return
    }

    val c = Ivy.colors
    Box(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 520.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLogo(64.dp)
                    Text(if (libraries.isEmpty()) "常青笔记" else "笔记库", style = SerifTitle, fontSize = 26.sp, color = c.ink)
                    Text(
                        if (libraries.isEmpty()) "本地优先的 Markdown 笔记，在手机、电脑和网页间同步。" else "选择要打开的笔记库",
                        color = c.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }
            }
            if (libraries.isNotEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = c.surface) {
                        Column {
                            libraries.sortedByDescending { it.openedAt }.forEachIndexed { i, ref ->
                                if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 64.dp))
                                LibraryRow(ref,
                                    onOpen = { catalog.open(ref.id) },
                                    onTurnOnSync = { cloud = ref.id },
                                    onRename = { renaming = ref },
                                    onRemove = { removing = ref })
                            }
                        }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = c.surface, modifier = Modifier.padding(top = 8.dp)) {
                        Column {
                            ActionRow(Icons.Outlined.Cloud, c.ivy, "登录云同步账户", "在多台设备间同步，支持官方节点和自建服务器") { cloud = "" }
                            HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 64.dp))
                            ActionRow(Icons.Outlined.Add, c.muted, "新建本地笔记库", "只保存在这台设备，无需账户，立即可用") { creatingLocal = true }
                        }
                    }
                }
                if (libraries.any { it.canTurnOnSync }) {
                    item { Caption("本地笔记库可以随时开启同步：注册新账户后，内容会自动上传。", Modifier.padding(horizontal = 8.dp)) }
                }
            } else {
                // First run: syncing is the way in; local is the quiet alternative.
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = c.ivy, modifier = Modifier.fillMaxWidth().clickable { cloud = "" }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Cloud, null, tint = c.onIvy)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("开始使用 · 多端同步", color = c.onIvy, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("登录或注册，笔记在手机、电脑和网页间自动同步", color = c.onIvy.copy(alpha = 0.85f), fontSize = 13.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.onIvy)
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { creatingLocal = true }) { Text("不登录，仅在本机使用", color = c.ink2) }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { about = true }) {
                        val newer = update.info?.takeIf { it.available }?.latest
                        Text(
                            if (newer != null) "关于常青笔记 · 有新版本 $newer" else "关于常青笔记 · ${IvyApp.instance.updater.current}",
                            color = if (newer != null) c.ivy else c.muted, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

    if (about) AboutSheet(onDismiss = { about = false })
    if (creatingLocal) {
        TextInputDialog(
            title = "新建本地笔记库",
            initial = if (libraries.isEmpty()) "我的笔记" else "",
            message = "笔记只保存在这台设备上，不会同步。",
            confirm = "创建",
            onDismiss = { creatingLocal = false },
        ) { name -> catalog.createLocal(name) }
    }
    renaming?.let { ref ->
        TextInputDialog(title = "重命名笔记库", initial = ref.name, onDismiss = { renaming = null }) { name -> catalog.rename(ref.id, name) }
    }
    removing?.let { ref ->
        ConfirmDialog(
            title = "移除笔记库",
            message = if (ref.isLocal) "将永久删除「${ref.name}」里的全部笔记，无法恢复。"
            else "将从这台设备删除「${ref.name}」的本地副本和登录状态。服务器上的笔记不受影响，但尚未同步的更改会丢失。",
            confirm = "移除",
            onDismiss = { removing = null },
        ) {
            catalog.remove(ref.id)
            IvyApp.instance.mediaDir(ref.id).deleteRecursively()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(ref: LibraryRef, onOpen: () -> Unit, onTurnOnSync: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val c = Ivy.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryIcon(ref, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ref.name, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail(ref), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = c.faint) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("打开") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, onClick = { menu = false; onOpen() })
            if (ref.canTurnOnSync) {
                DropdownMenuItem(text = { Text("开启同步") }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) }, onClick = { menu = false; onTurnOnSync() })
            }
            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("移除", color = c.danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = c.danger) }, onClick = { menu = false; onRemove() })
        }
    }
}

private fun detail(ref: LibraryRef): String = when {
    ref.migratedTo != null -> "已迁移到云端，确认无误后可以移除"
    ref.isLocal -> "仅保存在本机 · 未同步"
    else -> {
        val host = ref.server?.host ?: ""
        ref.account?.let { "$host · @$it" } ?: host
    }
}

@Composable
private fun ActionRow(icon: ImageVector, tint: Color, title: String, detail: String, onClick: () -> Unit) {
    val c = Ivy.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink)
            Text(detail, fontSize = 13.sp, color = c.muted)
        }
    }
}

