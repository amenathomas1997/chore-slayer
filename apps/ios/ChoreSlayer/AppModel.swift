import Foundation
import Combine
import UIKit

enum Screen {
    case connecting, connectDevice, role, kidLogin, kidBoard, kidSummary, changePin, deleteFamily, notifications, parentLogin, parentHome, childHub, childEditor
    case tasks, taskEditor, dailyReport, bhis, adjustDay, overrideEditor
}

@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .connecting {
        didSet {
            guard screen != oldValue else { return }
            screenChangedAt = Date()
            markParentActivity()
        }
    }
    /// When `screen` last changed, so status announcements can wait for the
    /// screen-change announcement instead of colliding with it.
    private(set) var screenChangedAt = Date.distantPast
    @Published var busy = false
    @Published var error: String?
    /// A non-error status message, e.g. "the parent area locked".
    @Published var notice: String?
    /// The family this device is linked to (nil ⇒ show "Connect this device").
    @Published var family: Family?

    @Published var statusInfo: StatusInfo?
    @Published var today = ""
    @Published var isParent = false

    @Published var child: Child?           // whose board is showing
    @Published var board: DaySummary?
    @Published var children: [Child] = []
    @Published var managedChild: Child?    // child a parent is managing
    @Published var editingChild: Child?    // nil on childEditor ⇒ adding new
    @Published var parentTasks: [ParentTask] = []
    @Published var editingTask: ParentTask?  // nil on taskEditor ⇒ adding new
    /// The task editor's fields. Kept here (not in the view) so the parent idle
    /// lock doesn't throw away unsaved edits.
    @Published var taskDraft = TaskDraft()
    /// The daily report's fields, kept across the idle lock for the same reason.
    @Published var reportDraft = ReportDraft()
    /// Kid board: check-offs being saved. Taps on these are ignored until done.
    @Published var pendingToggles: Set<String> = []
    /// Shown one minute before the parent idle lock.
    @Published var idleWarning = false
    @Published var reportBundle: ReportBundle?
    @Published var bhisReport: BhisReport?
    @Published var daySchedule: DaySchedule?
    @Published var overrideDay = ""
    @Published var editingOverride: DayTaskSchedule?
    /// "Discard changes?" before Back leaves an editor with unsaved edits.
    @Published var confirmDiscard = false
    /// Set by the child editor, whose fields live in the view.
    @Published var childEditorDirty = false

    private let session = Session()
    private lazy var api = Api(session)

    /// Lock the parent area after this long without a tap.
    static let parentIdleLimit: TimeInterval = {
        #if DEBUG
        // Debug builds only: shorten the lock for testing with CHORE_IDLE_SECONDS.
        if let v = ProcessInfo.processInfo.environment["CHORE_IDLE_SECONDS"], let n = Double(v) { return n }
        #endif
        return 10 * 60
    }()
    private var lastParentActivity = Date()
    private var activityObservers: [NSObjectProtocol] = []

    /// nil = not asked yet, false = turned off in Settings, true = allowed.
    @Published var pushAllowed: Bool?
    /// This device is getting reminders for the kid who is signed in.
    @Published var kidRemindersOn = false

    init() {
        family = session.family
        Push.shared.onTokenChange = { [weak self] new, old in
            Task { await self?.reRegister(newToken: new, oldToken: old) }
        }
        Push.shared.onReminderTap = { [weak self] childId in
            Task { await self?.openFromReminder(childId: childId) }
        }
        // Count every kind of input for the parent idle lock, not only touches:
        // typing (on-screen or hardware keyboard, dictation, Voice Control) and
        // VoiceOver moving focus. Switch Control focus is not counted, because
        // auto-scanning moves focus with nobody there; its activations still
        // count through the actions they trigger.
        let nc = NotificationCenter.default
        let names: [Notification.Name] = [
            UITextField.textDidChangeNotification,
            UITextView.textDidChangeNotification,
            UIAccessibility.elementFocusedNotification,
        ]
        for name in names {
            activityObservers.append(nc.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                if note.name == UIAccessibility.elementFocusedNotification {
                    let tech = note.userInfo?[UIAccessibility.assistiveTechnologyUserInfoKey] as? UIAccessibility.AssistiveTechnologyIdentifier
                    guard tech == .notificationVoiceOver else { return }
                }
                Task { @MainActor in self?.markParentActivity() }
            })
        }
    }

    // MARK: connect / role

    func connect() async {
        await run {
            let s = try await self.api.status()
            self.statusInfo = s
            self.today = s.today
            self.isParent = false   // a fresh launch never starts signed in as a parent
            self.screen = self.family == nil ? .connectDevice : .role
        }
    }

    // MARK: family / device link

    /// Link this device to a family with its family code and parent PIN.
    func connectDevice(code: String, pin: String) async {
        await run {
            let r = try await self.api.parentLogin(pin: pin, familyCode: code)
            self.session.family = r.family
            self.family = r.family
            self.session.token = r.token
            self.isParent = true
            self.markParentActivity()
            self.children = try await self.api.children()
            self.screen = .parentHome
        }
    }

    /// Create a brand-new family. The new family code is shown afterwards
    /// (`newFamilyCode`), because it is needed to add more devices.
    func createFamily(name: String, pin: String) async {
        await run {
            let r = try await self.api.signup(pin: pin, name: name)
            self.session.family = r.family
            self.family = r.family
            self.session.token = r.token
            self.isParent = true
            self.markParentActivity()
            self.children = try await self.api.children()
            self.newFamilyCode = r.family.code
            self.screen = .parentHome
        }
    }

    /// Set right after creating a family so the code is shown once.
    @Published var newFamilyCode: String?

    /// Unlink this device from its family (back to "Connect this device").
    func forgetFamily() {
        if let token = Push.shared.token {
            let api = self.api
            Task { await api.pushUnregister(token: token) }
        }
        Push.shared.clearChoices()
        kidRemindersOn = false
        endParentSession()
        session.family = nil
        family = nil
        child = nil
        board = nil
        screen = .connectDevice
    }

    func chooseKid() { error = nil; notice = nil; screen = .kidLogin }
    func chooseParent() { error = nil; notice = nil; screen = .parentLogin }

    /// Back button and the VoiceOver escape gesture: asks first when leaving
    /// an editor would lose unsaved changes.
    func requestBack() {
        if hasUnsavedChanges { confirmDiscard = true } else { back() }
    }

    /// The current editor has edits that haven't been saved.
    var hasUnsavedChanges: Bool {
        switch screen {
        case .taskEditor:
            let base = editingTask.map { TaskDraft($0) } ?? TaskDraft(childId: taskDraft.childId)
            var d = taskDraft
            // A follow-up rename typed back to its saved title isn't a change.
            d.subTitles = d.subTitles.filter { id, t in
                let saved = editingTask?.subtasks.first { $0.id == id }?.title
                return t.trimmingCharacters(in: .whitespaces) != saved
            }
            d.newSub = d.newSub.trimmingCharacters(in: .whitespaces)
            return d != base
        case .dailyReport:
            guard let b = reportBundle, let c = reportDraft.childId else { return false }
            return reportDraft != ReportDraft(childId: c, bundle: b)
        case .childEditor:
            return childEditorDirty
        default:
            return false
        }
    }

    func back() {
        confirmDiscard = false
        childEditorDirty = false
        error = nil
        notice = nil
        switch screen {
        case .kidLogin, .parentLogin: screen = .role
        case .kidBoard: screen = isParent ? .childHub : .kidLogin
        case .kidSummary: screen = .kidBoard
        case .changePin, .deleteFamily, .notifications: screen = .parentHome
        case .childHub: screen = .parentHome
        case .childEditor: screen = managedChild != nil ? .childHub : .parentHome
        case .tasks: screen = .childHub
        case .taskEditor: taskDraft = TaskDraft(); screen = .tasks
        case .dailyReport: reportDraft = ReportDraft(); screen = .childHub
        case .bhis: screen = .childHub
        case .adjustDay: screen = .childHub
        case .overrideEditor: screen = .adjustDay
        default: break
        }
    }

    // MARK: kid

    func kidLogin(first: String, last: String) async {
        guard let fam = family else { screen = .connectDevice; return }
        // A kid signing in always ends any parent sign-in on this device first.
        if session.token != nil { endParentSession() }
        await run {
            let c = try await self.api.childLogin(firstName: first, lastName: last, familyId: fam.id)
            self.child = c
            self.board = try await self.api.daySummary(childId: c.id)
            self.screen = .kidBoard
        }
        await syncKidReminders()
    }

    /// "See my day": the kid's end-of-day summary.
    func openKidSummary() async {
        guard let c = child else { return }
        await run {
            self.board = try await self.api.daySummary(childId: c.id)
            self.screen = .kidSummary
        }
    }

    /// A kid reminder notification was tapped: open that kid's board if they
    /// are the kid signed in on this device.
    func openFromReminder(childId: String) async {
        guard !isParent, let c = child, c.id == childId else { return }
        if let b = try? await api.daySummary(childId: c.id) { board = b }
        error = nil
        notice = nil
        screen = .kidBoard
    }

    /// "Switch user" on a kid's board.
    func kidSwitchUser() {
        if Push.shared.childId != nil, let token = Push.shared.token, let fam = family {
            Push.shared.childId = nil
            let api = self.api
            Task { try? await api.pushRegister(token: token, familyId: fam.id, childId: "") }
        }
        kidRemindersOn = false
        child = nil
        board = nil
        screen = .kidLogin
    }

    /// Check or uncheck a task or follow-up on the kid board, then say what changed.
    func toggle(_ taskId: String) async {
        guard let c = child, !pendingToggles.contains(taskId) else { return }
        pendingToggles.insert(taskId)
        defer { pendingToggles.remove(taskId) }
        error = nil   // so a repeated error is announced again
        let parentId = board?.tasks.first { t in t.subtasks.contains { $0.id == taskId } }?.id
        do {
            let r = try await api.toggleCompletion(childId: c.id, taskId: taskId)
            board = try await api.daySummary(childId: c.id)
            error = nil
            if let msg = toggleMessage(taskId: taskId, parentId: parentId, result: r) {
                Announcer.say(msg, after: 0.6)
            }
        } catch let e as ApiError {
            error = e.message
        } catch let e {
            error = e.localizedDescription
        }
    }

    private func toggleMessage(taskId: String, parentId: String?, result r: ToggleResult) -> String? {
        if let parentId {
            // A follow-up check.
            guard r.completed, let t = board?.tasks.first(where: { $0.id == parentId }) else { return nil }
            if t.complete { return "\(t.title) all done!" }
            let left = t.subtasks.filter { !$0.done }.count
            return left == 1 ? "1 step left." : "\(left) steps left."
        }
        guard let t = board?.tasks.first(where: { $0.id == taskId }) else { return nil }
        if !r.completed {
            return r.clearedFollowUps > 0 ? "\(t.title) unchecked. Its follow-up checks were cleared." : nil
        }
        let left = t.subtasks.filter { !$0.done }
        if t.subtasks.isEmpty { return "\(t.title) done" }
        if left.isEmpty { return "\(t.title) all done!" }
        let n = left.count
        return "\(t.title) checked. \(n) follow-up check\(n == 1 ? "" : "s") below: \(left.map(\.title).joined(separator: ", "))."
    }

    // MARK: parent

    func parentLogin(pin: String) async {
        guard let fam = family else { screen = .connectDevice; return }
        await run {
            let r = try await self.api.parentLogin(pin: pin, familyId: fam.id)
            self.session.token = r.token
            self.session.family = r.family
            self.family = r.family
            self.isParent = true
            self.markParentActivity()
            self.children = try await self.api.children()
            self.screen = .parentHome
        }
    }

    func openChangePin() { error = nil; notice = nil; screen = .changePin }
    func openNotifications() { error = nil; notice = nil; screen = .notifications }

    // MARK: push notifications

    func refreshPushAllowed() async {
        switch await Push.shared.authorizationStatus() {
        case .notDetermined: pushAllowed = nil
        case .denied: pushAllowed = false
        default: pushAllowed = true
        }
    }

    /// After a kid signs in: if notifications are already allowed here, move
    /// this device's reminders to them without asking again.
    func syncKidReminders() async {
        await refreshPushAllowed()
        guard let c = child, let fam = family else { kidRemindersOn = false; return }
        guard pushAllowed == true, let token = Push.shared.token else { kidRemindersOn = false; return }
        if Push.shared.childId == c.id { kidRemindersOn = true; return }
        if (try? await api.pushRegister(token: token, familyId: fam.id, childId: c.id)) != nil {
            Push.shared.childId = c.id
            kidRemindersOn = true
        }
    }

    /// "Remind me when it's time" on the kid board.
    func enableKidReminders() async {
        guard let c = child, let fam = family else { return }
        busy = true
        defer { busy = false }
        guard let token = await Push.shared.requestToken() else {
            await refreshPushAllowed()
            error = pushAllowed == false
                ? "Notifications are turned off for Chore Slayer. A parent can turn them on in the Settings app."
                : "Reminders couldn't start on this device. Try again in a moment."
            return
        }
        pushAllowed = true
        do {
            try await api.pushRegister(token: token, familyId: fam.id, childId: c.id)
            Push.shared.childId = c.id
            kidRemindersOn = true
            notice = "Reminders are on. You'll get one when each chore is due."
        } catch let e as ApiError { error = e.message } catch { self.error = error.localizedDescription }
    }

    /// Parent options → Notifications → Save.
    func saveParentAlerts(_ on: Bool) async {
        guard let fam = family else { return }
        busy = true
        defer { busy = false }
        var token = Push.shared.token
        if on {
            token = await Push.shared.requestToken()
            if token == nil {
                await refreshPushAllowed()
                error = "Notifications are turned off for Chore Slayer. Turn them on in the Settings app, then try again."
                return
            }
        }
        do {
            if let token { try await api.pushRegister(token: token, familyId: fam.id, parentAlerts: on) }
            Push.shared.parentAlerts = on
            notice = on ? "Parent alerts are on for this device." : "Parent alerts are off for this device."
            screen = .parentHome
        } catch let e as ApiError { error = e.message } catch { self.error = error.localizedDescription }
    }

    /// iOS handed out a new token: move this device's choices over to it.
    private func reRegister(newToken: String, oldToken: String?) async {
        guard let fam = family, oldToken != nil else { return }
        let p = Push.shared
        guard p.childId != nil || p.parentAlerts else { return }
        if let oldToken { await api.pushUnregister(token: oldToken) }
        // parentAlerts can only be switched on with a parent signed in; the
        // server keeps it off otherwise, and the parent can turn it on again.
        _ = try? await api.pushRegister(token: newToken, familyId: fam.id, childId: p.childId ?? "",
                                        parentAlerts: session.token != nil ? p.parentAlerts : nil)
    }

    func changePin(current: String, new: String) async {
        await run {
            try await self.api.changePin(current: current, new: new)
            self.notice = "PIN changed."
            self.screen = .parentHome
        }
    }

    func openDeleteFamily() { error = nil; notice = nil; screen = .deleteFamily }

    func deleteFamily(pin: String) async {
        await run {
            try await self.api.deleteFamily(pin: pin)
            self.forgetFamily()
            self.notice = "Your family and all its data were deleted."
        }
    }

    /// "Kid sign-in": sign the parent out and hand the device to a kid.
    func handToKid() {
        endParentSession()
        screen = .kidLogin
    }

    // MARK: parent idle lock

    /// Any parent interaction. Ignored while the "Still there?" warning is up, so
    /// VoiceOver landing on the warning itself doesn't cancel the lock.
    func markParentActivity() {
        guard !idleWarning else { return }
        lastParentActivity = Date()
    }

    /// "Stay signed in" on the idle warning.
    func stayParentSignedIn() {
        idleWarning = false
        lastParentActivity = Date()
        Announcer.say("You're still signed in.", after: 0.4)
    }

    /// Call on a timer and when the app comes back to the foreground.
    func checkParentIdle() {
        guard isParent, session.token != nil else {
            if idleWarning { idleWarning = false }
            return
        }
        let idle = Date().timeIntervalSince(lastParentActivity)
        if idle >= Self.parentIdleLimit {
            let hadDrafts = taskDraft.childId != nil || reportDraft.childId != nil
            idleWarning = false
            endParentSession(keepDrafts: true)
            screen = .kidLogin
            notice = "The parent area locked after 10 minutes without use. Enter the PIN to go back in."
                + (hadDrafts ? " Your unsaved edits are kept." : "")
        } else if idle >= Self.parentIdleLimit - min(60, Self.parentIdleLimit / 10), !idleWarning {
            idleWarning = true
        }
    }

    /// Ends the parent sign-in here and on the server; the device stays linked.
    private func endParentSession(keepDrafts: Bool = false) {
        if !keepDrafts { taskDraft = TaskDraft(); reportDraft = ReportDraft() }
        idleWarning = false
        if session.token != nil {
            let api = self.api
            Task { await api.parentLogout() }
        }
        session.token = nil
        isParent = false
        children = []
        managedChild = nil
        editingChild = nil
        parentTasks = []
        editingTask = nil
        reportBundle = nil
        bhisReport = nil
        daySchedule = nil
        editingOverride = nil
    }

    func openChildHub(_ c: Child) { managedChild = c; error = nil; screen = .childHub }

    func openBoard(_ c: Child) async {
        await run {
            self.child = c
            self.board = try await self.api.daySummary(childId: c.id)
            self.screen = .kidBoard
        }
        await syncKidReminders()
    }

    func addChildStart() { error = nil; editingChild = nil; screen = .childEditor }
    func editChildStart() { error = nil; editingChild = managedChild; screen = .childEditor }

    func saveChild(existing: Child?, firstName: String, lastName: String, color: String, emoji: String) async {
        await run {
            if let existing {
                try await self.api.updateChild(id: existing.id, firstName: firstName, lastName: lastName, color: color, emoji: emoji)
            } else {
                try await self.api.addChild(firstName: firstName, lastName: lastName, color: color, emoji: emoji)
            }
            self.children = try await self.api.children()
            if let existing {
                self.managedChild = self.children.first { $0.id == existing.id } ?? self.managedChild
                self.notice = "Changes to \(firstName) saved."
                self.screen = .childHub
            } else {
                self.notice = "\(firstName) added."
                self.screen = .parentHome
            }
        }
    }

    func removeManagedChild(forever: Bool) async {
        guard let child = managedChild else { return }
        await run {
            if forever { try await self.api.deleteChildForever(id: child.id) }
            else { try await self.api.removeChild(id: child.id) }
            self.children = try await self.api.children()
            self.managedChild = nil
            self.notice = forever
                ? "\(child.firstName) and all their history were deleted."
                : "\(child.firstName) removed. Their history is kept."
            self.screen = .parentHome
        }
    }

    // MARK: parent — tasks

    func openTasks() async {
        guard let c = managedChild else { return }
        await run {
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.screen = .tasks
        }
    }

    func addTaskStart() {
        error = nil
        editingTask = nil
        // Keep a draft left behind by the idle lock; otherwise start fresh.
        if !(taskDraft.taskId == nil && taskDraft.childId == managedChild?.id && taskDraft.childId != nil) {
            taskDraft = TaskDraft(childId: managedChild?.id)
        }
        screen = .taskEditor
    }

    func editTaskStart(_ task: ParentTask) {
        error = nil
        editingTask = task
        if !(taskDraft.taskId == task.id && taskDraft.childId == task.childId) {
            taskDraft = TaskDraft(task)
        }
        screen = .taskEditor
    }

    /// "Add" / "Save changes" in the task editor. Saving also saves any
    /// follow-up renames still waiting in their fields. A new task stays open
    /// in the editor so follow-up checks can be added straight away.
    func saveTask() async {
        guard let c = managedChild else { return }
        let d = taskDraft
        let title = d.title.trimmingCharacters(in: .whitespaces)
        let cleanEmoji = d.emoji.trimmingCharacters(in: .whitespaces)
        let emoji = cleanEmoji.isEmpty ? (d.type == "activity" ? "⭐" : "🧹") : cleanEmoji
        let time = d.time.trimmingCharacters(in: .whitespaces)
        await run {
            if let id = d.taskId {
                try await self.api.updateTask(id: id, title: title, type: d.type, emoji: emoji,
                                              daysOfWeek: d.mask, timeSlot: d.slot, timeOfDay: time)
                let subs = self.editingTask?.subtasks ?? []
                for sub in subs {
                    let t = (d.subTitles[sub.id] ?? sub.title).trimmingCharacters(in: .whitespaces)
                    if !t.isEmpty && t != sub.title {
                        try await self.api.updateSubtask(id: sub.id, title: t)
                    }
                }
                self.parentTasks = try await self.api.parentTasks(childId: c.id)
                self.editingTask = nil
                self.taskDraft = TaskDraft()
                self.notice = "\(title) saved."
                self.screen = .tasks
            } else {
                let id = try await self.api.addTask(childId: c.id, title: title, type: d.type, emoji: emoji,
                                                    daysOfWeek: d.mask, timeSlot: d.slot, timeOfDay: time)
                self.parentTasks = try await self.api.parentTasks(childId: c.id)
                if let id, let t = self.parentTasks.first(where: { $0.id == id }) {
                    self.editingTask = t
                    self.taskDraft = TaskDraft(t)
                    self.notice = "Task added. You can now add follow-up checks."
                } else {
                    self.editingTask = nil
                    self.taskDraft = TaskDraft()
                    self.notice = "Task added."
                    self.screen = .tasks
                }
            }
        }
    }

    func deleteTask(_ task: ParentTask) async {
        guard let c = managedChild else { return }
        await run {
            try await self.api.removeTask(id: task.id)
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = nil
            self.taskDraft = TaskDraft()
            self.notice = "\(task.title) deleted."
            self.screen = .tasks
        }
    }

    /// Returns true when the follow-up check was added (so the field can be cleared).
    @discardableResult
    func addSubtask(parentId: String, title: String) async -> Bool {
        guard let c = managedChild, !title.isEmpty else { return false }
        var ok = false
        await run {
            try await self.api.addSubtask(parentId: parentId, title: title, emoji: "☑️")
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == parentId } ?? self.editingTask
            self.taskDraft.newSub = ""
            self.notice = "Follow-up check \(title) added."
            ok = true
        }
        return ok
    }

    /// Moves a follow-up check one place. Returns what to announce ("Moved to
    /// position 2 of 3."), or nil if it failed. The caller announces it after
    /// moving VoiceOver focus, so the focus change doesn't cut it off.
    func moveSubtask(subId: String, up: Bool) async -> String? {
        guard let c = managedChild else { return nil }
        let keepId = editingTask?.id
        var msg: String?
        await run {
            let r = try await self.api.moveSubtask(id: subId, up: up)
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == keepId } ?? self.editingTask
            msg = "Moved to position \(r.position) of \(r.count)."
        }
        return msg
    }

    func renameSubtask(subId: String, title: String) async {
        guard let c = managedChild, !title.isEmpty else { return }
        let keepId = editingTask?.id
        await run {
            try await self.api.updateSubtask(id: subId, title: title)
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == keepId } ?? self.editingTask
            self.taskDraft.subTitles[subId] = nil
            self.notice = "Follow-up check renamed to \(title)."
        }
    }

    /// Returns true when the follow-up check was removed.
    @discardableResult
    func removeSubtask(subId: String) async -> Bool {
        guard let c = managedChild else { return false }
        let keepId = editingTask?.id
        let name = editingTask?.subtasks.first { $0.id == subId }?.title ?? "check"
        var ok = false
        await run {
            try await self.api.removeTask(id: subId) // remove_task also deactivates a follow-up
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == keepId } ?? self.editingTask
            self.taskDraft.subTitles[subId] = nil
            self.notice = "Follow-up check \(name) removed."
            ok = true
        }
        return ok
    }

    // MARK: parent — daily report

    func openReport() async {
        guard let c = managedChild else { return }
        await run {
            let b = try await self.api.getReport(childId: c.id)
            self.reportBundle = b
            // Keep a draft left behind by the idle lock for this child and day.
            if !(self.reportDraft.childId == c.id && self.reportDraft.day == b.day) {
                self.reportDraft = ReportDraft(childId: c.id, bundle: b)
            }
            self.screen = .dailyReport
        }
    }

    /// Returns true when the report was saved.
    @discardableResult
    func saveReport() async -> Bool {
        guard let c = managedChild else { return false }
        let day = reportBundle?.day
        let d = reportDraft
        func clean(_ v: String) -> String { v.trimmingCharacters(in: .whitespaces) }
        await run {
            try await self.api.saveReport(childId: c.id, day: day, mood: clean(d.mood), behaviorRating: d.rating,
                                          highlights: clean(d.highlights), challenges: clean(d.challenges),
                                          notes: clean(d.notes), goals: clean(d.goals))
            self.reportBundle = try await self.api.getReport(childId: c.id, day: day)
            self.notice = "Report saved."
        }
        return error == nil
    }

    // MARK: parent — BHIS report

    func openBhis() { error = nil; bhisReport = nil; screen = .bhis }

    // MARK: parent — per-day overrides

    func openAdjustDay() async {
        guard let c = managedChild else { return }
        await run {
            let s = try await self.api.daySchedule(childId: c.id, day: self.today.isEmpty ? nil : self.today)
            self.overrideDay = s.day
            self.daySchedule = s
            self.screen = .adjustDay
        }
    }

    func loadDaySchedule(day: String) async {
        guard let c = managedChild else { return }
        await run {
            let s = try await self.api.daySchedule(childId: c.id, day: day)
            self.overrideDay = s.day
            self.daySchedule = s
        }
    }

    func editOverrideStart(_ task: DayTaskSchedule) { error = nil; editingOverride = task; screen = .overrideEditor }

    func saveOverride(taskId: String, timeSlot: String, timeOfDay: String, skipped: Bool) async {
        guard let c = managedChild else { return }
        let day = overrideDay
        await run {
            try await self.api.setOverride(taskId: taskId, day: day, timeSlot: timeSlot, timeOfDay: timeOfDay, skipped: skipped)
            self.daySchedule = try await self.api.daySchedule(childId: c.id, day: day)
            let name = self.editingOverride?.title ?? "Task"
            let follow = self.editingOverride?.subtaskCount ?? 0
            self.editingOverride = nil
            let skippedSubs = follow == 0 ? "" : (follow == 1 ? " Its follow-up check is skipped too." : " Its \(follow) follow-up checks are skipped too.")
            self.notice = skipped ? "\(name) skipped on \(displayDay(day)).\(skippedSubs)" : "\(name) changed for \(displayDay(day))."
            self.screen = .adjustDay
        }
    }

    func clearOverride(taskId: String) async {
        guard let c = managedChild else { return }
        let day = overrideDay
        await run {
            try await self.api.setOverride(taskId: taskId, day: day, timeSlot: "", timeOfDay: "", skipped: false)
            self.daySchedule = try await self.api.daySchedule(childId: c.id, day: day)
            let name = self.editingOverride?.title ?? "Task"
            self.editingOverride = nil
            self.notice = "\(name) is back to its recurring schedule on \(displayDay(day))."
            self.screen = .adjustDay
        }
    }

    func generateBhis(from: String, to: String) async {
        guard let c = managedChild else { return }
        await run {
            let r = try await self.api.bhis(childId: c.id, from: from, to: to)
            self.bhisReport = r
            self.notice = "BHIS report ready: \(displayDay(r.range.from)) to \(displayDay(r.range.to))."
        }
    }

    func logout() {
        endParentSession()
        child = nil
        board = nil
        screen = .kidLogin
    }

    // MARK: helpers

    private func run(_ block: @escaping () async throws -> Void) async {
        if isParent { markParentActivity() }
        busy = true
        error = nil
        notice = nil
        do {
            try await block()
        } catch let e as ApiError {
            error = e.message
        } catch let e {
            error = e.localizedDescription
        }
        busy = false
    }
}

