package com.at365.choretracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ParentLoginScreen(m: AppModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    Title("Parent sign-in")
    PinField(pin, { pin = it }, "PIN")
    PrimaryButton("Sign in", enabled = !m.busy && pin.length >= 4) { m.parentLogin(pin) }
}

@Composable
fun ChildrenScreen(m: AppModel) {
    Title("Children")
    PrimaryButton("➕ Add a child") { m.addChildStart() }
    if (m.children.isEmpty() && !m.busy) Muted("No children yet.")
    m.children.forEach { c ->
        RowCard("${c.firstName} ${c.lastName}", hint = "manage", onClick = { m.openChildHub(c) }) {
            Text(c.emoji, fontSize = 28.sp, modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${c.firstName} ${c.lastName}", style = MaterialTheme.typography.titleMedium)
                Muted("Tap to manage")
            }
            Text("›", fontSize = 24.sp, color = Palette.muted)
        }
    }
}

@Composable
fun ChildHubScreen(m: AppModel) {
    val c = m.managedChild ?: return
    Title("${c.emoji} ${c.firstName} ${c.lastName}", a11y = "${c.firstName} ${c.lastName}")
    PrimaryButton("📋 Today's board") { m.openBoard(c) }
    PrimaryButton("✅ Tasks & activities") { m.openTasks() }
    PrimaryButton("📝 Daily report") { m.openReport() }
    PrimaryButton("📊 BHIS report") { m.openBhis() }
    PrimaryButton("📅 Adjust a day") { m.openAdjustDay() }
    PrimaryButton("✏️ Edit child") { m.editChildStart() }
}

