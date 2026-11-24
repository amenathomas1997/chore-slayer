package com.at365.choretracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun RoleScreen(m: AppModel) {
    var confirmForget by remember { mutableStateOf(false) }
    Title("Who's using the app?")
    m.family?.let { Muted("Family: ${it.name ?: it.code}") }
    PrimaryButton("I'm a Kid", icon = "🧒") { m.chooseKid() }
    PrimaryButton("Parent / Admin", icon = "👪", color = Palette.brandPurple) { m.chooseParent() }
    LinkButton("Use a different family") { confirmForget = true }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Disconnect this device from its family?") },
            text = { Text("You'll need the family code and parent PIN to connect again.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; m.forgetFamily() }) { Text("Disconnect", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun KidLoginScreen(m: AppModel) {
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    // "We couldn't find that name": shown under First name, which gets focus.
    val err = m.fieldError?.takeIf { it.field == "kidName" }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(err?.seq) { if (err != null) firstFocus.requestFocus() }
    Title("Type your name")
    Field(first, { first = it; m.fieldError = null }, "First name", error = err?.message, focus = firstFocus)
    Field(last, { last = it; m.fieldError = null }, "Last name", error = if (err != null) "Check this name too." else null)
    PrimaryButton("See my chores", enabled = !m.busy && first.isNotBlank() && last.isNotBlank()) {
        m.kidLogin(first.trim(), last.trim())
    }
}

/** Slot id, decorative emoji (not read aloud), name. */
private val slotLabels = listOf(
    Triple("morning", "🌅", "Morning"), Triple("midday", "☀️", "Midday"), Triple("afternoon", "🌤️", "Afternoon"),
    Triple("evening", "🌙", "Evening"), Triple("anytime", "🕓", "Anytime"),
)

@Composable
fun BoardScreen(m: AppModel) {
    val board = m.board
    val child = m.child
    if (board == null || child == null) { Muted("Loading…"); return }
    Title("${child.emoji} ${child.firstName}'s day", a11y = "${child.firstName}'s day")
    Muted(prettyDate(board.day))
    Text(progressText(board.counts), style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress = { board.counts.percent.coerceIn(0, 100) / 100f },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { })
    // What the last check-off did ("Brush teeth checked. 2 more steps below."). Always composed,
    // so each new message is a text change the polite live region announces.
    Text(m.kidStatus, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    if (board.tasks.isEmpty()) IconText("🎉", "Nothing scheduled for today!")
    for ((id, icon, label) in slotLabels) {
        val items = board.tasks.filter { it.slot == id }
        if (items.isEmpty()) continue
        SectionHeader(label, icon = icon)
        // key(): focus and state follow the task, not its position, if the list changes.
        items.forEach { t -> key(t.id) { TaskRow(t) { taskId -> m.toggle(taskId) } } }
    }
    Gap(8)
    PrimaryButton("See my day", icon = "🎉", color = Palette.goodGreen) { m.openKidSummary() }
    KidReminderCard(m)
}

private fun progressText(c: Counts): String {
    val pctLabel = if (c.steps != null && c.steps.total > 0) "${c.percent}% of steps" else "${c.percent}%"
    return "${c.done} of ${c.total} tasks finished · $pctLabel"
}

@Composable
private fun TaskRow(task: DayTask, onToggle: (String) -> Unit) {
    val subs = task.subtasks
    val left = subs.count { !it.done }
    val partial = task.done && !task.complete
    val bg = when { task.complete -> Palette.taskDone; partial -> Palette.taskPartial; else -> Palette.card }
    val toggle = when { task.complete -> ToggleableState.On; partial -> ToggleableState.Indeterminate; else -> ToggleableState.Off }
    val stepWord = { n: Int -> if (n == 1) "step" else "steps" }
    val state = when {
        task.complete -> "done"
        partial -> "checked, $left of ${subs.size} follow-up ${stepWord(subs.size)} left"
        subs.isNotEmpty() -> "not done, then ${subs.size} follow-up ${stepWord(subs.size)}"
        else -> "not done"
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(bg)
            // Complete gets a dark border (well over 3:1) so it differs from partial by more than colour.
            .then(if (task.complete) Modifier.border(2.dp, Palette.goodGreen, shape) else Modifier)
            .padding(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .triStateToggleable(state = toggle, role = Role.Checkbox) { onToggle(task.id) }
                .semantics(mergeDescendants = true) {
                    contentDescription = task.title + (prettyTime(task.time)?.let { ", at $it" } ?: "") + ", " + task.type
                    stateDescription = state
                }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(state = toggle, onClick = null, modifier = Modifier.clearAndSetSemantics { },
                colors = CheckboxDefaults.colors(checkedColor = Palette.goodGreen))
            Text(" ${task.emoji} ", modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(task.title, fontWeight = FontWeight.Medium)
                prettyTime(task.time)?.let { Muted("at $it") }
                when {
                    task.complete -> Text("All done!", color = Palette.goodGreen, fontWeight = FontWeight.Bold)
                    partial -> Text("$left of ${subs.size} ${stepWord(subs.size)} left", color = Color(0xFF6B4500), fontWeight = FontWeight.Bold)
                }
            }
        }
        // The server only accepts follow-up checks once the main task is checked.
        if (task.done) {
            subs.forEachIndexed { i, sub ->
                key(sub.id) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 28.dp)
                            .toggleable(value = sub.done, role = Role.Checkbox) { onToggle(sub.id) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${sub.title}, step ${i + 1} of ${subs.size} for ${task.title}"
                                stateDescription = if (sub.done) "done" else "not done"
                            }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = sub.done, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { },
                            colors = CheckboxDefaults.colors(checkedColor = Palette.goodGreen))
                        // The default follow-up emoji (☑️) reads as a second, already-ticked box, so leave it out.
                        val icon = if (sub.emoji == "☑️" || sub.emoji.isBlank()) "" else "${sub.emoji} "
                        Text(" $icon${sub.title}", modifier = Modifier.weight(1f).clearAndSetSemantics { })
                    }
                }
            }
        }
    }
}

