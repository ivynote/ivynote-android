package app.ivynote.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
    var choosingLanguage by remember { mutableStateOf(false) }
    val s = LocalStrings.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.canvas) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(s.account, style = SerifTitle, fontSize = 20.sp, color = c.ink)
            Card {
                Field(s.name, account.user.name)
                Field(s.username, account.user.username)
                if (account.user.email.isNotEmpty()) Field(s.email, account.user.email)
                Field(s.role, if (account.user.isAdmin) s.admin else s.user)
                // The account's language: kept by the server, on all its devices.
                Field(s.language, choiceLabel(IvyApp.instance.language.selected.collectAsState().value, s), onClick = { choosingLanguage = true })
            }
            Caption(s.syncSection(lib.ref.name))
            Card {
                Field(s.server, account.server.toString())
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.status, color = c.ink2, fontSize = 15.sp, modifier = Modifier.width(88.dp))
                    SyncStatusLine(engine, state)
                }
                Field(s.notes, remember(revision) { engine.liveNotes.size.toString() })
                Field(s.thisDevice, engine.device)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lib.engine.let { e -> app.appScope.launch { e.sync() } } }, enabled = state.status != SyncState.Status.SYNCING) {
                        Text(s.syncNow)
                    }
                    if (account.user.isAdmin) {
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.server.base + "/admin"))) }
                        }) { Text(s.openAdmin) }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { confirmSignOut = true }, enabled = !signingOut, modifier = Modifier.padding(4.dp)) {
                    Text(if (signingOut) s.signingOut else s.signOut, color = c.danger, fontWeight = FontWeight.Medium)
                }
            }
            Text(s.signOutHint, fontSize = 12.5.sp, color = c.muted)
        }
    }

    if (choosingLanguage) {
        LanguageDialog(onDismiss = { choosingLanguage = false })
    }

    if (confirmSignOut) {
        val unsynced = state.pending + state.blocked
        ConfirmDialog(
            title = s.signOutTitle,
            message = if (unsynced > 0) s.signOutUnsynced(unsynced) else s.signOutMessage,
            confirm = s.signOut,
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
private fun Field(label: String, value: String, onClick: (() -> Unit)? = null) {
    val c = Ivy.colors
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink2, fontSize = 15.sp, modifier = Modifier.width(88.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, color = c.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
