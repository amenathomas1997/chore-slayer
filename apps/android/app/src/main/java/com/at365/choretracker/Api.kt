package com.at365.choretracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fixed server, the family this device is linked to, and the parent sign-in.
 * The parent token lives only in memory, so closing the app (or the 10-minute
 * idle lock) ends the parent sign-in.
 */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("chore_slayer", Context.MODE_PRIVATE)

    /** Debug builds only: a test server can be set with `adb shell am start -e apiBase <url>`. */
    var debugBase: String? = null

    val baseUrl: String get() = if (BuildConfig.DEBUG && !debugBase.isNullOrBlank()) debugBase!! else BuildConfig.API_BASE

    var token: String? = null

    var family: Family?
        get() = Family.fromString(prefs.getString("family", null))
        set(v) { prefs.edit().apply { if (v == null) remove("family") else putString("family", v.toJson()) }.apply() }
}

/** Thin client over `<base>?action=<name>`. Reads use the query string; writes POST JSON. */
class Api(private val session: Session) {

    suspend fun status() = StatusInfo.from(get("status"))

    suspend fun parentLogin(pin: String, familyCode: String? = null, familyId: String? = null): Pair<String, Family> {
        val body = mutableMapOf("pin" to pin)
        if (familyId != null) body["familyId"] = familyId else if (familyCode != null) body["familyCode"] = familyCode
        return tokenAndFamily(post("parent_login", body))
    }

    suspend fun signup(pin: String, name: String) = tokenAndFamily(post("signup", mapOf("pin" to pin, "name" to name)))

    private fun tokenAndFamily(j: JSONObject): Pair<String, Family> {
        val token = j.optString("token").takeIf { it.isNotEmpty() }
        val fam = j.optJSONObject("family")?.let(Family::from)
        if (token == null || fam == null) throw ApiError("bad_response", "The server's answer was missing the sign-in details.", 0)
        return token to fam
    }

    suspend fun changePin(current: String, new: String) { post("change_pin", mapOf("current" to current, "pin" to new)) }
    suspend fun deleteFamily(pin: String) { post("delete_family", mapOf("pin" to pin)) }
    suspend fun parentLogout() { runCatching { post("parent_logout", emptyMap()) } }

    suspend fun childLogin(firstName: String, lastName: String, familyId: String): Child =
        Child.from(post("child_login", mapOf("firstName" to firstName, "lastName" to lastName, "familyId" to familyId)).getJSONObject("child"))

    suspend fun children(): List<Child> {
        val a = get("children").optJSONArray("children") ?: return emptyList()
        return (0 until a.length()).map { Child.from(a.getJSONObject(it)) }
    }

    suspend fun daySummary(childId: String, day: String? = null) =
        DaySummary.from(get("day_summary", buildMap { put("childId", childId); day?.let { put("day", it) } }))

    suspend fun toggleCompletion(childId: String, taskId: String) =
        post("toggle_completion", mapOf("childId" to childId, "taskId" to taskId)).optBoolean("completed")

    // Parent — children
    suspend fun addChild(firstName: String, lastName: String, color: String, emoji: String) {
        post("add_child", mapOf("firstName" to firstName, "lastName" to lastName, "color" to color, "emoji" to emoji))
    }
    suspend fun updateChild(id: String, firstName: String, lastName: String, color: String, emoji: String) {
        post("update_child", mapOf("id" to id, "firstName" to firstName, "lastName" to lastName, "color" to color, "emoji" to emoji))
    }
    suspend fun removeChild(id: String) { post("remove_child", mapOf("id" to id)) }
    suspend fun deleteChildForever(id: String) { post("delete_child_forever", mapOf("id" to id)) }

