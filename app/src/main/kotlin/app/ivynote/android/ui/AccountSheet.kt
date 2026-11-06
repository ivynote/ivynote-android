package app.ivynote.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.IvyApp
import app.ivynote.android.LibrarySession
import app.ivynote.core.SessionStore
import app.ivynote.core.SyncState
import kotlinx.coroutines.launch

/** Account and sync of a synced library: who, where, how sync stands; sign out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(lib: LibrarySession, session: SessionStore, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val context = LocalContext.current
    val app = IvyApp.instance
    val account = lib.account ?: return
    val engine = lib.engine
    val state by engine.state.collectAsState()
    val revision by engine.revision.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.canvas) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("账户", style = SerifTitle, fontSize = 20.sp, color = c.ink)
            Card {
                Field("名称", account.user.name)
                Field("用户名", account.user.username)
                if (account.user.email.isNotEmpty()) Field("邮箱", account.user.email)
                Field("角色", if (account.user.isAdmin) "管理员" else "用户")
            }
            Caption("同步 · ${lib.ref.name}")
            Card {
                Field("服务器", account.server.toString())
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("状态", color = c.ink2, fontSize = 15.sp, modifier = Modifier.width(88.dp))
                    SyncStatusLine(engine, state)
                }
                Field("笔记", remember(revision) { engine.liveNotes.size.toString() })
                Field("本设备", engine.device)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lib.engine.let { e -> app.appScope.launch { e.sync() } } }, enabled = state.status != SyncState.Status.SYNCING) {
                        Text("立即同步")
                    }
                    if (account.user.isAdmin) {
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.server.base + "/admin"))) }
                        }) { Text("打开管理后台") }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { confirmSignOut = true }, enabled = !signingOut, modifier = Modifier.padding(4.dp)) {
                    Text(if (signingOut) "正在退出…" else "退出登录", color = c.danger, fontWeight = FontWeight.Medium)
                }
            }
            Text("退出后，本设备上这个笔记库的副本会被删除；服务器上的数据不受影响。重新登录后会再次下载。", fontSize = 12.5.sp, color = c.muted)
        }
    }

    if (confirmSignOut) {
        val unsynced = state.pending + state.blocked
        ConfirmDialog(
            title = "退出登录？",
            message = if (unsynced > 0) "还有 $unsynced 条更改没有同步到服务器，退出后会丢失。" else "这台设备的登录令牌会被撤销，本地数据会被删除。",
            confirm = "退出登录",
            onDismiss = { confirmSignOut = false },
        ) {
            // Sync, revoke the token, then delete the local copy (guide 6.10).
            signingOut = true
            app.appScope.launch {
                lib.flush()
                session.signOut()
                lib.destroy()
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Ivy.colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun Field(label: String, value: String) {
    val c = Ivy.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink2, fontSize = 15.sp, modifier = Modifier.width(88.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, color = c.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
