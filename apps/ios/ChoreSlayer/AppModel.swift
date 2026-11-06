import Foundation
import Combine

enum Screen {
    case connecting, role, kidLogin, kidBoard, parentLogin, parentHome, childHub, childEditor
    case tasks, taskEditor, dailyReport, bhis, adjustDay, overrideEditor
}

@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .connecting
    @Published var busy = false
    @Published var error: String?

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

    // MARK: connect / role

    func connect() async {
        await run {
            let s = try await self.api.status()
            self.statusInfo = s
            self.today = s.today
            self.isParent = s.isParent
            self.screen = .role
        }
    }

    func chooseKid() { error = nil; screen = .kidLogin }
    func chooseParent() { error = nil; screen = .parentLogin }

    func back() {
        error = nil
        switch screen {
        case .kidLogin, .parentLogin: screen = .role
        case .kidBoard: screen = isParent ? .childHub : .kidLogin
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
        await run {
            let c = try await self.api.childLogin(firstName: first, lastName: last)
            self.child = c
            self.board = try await self.api.daySummary(childId: c.id)
            self.screen = .kidBoard
        }
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
        await run {
            self.session.token = try await self.api.parentLogin(pin: pin)
            self.children = try await self.api.children()
            self.isParent = true
            self.screen = .parentHome
        }
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
        Task { await api.parentLogout() }
        session.token = nil
        isParent = false
        child = nil
        board = nil
        children = []
        managedChild = nil
        screen = .role
    }

    // MARK: helpers

    private func run(_ block: @escaping () async throws -> Void) async {
        busy = true
        error = nil
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
