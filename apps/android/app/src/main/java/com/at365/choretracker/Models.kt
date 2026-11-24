package com.at365.choretracker

import org.json.JSONArray
import org.json.JSONObject

// Models decoded from the Chore Slayer JSON API (see API.md). Parsing uses the
// org.json classes built into Android, so there are no extra dependencies.

private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
private inline fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

data class StatusInfo(val installed: Boolean, val apiVersion: String, val isParent: Boolean, val today: String) {
    companion object {
        fun from(j: JSONObject) = StatusInfo(
            j.optBoolean("installed"), j.optString("apiVersion"), j.optBoolean("isParent"), j.optString("today"))
    }
}

data class Family(val id: String, val code: String, val name: String?) {
    fun toJson(): String = JSONObject().put("id", id).put("code", code).put("name", name ?: JSONObject.NULL).toString()
    companion object {
        fun from(j: JSONObject) = Family(j.getString("id"), j.optString("code"), j.str("name"))
        fun fromString(s: String?): Family? = runCatching { from(JSONObject(s!!)) }.getOrNull()
    }
}

data class Child(
    val id: String, val firstName: String, val lastName: String,
    val color: String, val emoji: String, val active: Boolean,
) {
    companion object {
        fun from(j: JSONObject) = Child(
            j.getString("id"), j.optString("firstName"), j.optString("lastName"),
            j.str("color") ?: "#5b8def", j.str("emoji") ?: "🙂", j.optBoolean("active", true))
    }
}

data class SubTask(val id: String, val title: String, val emoji: String, val done: Boolean) {
    companion object {
        fun from(j: JSONObject) = SubTask(j.getString("id"), j.optString("title"), j.str("emoji") ?: "☑️", j.optBoolean("done"))
    }
}

data class DayTask(
    val id: String, val title: String, val type: String, val emoji: String, val slot: String,
    val time: String?, val done: Boolean, val complete: Boolean, val subtasks: List<SubTask>,
) {
    companion object {
        fun from(j: JSONObject) = DayTask(
            j.getString("id"), j.optString("title"), j.optString("type"), j.optString("emoji"),
            j.str("slot") ?: "anytime", j.str("time"), j.optBoolean("done"), j.optBoolean("complete"),
            j.optJSONArray("subtasks").list(SubTask::from))
    }
}

data class DoneTotal(val total: Int, val done: Int) {
    companion object {
        fun from(j: JSONObject?) = j?.let { DoneTotal(it.optInt("total"), it.optInt("done")) }
    }
}

data class Counts(
    val total: Int, val done: Int, val percent: Int, val chores: DoneTotal?, val activities: DoneTotal?,
    /** Follow-up checks only (not main tasks); null on older servers. */
    val steps: DoneTotal? = null,
) {
    companion object {
        fun from(j: JSONObject) = Counts(
            j.optInt("total"), j.optInt("done"), j.optInt("percent"),
            DoneTotal.from(j.optJSONObject("chores")), DoneTotal.from(j.optJSONObject("activities")),
            DoneTotal.from(j.optJSONObject("steps")))
    }
}

data class DaySummary(val childId: String, val day: String, val tasks: List<DayTask>, val counts: Counts) {
    companion object {
        fun from(j: JSONObject) = DaySummary(
            j.optString("childId"), j.optString("day"),
            j.optJSONArray("tasks").list(DayTask::from), Counts.from(j.getJSONObject("counts")))
    }
}

data class ParentSub(val id: String, val title: String, val emoji: String)

data class ParentTask(
    val id: String, val childId: String, val title: String, val type: String, val emoji: String,
    val daysOfWeek: String, val timeSlot: String, val timeOfDay: String?, val subtasks: List<ParentSub>,
) {
    companion object {
        fun from(j: JSONObject) = ParentTask(
            j.getString("id"), j.optString("childId"), j.optString("title"), j.optString("type"),
            j.optString("emoji"), j.str("daysOfWeek") ?: "1111111", j.str("timeSlot") ?: "anytime",
            j.str("timeOfDay"),
            j.optJSONArray("subtasks").list { ParentSub(it.getString("id"), it.optString("title"), it.str("emoji") ?: "☑️") })
    }
}

