package com.at365.choretracker

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyReportScreen(m: AppModel) {
    val b = m.reportBundle ?: run { Muted("Loading…"); return }
    val r = b.report
    val childId = m.managedChild?.id ?: b.summary.childId
    // Text not yet saved survives the idle lock: it's kept in the model as a draft.
    val d = m.reportDraft?.takeIf { it.childId == childId && it.day == b.day }
    var mood by rememberSaveable(b.day) { mutableStateOf(d?.mood ?: r?.mood ?: "") }
    var rating by rememberSaveable(b.day) { mutableStateOf(if (d != null) d.rating else r?.behaviorRating) }
    var highlights by rememberSaveable(b.day) { mutableStateOf(d?.highlights ?: r?.highlights ?: "") }
    var challenges by rememberSaveable(b.day) { mutableStateOf(d?.challenges ?: r?.challenges ?: "") }
    var notes by rememberSaveable(b.day) { mutableStateOf(d?.notes ?: r?.notes ?: "") }
    var goals by rememberSaveable(b.day) { mutableStateOf(d?.goals ?: r?.goals ?: "") }
    val keep = { m.reportDraft = ReportDraft(childId, b.day, mood, rating, highlights, challenges, notes, goals) }
    val c = b.summary.counts

    Title("Daily report")
    Muted(b.day)
    Card(Modifier.semantics(mergeDescendants = true) { contentDescription = "Today: ${c.done} of ${c.total} tasks done, ${c.percent} percent" }) {
        Text("Today: ${c.done}/${c.total} tasks done · ${c.percent}%", style = MaterialTheme.typography.titleMedium)
    }
    Field(mood, { mood = it; keep() }, "Mood")
    SectionHeader("Behavior (1–5)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..5).forEach { n -> SelectChip("$n", rating == n, "Rating $n of 5") { rating = if (rating == n) null else n; keep() } }
    }
    Field(highlights, { highlights = it; keep() }, "Highlights", singleLine = false)
    Field(challenges, { challenges = it; keep() }, "Challenges", singleLine = false)
    Field(notes, { notes = it; keep() }, "Notes", singleLine = false)
    Field(goals, { goals = it; keep() }, "Goals", singleLine = false)
    PrimaryButton("Save report", enabled = !m.busy) {
        m.saveReport(mood.trim(), rating, highlights.trim(), challenges.trim(), notes.trim(), goals.trim())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BhisScreen(m: AppModel) {
    val active = LocalUserActivity.current
    val ctx = LocalContext.current
    var from by rememberSaveable { mutableStateOf(if (m.today.isEmpty()) "" else shiftDate(m.today, -6)) }
    var to by rememberSaveable { mutableStateOf(m.today) }
    LaunchedEffect(Unit) { if (m.bhisReport == null && from.isNotEmpty()) m.generateBhis(from, to) }

    Title("BHIS report")
    m.managedChild?.let { Muted("for ${it.firstName} ${it.lastName}") }
    // Stacked, so both fields stay usable at large text sizes.
    OutlinedTextField(from, { active(); from = it }, label = { Text("From (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(to, { active(); to = it }, label = { Text("To (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow {
        LinkButton("Last 7 days") { if (m.today.isNotEmpty()) { from = shiftDate(m.today, -6); to = m.today } }
        LinkButton("Last 30 days") { if (m.today.isNotEmpty()) { from = shiftDate(m.today, -29); to = m.today } }
    }
    PrimaryButton("Generate", enabled = !m.busy && from.isNotBlank() && to.isNotBlank()) { m.generateBhis(from.trim(), to.trim()) }

    val r = m.bhisReport ?: return
    SecondaryButton("Share as text") {
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, bhisShareText(r)), "Share BHIS report"))
    }
    Card {
        Text("${r.from} → ${r.to} · ${r.days} days", style = MaterialTheme.typography.titleMedium)
        StatLine("Tasks completed", "${r.stats.totalCompletions}")
        StatLine("Days with activity", "${r.stats.daysWithActivity} of ${r.days}")
        StatLine("Active tasks", "${r.stats.activeTaskCount}")
        StatLine("Avg completion", "${r.stats.avgCompletionPct}%")
        StatLine("Avg behavior", r.stats.avgBehaviorRating?.let { "$it / 5" } ?: "—")
        if (r.stats.moodCounts.isNotEmpty()) StatLine("Moods", r.stats.moodCounts.entries.joinToString("  ") { "${it.key}×${it.value}" })
    }
    if (r.daily.isNotEmpty()) {
        SectionHeader("Daily")
        r.daily.forEach { d ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "${d.day}: ${d.completed} of ${d.scheduled} done, ${d.percent} percent"
            }) { Text(d.day, Modifier.weight(1f)); Text("${d.completed}/${d.scheduled} · ${d.percent}%") }
        }
    }
    if (r.taskFreq.isNotEmpty()) {
        SectionHeader("How often each task was done")
        r.taskFreq.forEach { t ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "${t.title}, done ${t.count} time" + if (t.count == 1) "" else "s"
            }) { Text((if (t.type == "activity") "⭐ " else "✅ ") + t.title, Modifier.weight(1f)); Text("×${t.count}") }
        }
    }
    if (r.reports.isNotEmpty()) {
        SectionHeader("Daily notes")
        r.reports.forEach { rep ->
            Card(Modifier.semantics(mergeDescendants = true) { }) {
                Text(rep.day, fontWeight = FontWeight.SemiBold)
                if (!rep.mood.isNullOrEmpty() || rep.behaviorRating != null)
                    Muted("Mood: ${rep.mood?.ifEmpty { null } ?: "—"} · Behavior: ${rep.behaviorRating ?: "—"}")
                listOf("Highlights" to rep.highlights, "Challenges" to rep.challenges, "Notes" to rep.notes, "Goals" to rep.goals)
                    .forEach { (k, v) -> if (!v.isNullOrEmpty()) Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

fun bhisShareText(r: BhisReport): String = buildString {
    append("BHIS report — ${r.childName ?: ""}\n")
    append("${r.from} to ${r.to} (${r.days} days)\n\n")
    append("Tasks completed: ${r.stats.totalCompletions}\n")
    append("Days with activity: ${r.stats.daysWithActivity}/${r.days}\n")
    append("Active tasks: ${r.stats.activeTaskCount}\n")
    append("Avg completion: ${r.stats.avgCompletionPct}%\n")
    append("Avg behavior: ${r.stats.avgBehaviorRating?.let { "$it/5" } ?: "—"}\n")
    if (r.stats.moodCounts.isNotEmpty()) append("Moods: " + r.stats.moodCounts.entries.joinToString(", ") { "${it.key} ×${it.value}" } + "\n")
    append("\nDaily:\n")
    r.daily.forEach { append("  ${it.day}: ${it.completed}/${it.scheduled} (${it.percent}%)\n") }
    if (r.taskFreq.isNotEmpty()) {
        append("\nTask frequency:\n")
        r.taskFreq.forEach { append("  ${it.title} ×${it.count}\n") }
    }
    if (r.reports.isNotEmpty()) {
        append("\nDaily notes:\n")
        r.reports.forEach { rep ->
            append("  ${rep.day} — mood ${rep.mood?.ifEmpty { null } ?: "—"}, behavior ${rep.behaviorRating ?: "—"}\n")
            listOf("Highlights" to rep.highlights, "Challenges" to rep.challenges, "Notes" to rep.notes, "Goals" to rep.goals)
                .forEach { (k, v) -> if (!v.isNullOrEmpty()) append("    $k: $v\n") }
        }
    }
}

private fun adjustRowLabel(t: DayTaskSchedule): String {
    if (t.skipped) return "${t.title}, skipped this day"
    val chg = if (t.overrideSlot != null || t.overrideTime != null) ", changed for this day" else ""
    return "${t.title}, ${cap(t.effectiveSlot)}" + (t.effectiveTime?.let { " at $it" } ?: "") + chg
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdjustDayScreen(m: AppModel) {
    var day by rememberSaveable { mutableStateOf(m.overrideDay.ifEmpty { m.today }) }
    Title("Adjust a day")
    m.managedChild?.let { Muted("for ${it.firstName}") }
    Muted("Nudge a task's time or skip it — just for one day, without changing the recurring schedule.")
    Field(day, { day = it }, "Day (YYYY-MM-DD)")
    FlowRow {
        LinkButton("Yesterday") { if (m.today.isNotEmpty()) day = shiftDate(m.today, -1) }
        LinkButton("Today") { day = m.today }
        LinkButton("Tomorrow") { if (m.today.isNotEmpty()) day = shiftDate(m.today, 1) }
    }
    PrimaryButton("Show day", enabled = !m.busy && day.isNotBlank()) { m.loadDaySchedule(day.trim()) }
    val sched = m.daySchedule ?: return
    if (sched.tasks.isEmpty() && !m.busy) Muted("Nothing scheduled for ${sched.day}.")
    sched.tasks.forEach { t ->
        RowCard(adjustRowLabel(t), hint = "adjust", onClick = { m.editOverrideStart(t) }) {
            Text(t.emoji, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                if (t.skipped) Text("Skipped this day", color = Palette.danger)
                else Muted(cap(t.effectiveSlot) + (t.effectiveTime?.let { " · $it" } ?: "") +
                    if (t.overrideSlot != null || t.overrideTime != null) " · changed" else "")
            }
            Text("✎", color = Palette.muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverrideEditorScreen(m: AppModel) {
    val t = m.editingOverride ?: run { Muted("No task selected."); return }
    var skip by rememberSaveable(t.id) { mutableStateOf(t.skipped) }
    var slot by rememberSaveable(t.id) { mutableStateOf(t.overrideSlot) }
    var time by rememberSaveable(t.id) { mutableStateOf(t.overrideTime ?: "") }
    Title("Adjust for ${m.overrideDay}")
    Text("${t.emoji} ${t.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = t.title })
    Muted("Recurring default: ${cap(t.defaultSlot)}" + (t.defaultTime?.let { " · $it" } ?: ""))
    SectionHeader("Just for this day")
    SelectChip(if (skip) "Skipped — won't show this day" else "Skip this day", skip) { skip = !skip }
    if (!skip) {
        SectionHeader("Time slot")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectChip("Default (${cap(t.defaultSlot)})", slot == null) { slot = null }
            slotIds.forEach { s -> SelectChip(cap(s), slot == s) { slot = s } }
        }
        Field(time, { time = it }, "Time (HH:MM · blank = default)")
    }
    PrimaryButton("Save for this day", enabled = !m.busy) {
        m.saveOverride(t.id, if (skip) "" else (slot ?: ""), if (skip) "" else time.trim(), skip)
    }
    if (t.overrideSlot != null || t.overrideTime != null || t.skipped) {
        SecondaryButton("Reset to recurring default") { m.clearOverride(t.id) }
    }
}
