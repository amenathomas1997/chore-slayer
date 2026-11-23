import Foundation
import Combine

enum Screen {
    case connecting, connectDevice, role, kidLogin, kidBoard, kidSummary, changePin, deleteFamily, parentLogin, parentHome, childHub, childEditor
    case tasks, taskEditor, dailyReport, bhis, adjustDay, overrideEditor
}

@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .connecting
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
    @Published var reportBundle: ReportBundle?
    @Published var bhisReport: BhisReport?
    @Published var daySchedule: DaySchedule?
    @Published var overrideDay = ""
    @Published var editingOverride: DayTaskSchedule?

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

    init() {
        family = session.family
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
        endParentSession()
        session.family = nil
        family = nil
        child = nil
        board = nil
        screen = .connectDevice
    }

    func chooseKid() { error = nil; notice = nil; screen = .kidLogin }
    func chooseParent() { error = nil; notice = nil; screen = .parentLogin }

    func back() {
        error = nil
        notice = nil
        switch screen {
        case .kidLogin, .parentLogin: screen = .role
        case .kidBoard: screen = isParent ? .childHub : .kidLogin
        case .kidSummary: screen = .kidBoard
        case .changePin, .deleteFamily: screen = .parentHome
        case .childHub: screen = .parentHome
        case .childEditor: screen = managedChild != nil ? .childHub : .parentHome
        case .tasks: screen = .childHub
        case .taskEditor: screen = .tasks
        case .dailyReport, .bhis: screen = .childHub
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
    }

    /// "See my day": the kid's end-of-day summary.
    func openKidSummary() async {
        guard let c = child else { return }
        await run {
            self.board = try await self.api.daySummary(childId: c.id)
            self.screen = .kidSummary
        }
    }

    /// "Switch user" on a kid's board.
    func kidSwitchUser() {
        child = nil
        board = nil
        screen = .kidLogin
    }

    func toggle(_ taskId: String) async {
        guard let c = child else { return }
        do {
            try await api.toggleCompletion(childId: c.id, taskId: taskId)
            board = try await api.daySummary(childId: c.id)
            error = nil
        } catch let e as ApiError {
            error = e.message
        } catch let e {
            error = e.localizedDescription
        }
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

    func markParentActivity() { lastParentActivity = Date() }

    /// Call on a timer and when the app comes back to the foreground.
    func checkParentIdle() {
        guard isParent, session.token != nil else { return }
        guard Date().timeIntervalSince(lastParentActivity) >= Self.parentIdleLimit else { return }
        endParentSession()
        screen = .kidLogin
        notice = "The parent area locked after 10 minutes without use. Enter the PIN to go back in."
    }

    /// Ends the parent sign-in here and on the server; the device stays linked.
    private func endParentSession() {
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
                self.screen = .childHub
            } else {
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

    func addTaskStart() { error = nil; editingTask = nil; screen = .taskEditor }
    func editTaskStart(_ task: ParentTask) { error = nil; editingTask = task; screen = .taskEditor }

    func saveTask(existing: ParentTask?, title: String, type: String, emoji: String,
                  daysOfWeek: String, timeSlot: String, timeOfDay: String) async {
        guard let c = managedChild else { return }
        await run {
            if let existing {
                try await self.api.updateTask(id: existing.id, title: title, type: type, emoji: emoji,
                                              daysOfWeek: daysOfWeek, timeSlot: timeSlot, timeOfDay: timeOfDay)
            } else {
                try await self.api.addTask(childId: c.id, title: title, type: type, emoji: emoji,
                                           daysOfWeek: daysOfWeek, timeSlot: timeSlot, timeOfDay: timeOfDay)
            }
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = nil
            self.screen = .tasks
        }
    }

    func deleteTask(_ task: ParentTask) async {
        guard let c = managedChild else { return }
        await run {
            try await self.api.removeTask(id: task.id)
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = nil
            self.screen = .tasks
        }
    }

    func addSubtask(parentId: String, title: String) async {
        guard let c = managedChild, !title.isEmpty else { return }
        await run {
            try await self.api.addSubtask(parentId: parentId, title: title, emoji: "☑️")
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == parentId } ?? self.editingTask
        }
    }

    func renameSubtask(subId: String, title: String) async {
        guard let c = managedChild, !title.isEmpty else { return }
        let keepId = editingTask?.id
        await run {
            try await self.api.updateSubtask(id: subId, title: title)
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == keepId } ?? self.editingTask
        }
    }

    func removeSubtask(subId: String) async {
        guard let c = managedChild else { return }
        let keepId = editingTask?.id
        await run {
            try await self.api.removeTask(id: subId) // remove_task also deactivates a follow-up
            self.parentTasks = try await self.api.parentTasks(childId: c.id)
            self.editingTask = self.parentTasks.first { $0.id == keepId } ?? self.editingTask
        }
    }

    // MARK: parent — daily report

    func openReport() async {
        guard let c = managedChild else { return }
        await run {
            self.reportBundle = try await self.api.getReport(childId: c.id)
            self.screen = .dailyReport
        }
    }

    func saveReport(mood: String, rating: Int?, highlights: String,
                    challenges: String, notes: String, goals: String) async {
        guard let c = managedChild else { return }
        let day = reportBundle?.day
        await run {
            try await self.api.saveReport(childId: c.id, day: day, mood: mood, behaviorRating: rating,
                                          highlights: highlights, challenges: challenges, notes: notes, goals: goals)
            self.reportBundle = try await self.api.getReport(childId: c.id, day: day)
        }
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
            self.editingOverride = nil
            self.screen = .adjustDay
        }
    }

    func clearOverride(taskId: String) async {
        guard let c = managedChild else { return }
        let day = overrideDay
        await run {
            try await self.api.setOverride(taskId: taskId, day: day, timeSlot: "", timeOfDay: "", skipped: false)
            self.daySchedule = try await self.api.daySchedule(childId: c.id, day: day)
            self.editingOverride = nil
            self.screen = .adjustDay
        }
    }

    func generateBhis(from: String, to: String) async {
        guard let c = managedChild else { return }
        await run {
            self.bhisReport = try await self.api.bhis(childId: c.id, from: from, to: to)
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
