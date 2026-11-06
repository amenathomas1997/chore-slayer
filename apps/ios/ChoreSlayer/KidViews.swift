import SwiftUI

struct ConnectingView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Chore Slayer").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            if model.error != nil && !model.busy {
                Text("Couldn't reach Chore Slayer. Check your internet connection, then try again.")
                Button("Try again") { Task { await model.connect() } }
                    .buttonStyle(.borderedProminent)
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
    var body: some View {
        VStack(spacing: 12) {
            Text("Who's using the app?")
                .font(.title2.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            BigButton(symbol: "person.fill", title: "I'm a Kid", color: .brand) { model.chooseKid() }
            BigButton(symbol: "person.2.fill", title: "Parent", color: .brandPurple) { model.chooseParent() }
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
        .buttonStyle(.borderedProminent)
        .tint(color)
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
                .accessibilityAddTraits(.isHeader)
            TextField("First name", text: $first).textFieldStyle(.roundedBorder)
            TextField("Last name", text: $last).textFieldStyle(.roundedBorder)
            Button("See my chores") {
                Task {
                    await model.kidLogin(
                        first: first.trimmingCharacters(in: .whitespaces),
                        last: last.trimmingCharacters(in: .whitespaces))
                }
            }
            .buttonStyle(.borderedProminent)
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
                    .accessibilityAddTraits(.isHeader)
                Text(board.day).font(.footnote).foregroundStyle(Color.muted)
                Text("\(board.counts.done) of \(board.counts.total) done • \(board.counts.percent)%")
                    .font(.headline)
                ProgressView(value: Double(min(max(board.counts.percent, 0), 100)), total: 100)
                    .accessibilityHidden(true)
                if board.tasks.isEmpty {
                    Text("🎉 Nothing scheduled for today!")
                }
                ForEach(slots) { slot in
                    SlotSection(
                        label: slot.label,
                        items: board.tasks.filter { $0.slot == slot.id },
                        onToggle: { id in Task { await model.toggle(id) } })
                }
            }
        } else {
            Text("Loading…")
        }
    }
}

private struct SlotSection: View {
    let label: String
    let items: [DayTask]
    let onToggle: (String) -> Void
    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(label)
                    .font(.subheadline.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                ForEach(items) { task in
                    TaskRow(task: task, onToggle: onToggle)
                }
            }
            .padding(.top, 8)
        }
    }
}

struct TaskRow: View {
    let task: DayTask
    let onToggle: (String) -> Void

    private var bg: Color {
        if task.complete { return .taskDone }
        if task.done { return .taskPartial }
        return Color(.secondarySystemBackground)
    }
    private var stateWord: String {
        task.complete ? "completed" : (task.done ? "checked, follow-ups remaining" : "not done")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { onToggle(task.id) } label: {
                HStack(alignment: .top) {
                    Image(systemName: task.done ? "checkmark.square.fill" : "square").accessibilityHidden(true)
                    Text(task.emoji).accessibilityHidden(true)
                    VStack(alignment: .leading) {
                        Text(task.title).font(.body.weight(.medium))
                        if let t = task.time {
                            Text("at \(t)").font(.footnote).foregroundStyle(Color.muted)
                        }
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(task.title)\(task.time.map { ", at \($0)" } ?? ""), \(task.type)")
            .accessibilityValue(stateWord)
            .accessibilityAddTraits(.isButton)

            if task.done && !task.subtasks.isEmpty {
                ForEach(task.subtasks) { sub in
                    Button { onToggle(sub.id) } label: {
                        HStack {
                            Image(systemName: sub.done ? "checkmark.square.fill" : "square").accessibilityHidden(true)
                            Text(sub.emoji).accessibilityHidden(true)
                            Text(sub.title)
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .padding(.leading, 28)
                    .accessibilityLabel(sub.title)
                    .accessibilityValue(sub.done ? "done" : "not done")
                    .accessibilityAddTraits(.isButton)
                }
            }
        }
        .padding(12)
        .background(bg)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
