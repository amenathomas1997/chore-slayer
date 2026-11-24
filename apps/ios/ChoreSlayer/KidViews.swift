import SwiftUI

struct ConnectingView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Chore Slayer").font(.largeTitle.bold()).screenHeading()
            if model.error != nil && !model.busy {
                Text("Couldn't reach Chore Slayer. Check your internet connection, then try again.")
                Button("Try again") { Task { await model.connect() } }
                    .primaryButton()
                    .frame(maxWidth: .infinity, minHeight: 48)
            } else {
                Text("Connecting…").foregroundStyle(Color.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct RoleView: View {
    @ObservedObject var model: AppModel
    @State private var confirmForget = false
    var body: some View {
        VStack(spacing: 12) {
            Text("Who's using the app?")
                .font(.title2.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
                .screenHeading()
            if let fam = model.family {
                Text("Family: \(fam.name ?? fam.code)")
                    .foregroundStyle(Color.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            BigButton(symbol: "person.fill", title: "I'm a Kid", color: .brandFill) { model.chooseKid() }
            BigButton(symbol: "person.2.fill", title: "Parent / Admin", color: .brandPurple) { model.chooseParent() }
            Button("Use a different family") { confirmForget = true }
                .font(.footnote)
                .padding(.top, 8)
                .confirmationDialog("Disconnect this device from its family?", isPresented: $confirmForget, titleVisibility: .visible) {
                    Button("Disconnect", role: .destructive) { model.forgetFamily() }
                } message: {
                    Text("You'll need the family code and parent PIN to connect again.")
                }
        }
    }
}

struct BigButton: View {
    let symbol: String  // SF Symbol: monochrome, inherits white, scales with Dynamic Type
    let title: String
    let color: Color
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: symbol).accessibilityHidden(true)
                Text(title).font(.title3.weight(.semibold))
            }
            .frame(maxWidth: .infinity, minHeight: 60)
        }
        .primaryButton(color)
        .accessibilityLabel(title)
    }
}

struct KidLoginView: View {
    @ObservedObject var model: AppModel
    @State private var first = ""
    @State private var last = ""
    private var canSubmit: Bool {
        !model.busy
            && !first.trimmingCharacters(in: .whitespaces).isEmpty
            && !last.trimmingCharacters(in: .whitespaces).isEmpty
    }
    var body: some View {
        VStack(spacing: 12) {
            Text("Type your name")
                .font(.title2.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
                .screenHeading()
            LabeledField(title: "First name") {
                TextField("First name", text: $first, prompt: Text(""))
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.givenName)
            }
            LabeledField(title: "Last name") {
                TextField("Last name", text: $last, prompt: Text(""))
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.familyName)
            }
            Button("See my chores") {
                Task {
                    await model.kidLogin(
                        first: first.trimmingCharacters(in: .whitespaces),
                        last: last.trimmingCharacters(in: .whitespaces))
                }
            }
            .primaryButton()
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(!canSubmit)
        }
    }
}

private struct Slot: Identifiable {
    let id: String
    let label: String
}

struct BoardView: View {
    @ObservedObject var model: AppModel
    /// Asked before unchecking a task whose follow-up checks are partly done.
    @State private var confirmUncheck: DayTask?
    private let slots: [Slot] = [
        Slot(id: "morning", label: "🌅 Morning"),
        Slot(id: "midday", label: "☀️ Midday"),
        Slot(id: "afternoon", label: "🌤️ Afternoon"),
        Slot(id: "evening", label: "🌙 Evening"),
        Slot(id: "anytime", label: "🕓 Anytime"),
    ]
    var body: some View {
        if let board = model.board, let child = model.child {
            VStack(alignment: .leading, spacing: 12) {
                Text("\(child.emoji) \(child.firstName)'s day")
                    .font(.title2.bold())
                    .accessibilityLabel("\(child.firstName)'s day")
                    .screenHeading()
                Text(displayDay(board.day)).font(.footnote).foregroundStyle(Color.muted)
                Text(countsLine(board.counts))
                    .font(.headline)
                    .accessibilityLabel(countsSpoken(board.counts))
                ProgressView(value: Double(min(max(board.counts.percent, 0), 100)), total: 100)
                    .accessibilityHidden(true)
                if board.tasks.isEmpty {
                    Text("🎉 Nothing scheduled for today!")
                        .accessibilityLabel("Nothing scheduled for today!")
                }
                ForEach(slots) { slot in
                    SlotSection(
                        label: slot.label,
                        items: board.tasks.filter { $0.slot == slot.id },
                        onToggle: { task in
                            if task.done && task.subtasks.contains(where: \.done) {
                                confirmUncheck = task
                            } else {
                                Task { await model.toggle(task.id) }
                            }
                        },
                        onToggleSub: { id in Task { await model.toggle(id) } })
                }
                Button("🎉 See my day") { Task { await model.openKidSummary() } }
                    .primaryButton(.goodGreenFill)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .padding(.top, 8)
                    .accessibilityLabel("See my day")
                KidReminderCard(model: model)
            }
            .confirmationDialog(
                "Uncheck \(confirmUncheck?.title ?? "this task")?",
                isPresented: Binding(get: { confirmUncheck != nil }, set: { if !$0 { confirmUncheck = nil } }),
                titleVisibility: .visible,
                presenting: confirmUncheck
            ) { task in
                Button("Uncheck it", role: .destructive) { Task { await model.toggle(task.id) } }
                Button("Keep it checked", role: .cancel) {}
            } message: { _ in
                Text("Its follow-up checks will be cleared too.")
            }
        } else {
            Text("Loading…")
        }
    }
}

