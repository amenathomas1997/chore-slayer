package com.at365.choretracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    PrimaryButton("🧒 I'm a Kid") { m.chooseKid() }
    PrimaryButton("👪 Parent / Admin", color = Palette.brandPurple) { m.chooseParent() }
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
    Title("Type your name")
    Field(first, { first = it }, "First name")
    Field(last, { last = it }, "Last name")
    PrimaryButton("See my chores", enabled = !m.busy && first.isNotBlank() && last.isNotBlank()) {
        m.kidLogin(first.trim(), last.trim())
    }
}

private val slotLabels = listOf(
    "morning" to "🌅 Morning", "midday" to "☀️ Midday", "afternoon" to "🌤️ Afternoon",
    "evening" to "🌙 Evening", "anytime" to "🕓 Anytime",
)

@Composable
fun BoardScreen(m: AppModel) {
    val board = m.board
    val child = m.child
    if (board == null || child == null) { Muted("Loading…"); return }
    Title("${child.emoji} ${child.firstName}'s day", a11y = "${child.firstName}'s day")
    Muted(board.day)
    Text("${board.counts.done} of ${board.counts.total} done • ${board.counts.percent}%", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress = { board.counts.percent.coerceIn(0, 100) / 100f },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { })
    if (board.tasks.isEmpty()) Text("🎉 Nothing scheduled for today!")
    for ((id, label) in slotLabels) {
        val items = board.tasks.filter { it.slot == id }
        if (items.isEmpty()) continue
        SectionHeader(label)
        items.forEach { TaskRow(it) { taskId -> m.toggle(taskId) } }
    }
    Gap(8)
    PrimaryButton("🎉 See my day", color = Palette.goodGreen) { m.openKidSummary() }
    KidReminderCard(m)
}

@Composable
private fun TaskRow(task: DayTask, onToggle: (String) -> Unit) {
    val bg = when { task.complete -> Palette.taskDone; task.done -> Palette.taskPartial; else -> Palette.card }
    val state = when { task.complete -> "completed"; task.done -> "checked, follow-ups remaining"; else -> "not done" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(role = Role.Checkbox) { onToggle(task.id) }
                .semantics(mergeDescendants = true) {
                    contentDescription = task.title + (task.time?.let { ", at $it" } ?: "") + ", " + task.type
                    stateDescription = state
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (task.done) "☑" else "☐", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { })
            Text(" ${task.emoji} ", modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(task.title, fontWeight = FontWeight.Medium)
                task.time?.let { Muted("at $it") }
            }
        }
        if (task.done) {
            task.subtasks.forEach { sub ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 28.dp)
                        .clickable(role = Role.Checkbox) { onToggle(sub.id) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = sub.title
                            stateDescription = if (sub.done) "done" else "not done"
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The default follow-up emoji (☑️) reads as a second, already-ticked box, so leave it out.
                    val icon = if (sub.emoji == "☑️" || sub.emoji.isBlank()) "" else "${sub.emoji} "
                    Text((if (sub.done) "☑" else "☐") + " $icon${sub.title}", modifier = Modifier.clearAndSetSemantics { })
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
        Muted("🔔 Reminders are on for this device.", Modifier.fillMaxWidth().padding(top = 4.dp))
        return
    }
    if (m.pushAllowed == false) return
    Card(Modifier.padding(top = 8.dp)) {
        SectionHeader("Chore reminders")
        Text("Get a reminder on this device when each chore is due.")
        PrimaryButton("🔔 Remind me when it's time", enabled = !m.busy) { ask() }
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
        Muted(board.day)
        Box(Modifier.size(150.dp).semantics(mergeDescendants = true) { contentDescription = "$pct percent of tasks done" },
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { pct / 100f }, strokeWidth = 13.dp, modifier = Modifier.size(150.dp),
                color = Palette.brand, trackColor = Color(0xFFE5E5EA))
            Text("$pct%", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { })
        }
        Text(cheer(pct), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                    Text(if (t.complete) "✅" else if (partial) "🟡" else "⬜", modifier = Modifier.clearAndSetSemantics { })
                    Text(" ${t.emoji} ${t.title}", modifier = Modifier.weight(1f))
                    Muted(if (t.complete) "Done" else if (partial) "Partly" else "Not yet")
                }
                t.subtasks.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp).semantics(mergeDescendants = true) { }) {
                        Text(if (s.done) "✅" else "⬜", modifier = Modifier.clearAndSetSemantics { })
                        Text(" ${s.title}", modifier = Modifier.weight(1f))
                        Muted(if (s.done) "Done" else "Not yet")
                    }
                }
            }
        }
        SecondaryButton("← Back to my chores") { m.back() }
    }
}

private fun cheer(pct: Int) = when {
    pct >= 100 -> "Amazing! You finished everything! 🏆"
    pct >= 75 -> "Awesome work — almost there! 🌟"
    pct >= 50 -> "Great job, halfway and beyond! 💪"
    pct >= 25 -> "Good start — keep going! 🙌"
    pct > 0 -> "You've begun — you can do it! 👍"
    else -> "Let's get started when you're ready! 🌱"
}
