package app.ivynote.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ivynote.core.Links
import app.ivynote.core.Note
import app.ivynote.core.SyncEngine
import app.ivynote.core.Tag

/** The six tag colors of the web client (`--tag-*-fg`), "" for none. */
val TAG_COLORS = listOf("", "green", "blue", "amber", "pink", "purple", "gray")

private val TAG_NAMES = mapOf(
    "" to "无", "green" to "绿", "blue" to "蓝", "amber" to "琥珀", "pink" to "粉", "purple" to "紫", "gray" to "灰",
)

@Composable
fun tagColor(color: String): Color {
    val dark = Ivy.colors.dark
    return when (color) {
        "green" -> if (dark) Color(0xFF92C4A6) else Color(0xFF3F7A5B)
        "blue" -> if (dark) Color(0xFF9DB2DC) else Color(0xFF4F6A9A)
        "amber" -> if (dark) Color(0xFFD9B77E) else Color(0xFFA0702C)
        "pink" -> if (dark) Color(0xFFDBA6BF) else Color(0xFFA65A7C)
        "purple" -> if (dark) Color(0xFFBCAAE0) else Color(0xFF7A62A8)
        "gray" -> if (dark) Color(0xFFA3A9AF) else Color(0xFF6F767D)
        else -> Ivy.colors.ink2
    }
}

/** `#name` on a soft pill in the tag's color. */
@Composable
fun TagChip(tag: Tag, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val fg = tagColor(tag.color)
    Text(
        "#" + tag.name,
        fontSize = 12.5.sp,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** The note's tags under its title, then "+ 标签" (guide 11.3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteTagsRow(note: Note, engine: SyncEngine, editable: Boolean, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ivy.colors
    val tags = note.tags.mapNotNull { engine.tags[it] }
    if (tags.isEmpty() && !editable) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in tags) TagChip(t, onClick = if (editable) onEdit else null)
        if (editable) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onEdit).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = c.faint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("标签", fontSize = 12.5.sp, color = c.faint)
            }
        }
    }
}

/**
 * Picks a note's tags: search or create (「创建 xxx」), tap to toggle, the
 * pencil edits a tag (name, color, delete). At most 50 per note (guide 4.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(engine: SyncEngine, noteId: String, onDismiss: () -> Unit) {
    val c = Ivy.colors
    val revision by engine.revision.collectAsState()
    val note = remember(revision) { engine.notes[noteId] } ?: return
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Tag?>(null) }
    val all = remember(revision) { engine.tags.values.filter { !it.deleted }.sortedBy { it.name.lowercase() } }
    val clean = SyncEngine.cleanTagName(query)
    val key = Links.normalizeTitle(clean)
    val shown = if (key.isEmpty()) all else all.filter { Links.normalizeTitle(it.name).contains(key) }
    val exact = engine.tagNamed(clean)
    val full = note.tags.size >= SyncEngine.MAX_NOTE_TAGS

    fun submit() {
        if (clean.isEmpty()) return
        val tag = exact ?: engine.createTag(clean)
        engine.setNoteTag(note.id, tag.id, true)
        query = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("标签", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink, modifier = Modifier.weight(1f))
            Text("${note.tags.size}/${SyncEngine.MAX_NOTE_TAGS}", fontSize = 13.sp, color = c.faint)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = c.faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索或创建标签", color = c.faint, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(60) },
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.ivy),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(Icons.Outlined.Close, contentDescription = "清除", tint = c.faint,
                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { query = "" })
            }
        }
        if (full) Text("一篇笔记最多 ${SyncEngine.MAX_NOTE_TAGS} 个标签", color = c.warn, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (clean.isNotEmpty() && exact == null) {
                item(key = "create") {
                    PickerRow(enabled = !full, onClick = { submit() }) {
                        Icon(Icons.Outlined.Add, null, tint = c.ivy, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("创建「$clean」", color = c.ivy, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            items(shown, key = { it.id }) { t ->
                val on = t.id in note.tags
                PickerRow(enabled = on || !full, onClick = { engine.setNoteTag(note.id, t.id, !on) }) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (on) Icon(Icons.Outlined.Check, null, tint = c.ivy, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(t.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(t.name, color = c.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editing = t }) { Icon(Icons.Outlined.Edit, contentDescription = "编辑标签", tint = c.faint, modifier = Modifier.size(18.dp)) }
                }
            }
            if (all.isEmpty() && clean.isEmpty()) {
                item { Text("还没有标签。输入名称即可创建。", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)) }
            }
        }
    }
    editing?.let { t -> TagEditDialog(engine, t, onDismiss = { editing = null }) }
}

@Composable
private fun PickerRow(enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Renames, recolors or deletes a tag (guide 3.3); deleting takes it off every note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditDialog(engine: SyncEngine, tag: Tag, onDismiss: () -> Unit) {
    val c = Ivy.colors
    var name by remember { mutableStateOf(tag.name) }
    var color by remember { mutableStateOf(tag.color) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clean = SyncEngine.cleanTagName(name)
    val clash = engine.tagNamed(clean)?.takeIf { it.id != tag.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    label = { Text("名称") }, prefix = { Text("#") },
                    isError = clash != null,
                    supportingText = if (clash != null) ({ Text("已有同名标签") }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("颜色", fontSize = 13.sp, color = c.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (k in TAG_COLORS) {
                        val fg = tagColor(k)
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(fg.copy(alpha = if (k.isEmpty()) 0.08f else 0.85f))
                                .border(2.dp, if (k == color) c.ink else Color.Transparent, CircleShape)
                                .clickable(onClickLabel = TAG_NAMES[k]) { color = k },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == color) Icon(Icons.Outlined.Check, contentDescription = TAG_NAMES[k], tint = if (k.isEmpty()) c.ink else c.surface, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(color = c.line)
                TextButton(onClick = { confirmDelete = true }) { Text("删除标签", color = c.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = clean.isNotEmpty() && clash == null, onClick = {
                engine.updateTag(tag.id, name = clean, color = color)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (confirmDelete) {
        ConfirmDialog("删除标签？", "「#${tag.name}」会从所有笔记上移除，笔记本身不受影响。", "删除", onDismiss = { confirmDelete = false }) {
            engine.deleteTag(tag.id)
            onDismiss()
        }
    }
}
