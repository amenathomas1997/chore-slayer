package com.at365.choretracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ParentLoginScreen(m: AppModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    val err = m.fieldError?.takeIf { it.field == "pin" }
    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(err?.seq) { if (err != null) pinFocus.requestFocus() }
    Title("Parent sign-in")
    PinField(pin, { pin = it; m.fieldError = null }, "PIN", error = err?.message, focus = pinFocus)
    PrimaryButton("Sign in", enabled = !m.busy && pin.length >= 4) { m.parentLogin(pin) }
}

@Composable
fun ChildrenScreen(m: AppModel) {
    Title("Children")
    PrimaryButton("Add a child", icon = "➕") { m.addChildStart() }
    if (m.children.isEmpty() && !m.busy) Muted("No children yet.")
    m.children.forEach { c ->
        RowCard("${c.firstName} ${c.lastName}", hint = "manage", onClick = { m.openChildHub(c) }) {
            Text(c.emoji, fontSize = 28.sp, modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${c.firstName} ${c.lastName}", style = MaterialTheme.typography.titleMedium)
                Muted("Manage")
            }
            Text("›", fontSize = 24.sp, color = Palette.muted, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
fun ChildHubScreen(m: AppModel) {
    val c = m.managedChild ?: return
    Title("${c.emoji} ${c.firstName} ${c.lastName}", a11y = "${c.firstName} ${c.lastName}")
    PrimaryButton("Today's board", icon = "📋") { m.openBoard(c) }
    PrimaryButton("Tasks & activities", icon = "✅") { m.openTasks() }
    PrimaryButton("Daily report", icon = "📝") { m.openReport() }
    PrimaryButton("BHIS report", icon = "📊") { m.openBhis() }
    PrimaryButton("Adjust a day", icon = "📅") { m.openAdjustDay() }
    PrimaryButton("Edit child", icon = "✏️") { m.editChildStart() }
}

/** Child icons with plain names, so screen readers and voice control (Voice Access, Dragon) can say them. */
private val childEmojis = listOf(
    "🙂" to "Smile", "😀" to "Grin", "🦊" to "Fox", "🐱" to "Cat", "🦄" to "Unicorn",
    "🐶" to "Dog", "🌟" to "Star", "🚀" to "Rocket", "🦖" to "Dinosaur", "🐢" to "Turtle",
)
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
    var confirmRemove by remember { mutableStateOf(false) }
    val changed = if (existing == null) first.isNotBlank() || last.isNotBlank() || emoji != "🙂" || color != "#5b8def"
        else first != existing.firstName || last != existing.lastName || emoji != existing.emoji || color != existing.color
    SideEffect { m.unsavedChanges = changed }

    Title(if (existing == null) "Add a child" else "Edit child")
    Field(first, { first = it }, "First name")
    Field(last, { last = it }, "Last name")
    ChoiceGroup("Icon") {
        childEmojis.forEach { (e, name) ->
            val sel = emoji == e
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Palette.brand.copy(alpha = 0.18f) else Palette.card)
                    // Selected: a thick brand border and a tick badge, not colour alone.
                    .border(if (sel) 3.dp else 1.dp, if (sel) Palette.brand else Palette.muted, RoundedCornerShape(10.dp))
                    .selectable(selected = sel, role = Role.RadioButton) { emoji = e }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics { })
                if (sel) CheckBadge(Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
    }
    ChoiceGroup("Color") {
        childColors.forEach { hex ->
            val sel = color == hex
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(hexColor(hex))
                    .border(1.dp, Palette.muted, CircleShape)
                    .selectable(selected = sel, role = Role.RadioButton) { color = hex }
                    .semantics { contentDescription = colorName(hex) },
                contentAlignment = Alignment.Center,
            ) { if (sel) CheckBadge(size = 26) }
        }
    }
    PrimaryButton(if (existing == null) "Add child" else "Save changes", enabled = !m.busy && first.isNotBlank() && last.isNotBlank()) {
        m.saveChild(existing, first.trim(), last.trim(), color, emoji)
    }
    if (existing != null) {
        HorizontalDivider()
        SecondaryButton("Remove (keep their history)") { confirmRemove = true }
        LinkButton("Delete forever", color = Palette.danger) { confirmDelete = true }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${existing?.firstName ?: "this child"}?") },
            text = { Text("They won't be able to sign in and their chores stop showing. Their history and reports are kept.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; m.removeManagedChild(false) }) { Text("Remove", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
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

/** A white tick on a dark disc: marks the selected icon or colour without relying on colour. */
@Composable
private fun CheckBadge(modifier: Modifier = Modifier, size: Int = 18) {
    Box(modifier.size(size.dp).clip(CircleShape).background(Palette.brand).border(1.dp, Color.White, CircleShape)
        .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.6f).sp)
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
    return "$typ · ${daysSummary(t.daysOfWeek)} · ${cap(t.timeSlot)}" + (prettyTime(t.timeOfDay)?.let { " · $it" } ?: "")
}

@Composable
fun TasksScreen(m: AppModel) {
    Title("Tasks & activities")
    m.managedChild?.let { Muted("for ${it.firstName}") }
    PrimaryButton("Add task or activity", icon = "➕") { m.addTaskStart() }
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
    val childId = m.managedChild?.id ?: existing?.childId ?: ""
    // Unsaved fields survive the idle lock: they're kept in the model as a draft.
    val d = m.taskDraft?.takeIf { it.childId == childId && it.taskKey == key }
    var title by rememberSaveable(key) { mutableStateOf(d?.title ?: existing?.title ?: "") }
    var type by rememberSaveable(key) { mutableStateOf(d?.type ?: existing?.type ?: "chore") }
    var emoji by rememberSaveable(key) { mutableStateOf(d?.emoji ?: existing?.emoji ?: "🧹") }
    var mask by rememberSaveable(key) { mutableStateOf(d?.mask ?: existing?.daysOfWeek ?: "1111111") }
    var slot by rememberSaveable(key) { mutableStateOf(d?.slot ?: existing?.timeSlot ?: "anytime") }
    var time by rememberSaveable(key) { mutableStateOf(d?.time ?: existing?.timeOfDay ?: "") }
    var newSub by rememberSaveable(key) { mutableStateOf(d?.newSub ?: "") }
    // Unsaved follow-up renames (sub id -> typed text); "Save changes" saves these too.
    val subEdits = rememberSaveable(key, saver = subEditsSaver) {
        mutableStateMapOf<String, String>().apply { d?.subEdits?.let { putAll(it) } }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var titleError by remember(key) { mutableStateOf<String?>(null) }
    var titleTries by remember { mutableStateOf(0) }
    val active = LocalUserActivity.current

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(titleTries) { if (titleError != null) titleFocus.requestFocus() }
    val addFocus = remember { FocusRequester() }
    val rowFocus = remember(key) { mutableMapOf<String, FocusRequester>() }
    val upFocus = remember(key) { mutableMapOf<String, FocusRequester>() }
    val downFocus = remember(key) { mutableMapOf<String, FocusRequester>() }
    // Where to put focus once the list has updated: a follow-up id, "add", "up:<id>", "down:<id>", or null.
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFocus, existing?.subtasks, m.busy) {
        val target = pendingFocus ?: return@LaunchedEffect
        if (m.busy) return@LaunchedEffect   // buttons are disabled until the save finishes
        val subs = existing?.subtasks.orEmpty()
        runCatching {
            when {
                target == "add" -> addFocus.requestFocus()
                target.startsWith("up:") || target.startsWith("down:") -> {
                    // After a move, stay on the same row: the same button if it still works, else the other one.
                    val id = target.substringAfter(':')
                    val i = subs.indexOfFirst { it.id == id }
                    val wantUp = target.startsWith("up:")
                    val useUp = if (wantUp) i > 0 else i >= subs.size - 1
                    (if (useUp) upFocus[id] else downFocus[id])?.requestFocus()
                }
                else -> rowFocus[target]?.requestFocus()
            }
        }
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
    // Anything Back would lose (a new task only once something was filled in).
    val unsaved = if (existing == null)
        title.isNotBlank() || type != "chore" || emoji != "🧹" || mask != "1111111" || slot != "anytime" || time.isNotEmpty()
    else dirty || newSub.isNotBlank()
    SideEffect {
        m.unsavedChanges = unsaved
        if (unsaved) m.taskDraft = TaskDraft(childId, key, title, type, emoji, mask, slot, time, newSub, subEdits.toMap())
        else if (m.taskDraft?.taskKey == key) m.taskDraft = null
    }

    Title(if (existing == null) "Add task / activity" else "Edit task")
    Field(title, { title = it; titleError = null }, "Title", error = titleError, focus = titleFocus)
    ChoiceGroup("Type") {
        RadioChip("Chore", type == "chore", icon = "✅") { type = "chore" }
        RadioChip("Activity", type == "activity", icon = "⭐") { type = "activity" }
    }
    SectionHeader("Icon")
    Field(emoji, { emoji = it }, "Emoji", supporting = "One emoji, shown next to the title")
    ChoiceGroup("Repeats on", single = false) {
        (0..6).forEach { i ->
            val on = mask.getOrNull(i) == '1'
            CheckChip(dow[i], on, dowFull[i]) {
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
    ChoiceGroup("Time of day") {
        slotIds.forEach { s -> RadioChip(cap(s), slot == s) { slot = s } }
    }
    TimeField("Specific time", time, noneText = "No specific time") { active(); time = it }

    // Follow-ups come before "Save changes", so going top to bottom reaches them before leaving.
    if (existing != null) {
        HorizontalDivider()
        SectionHeader("Follow-up checks")
        Muted("Sub-steps that appear after this task is checked. Adding, removing or moving one saves straight away.")
        val subs = existing.subtasks
        subs.forEachIndexed { i, sub ->
            key(sub.id) {
                SubtaskRow(
                    sub = sub, index = i, count = subs.size, busy = m.busy,
                    text = subEdits[sub.id] ?: sub.title,
                    onText = { active(); subEdits[sub.id] = it },
                    focus = rowFocus.getOrPut(sub.id) { FocusRequester() },
                    upFocus = upFocus.getOrPut(sub.id) { FocusRequester() },
                    downFocus = downFocus.getOrPut(sub.id) { FocusRequester() },
                    onSave = { t -> m.renameSubtask(sub.id, t) { ok -> if (ok) subEdits.remove(sub.id) } },
                    onRemove = {
                        val next = subs.getOrNull(i + 1)?.id ?: "add"
                        m.removeSubtask(sub.id, sub.title) { ok -> if (ok) { subEdits.remove(sub.id); rowFocus.remove(sub.id); pendingFocus = next } }
                    },
                    // From a button: focus follows to that row's move button. From TalkBack's actions
                    // menu: focus is already on the row's field, and key(id) keeps it there.
                    onMove = { up, fromButton ->
                        m.moveSubtask(sub.id, up) { ok -> if (ok && fromButton) pendingFocus = (if (up) "up:" else "down:") + sub.id }
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

    PrimaryButton(if (existing == null) "Add" else "Save changes", enabled = !m.busy && dirty) {
        if (title.isBlank()) {
            titleError = "Enter a title for this task."; titleTries++
        } else {
            val e = emoji.trim().ifEmpty { if (type == "activity") "⭐" else "🧹" }
            m.saveTask(existing, title.trim(), type, e, mask, slot, time.trim(), renames) { subEdits.clear() }
        }
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

/**
 * One follow-up check: rename it in place (Save, or the keyboard's Done), move it up or down, or
 * remove it. Stacked so it works at large text. Move up / Move down are also TalkBack actions on the field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtaskRow(
    sub: ParentSub, index: Int, count: Int, busy: Boolean, text: String, onText: (String) -> Unit,
    focus: FocusRequester, upFocus: FocusRequester, downFocus: FocusRequester,
    onSave: (String) -> Unit, onRemove: () -> Unit, onMove: (up: Boolean, fromButton: Boolean) -> Unit,
) {
    val changed = text.trim().isNotEmpty() && text.trim() != sub.title
    val n = index + 1
    val canUp = index > 0
    val canDown = index < count - 1
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(text, onText, label = { Text("Follow-up $n of $count") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (changed && !busy) onSave(text.trim()) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
                customActions = listOfNotNull(
                    if (canUp) CustomAccessibilityAction("Move up: ${sub.title}") { if (!busy) onMove(true, false); true } else null,
                    if (canDown) CustomAccessibilityAction("Move down: ${sub.title}") { if (!busy) onMove(false, false); true } else null,
                )
            })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onMove(true, true) }, enabled = !busy && canUp,
                modifier = Modifier.heightIn(min = 48.dp).focusRequester(upFocus)
                    .semantics { contentDescription = "Move up: ${sub.title}" }) { Text("Move up") }
            TextButton(onClick = { onMove(false, true) }, enabled = !busy && canDown,
                modifier = Modifier.heightIn(min = 48.dp).focusRequester(downFocus)
                    .semantics { contentDescription = "Move down: ${sub.title}" }) { Text("Move down") }
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