    // Parent — tasks & follow-up checks
    suspend fun parentTasks(childId: String): List<ParentTask> {
        val a = get("tasks", mapOf("childId" to childId)).optJSONArray("tasks") ?: return emptyList()
        return (0 until a.length()).map { ParentTask.from(a.getJSONObject(it)) }
    }
    suspend fun addTask(childId: String, title: String, type: String, emoji: String, daysOfWeek: String, timeSlot: String, timeOfDay: String) {
        post("add_task", mapOf("childId" to childId, "title" to title, "type" to type, "emoji" to emoji,
            "daysOfWeek" to daysOfWeek, "timeSlot" to timeSlot, "timeOfDay" to timeOfDay))
    }
    suspend fun updateTask(id: String, title: String, type: String, emoji: String, daysOfWeek: String, timeSlot: String, timeOfDay: String) {
        post("update_task", mapOf("id" to id, "title" to title, "type" to type, "emoji" to emoji,
            "daysOfWeek" to daysOfWeek, "timeSlot" to timeSlot, "timeOfDay" to timeOfDay))
    }
    suspend fun removeTask(id: String) { post("remove_task", mapOf("id" to id)) }
    suspend fun addSubtask(parentId: String, title: String, emoji: String) {
        post("add_subtask", mapOf("parentId" to parentId, "title" to title, "emoji" to emoji))
    }
    suspend fun updateSubtask(id: String, title: String) { post("update_subtask", mapOf("id" to id, "title" to title)) }

    // Parent — reports
    suspend fun getReport(childId: String, day: String? = null): ReportBundle {
        val j = get("report", buildMap { put("childId", childId); day?.let { put("day", it) } })
        val summary = DaySummary.from(j.getJSONObject("summary"))
        return ReportBundle(summary.day, ReportData.from(j.optJSONObject("report")), summary)
    }
    suspend fun saveReport(childId: String, day: String?, mood: String, behaviorRating: Int?,
                           highlights: String, challenges: String, notes: String, goals: String) {
        post("save_report", buildMap {
            put("childId", childId); put("mood", mood); put("highlights", highlights)
            put("challenges", challenges); put("notes", notes); put("goals", goals)
            put("behaviorRating", behaviorRating?.toString() ?: "")
            day?.let { put("day", it) }
        })
    }
    suspend fun bhis(childId: String, from: String, to: String) =
        BhisReport.from(get("bhis", mapOf("childId" to childId, "from" to from, "to" to to)))

    // Parent — per-day overrides
    suspend fun daySchedule(childId: String, day: String? = null) =
        DaySchedule.from(get("day_schedule", buildMap { put("childId", childId); day?.let { put("day", it) } }))
    suspend fun setOverride(taskId: String, day: String, timeSlot: String, timeOfDay: String, skipped: Boolean) {
        post("set_override", mapOf("taskId" to taskId, "day" to day, "timeSlot" to timeSlot,
            "timeOfDay" to timeOfDay, "skipped" to if (skipped) "1" else "0"))
    }

    // Push
    suspend fun pushRegister(token: String, familyId: String, childId: String? = null, parentAlerts: Boolean? = null): PushDeviceState {
        val body = mutableMapOf("platform" to "android", "token" to token, "familyId" to familyId)
        childId?.let { body["childId"] = it }
        parentAlerts?.let { body["parentAlerts"] = if (it) "1" else "0" }
        return PushDeviceState.from(post("push_register", body))
    }
    suspend fun pushUnregister(token: String) { runCatching { post("push_unregister", mapOf("token" to token)) } }

    // ---- transport ----

    private suspend fun get(action: String, query: Map<String, String> = emptyMap()) = request(action, "GET", query, null)
    private suspend fun post(action: String, body: Map<String, String>) = request(action, "POST", emptyMap(), body)

    private suspend fun request(action: String, method: String, query: Map<String, String>, body: Map<String, String>?): JSONObject =
        withContext(Dispatchers.IO) {
            val qs = (mapOf("action" to action) + query).entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            val conn = (URL(session.baseUrl + "?" + qs).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                session.token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(JSONObject(body as Map<*, *>).toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (code !in 200..299) {
                    throw ApiError(json?.optString("error")?.ifEmpty { null } ?: "http_$code",
                        json?.optString("message")?.ifEmpty { null } ?: "Request failed ($code).", code)
                }
                json ?: throw ApiError("bad_response", "The server sent an unexpected answer.", code)
            } catch (e: IOException) {
                throw ApiError("network", "Couldn't reach Chore Slayer. Check your internet connection.", 0)
            } finally {
                conn.disconnect()
            }
        }
}