data class ReportData(
    val mood: String?, val behaviorRating: Int?, val highlights: String?,
    val challenges: String?, val notes: String?, val goals: String?,
) {
    companion object {
        fun from(j: JSONObject?) = j?.let {
            ReportData(it.str("mood"), it.int("behaviorRating"), it.str("highlights"),
                it.str("challenges"), it.str("notes"), it.str("goals"))
        }
    }
}

data class ReportBundle(val day: String, val report: ReportData?, val summary: DaySummary)

data class BhisStats(
    val totalCompletions: Int, val daysWithActivity: Int, val activeTaskCount: Int,
    val avgCompletionPct: Int, val avgBehaviorRating: Double?, val moodCounts: Map<String, Int>,
)
/** [partly]: tasks whose main box was checked that day but whose follow-ups weren't all done. */
data class BhisDay(val day: String, val completed: Int, val scheduled: Int, val percent: Int, val partly: Int = 0)
/**
 * [count]: days the main box was checked. [fullCount]: days it was fully done (main box and every
 * follow-up); null on older servers. [followUps]: how many follow-up checks the task has.
 */
data class TaskFreq(val title: String, val type: String, val count: Int, val fullCount: Int? = null, val followUps: Int = 0)
data class DatedReport(
    val day: String, val mood: String?, val behaviorRating: Int?, val highlights: String?,
    val challenges: String?, val notes: String?, val goals: String?,
)

data class BhisReport(
    val childName: String?, val from: String, val to: String, val days: Int, val stats: BhisStats,
    val daily: List<BhisDay>, val taskFreq: List<TaskFreq>, val reports: List<DatedReport>,
) {
    companion object {
        fun from(j: JSONObject): BhisReport {
            val s = j.getJSONObject("stats")
            val moods = s.optJSONObject("moodCounts")
            val moodMap = buildMap { moods?.keys()?.forEach { k -> put(k, moods.optInt(k)) } }
            val range = j.getJSONObject("range")
            return BhisReport(
                j.optJSONObject("child")?.str("name"),
                range.optString("from"), range.optString("to"), range.optInt("days"),
                BhisStats(
                    s.optInt("totalCompletions"), s.optInt("daysWithActivity"), s.optInt("activeTaskCount"),
                    s.optInt("avgCompletionPct"),
                    if (s.isNull("avgBehaviorRating")) null else s.optDouble("avgBehaviorRating"),
                    moodMap),
                j.optJSONArray("daily").list { BhisDay(it.optString("day"), it.optInt("completed"), it.optInt("scheduled"), it.optInt("percent"), it.optInt("partly")) },
                j.optJSONArray("taskFreq").list { TaskFreq(it.optString("title"), it.optString("type"), it.optInt("count"), it.int("fullCount"), it.optInt("followUps")) },
                j.optJSONArray("reports").list {
                    DatedReport(it.optString("day"), it.str("mood"), it.int("behaviorRating"), it.str("highlights"),
                        it.str("challenges"), it.str("notes"), it.str("goals"))
                })
        }
    }
}

data class DayTaskSchedule(
    val id: String, val title: String, val type: String, val emoji: String,
    val defaultSlot: String, val defaultTime: String?, val overrideSlot: String?, val overrideTime: String?,
    val skipped: Boolean, val effectiveSlot: String, val effectiveTime: String?,
    /** How many follow-up checks the task has (they are skipped with it). */
    val subtaskCount: Int = 0,
) {
    companion object {
        fun from(j: JSONObject) = DayTaskSchedule(
            j.getString("id"), j.optString("title"), j.optString("type"), j.optString("emoji"),
            j.str("defaultSlot") ?: "anytime", j.str("defaultTime"), j.str("overrideSlot"), j.str("overrideTime"),
            j.optBoolean("skipped"), j.str("effectiveSlot") ?: "anytime", j.str("effectiveTime"), j.optInt("subtaskCount"))
    }
}

data class DaySchedule(val day: String, val tasks: List<DayTaskSchedule>) {
    companion object {
        fun from(j: JSONObject) = DaySchedule(j.optString("day"), j.optJSONArray("tasks").list(DayTaskSchedule::from))
    }
}

data class PushDeviceState(val registered: Boolean, val childId: String?, val parentAlerts: Boolean) {
    companion object {
        fun from(j: JSONObject) = PushDeviceState(j.optBoolean("registered"), j.str("childId"), j.optBoolean("parentAlerts"))
    }
}

class ApiError(val code: String, override val message: String, val status: Int) : Exception(message)
