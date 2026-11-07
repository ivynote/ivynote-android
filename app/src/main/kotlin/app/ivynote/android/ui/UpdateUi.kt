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
    val str = LocalStrings.current
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
        title = { Text(str.updateAvailable, style = SerifTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(str.versionReleased(info.latest), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    buildString {
                        append(str.currentVersionIs(info.current))
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
                            str.downloading(Formatter.formatShortFileSize(context, s.downloaded) +
                                (if (s.total > 0) " / " + Formatter.formatShortFileSize(context, s.total) else "")),
                            color = c.muted, fontSize = 12.5.sp,
                        )
                    }
                    Phase.INSTALLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.muted)
                        Spacer(Modifier.width(8.dp))
                        Text(str.savingAndInstalling, color = c.muted, fontSize = 12.5.sp)
                    }
                    Phase.READY -> Text(str.updateReady, color = c.muted, fontSize = 12.5.sp)
                    else -> Text(
                        when {
                            web && asset != null -> str.debugUpdateHint
                            web -> str.webUpdateHint
                            else -> str.updateHint
                        },
                        color = c.muted, fontSize = 12.5.sp,
                    )
                }
                if (s.error.isNotEmpty() && (s.failed == Updater.Failed.DOWNLOAD || s.failed == Updater.Failed.INSTALL)) {
                    Text(
                        if (s.failed == Updater.Failed.DOWNLOAD) str.downloadFailed(s.error) else str.installFailed(s.error),
                        color = c.danger, fontSize = 12.5.sp,
                    )
                }
            }
        },
        confirmButton = {
            when (s.phase) {
                Phase.DOWNLOADING -> TextButton(onClick = { updater.hide() }) { Text(str.downloadInBackground, color = c.ivy) }
                Phase.INSTALLING -> TextButton(onClick = {}, enabled = false) { Text(str.installing) }
                Phase.READY -> TextButton(onClick = { updater.install() }) { Text(str.installNow, color = c.ivy, fontWeight = FontWeight.SemiBold) }
                else -> TextButton(onClick = { if (web) { updater.openDownloadPage(); updater.later() } else updater.downloadAndInstall() }) {
                    Text(if (web) str.downloadFromWebsite else str.updateNow, color = c.ivy, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            Row {
                when (s.phase) {
                    Phase.DOWNLOADING -> TextButton(onClick = { updater.cancelDownload() }) { Text(str.cancelDownload, color = c.ink2) }
                    Phase.INSTALLING -> Unit
                    else -> {
                        if (s.failed == Updater.Failed.DOWNLOAD || s.failed == Updater.Failed.INSTALL) {
                            TextButton(onClick = { updater.openDownloadPage() }) { Text(str.websiteDownload, color = c.ink2) }
                        } else {
                            TextButton(onClick = { updater.skip() }) { Text(str.skipVersion, color = c.muted) }
                        }
                        TextButton(onClick = { updater.later() }) { Text(str.later, color = c.ink2) }
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
    val str = LocalStrings.current
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
            Text(str.appName, style = SerifTitle, fontSize = 20.sp, color = c.ink)
            Text(str.aboutTagline, color = c.muted, fontSize = 13.5.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(str.version, color = c.ink2, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(updater.current, color = c.ink, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = c.line)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = s.phase != Phase.CHECKING) {
                            if (info?.available == true && s.phase != Phase.IDLE) updater.show() else updater.checkNow()
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(str.checkForUpdates, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        when {
                            s.phase == Phase.CHECKING -> {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.muted)
                                Spacer(Modifier.width(6.dp))
                                Text(str.checking, color = c.muted, fontSize = 13.sp)
                            }
                            s.phase == Phase.DOWNLOADING -> Text(str.downloadingVersion(info?.latest.orEmpty()), color = c.ivy, fontSize = 13.sp)
                            info?.available == true -> Text(str.newVersion(info.latest), color = c.ivy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            s.failed == Updater.Failed.CHECK -> Text(str.checkFailed, color = c.danger, fontSize = 13.sp)
                            info != null -> Text(str.upToDate, color = c.muted, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = c.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(str.anonymousStats, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = stats,
                            onCheckedChange = { stats = it; updater.prefs.statsEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.ivy, checkedThumbColor = c.onIvy),
                        )
                    }
                    Text(
                        str.anonymousStatsHint,
                        color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.SITES.first()))) }
            }) { Text(str.website, color = c.ivy) }
        }
    }
}