/// "2 of 3 tasks finished · 67% of steps"
func countsLine(_ c: Counts) -> String {
    "\(c.done) of \(c.total) tasks finished · \(c.percent)% of steps"
}

func countsSpoken(_ c: Counts) -> String {
    "\(c.done) of \(c.total) tasks finished, \(c.percent) percent of steps done"
}

private struct SlotSection: View {
    let label: String
    let items: [DayTask]
    let onToggle: (DayTask) -> Void
    let onToggleSub: (String) -> Void
    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(label)
                    .font(.subheadline.weight(.semibold))
                    .accessibilityLabel(plainSpoken(label))
                    .accessibilityAddTraits(.isHeader)
                ForEach(items) { task in
                    TaskRow(task: task, onToggle: { onToggle(task) }, onToggleSub: onToggleSub)
                }
            }
            .padding(.top, 8)
        }
    }
}

struct TaskRow: View {
    let task: DayTask
    let onToggle: () -> Void
    let onToggleSub: (String) -> Void

    private var stepCount: Int { task.subtasks.count }
    private var stepsLeft: Int { task.subtasks.filter { !$0.done }.count }
    private var partial: Bool { task.done && !task.complete }

    private var bg: Color {
        if task.complete { return .taskDone }
        if task.done { return .taskPartial }
        return Color(.secondarySystemBackground)
    }
    /// Shape, not only colour: empty, minus (part done) or check (all done).
    private var symbol: String {
        task.complete ? "checkmark.square.fill" : (task.done ? "minus.square.fill" : "square")
    }
    private func steps(_ n: Int) -> String { n == 1 ? "1 step" : "\(n) steps" }
    private func checks(_ n: Int) -> String { n == 1 ? "1 follow-up check" : "\(n) follow-up checks" }

    /// Visible status line under the title (nil when there's nothing to add).
    private var statusText: String? {
        if stepCount == 0 { return task.done ? "Done!" : nil }
        if task.complete { return "All done!" }
        if task.done { return "\(stepsLeft) of \(stepCount) steps left" }
        return "+\(steps(stepCount)) after"
    }
    private var stateWord: String {
        if stepCount == 0 { return task.done ? "done" : "not done" }
        if task.complete { return "all done" }
        if task.done { return "checked, \(stepsLeft) of \(checks(stepCount)) left" }
        return "not done, then \(checks(stepCount))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggle) {
                HStack(alignment: .top) {
                    Image(systemName: symbol).accessibilityHidden(true)
                    Text(task.emoji).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(task.title).font(.body.weight(.medium))
                        if let t = task.time {
                            Text("at \(displayTime(t))").font(.footnote).foregroundStyle(Color.muted)
                        }
                        if let st = statusText {
                            Text(st).font(.footnote.weight(.semibold))
                                .foregroundStyle(task.complete ? Color.goodGreen : Color.muted)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(12)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(task.title)\(task.time.map { ", at \(displayTime($0))" } ?? ""), \(task.type)")
            .accessibilityValue(stateWord)
            .accessibilityAddTraits(.isButton)

            if task.done && !task.subtasks.isEmpty {
                ForEach(Array(task.subtasks.enumerated()), id: \.element.id) { i, sub in
                    Button { onToggleSub(sub.id) } label: {
                        HStack(alignment: .firstTextBaseline) {
                            Image(systemName: sub.done ? "checkmark.square.fill" : "square").accessibilityHidden(true)
                            // The default follow-up emoji (☑️) reads as a second, already-ticked box, so leave it out.
                            if sub.emoji != "☑️" && !sub.emoji.isEmpty {
                                Text(sub.emoji).accessibilityHidden(true)
                            }
                            VStack(alignment: .leading, spacing: 0) {
                                Text(sub.title)
                                Text("Step \(i + 1) of \(stepCount)")
                                    .font(.footnote).foregroundStyle(Color.muted)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.leading, 40)
                        .padding(.trailing, 12)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(sub.title), step \(i + 1) of \(stepCount) for \(task.title)")
                    .accessibilityValue(sub.done ? "done" : "not done")
                    .accessibilityAddTraits(.isButton)
                }
                Spacer().frame(height: 4)
            }
        }
        .background(bg)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay {
            // A ≥ 3:1 border marks "all done" without relying on the fill colour.
            if task.complete {
                RoundedRectangle(cornerRadius: 12).strokeBorder(Color.goodGreen, lineWidth: 2)
            }
        }
    }
}
