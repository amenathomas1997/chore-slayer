import Foundation

// MARK: - Models (decoded from the Chore Slayer JSON API)

struct StatusInfo: Codable {
    let installed: Bool
    let apiVersion: String
    let hasPin: Bool
    let isParent: Bool
    let today: String
}

struct Child: Codable, Identifiable, Equatable, Hashable {
    let id: String
    let firstName: String
    let lastName: String
    let color: String
    let emoji: String
    let active: Bool
}

struct SubTask: Codable, Identifiable {
    let id: String
    let title: String
    let emoji: String
    let done: Bool
}

struct DayTask: Codable, Identifiable {
    let id: String
    let title: String
    let type: String
    let emoji: String
    let slot: String
    let time: String?
    let done: Bool
    let complete: Bool
    let completedAt: String?
    let subtasks: [SubTask]
}

struct Counts: Codable {
    let total: Int
    let done: Int
    let percent: Int
}

struct DaySummary: Codable {
    let childId: String
    let day: String
    let tasks: [DayTask]
    let counts: Counts
}

struct ParentSub: Codable, Identifiable {
    let id: String
    let title: String
    let emoji: String
}

struct ParentTask: Codable, Identifiable {
    let id: String
    let childId: String
    let title: String
    let type: String
    let emoji: String
    let daysOfWeek: String
    let timeSlot: String
    let timeOfDay: String?
    let subtasks: [ParentSub]
}

struct ReportData: Codable {
    let mood: String?
    let behaviorRating: Int?
    let highlights: String?
    let challenges: String?
    let notes: String?
    let goals: String?
}

struct ReportBundle {
    let day: String
    let report: ReportData?
    let summary: DaySummary
}

struct BhisStats: Codable {
    let totalCompletions: Int
    let daysWithActivity: Int
    let activeTaskCount: Int
    let avgCompletionPct: Int
    let avgBehaviorRating: Double?
    let moodCounts: [String: Int]
}

struct BhisDay: Codable, Identifiable {
    var id: String { day }
    let day: String
    let completed: Int
    let scheduled: Int
    let percent: Int
}

struct TaskFreq: Codable, Identifiable {
    var id: String { title }
    let title: String
    let type: String
    let count: Int
}

struct DatedReport: Codable, Identifiable {
    var id: String { day }
    let day: String
    let mood: String?
    let behaviorRating: Int?
    let highlights: String?
    let challenges: String?
    let notes: String?
    let goals: String?
}

struct BhisChild: Codable { let id: String; let name: String }
struct BhisRange: Codable { let from: String; let to: String; let days: Int }

struct BhisReport: Codable {
    let child: BhisChild?
    let range: BhisRange
    let stats: BhisStats
    let daily: [BhisDay]
    let taskFreq: [TaskFreq]
    let reports: [DatedReport]
}

struct DayTaskSchedule: Codable, Identifiable {
    let id: String
    let title: String
    let type: String
    let emoji: String
    let defaultSlot: String
    let defaultTime: String?
    let overrideSlot: String?
    let overrideTime: String?
    let skipped: Bool
    let effectiveSlot: String
    let effectiveTime: String?
}

struct DaySchedule: Codable {
    let day: String
    let tasks: [DayTaskSchedule]
}

// MARK: - Errors

struct ApiError: Error {
    let code: String
    let message: String
    let status: Int
}

// MARK: - Client

/// Thin async client over `<baseURL>?action=<name>`. Reads use the query string,
/// writes POST a JSON body. The parent bearer token (if any) is attached.
final class Api {
    private let session: Session
    init(_ session: Session) { self.session = session }

    func status() async throws -> StatusInfo { try await get("status") }

    func parentLogin(pin: String) async throws -> String {
        let r: TokenResp = try await send("parent_login", method: "POST", body: ["pin": pin])
        return r.token ?? ""
    }

    func parentLogout() async {
        _ = try? await raw("parent_logout", method: "POST")
    }

    func childLogin(firstName: String, lastName: String) async throws -> Child {
        let r: ChildResp = try await send("child_login", method: "POST",
                                          body: ["firstName": firstName, "lastName": lastName])
        return r.child
    }

    func children() async throws -> [Child] {
        let r: ChildrenResp = try await get("children")
        return r.children
    }

    func daySummary(childId: String, day: String? = nil) async throws -> DaySummary {
        var q = ["childId": childId]
        if let day { q["day"] = day }
        return try await get("day_summary", query: q)
    }

    /// Toggles a check-off (task or follow-up); returns the new completed state.
    @discardableResult
    func toggleCompletion(childId: String, taskId: String, day: String? = nil) async throws -> Bool {
        var b = ["childId": childId, "taskId": taskId]
        if let day { b["day"] = day }
        let r: ToggleResp = try await send("toggle_completion", method: "POST", body: b)
        return r.completed
    }

    // MARK: parent — children

    func addChild(firstName: String, lastName: String, color: String, emoji: String) async throws {
        _ = try await raw("add_child", method: "POST",
                          body: ["firstName": firstName, "lastName": lastName, "color": color, "emoji": emoji])
    }

