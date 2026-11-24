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

    var screen by mutableStateOf(Screen.Connecting); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

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

    private var lastParentActivity = System.currentTimeMillis()
    val parentIdleLimitMs = 10 * 60 * 1000L

    init {
        Push.onTokenChange = { new, old -> viewModelScope.launch { reRegister(new, old) } }
        if (pushAvailable && Push.allowed(app)) viewModelScope.launch { Push.fetchToken(app) }
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    /** Run an API action with the busy flag and error handling. */
    private suspend fun act(block: suspend () -> Unit) {
        if (isParent) markParentActivity()
        busy = true; error = null; notice = null
        try { block() } catch (e: ApiError) { error = e.message } catch (e: Exception) { error = e.message ?: "Something went wrong." }
        busy = false
    }

    // ---- connect / role ----

    fun connect() = launch {
        act {
            val s = api.status()
            statusInfo = s; today = s.today; isParent = false
            screen = if (family == null) Screen.ConnectDevice else Screen.Role
        }
    }

    fun connectDevice(code: String, pin: String) = launch {
        act {
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
        error = null
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
        act {
            val c = api.childLogin(first, last, fam.id)
            child = c; board = api.daySummary(c.id); screen = Screen.KidBoard
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
        try {
            api.toggleCompletion(c.id, taskId)
            board = api.daySummary(c.id); error = null
        } catch (e: ApiError) { error = e.message }
    }

    // ---- parent ----

    fun parentLogin(pin: String) = launch {
        val fam = family ?: run { screen = Screen.ConnectDevice; return@launch }
        act {
            val (token, f) = api.parentLogin(pin, familyId = fam.id)
            session.token = token; session.family = f; family = f
            isParent = true; markParentActivity()
            children = api.children(); screen = Screen.ParentHome
        }
    }

    fun openChangePin() { error = null; notice = null; screen = Screen.ChangePin }
    fun openNotifications() { error = null; notice = null; refreshPushAllowed(); screen = Screen.Notifications }

    fun changePin(current: String, new: String) = launch {
        act { api.changePin(current, new); notice = "PIN changed."; screen = Screen.ParentHome }
    }

    fun openDeleteFamily() { error = null; notice = null; screen = Screen.DeleteFamily }

    fun deleteFamily(pin: String) = launch {
        act {
            api.deleteFamily(pin)
            forgetFamily()
            notice = "Your family and all its data were deleted."
        }
    }

    fun handToKid() { endParentSession(); screen = Screen.KidLogin }

    fun markParentActivity() { lastParentActivity = System.currentTimeMillis() }

    fun checkParentIdle() {
        if (!isParent || session.token == null) return
        if (System.currentTimeMillis() - lastParentActivity < parentIdleLimitMs) return
        endParentSession()
        screen = Screen.KidLogin
        notice = "The parent area locked after 10 minutes without use. Enter the PIN to go back in."
    }

    private fun endParentSession() {
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
        act { child = c; board = api.daySummary(c.id); screen = Screen.KidBoard }
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
            } else screen = Screen.ParentHome
        }
    }

    fun removeManagedChild(forever: Boolean) = launch {
        val c = managedChild ?: return@launch
        act {
            if (forever) api.deleteChildForever(c.id) else api.removeChild(c.id)
            children = api.children(); managedChild = null; screen = Screen.ParentHome
        }
    }

    // ---- parent: tasks ----

    fun openTasks() = launch {
        val c = managedChild ?: return@launch
        act { parentTasks = api.parentTasks(c.id); screen = Screen.Tasks }
    }

    fun addTaskStart() { error = null; editingTask = null; screen = Screen.TaskEditor }
    fun editTaskStart(t: ParentTask) { error = null; editingTask = t; screen = Screen.TaskEditor }

    fun saveTask(existing: ParentTask?, title: String, type: String, emoji: String,
                 daysOfWeek: String, timeSlot: String, timeOfDay: String) = launch {
        val c = managedChild ?: return@launch
        act {
            if (existing != null) api.updateTask(existing.id, title, type, emoji, daysOfWeek, timeSlot, timeOfDay)
            else api.addTask(c.id, title, type, emoji, daysOfWeek, timeSlot, timeOfDay)
            parentTasks = api.parentTasks(c.id); editingTask = null; screen = Screen.Tasks
        }
    }

    fun deleteTask(t: ParentTask) = launch {
        val c = managedChild ?: return@launch
        act { api.removeTask(t.id); parentTasks = api.parentTasks(c.id); editingTask = null; screen = Screen.Tasks }
    }

    fun addSubtask(parentId: String, title: String) = launch {
        val c = managedChild ?: return@launch
        if (title.isBlank()) return@launch
        act {
            api.addSubtask(parentId, title, "☑️")
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == parentId } ?: editingTask
        }
    }

    fun renameSubtask(subId: String, title: String) = launch {
        val c = managedChild ?: return@launch
        if (title.isBlank()) return@launch
        val keep = editingTask?.id
        act {
            api.updateSubtask(subId, title)
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == keep } ?: editingTask
        }
    }

    fun removeSubtask(subId: String) = launch {
        val c = managedChild ?: return@launch
        val keep = editingTask?.id
        act {
            api.removeTask(subId)
            parentTasks = api.parentTasks(c.id)
            editingTask = parentTasks.firstOrNull { it.id == keep } ?: editingTask
        }
    }

    // ---- parent: reports ----

    fun openReport() = launch {
        val c = managedChild ?: return@launch
        act { reportBundle = api.getReport(c.id); screen = Screen.DailyReport }
    }

    fun saveReport(mood: String, rating: Int?, highlights: String, challenges: String, notes: String, goals: String) = launch {
        val c = managedChild ?: return@launch
        val day = reportBundle?.day
        act {
            api.saveReport(c.id, day, mood, rating, highlights, challenges, notes, goals)
            reportBundle = api.getReport(c.id, day)
            notice = "Report saved."
        }
    }

    fun openBhis() { error = null; bhisReport = null; screen = Screen.Bhis }

    fun generateBhis(from: String, to: String) = launch {
        val c = managedChild ?: return@launch
        act { bhisReport = api.bhis(c.id, from, to) }
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

    fun saveOverride(taskId: String, timeSlot: String, timeOfDay: String, skipped: Boolean) = launch {
        val c = managedChild ?: return@launch
        val day = overrideDay
        act {
            api.setOverride(taskId, day, timeSlot, timeOfDay, skipped)
            daySchedule = api.daySchedule(c.id, day); editingOverride = null; screen = Screen.AdjustDay
        }
    }

    fun clearOverride(taskId: String) = saveOverride(taskId, "", "", false)

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