// MARK: - Editor drafts

struct TaskDraft: Equatable {
    var taskId: String?          // nil ⇒ a new task
    var childId: String?         // nil ⇒ no draft
    var title = ""
    var type = "chore"
    var emoji = "🧹"
    var mask = "1111111"
    var slot = "anytime"
    var time = ""
    var newSub = ""
    /// Follow-up renames typed but not saved yet, by follow-up id.
    var subTitles: [String: String] = [:]

    init(childId: String? = nil) { self.childId = childId }

    init(_ t: ParentTask) {
        taskId = t.id; childId = t.childId
        title = t.title; type = t.type; emoji = t.emoji
        mask = t.daysOfWeek; slot = t.timeSlot; time = t.timeOfDay ?? ""
    }
}

struct ReportDraft: Equatable {
    var childId: String?         // nil ⇒ no draft
    var day = ""
    var mood = ""
    var rating: Int?
    var highlights = ""
    var challenges = ""
    var notes = ""
    var goals = ""

    init() {}

    init(childId: String, bundle b: ReportBundle) {
        self.childId = childId
        day = b.day
        mood = b.report?.mood ?? ""
        rating = b.report?.behaviorRating
        highlights = b.report?.highlights ?? ""
        challenges = b.report?.challenges ?? ""
        notes = b.report?.notes ?? ""
        goals = b.report?.goals ?? ""
    }
}