/** Kid board card: turn on chore reminders for this kid on this device. */
@Composable
private fun KidReminderCard(m: AppModel) {
    if (!m.pushAvailable) return
    val ask = rememberNotificationPermission { granted -> m.enableKidReminders(granted) }
    if (m.kidRemindersOn) {
        IconText("🔔", "Reminders are on for this device.", Modifier.fillMaxWidth().padding(top = 4.dp),
            color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (m.pushAllowed == false) return
    Card(Modifier.padding(top = 8.dp)) {
        SectionHeader("Chore reminders")
        Text("Get a reminder on this device when each chore is due.")
        PrimaryButton("Remind me when it's time", icon = "🔔", enabled = !m.busy) { ask() }
    }
}

/** "See my day": the kid's celebration summary. */
@Composable
fun KidSummaryScreen(m: AppModel) {
    val board = m.board
    val child = m.child
    if (board == null || child == null) { Muted("Loading…"); return }
    val c = board.counts
    val pct = c.percent.coerceIn(0, 100)
    val stars = (pct / 20.0).roundToInt().coerceIn(0, 5)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("${child.firstName}'s Day", center = true)
        Muted(prettyDate(board.day))
        Box(Modifier.size(150.dp).semantics(mergeDescendants = true) { contentDescription = "$pct percent of steps done" },
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { pct / 100f }, strokeWidth = 13.dp, modifier = Modifier.size(150.dp),
                color = Palette.brand, trackColor = Color(0xFFE5E5EA))
            Text("$pct%", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { })
        }
        val (cheerText, cheerIcon) = cheer(pct)
        Row(Modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
            Text(cheerText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(" $cheerIcon", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
        }
        Text("★".repeat(stars) + "☆".repeat(5 - stars), fontSize = 26.sp, color = Color(0xFF9A5B00),
            modifier = Modifier.semantics { contentDescription = "$stars out of 5 stars" })
        val tiles = buildList {
            add("${c.done}" to "Finished"); add("${c.total - c.done}" to "Still to do")
            c.chores?.let { add("${it.done}/${it.total}" to "Chores") }
            c.activities?.let { add("${it.done}/${it.total}" to "Activities") }
        }
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (v, l) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.card).heightIn(min = 64.dp)
                        .padding(8.dp).semantics(mergeDescendants = true) { },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Muted(l)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (board.tasks.isEmpty()) Muted("Nothing on the list today.")
            board.tasks.forEach { t ->
                val partial = !t.complete && t.done
                val stepsTotal = 1 + t.subtasks.size
                val stepsDone = (if (t.done) 1 else 0) + t.subtasks.count { it.done }
                val status = if (t.complete) "Done" else if (partial) "$stepsDone of $stepsTotal steps done" else "Not yet"
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                    Text(if (t.complete) "✅" else if (partial) "🟡" else "⬜", modifier = Modifier.clearAndSetSemantics { })
                    Text(" ${t.emoji} ", modifier = Modifier.clearAndSetSemantics { })
                    Text(t.title, modifier = Modifier.weight(1f))
                    Muted(status)
                }
                t.subtasks.forEach { s ->
                    val st = if (s.done) "Done" else "Not yet"
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp)
                        .clearAndSetSemantics { contentDescription = "${s.title}, step for ${t.title}, $st" }) {
                        Text(if (s.done) "✅" else "⬜")
                        Text(" ${s.title}", modifier = Modifier.weight(1f))
                        Muted(st)
                    }
                }
            }
        }
        SecondaryButton("Back to my chores", icon = "←") { m.back() }
    }
}

/** The cheer and its decorative emoji (shown, not read aloud). */
private fun cheer(pct: Int) = when {
    pct >= 100 -> "Amazing! You finished everything!" to "🏆"
    pct >= 75 -> "Awesome work — almost there!" to "🌟"
    pct >= 50 -> "Great job, halfway and beyond!" to "💪"
    pct >= 25 -> "Good start — keep going!" to "🙌"
    pct > 0 -> "You've begun — you can do it!" to "👍"
    else -> "Let's get started when you're ready!" to "🌱"
}