    func updateChild(id: String, firstName: String, lastName: String, color: String, emoji: String) async throws {
        _ = try await raw("update_child", method: "POST",
                          body: ["id": id, "firstName": firstName, "lastName": lastName, "color": color, "emoji": emoji])
    }

    func removeChild(id: String) async throws {
        _ = try await raw("remove_child", method: "POST", body: ["id": id])
    }

    func deleteChildForever(id: String) async throws {
        _ = try await raw("delete_child_forever", method: "POST", body: ["id": id])
    }

    // MARK: parent — tasks & follow-up checks

    func parentTasks(childId: String) async throws -> [ParentTask] {
        let r: TasksResp = try await get("tasks", query: ["childId": childId])
        return r.tasks
    }

    func addTask(childId: String, title: String, type: String, emoji: String,
                 daysOfWeek: String, timeSlot: String, timeOfDay: String) async throws {
        _ = try await raw("add_task", method: "POST", body: [
            "childId": childId, "title": title, "type": type, "emoji": emoji,
            "daysOfWeek": daysOfWeek, "timeSlot": timeSlot, "timeOfDay": timeOfDay])
    }

    func updateTask(id: String, title: String, type: String, emoji: String,
                    daysOfWeek: String, timeSlot: String, timeOfDay: String) async throws {
        _ = try await raw("update_task", method: "POST", body: [
            "id": id, "title": title, "type": type, "emoji": emoji,
            "daysOfWeek": daysOfWeek, "timeSlot": timeSlot, "timeOfDay": timeOfDay])
    }

    func removeTask(id: String) async throws {
        _ = try await raw("remove_task", method: "POST", body: ["id": id])
    }

    func addSubtask(parentId: String, title: String, emoji: String) async throws {
        _ = try await raw("add_subtask", method: "POST",
                          body: ["parentId": parentId, "title": title, "emoji": emoji])
    }

    // MARK: parent — daily report

    func getReport(childId: String, day: String? = nil) async throws -> ReportBundle {
        var q = ["childId": childId]
        if let day { q["day"] = day }
        let r: ReportResp = try await get("report", query: q)
        return ReportBundle(day: r.summary.day, report: r.report, summary: r.summary)
    }

    func saveReport(childId: String, day: String?, mood: String, behaviorRating: Int?,
                    highlights: String, challenges: String, notes: String, goals: String) async throws {
        var b = [
            "childId": childId, "mood": mood, "highlights": highlights,
            "challenges": challenges, "notes": notes, "goals": goals,
            "behaviorRating": behaviorRating.map(String.init) ?? "",
        ]
        if let day { b["day"] = day }
        _ = try await raw("save_report", method: "POST", body: b)
    }

    // MARK: parent — BHIS report

    func bhis(childId: String, from: String, to: String) async throws -> BhisReport {
        try await get("bhis", query: ["childId": childId, "from": from, "to": to])
    }

    // MARK: parent — per-day overrides

    func daySchedule(childId: String, day: String? = nil) async throws -> DaySchedule {
        var q = ["childId": childId]
        if let day { q["day"] = day }
        return try await get("day_schedule", query: q)
    }

    func setOverride(taskId: String, day: String, timeSlot: String, timeOfDay: String, skipped: Bool) async throws {
        _ = try await raw("set_override", method: "POST", body: [
            "taskId": taskId, "day": day, "timeSlot": timeSlot,
            "timeOfDay": timeOfDay, "skipped": skipped ? "1" : "0"])
    }

    // MARK: transport

    private func get<T: Decodable>(_ action: String, query: [String: String] = [:]) async throws -> T {
        let data = try await raw(action, method: "GET", query: query)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func send<T: Decodable>(_ action: String, method: String, body: [String: String]) async throws -> T {
        let data = try await raw(action, method: method, body: body)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func raw(_ action: String, method: String,
                     query: [String: String] = [:], body: [String: String]? = nil) async throws -> Data {
        guard var comps = URLComponents(string: session.baseURL) else {
            throw ApiError(code: "bad_url", message: "Invalid server URL.", status: 0)
        }
        var items = [URLQueryItem(name: "action", value: action)]
        for (k, v) in query { items.append(URLQueryItem(name: k, value: v)) }
        comps.queryItems = items
        guard let url = comps.url else {
            throw ApiError(code: "bad_url", message: "Invalid server URL.", status: 0)
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = session.token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            let errBody = try? JSONDecoder().decode(ApiErrorBody.self, from: data)
            throw ApiError(code: errBody?.error ?? "http_\(code)",
                           message: errBody?.message ?? "Request failed (\(code)).",
                           status: code)
        }
        return data
    }
}

// MARK: - Response envelopes

private struct TokenResp: Codable { let token: String? }
private struct ChildResp: Codable { let child: Child }
private struct ChildrenResp: Codable { let children: [Child] }
private struct ToggleResp: Codable { let completed: Bool }
private struct TasksResp: Codable { let tasks: [ParentTask] }
private struct ReportResp: Codable { let report: ReportData?; let summary: DaySummary }
private struct ApiErrorBody: Codable { let error: String?; let message: String? }
