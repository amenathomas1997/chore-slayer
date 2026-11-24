package com.at365.choretracker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class Screen {
    Connecting, ConnectDevice, Role, KidLogin, KidBoard, KidSummary, ChangePin, DeleteFamily, Notifications,
    ParentLogin, ParentHome, ChildHub, ChildEditor, Tasks, TaskEditor, DailyReport, Bhis, AdjustDay, OverrideEditor
}

/** All app state and actions; mirrors the iOS AppModel. */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val session = Session(app)
    private val api = Api(session)

    private var currentScreen by mutableStateOf(Screen.Connecting)
    /** Changing screen drops the old screen's field error and unsaved-changes flag. */
    var screen: Screen
        get() = currentScreen
        private set(v) { if (v != currentScreen) { fieldError = null; unsavedChanges = false }; currentScreen = v }
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    /** A server error that belongs to one field (wrong PIN, name not found): shown under that field, which gets focus. */
    var fieldError by mutableStateOf<FieldError?>(null)
    private var fieldErrorSeq = 0
    /** Set by the editors (task, child, daily report) while they hold unsaved changes, so Back can ask first. */
    var unsavedChanges by mutableStateOf(false)

    var family by mutableStateOf(session.family); private set
    var statusInfo by mutableStateOf<StatusInfo?>(null); private set
    var today by mutableStateOf(""); private set
    var isParent by mutableStateOf(false); private set
    var newFamilyCode by mutableStateOf<String?>(null)

    var child by mutableStateOf<Child?>(null); private set
    var board by mutableStateOf<DaySummary?>(null); private set
    var children by mutableStateOf<List<Child>>(emptyList()); private set
    var managedChild by mutableStateOf<Child?>(null); private set
    var editingChild by mutableStateOf<Child?>(null); private set
    var parentTasks by mutableStateOf<List<ParentTask>>(emptyList()); private set
    var editingTask by mutableStateOf<ParentTask?>(null); private set
    var reportBundle by mutableStateOf<ReportBundle?>(null); private set
    var bhisReport by mutableStateOf<BhisReport?>(null); private set
    var daySchedule by mutableStateOf<DaySchedule?>(null); private set
    var overrideDay by mutableStateOf(""); private set
    var editingOverride by mutableStateOf<DayTaskSchedule?>(null); private set

    /** Push can work in this build (Firebase configured). */
    val pushAvailable: Boolean = Push.available(app)
    /** null = not asked yet, false = blocked, true = allowed. */
    var pushAllowed by mutableStateOf<Boolean?>(null); private set
    var kidRemindersOn by mutableStateOf(false); private set

    /** Kid board: what the last check-off did, for a visible + spoken (polite live region) status line. */
    var kidStatus by mutableStateOf(""); private set
    /** A check-off is being saved; further taps are ignored until it finishes (double-tap guard). */
    private var togglingId: String? = null

    /** Task editor: put keyboard focus in the "Add a follow-up check" field (after a new task is added). */
    var focusAddFollowUp by mutableStateOf(false)

    /** Unsaved daily-report text, kept in memory so the idle lock doesn't lose it. */
    var reportDraft: ReportDraft? = null
    /** Unsaved task-editor fields, kept in memory so the idle lock doesn't lose them. */
    var taskDraft: TaskDraft? = null

    private var lastParentActivity = System.currentTimeMillis()
    val parentIdleLimitMs = 10 * 60 * 1000L
    val parentIdleWarnMs = 9 * 60 * 1000L
    /** Show the "Still there?" warning one minute before the parent lock. */
    var idleWarning by mutableStateOf(false); private set

    init {
        Push.onTokenChange = { new, old -> viewModelScope.launch { reRegister(new, old) } }
        if (pushAvailable && Push.allowed(app)) viewModelScope.launch { Push.fetchToken(app) }
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    /**
     * Run an API action with the busy flag and error handling. [fieldFor] maps an error that
     * belongs to one field (e.g. bad_pin) to that field's key; other errors go to the status area.
     */
    private suspend fun act(fieldFor: (ApiError) -> String? = { null }, block: suspend () -> Unit) {
        if (isParent) markParentActivity()
        busy = true; error = null; notice = null; fieldError = null
        try { block() } catch (e: ApiError) {
            val f = fieldFor(e)
            if (f != null) fieldError = FieldError(f, e.message, ++fieldErrorSeq) else error = e.message
        } catch (e: Exception) { error = e.message ?: "Something went wrong." }
        busy = false
    }

    private val pinField: (ApiError) -> String? = { if (it.code == "bad_pin") "pin" else null }

    // ---- connect / role ----

    fun connect() = launch {
        act {
            val s = api.status()
            statusInfo = s; today = s.today; isParent = false
            screen = if (family == null) Screen.ConnectDevice else Screen.Role
        }
    }

    fun connectDevice(code: String, pin: String) = launch {
        act(pinField) {
            val (token, fam) = api.parentLogin(pin, familyCode = code)
            session.family = fam; family = fam; session.token = token
            isParent = true; markParentActivity()
            children = api.children(); screen = Screen.ParentHome
        }
    }

    fun createFamily(name: String, pin: String) = launch {
        act {
            val (token, fam) = api.signup(pin, name)
            session.family = fam; family = fam; session.token = token
            isParent = true; markParentActivity()
            children = api.children(); newFamilyCode = fam.code; screen = Screen.ParentHome
        }
    }

    fun forgetFamily() {
        Push.token(ctx)?.let { t -> launch { api.pushUnregister(t) } }
        Push.clearChoices(ctx)
        kidRemindersOn = false
        endParentSession()
        session.family = null; family = null; child = null; board = null
        screen = Screen.ConnectDevice
    }

    fun chooseKid() { error = null; notice = null; screen = Screen.KidLogin }
    fun chooseParent() { error = null; notice = null; screen = Screen.ParentLogin }

    /** Back from the current screen; returns false when there is nowhere to go. */
    fun back(): Boolean {
        error = null; notice = null
        screen = when (screen) {
            Screen.KidLogin, Screen.ParentLogin -> Screen.Role
            Screen.KidBoard -> if (isParent) Screen.ChildHub else Screen.KidLogin
            Screen.KidSummary -> Screen.KidBoard
            Screen.ChangePin, Screen.DeleteFamily, Screen.Notifications -> Screen.ParentHome
            Screen.ChildHub -> Screen.ParentHome
            Screen.ChildEditor -> if (managedChild != null) Screen.ChildHub else Screen.ParentHome
            Screen.Tasks -> Screen.ChildHub
            Screen.TaskEditor -> Screen.Tasks
            Screen.DailyReport, Screen.Bhis, Screen.AdjustDay -> Screen.ChildHub
            Screen.OverrideEditor -> Screen.AdjustDay
            else -> return false
        }
        return true
    }

    // ---- kid ----

    fun kidLogin(first: String, last: String) = launch {
        val fam = family ?: run { screen = Screen.ConnectDevice; return@launch }
        if (session.token != null) endParentSession()
        act({ if (it.code == "not_found") "kidName" else null }) {
            val c = api.childLogin(first, last, fam.id)
            child = c; kidStatus = ""; board = api.daySummary(c.id); screen = Screen.KidBoard
        }
        syncKidReminders()
    }

    fun openKidSummary() = launch {
        val c = child ?: return@launch
        act { board = api.daySummary(c.id); screen = Screen.KidSummary }
    }

    fun kidSwitchUser() {
        val fam = family
        val token = Push.token(ctx)
        if (Push.childId(ctx) != null && token != null && fam != null) {
            Push.setChildId(ctx, null)
            launch { runCatching { api.pushRegister(token, fam.id, childId = "") } }
        }
        kidRemindersOn = false
        child = null; board = null; screen = Screen.KidLogin
    }

    fun toggle(taskId: String) = launch {
        val c = child ?: return@launch
        if (togglingId != null) return@launch   // a save is in flight: ignore the double tap
        togglingId = taskId
        val before = board
        try {
            val (completed, cleared) = api.toggleCompletion(c.id, taskId)
            val after = api.daySummary(c.id)
            board = after; error = null
            kidStatus = toggleMessage(before, after, taskId, completed, cleared)
        } catch (e: ApiError) {
            error = e.message
            // e.g. 409 parent_not_done: the board was out of date, so refresh it.
            runCatching { board = api.daySummary(c.id) }
        } finally {
            togglingId = null
        }
    }

    private fun steps(n: Int) = if (n == 1) "1 more step" else "$n more steps"

    /** What to tell the kid after a check-off, based on the board before and after. */
    private fun toggleMessage(before: DaySummary?, after: DaySummary, taskId: String, completed: Boolean, cleared: Int): String {
        after.tasks.firstOrNull { it.id == taskId }?.let { t ->
            if (!completed) return if (cleared > 0) "${t.title} unchecked. Its follow-up checks were cleared." else "${t.title} unchecked."
            val left = t.subtasks.count { !it.done }
            return if (left > 0) "${t.title} checked. ${steps(left)} below." else "${t.title} done"
        }
        val parent = after.tasks.firstOrNull { p -> p.subtasks.any { it.id == taskId } } ?: return ""
        val sub = parent.subtasks.first { it.id == taskId }
        val wasComplete = before?.tasks?.firstOrNull { it.id == parent.id }?.complete == true
        if (completed && parent.complete && !wasComplete) return "${parent.title}: all done!"
        if (!completed) return "${sub.title} unchecked."
        val left = parent.subtasks.count { !it.done }
        return "${sub.title} done. ${steps(left)} for ${parent.title}."
    }

    // ---- parent ----

    fun parentLogin(pin: String) = launch {
        val fam = family ?: run { screen = Screen.ConnectDevice; return@launch }
        act(pinField) {
            val (token, f) = api.parentLogin(pin, familyId = fam.id)
            session.token = token; session.family = f; family = f
            isParent = true; markParentActivity()
            children = api.children(); screen = Screen.ParentHome
        }
    }

    fun openChangePin() { error = null; notice = null; screen = Screen.ChangePin }
    fun openNotifications() { error = null; notice = null; refreshPushAllowed(); screen = Screen.Notifications }

    fun changePin(current: String, new: String) = launch {
        act({ if (it.code == "bad_pin" && it.status == 403) "currentPin" else null }) {
            api.changePin(current, new); notice = "PIN changed."; screen = Screen.ParentHome
        }
    }

    fun openDeleteFamily() { error = null; notice = null; screen = Screen.DeleteFamily }

    fun deleteFamily(pin: String) = launch {
        act(pinField) {
            api.deleteFamily(pin)
            forgetFamily()
            notice = "Your family and all its data were deleted."
        }
    }

    fun handToKid() { endParentSession(); screen = Screen.KidLogin }

    /** Any touch, key, typing, or screen-reader / switch / voice action counts as parent activity. */
    fun markParentActivity() { lastParentActivity = System.currentTimeMillis() }

    /** "Stay signed in" on the idle warning. */
    fun stayParentSignedIn() { markParentActivity(); idleWarning = false }

    fun checkParentIdle() {
        if (!isParent || session.token == null) { idleWarning = false; return }
        val idle = System.currentTimeMillis() - lastParentActivity
        idleWarning = idle in parentIdleWarnMs until parentIdleLimitMs
        if (idle < parentIdleLimitMs) return
        endParentSession(keepDrafts = true)
        screen = Screen.KidLogin
        notice = "The parent area locked after 10 minutes without use. Enter the PIN to go back in." +
            if (reportDraft != null || taskDraft != null) " Your unsaved changes are kept." else ""
    }

    private fun endParentSession(keepDrafts: Boolean = false) {
        idleWarning = false
        if (!keepDrafts) { reportDraft = null; taskDraft = null }
        if (session.token != null) launch { api.parentLogout() }
        session.token = null
        isParent = false
        children = emptyList(); managedChild = null; editingChild = null
        parentTasks = emptyList(); editingTask = null
        reportBundle = null; bhisReport = null; daySchedule = null; editingOverride = null
    }

    fun logout() { endParentSession(); child = null; board = null; screen = Screen.KidLogin }

    fun openChildHub(c: Child) { managedChild = c; error = null; screen = Screen.ChildHub }

    fun openBoard(c: Child) = launch {
        act { child = c; kidStatus = ""; board = api.daySummary(c.id); screen = Screen.KidBoard }
        syncKidReminders()
    }

    fun addChildStart() { error = null; editingChild = null; screen = Screen.ChildEditor }
    fun editChildStart() { error = null; editingChild = managedChild; screen = Screen.ChildEditor }

    fun saveChild(existing: Child?, firstName: String, lastName: String, color: String, emoji: String) = launch {
        act {
            if (existing != null) api.updateChild(existing.id, firstName, lastName, color, emoji)
            else api.addChild(firstName, lastName, color, emoji)
            children = api.children()
            if (existing != null) {
                managedChild = children.firstOrNull { it.id == existing.id } ?: managedChild
                screen = Screen.ChildHub
                notice = "Changes to $firstName saved."
            } else {
                screen = Screen.ParentHome
                notice = "$firstName added."
            }
        }
    }

    fun removeManagedChild(forever: Boolean) = launch {
        val c = managedChild ?: return@launch
        act {
            if (forever) api.deleteChildForever(c.id) else api.removeChild(c.id)
            children = api.children(); managedChild = null; screen = Screen.ParentHome
            notice = if (forever) "${c.firstName} was deleted forever." else "${c.firstName} was removed. Their history is kept."
        }
    }

    // ---- parent: tasks ----

    fun openTasks() = launch {
        val c = managedChild ?: return@launch
        act { parentTasks = api.parentTasks(c.id); screen = Screen.Tasks }
    }

    fun addTaskStart() { error = null; editingTask = null; screen = Screen.TaskEditor; draftKeptNotice("new") }
    fun editTaskStart(t: ParentTask) { error = null; editingTask = t; screen = Screen.TaskEditor; draftKeptNotice(t.id) }

    private fun draftKeptNotice(taskKey: String) {
        if (taskDraft?.let { it.childId == managedChild?.id && it.taskKey == taskKey } == true) notice = "Your unsaved changes were kept."
    }

    /** "Discard" on the Discard changes? question: forget the drafts and go back. */
    fun discardAndGoBack() {
        when (screen) {
            Screen.TaskEditor -> taskDraft = null
            Screen.DailyReport -> reportDraft = null
            else -> {}
        }
        unsavedChanges = false
        back()
    }

    /**
     * Existing task: saves pending follow-up renames ([renames] = subId to new title) and the main
     * fields, then returns to the list. New task: adds it and stays in the editor (now editing the
     * new task) so follow-up checks can be added straight away.
     */
    fun saveTask(existing: ParentTask?, title: String, type: String, emoji: String,
                 daysOfWeek: String, timeSlot: String, timeOfDay: String,
                 renames: Map<String, String> = emptyMap(), onSaved: () -> Unit = {}) = launch {
        val c = managedChild ?: return@launch
        act {
            if (existing != null) {
                renames.forEach { (id, t) -> if (t.isNotBlank()) api.updateSubtask(id, t) }
                api.updateTask(existing.id, title, type, emoji, daysOfWeek, timeSlot, timeOfDay)
                val list = api.parentTasks(c.id)
                // No suspension from here on, so the editor can't write the draft back in between.
                taskDraft = null; parentTasks = list; editingTask = null; screen = Screen.Tasks
                notice = "$title saved."
            } else {
                val id = api.addTask(c.id, title, type, emoji, daysOfWeek, timeSlot, timeOfDay)
                parentTasks = api.parentTasks(c.id)
                taskDraft = null
                val added = parentTasks.firstOrNull { it.id == id }
                    ?: parentTasks.lastOrNull { it.title == title }
                if (added != null) {
                    editingTask = added
                    focusAddFollowUp = true
                    notice = "Task added. You can now add follow-up checks."
                } else {
                    editingTask = null; screen = Screen.Tasks
                    notice = "Task added."
                }
            }
            onSaved()
        }
    }

    fun deleteTask(t: ParentTask) = launch {
        val c = managedChild ?: return@launch
        act {
            api.removeTask(t.id); taskDraft = null
            parentTasks = api.parentTasks(c.id); editingTask = null; screen = Screen.Tasks
            notice = "${t.title} deleted."
        }
    }

    /** [onResult] gets true only when the follow-up was saved (so the field keeps the text on failure). */
    fun addSubtask(parentId: String, title: String, onResult: (Boolean) -> Unit = {}) = launch {
        val c = managedChild ?: return@launch
        if (title.isBlank()) return@launch
        var ok = false
        act {
            api.addSubtask(parentId, title, "☑️")
            ok = true
            notice = "Follow-up added: $title."
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == parentId } ?: editingTask
        }
        onResult(ok)
    }

    fun renameSubtask(subId: String, title: String, onResult: (Boolean) -> Unit = {}) = launch {
        val c = managedChild ?: return@launch
        if (title.isBlank()) return@launch
        val keep = editingTask?.id
        var ok = false
        act {
            api.updateSubtask(subId, title)
            ok = true
            notice = "Follow-up renamed to $title."
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == keep } ?: editingTask
        }
        onResult(ok)
    }

    fun removeSubtask(subId: String, title: String, onResult: (Boolean) -> Unit = {}) = launch {
        val c = managedChild ?: return@launch
        val keep = editingTask?.id
        var ok = false
        act {
            api.removeTask(subId)
            ok = true
            notice = "Removed follow-up: $title."
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == keep } ?: editingTask
        }
        onResult(ok)
    }

    /** Move a follow-up check one place up or down. [onResult] gets true once it moved. */
    fun moveSubtask(subId: String, up: Boolean, onResult: (Boolean) -> Unit = {}) = launch {
        val c = managedChild ?: return@launch
        val keep = editingTask?.id
        var ok = false
        act {
            val (pos, count) = api.moveSubtask(subId, up)
            ok = true
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == keep } ?: editingTask
            notice = "Moved to position $pos of $count."
        }
        onResult(ok)
    }

    // ---- parent: reports ----

    fun openReport() = launch {
        val c = managedChild ?: return@launch
        act {
            val b = api.getReport(c.id)
            reportBundle = b; screen = Screen.DailyReport
            if (reportDraft?.let { it.childId == c.id && it.day == b.day } == true) notice = "Your unsaved report notes were kept."
        }
    }

    fun saveReport(mood: String, rating: Int?, highlights: String, challenges: String, notes: String, goals: String) = launch {
        val c = managedChild ?: return@launch
        val day = reportBundle?.day
        act {
            api.saveReport(c.id, day, mood, rating, highlights, challenges, notes, goals)
            reportBundle = api.getReport(c.id, day)
            reportDraft = null
            notice = "Report saved."
        }
    }

    fun openBhis() { error = null; bhisReport = null; screen = Screen.Bhis }

    fun generateBhis(from: String, to: String) = launch {
        val c = managedChild ?: return@launch
        act {
            val r = api.bhis(c.id, from, to)
            bhisReport = r
            notice = "BHIS report ready for ${prettyDate(r.from)} to ${prettyDate(r.to)}."
        }
    }

    // ---- parent: per-day overrides ----

    fun openAdjustDay() = launch {
        val c = managedChild ?: return@launch
        act {
            val s = api.daySchedule(c.id, today.ifEmpty { null })
            overrideDay = s.day; daySchedule = s; screen = Screen.AdjustDay
        }
    }

    fun loadDaySchedule(day: String) = launch {
        val c = managedChild ?: return@launch
        act { val s = api.daySchedule(c.id, day); overrideDay = s.day; daySchedule = s }
    }

    fun editOverrideStart(t: DayTaskSchedule) { error = null; editingOverride = t; screen = Screen.OverrideEditor }

    fun saveOverride(taskId: String, timeSlot: String, timeOfDay: String, skipped: Boolean, reset: Boolean = false) = launch {
        val c = managedChild ?: return@launch
        val day = overrideDay
        val title = editingOverride?.title ?: "Task"
        val dayName = prettyDate(day)
        act {
            api.setOverride(taskId, day, timeSlot, timeOfDay, skipped)
            daySchedule = api.daySchedule(c.id, day); editingOverride = null; screen = Screen.AdjustDay
            notice = when {
                reset -> "$title is back to its usual schedule for $dayName."
                skipped -> "$title is skipped on $dayName." +
                    if ((editingOverride?.subtaskCount ?: 0) > 0) " Its follow-up checks are skipped too." else ""
                else -> "$title changed for $dayName."
            }
        }
    }

    fun clearOverride(taskId: String) = saveOverride(taskId, "", "", false, reset = true)

    /**
     * A kid reminder was tapped: if that kid is signed in on this device (and no parent is working
     * here), show their board, freshly loaded. Otherwise the app just stays where it was.
     */
    fun openFromNotification(childId: String?) {
        val c = child ?: return
        if (childId.isNullOrEmpty() || c.id != childId || isParent) return
        launch {
            runCatching { board = api.daySummary(c.id) }
            screen = Screen.KidBoard
        }
    }

    // ---- push notifications ----

    fun refreshPushAllowed() {
        pushAllowed = Push.allowed(ctx)
    }

    /** After a kid signs in: if notifications are already allowed, move reminders to them quietly. */
    private suspend fun syncKidReminders() {
        refreshPushAllowed()
        val c = child; val fam = family
        if (c == null || fam == null || !pushAvailable || pushAllowed != true) { kidRemindersOn = false; return }
        val token = Push.token(ctx) ?: Push.fetchToken(ctx) ?: run { kidRemindersOn = false; return }
        if (Push.childId(ctx) == c.id) { kidRemindersOn = true; return }
        if (runCatching { api.pushRegister(token, fam.id, childId = c.id) }.isSuccess) {
            Push.setChildId(ctx, c.id); kidRemindersOn = true
        }
    }

    /** "Remind me when it's time" — call after the notification permission prompt. */
    fun enableKidReminders(granted: Boolean) = launch {
        val c = child ?: return@launch
        val fam = family ?: return@launch
        refreshPushAllowed()
        if (!granted || pushAllowed != true) {
            error = "Notifications are turned off for Chore Slayer. A parent can turn them on in the phone's Settings."
            return@launch
        }
        busy = true
        try {
            val token = Push.fetchToken(ctx) ?: throw ApiError("no_token", "Reminders couldn't start on this device. Try again in a moment.", 0)
            api.pushRegister(token, fam.id, childId = c.id)
            Push.setChildId(ctx, c.id); kidRemindersOn = true
            notice = "Reminders are on. You'll get one when each chore is due."
        } catch (e: ApiError) { error = e.message }
        busy = false
    }

    /** Parent options → Notifications → Save. `granted` is the permission answer when turning on. */
    fun saveParentAlerts(on: Boolean, granted: Boolean) = launch {
        val fam = family ?: return@launch
        refreshPushAllowed()
        if (on && (!granted || pushAllowed != true)) {
            error = "Notifications are turned off for Chore Slayer. Turn them on in the phone's Settings, then try again."
            return@launch
        }
        act {
            val token = if (on) Push.fetchToken(ctx) else Push.token(ctx)
            if (on && token == null) throw ApiError("no_token", "Notifications couldn't start on this device. Try again in a moment.", 0)
            if (token != null) api.pushRegister(token, fam.id, parentAlerts = on)
            Push.setParentAlerts(ctx, on)
            notice = if (on) "Parent alerts are on for this device." else "Parent alerts are off for this device."
            screen = Screen.ParentHome
        }
    }

    /** Firebase handed out a new token: move this device's choices over to it. */
    private suspend fun reRegister(new: String, old: String?) {
        val fam = family ?: return
        if (old == null) return
        val childId = Push.childId(ctx)
        val parent = Push.parentAlerts(ctx)
        if (childId == null && !parent) return
        api.pushUnregister(old)
        runCatching {
            api.pushRegister(new, fam.id, childId = childId ?: "",
                parentAlerts = if (session.token != null) parent else null)
        }
    }
}

/** A daily report being written but not yet saved. */
data class ReportDraft(
    val childId: String, val day: String, val mood: String, val rating: Int?,
    val highlights: String, val challenges: String, val notes: String, val goals: String,
)

/** Unsaved task-editor fields. [taskKey] is the task id, or "new". */
data class TaskDraft(
    val childId: String, val taskKey: String, val title: String, val type: String, val emoji: String,
    val mask: String, val slot: String, val time: String, val newSub: String, val subEdits: Map<String, String>,
)

/** [seq] changes on every new error, so the same message twice still moves focus again. */
data class FieldError(val field: String, val message: String, val seq: Int)
