package app.ivynote.android.ui

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.android.LibrarySession
import app.ivynote.core.NoteLock
import app.ivynote.core.SyncEngine
import app.ivynote.core.Tasks
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The agenda (guide 11.8): open to-dos with a 📅 date across the library,
 * overdue first. Checking one off writes the note, as the editor would; a
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
    val anyProtected = remember(revision) { engine.notes.values.any { !it.deleted && !it.isTrashed && NoteLock.isEncrypted(it.body) } }
    val dateFormat = remember(s) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(s.locale) }

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
            var lastGroup = ""
            for (item in items) {
                val group = when {
                    item.days < 0 -> s.agendaOverdue
                    item.days == 0L -> s.agendaToday
                    else -> s.agendaLater
                }
                if (group != lastGroup) {
                    lastGroup = group
                    Caption(group, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
                }
                val due = item.task.due!!
                val color = when {
                    item.days < 0 -> c.danger
                    item.days == 0L -> c.ivy
                    else -> c.muted
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(item.noteId, item.task.line) }.padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
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
                        Text(item.task.text.ifEmpty { s.untitled }, color = c.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val time = due.time?.let { t -> " %02d:%02d".format(t.hour, t.minute) } ?: ""
                        val title = item.noteTitle.ifEmpty { s.untitled }
                        Text("${due.date.format(dateFormat)}$time · $title", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(s.daysLeft(item.days), color = color, fontSize = 12.sp)
                }
            }
            if (anyProtected) {
                Text(s.agendaProtectedHint, color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            }
        }
    }
}