private val childEmojis = listOf("🙂", "😀", "🦊", "🐱", "🦄", "🐶", "🌟", "🚀", "🦖", "🐢")
private val childColors = listOf("#5b8def", "#e8618c", "#33b679", "#f5a623", "#6d4fc0", "#16a3b8")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChildEditorScreen(m: AppModel) {
    val existing = m.editingChild
    var first by rememberSaveable(existing?.id) { mutableStateOf(existing?.firstName ?: "") }
    var last by rememberSaveable(existing?.id) { mutableStateOf(existing?.lastName ?: "") }
    var emoji by rememberSaveable(existing?.id) { mutableStateOf(existing?.emoji ?: "🙂") }
    var color by rememberSaveable(existing?.id) { mutableStateOf(existing?.color ?: "#5b8def") }
    var confirmDelete by remember { mutableStateOf(false) }

    Title(if (existing == null) "Add a child" else "Edit child")
    Field(first, { first = it }, "First name")
    Field(last, { last = it }, "Last name")
    SectionHeader("Icon")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        childEmojis.forEach { e ->
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (emoji == e) Palette.brand.copy(alpha = 0.25f) else Palette.card)
                    .clickable(role = Role.Button) { emoji = e }
                    .semantics { contentDescription = "Icon $e"; selected = emoji == e },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 26.sp) }
        }
    }
    SectionHeader("Color")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        childColors.forEach { hex ->
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(hexColor(hex))
                    .border(if (color == hex) 3.dp else 1.dp, if (color == hex) Color.Black else Color(0x33000000), CircleShape)
                    .clickable(role = Role.Button) { color = hex }
                    .semantics { contentDescription = colorName(hex); selected = color == hex },
            )
        }
    }
    PrimaryButton(if (existing == null) "Add child" else "Save changes", enabled = !m.busy && first.isNotBlank() && last.isNotBlank()) {
        m.saveChild(existing, first.trim(), last.trim(), color, emoji)
    }
    if (existing != null) {
        HorizontalDivider()
        SecondaryButton("Remove (keep their history)") { m.removeManagedChild(false) }
        LinkButton("Delete forever", color = Palette.danger) { confirmDelete = true }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.firstName ?: "child"} forever?") },
            text = { Text("This permanently erases this child and all of their tasks, check-offs, and reports.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; m.removeManagedChild(true) }) { Text("Delete forever", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

fun daysSummary(mask: String): String {
    if (mask.length != 7) return "Some days"
    return when (mask) {
        "1111111" -> "Every day"; "0111110" -> "Weekdays"; "1000001" -> "Weekends"
        else -> listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").filterIndexed { i, _ -> mask[i] == '1' }
            .ifEmpty { listOf("No days") }.joinToString(" ")
    }
}

fun scheduleSummary(t: ParentTask): String {
    val typ = if (t.type == "activity") "Activity" else "Chore"
    return "$typ · ${daysSummary(t.daysOfWeek)} · ${cap(t.timeSlot)}" + (t.timeOfDay?.let { " · $it" } ?: "")
}

@Composable
fun TasksScreen(m: AppModel) {
    Title("Tasks & activities")
    m.managedChild?.let { Muted("for ${it.firstName}") }
    PrimaryButton("➕ Add task or activity") { m.addTaskStart() }
    if (m.parentTasks.isEmpty() && !m.busy) Muted("No tasks yet.")
    m.parentTasks.forEach { t -> key(t.id) {
        val n = t.subtasks.size
        val follow = if (n == 0) "" else ". $n follow-up check" + if (n == 1) "" else "s"
        RowCard("${t.title}. ${scheduleSummary(t)}$follow", hint = "edit", onClick = { m.editTaskStart(t) }) {
            Text(t.emoji, fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Muted(scheduleSummary(t))
                if (t.subtasks.isNotEmpty()) Muted("↳ ${t.subtasks.size} follow-up" + if (t.subtasks.size == 1) "" else "s")
            }
            Text("✎", fontSize = 20.sp, color = Palette.muted, modifier = Modifier.clearAndSetSemantics { })
        }
    } }
}

private val dow = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
private val dowFull = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private val subEditsSaver = listSaver<SnapshotStateMap<String, String>, String>(
    save = { map -> map.flatMap { (k, v) -> listOf(k, v) } },
    restore = { l -> mutableStateMapOf<String, String>().apply { l.chunked(2).forEach { put(it[0], it[1]) } } },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(m: AppModel) {
    val existing = m.editingTask
    val key = existing?.id ?: "new"
    var title by rememberSaveable(key) { mutableStateOf(existing?.title ?: "") }
    var type by rememberSaveable(key) { mutableStateOf(existing?.type ?: "chore") }
    var emoji by rememberSaveable(key) { mutableStateOf(existing?.emoji ?: "🧹") }
    var mask by rememberSaveable(key) { mutableStateOf(existing?.daysOfWeek ?: "1111111") }
    var slot by rememberSaveable(key) { mutableStateOf(existing?.timeSlot ?: "anytime") }
    var time by rememberSaveable(key) { mutableStateOf(existing?.timeOfDay ?: "") }
    var newSub by rememberSaveable(key) { mutableStateOf("") }
    // Unsaved follow-up renames (sub id -> typed text); "Save changes" saves these too.
    val subEdits = rememberSaveable(key, saver = subEditsSaver) { mutableStateMapOf<String, String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val active = LocalUserActivity.current

    val addFocus = remember { FocusRequester() }
    val rowFocus = remember(key) { mutableMapOf<String, FocusRequester>() }
    // Where to put focus once the list has updated: a follow-up id, "add", or null.
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFocus, existing?.subtasks) {
        val target = pendingFocus ?: return@LaunchedEffect
        runCatching { (if (target == "add") addFocus else rowFocus[target])?.requestFocus() }
        pendingFocus = null
    }
    LaunchedEffect(key, m.focusAddFollowUp) {
        if (m.focusAddFollowUp && existing != null) { m.focusAddFollowUp = false; pendingFocus = "add" }
    }

    val renames = existing?.subtasks.orEmpty().mapNotNull { sub ->
        subEdits[sub.id]?.trim()?.takeIf { it.isNotEmpty() && it != sub.title }?.let { sub.id to it }
    }.toMap()
    val dirty = existing == null || title != existing.title || type != existing.type || emoji != existing.emoji ||
        mask != existing.daysOfWeek || slot != existing.timeSlot || time != (existing.timeOfDay ?: "") || renames.isNotEmpty()

    Title(if (existing == null) "Add task / activity" else "Edit task")
    Field(title, { title = it }, "Title")
    SectionHeader("Type")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectChip("✅ Chore", type == "chore", "Chore") { type = "chore" }
        SelectChip("⭐ Activity", type == "activity", "Activity") { type = "activity" }
    }
    SectionHeader("Icon")
    Field(emoji, { emoji = it }, "Emoji")
    SectionHeader("Repeats on")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..6).forEach { i ->
            val on = mask.getOrNull(i) == '1'
            SelectChip(dow[i], on, dowFull[i]) {
                val chars = (if (mask.length == 7) mask else "1111111").toCharArray()
                chars[i] = if (chars[i] == '1') '0' else '1'
                mask = String(chars)
            }
        }
    }
    FlowRow {
        LinkButton("Every day") { mask = "1111111" }
        LinkButton("Weekdays") { mask = "0111110" }
        LinkButton("Weekends") { mask = "1000001" }
    }
    SectionHeader("Time of day")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slotIds.forEach { s -> SelectChip(cap(s), slot == s) { slot = s } }
    }
    Field(time, { time = it }, "Specific time (HH:MM, optional)", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))

    // Follow-ups come before "Save changes", so going top to bottom reaches them before leaving.
    if (existing != null) {
        HorizontalDivider()
        SectionHeader("Follow-up checks")
        Muted("Sub-steps that appear after this task is checked. Adding or removing one saves straight away.")
        val subs = existing.subtasks
        subs.forEachIndexed { i, sub ->
            key(sub.id) {
                SubtaskRow(
                    sub = sub, index = i, count = subs.size, busy = m.busy,
                    text = subEdits[sub.id] ?: sub.title,
                    onText = { active(); subEdits[sub.id] = it },
                    focus = rowFocus.getOrPut(sub.id) { FocusRequester() },
                    onSave = { t -> m.renameSubtask(sub.id, t) { ok -> if (ok) subEdits.remove(sub.id) } },
                    onRemove = {
                        val next = subs.getOrNull(i + 1)?.id ?: "add"
                        m.removeSubtask(sub.id, sub.title) { ok -> if (ok) { subEdits.remove(sub.id); rowFocus.remove(sub.id); pendingFocus = next } }
                    },
                )
            }
        }
        val addNow = {
            val t = newSub.trim()
            if (t.isNotEmpty() && !m.busy) m.addSubtask(existing.id, t) { ok ->
                // Clear only once it's saved, and keep focus here for the next one.
                if (ok) { newSub = ""; pendingFocus = "add" }
            }
        }
        OutlinedTextField(newSub, { active(); newSub = it }, label = { Text("Add a follow-up check") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addNow() }),
            modifier = Modifier.fillMaxWidth().focusRequester(addFocus))
        SecondaryButton("Add follow-up check", enabled = !m.busy && newSub.isNotBlank()) { addNow() }
        HorizontalDivider()
    } else {
        Muted("After you add the task, you can add follow-up checks here.")
    }

    PrimaryButton(if (existing == null) "Add" else "Save changes", enabled = !m.busy && title.isNotBlank() && dirty) {
        val e = emoji.trim().ifEmpty { if (type == "activity") "⭐" else "🧹" }
        m.saveTask(existing, title.trim(), type, e, mask, slot, time.trim(), renames) { subEdits.clear() }
    }
    if (existing != null && !dirty) Muted("No unsaved changes.")
    if (existing != null) {
        LinkButton("Delete this task", color = Palette.danger) { confirmDelete = true }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this task?") },
            text = { Text("This removes the task and its follow-up checks. Past check-off history is kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; m.deleteTask(existing) }) { Text("Delete", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** One follow-up check: rename it in place (Save, or the keyboard's Done), or remove it. Stacked so it works at large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtaskRow(
    sub: ParentSub, index: Int, count: Int, busy: Boolean, text: String, onText: (String) -> Unit,
    focus: FocusRequester, onSave: (String) -> Unit, onRemove: () -> Unit,
) {
    val changed = text.trim().isNotEmpty() && text.trim() != sub.title
    val n = index + 1
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(text, onText, label = { Text("Follow-up $n of $count") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (changed && !busy) onSave(text.trim()) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (changed) TextButton(onClick = { onSave(text.trim()) }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Save follow-up $n" }) { Text("Save") }
            TextButton(onClick = onRemove, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Remove ${sub.title}" }) { Text("Remove", color = Palette.danger) }
        }
    }
}

@Composable
fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        Text(label, color = Palette.muted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LoadOnce(key: Any?, block: () -> Unit) {
    LaunchedEffect(key) { block() }
}
