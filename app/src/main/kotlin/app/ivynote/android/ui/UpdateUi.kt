package app.ivynote.android.ui

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.ivynote.android.IvyApp
import app.ivynote.android.update.Updater
import app.ivynote.android.update.Updater.Phase
import app.ivynote.core.AppUpdates

/** The update dialog, shown over everything while the updater asks for it. */
@Composable
fun UpdateDialogHost() {
    val updater = IvyApp.instance.updater
    val s by updater.state.collectAsState()
    val info = s.info
    if (s.open && info != null && info.available) UpdateDialog(updater, s)
}

@Composable
private fun UpdateDialog(updater: Updater, s: Updater.State) {
    val c = Ivy.colors
    val context = LocalContext.current
    val info = s.info ?: return
    val asset = info.asset
    // No APK on offer, or a debug build: the website's download page.
    val web = asset == null || !updater.canInstallHere
    val busy = s.phase == Phase.DOWNLOADING || s.phase == Phase.INSTALLING
    val notes = remember(info.notes) { AppUpdates.noteLines(info.notes) }

    AlertDialog(
        onDismissRequest = { if (s.phase == Phase.DOWNLOADING) updater.hide() else if (s.phase != Phase.INSTALLING) updater.later() },
        properties = DialogProperties(dismissOnClickOutside = !busy),
        containerColor = c.canvas,
        title = { Text("发现新版本", style = SerifTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("常青笔记 ${info.latest} 已发布", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    buildString {
                        append("当前版本 ${info.current}")
                        if (asset != null && asset.size > 0) append(" · ").append(Formatter.formatShortFileSize(context, asset.size))
                    },
                    color = c.muted, fontSize = 13.sp,
                )
                if (notes.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (l in notes) {
                                if (l.startsWith("# ")) Text(l.removePrefix("# "), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                else Text("· $l", color = c.ink2, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
                when (s.phase) {
                    Phase.DOWNLOADING -> {
                        val share = if (s.total > 0) (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth(), color = c.ivy, trackColor = c.sunken)
                        Text(
                            "正在下载… " + Formatter.formatShortFileSize(context, s.downloaded) +
                                (if (s.total > 0) " / " + Formatter.formatShortFileSize(context, s.total) else ""),
                            color = c.muted, fontSize = 12.5.sp,
                        )
                    }
                    Phase.INSTALLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.muted)
                        Spacer(Modifier.width(8.dp))
                        Text("正在保存笔记并打开安装程序…", color = c.muted, fontSize = 12.5.sp)
                    }
                    Phase.READY -> Text("新版本已下载并校验，按系统提示完成安装即可，笔记会先保存。", color = c.muted, fontSize = 12.5.sp)
                    else -> Text(
                        when {
                            web && asset != null -> "调试版无法直接更新，请前往官网下载正式版。"
                            web -> "请前往官网下载新版本。"
                            else -> "下载完成后由系统安装，笔记会先保存。首次更新需要允许常青笔记安装应用。"
                        },
                        color = c.muted, fontSize = 12.5.sp,
                    )
                }
                if (s.error.isNotEmpty() && (s.failed == Updater.Failed.DOWNLOAD || s.failed == Updater.Failed.INSTALL)) {
                    Text(
                        (if (s.failed == Updater.Failed.DOWNLOAD) "下载失败" else "安装失败") + "：${s.error}。可以重试或前往官网下载。",
                        color = c.danger, fontSize = 12.5.sp,
                    )
                }
            }
        },
        confirmButton = {
            when (s.phase) {
                Phase.DOWNLOADING -> TextButton(onClick = { updater.hide() }) { Text("后台下载", color = c.ivy) }
                Phase.INSTALLING -> TextButton(onClick = {}, enabled = false) { Text("正在安装…") }
                Phase.READY -> TextButton(onClick = { updater.install() }) { Text("立即安装", color = c.ivy, fontWeight = FontWeight.SemiBold) }
                else -> TextButton(onClick = { if (web) { updater.openDownloadPage(); updater.later() } else updater.downloadAndInstall() }) {
                    Text(if (web) "前往官网下载" else "立即更新", color = c.ivy, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            Row {
                when (s.phase) {
                    Phase.DOWNLOADING -> TextButton(onClick = { updater.cancelDownload() }) { Text("取消下载", color = c.ink2) }
                    Phase.INSTALLING -> Unit
                    else -> {
                        if (s.failed == Updater.Failed.DOWNLOAD || s.failed == Updater.Failed.INSTALL) {
                            TextButton(onClick = { updater.openDownloadPage() }) { Text("官网下载", color = c.ink2) }
                        } else {
                            TextButton(onClick = { updater.skip() }) { Text("跳过此版本", color = c.muted) }
                        }
                        TextButton(onClick = { updater.later() }) { Text("稍后", color = c.ink2) }
                    }
                }
            }
        },
    )
}

/** 关于常青笔记: the version, checking for updates, anonymous statistics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val c = Ivy.colors
    val context = LocalContext.current
    val updater = IvyApp.instance.updater
    val s by updater.state.collectAsState()
    var stats by remember { mutableStateOf(updater.prefs.statsEnabled) }
    val info = s.info

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.canvas) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppLogo(56.dp)
            Text("常青笔记", style = SerifTitle, fontSize = 20.sp, color = c.ink)
            Text("本地优先的 Markdown 笔记。离线也能写，联网后自动同步。", color = c.muted, fontSize = 13.5.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("版本", color = c.ink2, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(updater.current, color = c.ink, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = c.line)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = s.phase != Phase.CHECKING) {
                            if (info?.available == true && s.phase != Phase.IDLE) updater.show() else updater.checkNow()
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("检查更新", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        when {
                            s.phase == Phase.CHECKING -> {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.muted)
                                Spacer(Modifier.width(6.dp))
                                Text("正在检查…", color = c.muted, fontSize = 13.sp)
                            }
                            s.phase == Phase.DOWNLOADING -> Text("正在下载 ${info?.latest}…", color = c.ivy, fontSize = 13.sp)
                            info?.available == true -> Text("有新版本 ${info.latest}", color = c.ivy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            s.failed == Updater.Failed.CHECK -> Text("检查失败", color = c.danger, fontSize = 13.sp)
                            info != null -> Text("已是最新版本", color = c.muted, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = c.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("匿名使用统计", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = stats,
                            onCheckedChange = { stats = it; updater.prefs.statsEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.ivy, checkedThumbColor = c.onIvy),
                        )
                    }
                    Text(
                        "检查更新时附带一个随机的安装编号（与账号无关），帮助我们了解有多少设备在使用常青笔记。不包含笔记内容或个人信息。",
                        color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.SITES.first()))) }
            }) { Text("官网 ivynote.net", color = c.ivy) }
        }
    }
}
