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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val unsaved = mood != (r?.mood ?: "") || rating != r?.behaviorRating || highlights != (r?.highlights ?: "") ||
        challenges != (r?.challenges ?: "") || notes != (r?.notes ?: "") || goals != (r?.goals ?: "")
    SideEffect { m.unsavedChanges = unsaved }

    Title("Daily report")
    Muted(prettyDate(b.day))
    Card {
        StatLine("Tasks fully done", "${c.done} of ${c.total}")
        StatLine("Of all checks done", "${c.percent}%")
    }
    if (b.summary.tasks.isNotEmpty()) {
        SectionHeader("The day's tasks")
        b.summary.tasks.forEach { t ->
            val line = "${t.title}: ${reportTaskStatus(t)}"
            Text(line, modifier = Modifier.fillMaxWidth())
        }
    }
    Field(mood, { mood = it; keep() }, "Mood")
    ChoiceGroup("Behavior rating") {
        RadioChip("No rating", rating == null) { rating = null; keep() }
        (1..5).forEach { n -> RadioChip("$n", rating == n, a11y = "$n of 5") { rating = n; keep() } }
    }
    Field(highlights, { highlights = it; keep() }, "Highlights", singleLine = false)
    Field(challenges, { challenges = it; keep() }, "Challenges", singleLine = false)
    Field(notes, { notes = it; keep() }, "Notes", singleLine = false)
    Field(goals, { goals = it; keep() }, "Goals", singleLine = false)
    PrimaryButton("Save report", enabled = !m.busy) {
        m.saveReport(mood.trim(), rating, highlights.trim(), challenges.trim(), notes.trim(), goals.trim())
    }
}

/** "Done", "1 of 2 follow-ups done", "Not done (2 follow-ups)" — for the daily report's task list. */
fun reportTaskStatus(t: DayTask): String {
    val n = t.subtasks.size
    val k = t.subtasks.count { it.done }
    val fu = { x: Int -> if (x == 1) "follow-up" else "follow-ups" }
    return when {
        n == 0 -> if (t.done) "done" else "not done"
        t.complete -> "done, all $n ${fu(n)} done"
        t.done -> "$k of $n ${fu(n)} done"
        else -> "not done ($n ${fu(n)})"
    }
}

private fun times(n: Int) = if (n == 1) "1 time" else "$n times"

/** "main box checked 5 times, fully done 3 times, 2 follow-up checks" (just "done 5 times" without follow-ups). */
fun freqText(t: TaskFreq): String {
    if (t.followUps == 0 || t.fullCount == null) return "done ${times(t.count)}"
    val fu = if (t.followUps == 1) "1 follow-up check" else "${t.followUps} follow-up checks"
    return "main box checked ${times(t.count)}, fully done ${times(t.fullCount)}, $fu"
}

