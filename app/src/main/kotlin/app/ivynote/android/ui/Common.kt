package app.ivynote.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.R
import app.ivynote.core.L10n
import app.ivynote.core.LibraryRef
import app.ivynote.core.SyncEngine
import app.ivynote.core.SyncState
import kotlinx.coroutines.delay

/** A dialog with one text field: names of notebooks, notes, libraries. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    placeholder: String = L10n.s.name,
    confirm: String = L10n.s.save,
    message: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, color = Ivy.colors.muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(value.text); onDismiss() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalStrings.current.cancel) } },
    )
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) Ivy.colors.danger else Ivy.colors.ivy)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalStrings.current.cancel) } },
    )
}

/** The primary button: an ink pill (guide 10.5). */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Ivy.colors.ink, contentColor = Ivy.colors.canvas),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ivy.colors.canvas) else Text(text)
    }
}

/** The ivy leaf (guide 10.7). */
@Composable
fun IvyLeaf(size: Dp = 64.dp, alpha: Float = 1f) {
    Image(painterResource(R.drawable.ivy_leaf), contentDescription = null, modifier = Modifier.size(size), alpha = alpha)
}

/** The app icon: the leaf on ivy, rounded. */
@Composable
fun AppLogo(size: Dp = 64.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.235f)).background(Color(0xFF3A6B50)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(size * 1.5f))
    }
}

@Composable
fun LibraryIcon(ref: LibraryRef, size: Dp = 36.dp) {
    val c = Ivy.colors
    val tint = if (ref.isLocal) c.muted else c.ivy
    val icon = when {
        ref.migratedTo != null -> Icons.Outlined.CloudDone
        ref.isLocal -> Icons.Outlined.PhoneAndroid
        else -> Icons.Outlined.Cloud
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** 已同步 · 2 分钟前, 同步中…, 离线 · 3 条待同步 (guide 11.7). */
@Composable
fun SyncStatusLine(engine: SyncEngine, state: SyncState, modifier: Modifier = Modifier) {
    val c = Ivy.colors
    // Re-render the relative time now and then.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val s = LocalStrings.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon: ImageVector?, text: String, tint: Color) = when {
                engine.isLocal -> Triple(Icons.Outlined.CloudOff, s.localUnsynced, c.muted)
                state.status == SyncState.Status.SYNCING -> Triple(null, if (state.initial) s.downloadingNotes else s.syncing, c.muted)
                state.status == SyncState.Status.OFFLINE ->
                    Triple(Icons.Outlined.WifiOff, if (state.pending > 0) s.offlinePending(state.pending) else s.offline, c.warn)
                state.status == SyncState.Status.ERROR -> Triple(Icons.Outlined.Warning, s.syncError, c.danger)
                state.pending > 0 -> Triple(Icons.Outlined.Sync, s.pending(state.pending), c.muted)
                state.lastSyncAt > 0 -> Triple(Icons.Outlined.CloudDone, s.synced(relative(state.lastSyncAt, tick)), c.muted)
                else -> Triple(null, s.neverSynced, c.muted)
            }
            if (state.status == SyncState.Status.SYNCING && !engine.isLocal) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = c.muted)
                Spacer(Modifier.width(6.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.blocked > 0) {
            Text(s.blocked(state.blocked), fontSize = 12.5.sp, color = c.warn)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
fun relative(at: Long, tick: Long = 0): String {
    val now = System.currentTimeMillis()
    if (now - at < 60_000) return L10n.s.justNow
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** 今天 16:20、昨天、周几、10月29日、2025年3月1日 (guide 11.7), in the interface language. */
fun shortDate(at: Long): String {
    val s = L10n.s
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val days = ((startOfDay(now) - startOfDay(then)) / 86_400_000L).toInt()
    val time = String.format(java.util.Locale.ROOT, "%02d:%02d", then.get(java.util.Calendar.HOUR_OF_DAY), then.get(java.util.Calendar.MINUTE))
    return when {
        days <= 0 -> time
        days == 1 -> s.yesterday(time)
        days < 7 -> s.weekday(then.get(java.util.Calendar.DAY_OF_WEEK))
        then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) ->
            s.monthDay(then.get(java.util.Calendar.MONTH) + 1, then.get(java.util.Calendar.DAY_OF_MONTH))
        else -> s.yearMonthDay(then.get(java.util.Calendar.YEAR), then.get(java.util.Calendar.MONTH) + 1, then.get(java.util.Calendar.DAY_OF_MONTH))
    }
}

private fun startOfDay(c: java.util.Calendar): Long = (c.clone() as java.util.Calendar).apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** A small dot: pending changes (warn) on a note, sync trouble on an avatar (guide 11.1). */
@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Section caption in lists (12px, muted). */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.5.sp, color = Ivy.colors.muted, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
fun rememberSaveableString(initial: String = "") = rememberSaveable { mutableStateOf(initial) }
