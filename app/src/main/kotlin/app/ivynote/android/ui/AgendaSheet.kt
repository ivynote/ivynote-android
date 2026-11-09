package app.ivynote.android.ui

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.LibrarySession
import app.ivynote.core.NoteLock
import app.ivynote.core.SyncEngine
import app.ivynote.core.Tasks
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val FOLDS = "ivy.agenda"

/**
 * Folded and unfolded branches, as on the web: for branches open by default
 * a stored key means folded, for the others unfolded. Days gone are dropped.
 */
private fun loadToggled(context: Context): Set<String> =
    context.getSharedPreferences(FOLDS, Context.MODE_PRIVATE).getStringSet("toggled", emptySet())?.toSet() ?: emptySet()

private fun saveToggled(context: Context, keys: Set<String>) {
    context.getSharedPreferences(FOLDS, Context.MODE_PRIVATE).edit().putStringSet("toggled", keys).apply()
}

private fun toggle(keys: Set<String>, key: String, tree: List<Tasks.AgendaNode>): Set<String> {
    val live = tree.map { it.key }.toSet()
    return (if (key in keys) keys - key else keys + key).filter { it in live }.toSet()
}

/**
 * The agenda (guide 11.8): open to-dos with a 📅 date across the library as
 * a tree by day — overdue, each day of the coming week, then by month. Checking one off writes the note, as the editor would; a
 * tap opens the note at the task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaSheet(lib: LibrarySession, onOpen: (noteId: String, line: Int) -> Unit, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val s = LocalStrings.current
    val engine = lib.engine
    val revision by engine.revision.collectAsState()
    val today = LocalDate.now()
    val items = remember(revision, today) { Tasks.agenda(engine.notes.values, today) }
    val tree = remember(items) { Tasks.agendaTree(items) }
    val context = LocalContext.current
    var toggled by remember { mutableStateOf(loadToggled(context)) }
    LaunchedEffect(toggled) { saveToggled(context, toggled) }
    val anyProtected = remember(revision) { engine.notes.values.any { !it.deleted && !it.isTrashed && NoteLock.isEncrypted(it.body) } }
    val dateFormat = remember(s) { DateTimeFormatter.ofPattern(if (s.locale.language == "zh") "M月d日" else "MMM d", s.locale) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.canvas) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(s.agenda, style = SerifTitle, fontSize = 20.sp, color = c.ink, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
            if (items.isEmpty()) {
                Text(s.agendaEmpty, color = c.ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                Text(s.agendaEmptyHint, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
            for (node in tree) {
                val open = (node.key in toggled) != node.openByDefault
                val label = when (node.kind) {
                    Tasks.AgendaNode.Kind.OVERDUE -> s.agendaOverdue
                    Tasks.AgendaNode.Kind.MONTH -> s.agendaMonth(node.month!!, today)
                    else -> s.agendaDay(node.date!!, today)
                }
                val tint = when (node.kind) {
                    Tasks.AgendaNode.Kind.OVERDUE -> c.danger
                    Tasks.AgendaNode.Kind.TODAY -> c.ivy
                    else -> c.muted
                }
                // A branch: tap to fold or unfold; remembered on this device.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { toggled = toggle(toggled, node.key, tree) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.faint, modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f))
                    Spacer(Modifier.width(4.dp))
                    Icon(if (node.kind == Tasks.AgendaNode.Kind.OVERDUE) Icons.Outlined.ErrorOutline else Icons.Outlined.Event, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = if (node.kind == Tasks.AgendaNode.Kind.OVERDUE) c.danger else c.ink, fontSize = 15.sp,
                        fontWeight = if (node.openByDefault) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(node.items.size.toString(), color = c.faint, fontSize = 12.sp)
                }
                if (!open) continue
                for (item in node.items) {
                    val due = item.task.due!!
                    val time = due.time?.let { t -> "%02d:%02d".format(t.hour, t.minute) } ?: ""
                    // Branches of single days say the day; the rest say it on each task.
                    val whenText = when (node.kind) {
                        Tasks.AgendaNode.Kind.OVERDUE, Tasks.AgendaNode.Kind.MONTH -> due.date.format(dateFormat) + (if (time.isEmpty()) "" else " $time")
                        else -> time
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(item.noteId, item.task.line) }.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = false,
                            onCheckedChange = { _ ->
                                val note = engine.notes[item.noteId] ?: return@Checkbox
                                // Only if the line is still the task shown.
                                if (note.body.split('\n').getOrNull(item.task.line) != item.task.raw) return@Checkbox
                                engine.updateNote(note.id, SyncEngine.NotePatch(body = Tasks.toggle(note.body, item.task.line, today)))
                            },
                            colors = CheckboxDefaults.colors(checkedColor = c.ivy, uncheckedColor = c.faint),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.task.text.ifEmpty { s.untitled }, color = c.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.noteTitle.ifEmpty { s.untitled }, color = c.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.task.repeat != null) Text("🔁", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        if (whenText.isNotEmpty()) Text(whenText, color = if (item.days < 0) c.danger else c.faint, fontSize = 12.sp)
                    }
                }
            }
            if (anyProtected) {
                Text(s.agendaProtectedHint, color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            }
        }
    }
}