private fun dailyText(d: BhisDay): String =
    "${d.completed} of ${d.scheduled} done, ${d.percent}%" + if (d.partly > 0) ", ${d.partly} partly done" else ""

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BhisScreen(m: AppModel) {
    val ctx = LocalContext.current
    var from by rememberSaveable { mutableStateOf(if (m.today.isEmpty()) "" else shiftDate(m.today, -6)) }
    var to by rememberSaveable { mutableStateOf(m.today) }
    LaunchedEffect(Unit) { if (m.bhisReport == null && from.isNotEmpty()) m.generateBhis(from, to) }

    Title("BHIS report")
    m.managedChild?.let { Muted("for ${it.firstName} ${it.lastName}") }
    // Stacked, so both stay usable at large text sizes. Each opens a date picker.
    DateField("From", from) { from = it }
    DateField("To", to) { to = it }
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
        Text("${prettyDate(r.from, withYear = true)} to ${prettyDate(r.to, withYear = true)} · ${r.days} days",
            style = MaterialTheme.typography.titleMedium)
        StatLine("Tasks completed", "${r.stats.totalCompletions}")
        StatLine("Days with activity", "${r.stats.daysWithActivity} of ${r.days}")
        StatLine("Active tasks", "${r.stats.activeTaskCount}")
        StatLine("Avg completion", "${r.stats.avgCompletionPct}%")
        StatLine("Avg behavior", r.stats.avgBehaviorRating?.let { "$it / 5" } ?: "—")
        if (r.stats.moodCounts.isNotEmpty()) StatLine("Moods", r.stats.moodCounts.entries.joinToString("  ") { "${it.key}×${it.value}" })
    }
    if (r.daily.isNotEmpty()) {
        SectionHeader("Daily")
        Muted("Done counts tasks finished with all their follow-ups. Partly done: main box checked, follow-ups not all done.")
        r.daily.forEach { d ->
            // Stacked (day, then numbers) so it reads in order and fits at large text.
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                Text(prettyDate(d.day), fontWeight = FontWeight.SemiBold)
                Muted(dailyText(d))
            }
        }
    }
    if (r.taskFreq.isNotEmpty()) {
        SectionHeader("How often each task was done")
        r.taskFreq.forEach { t ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "${t.title}, ${if (t.type == "activity") "activity" else "chore"}: ${freqText(t)}"
            }) {
                IconText(if (t.type == "activity") "⭐" else "✅", t.title)
                Muted(freqText(t))
            }
        }
    }
    if (r.reports.isNotEmpty()) {
        SectionHeader("Daily notes")
        r.reports.forEach { rep ->
            Card(Modifier.semantics(mergeDescendants = true) { }) {
                Text(prettyDate(rep.day, withYear = true), fontWeight = FontWeight.SemiBold)
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
    append("${prettyDate(r.from, withYear = true)} to ${prettyDate(r.to, withYear = true)} (${r.days} days)\n\n")
    append("Tasks completed: ${r.stats.totalCompletions}\n")
    append("Days with activity: ${r.stats.daysWithActivity}/${r.days}\n")
    append("Active tasks: ${r.stats.activeTaskCount}\n")
    append("Avg completion: ${r.stats.avgCompletionPct}%\n")
    append("Avg behavior: ${r.stats.avgBehaviorRating?.let { "$it/5" } ?: "—"}\n")
    if (r.stats.moodCounts.isNotEmpty()) append("Moods: " + r.stats.moodCounts.entries.joinToString(", ") { "${it.key} ×${it.value}" } + "\n")
    append("\nDaily:\n")
    r.daily.forEach { append("  ${prettyDate(it.day)}: ${dailyText(it)}\n") }
    if (r.taskFreq.isNotEmpty()) {
        append("\nTask frequency:\n")
        r.taskFreq.forEach { append("  ${it.title}: ${freqText(it)}\n") }
    }
    if (r.reports.isNotEmpty()) {
        append("\nDaily notes:\n")
        r.reports.forEach { rep ->
            append("  ${prettyDate(rep.day)} — mood ${rep.mood?.ifEmpty { null } ?: "—"}, behavior ${rep.behaviorRating ?: "—"}\n")
            listOf("Highlights" to rep.highlights, "Challenges" to rep.challenges, "Notes" to rep.notes, "Goals" to rep.goals)
                .forEach { (k, v) -> if (!v.isNullOrEmpty()) append("    $k: $v\n") }
        }
    }
}

private fun followUpCount(n: Int) = if (n == 1) "1 follow-up check" else "$n follow-up checks"

private fun adjustRowLabel(t: DayTaskSchedule): String {
    val fu = if (t.subtaskCount > 0) ", ${followUpCount(t.subtaskCount)}" else ""
    if (t.skipped) return "${t.title}, skipped this day" + if (t.subtaskCount > 0) ", with its ${followUpCount(t.subtaskCount)}" else ""
    val chg = if (t.overrideSlot != null || t.overrideTime != null) ", changed for this day" else ""
    return "${t.title}, ${cap(t.effectiveSlot)}" + (prettyTime(t.effectiveTime)?.let { " at $it" } ?: "") + chg + fu
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdjustDayScreen(m: AppModel) {
    var day by rememberSaveable { mutableStateOf(m.overrideDay.ifEmpty { m.today }) }
    Title("Adjust a day")
    m.managedChild?.let { Muted("for ${it.firstName}") }
    Muted("Nudge a task's time or skip it — just for one day, without changing the recurring schedule.")
    DateField("Day", day) { day = it; m.loadDaySchedule(it) }
    FlowRow {
        LinkButton("Yesterday") { if (m.today.isNotEmpty()) day = shiftDate(m.today, -1) }
        LinkButton("Today") { day = m.today }
        LinkButton("Tomorrow") { if (m.today.isNotEmpty()) day = shiftDate(m.today, 1) }
    }
    PrimaryButton("Show day", enabled = !m.busy && day.isNotBlank()) { m.loadDaySchedule(day.trim()) }
    val sched = m.daySchedule ?: return
    SectionHeader(prettyDate(sched.day))
    if (sched.tasks.isEmpty() && !m.busy) Muted("Nothing scheduled for ${prettyDate(sched.day)}.")
    sched.tasks.forEach { t ->
        RowCard(adjustRowLabel(t), hint = "adjust", onClick = { m.editOverrideStart(t) }) {
            Text(t.emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { })
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                if (t.skipped) Text("Skipped this day", color = Palette.danger)
                else Muted(cap(t.effectiveSlot) + (prettyTime(t.effectiveTime)?.let { " · $it" } ?: "") +
                    if (t.overrideSlot != null || t.overrideTime != null) " · changed" else "")
                if (t.subtaskCount > 0) Muted(followUpCount(t.subtaskCount) + if (t.skipped) ", skipped too" else "")
            }
            Text("✎", color = Palette.muted, modifier = Modifier.clearAndSetSemantics { })
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
    Title("Adjust for ${prettyDate(m.overrideDay)}")
    IconText(t.emoji, t.title, style = MaterialTheme.typography.titleMedium)
    Muted("Recurring default: ${cap(t.defaultSlot)}" + (prettyTime(t.defaultTime)?.let { " · $it" } ?: ""))
    SectionHeader("Just for this day")
    // A fixed label: the switch state says whether it's skipped; the note below says what that means.
    SwitchRow("Skip this day", skip) { skip = it }
    val fu = if (t.subtaskCount > 0) " Its ${followUpCount(t.subtaskCount)} are skipped too." else ""
    Muted(if (skip) "Won't show on this day.$fu" else "Skipping hides it for this day only.$fu")
    if (!skip) {
        ChoiceGroup("Time slot") {
            RadioChip("Default (${cap(t.defaultSlot)})", slot == null) { slot = null }
            slotIds.forEach { s -> RadioChip(cap(s), slot == s) { slot = s } }
        }
        TimeField("Time", time,
            noneText = "Default" + (prettyTime(t.defaultTime)?.let { " ($it)" } ?: " (no specific time)")) { time = it }
    }
    PrimaryButton("Save for this day", enabled = !m.busy) {
        m.saveOverride(t.id, if (skip) "" else (slot ?: ""), if (skip) "" else time.trim(), skip)
    }
    if (t.overrideSlot != null || t.overrideTime != null || t.skipped) {
        SecondaryButton("Reset to recurring default") { m.clearOverride(t.id) }
    }
}
