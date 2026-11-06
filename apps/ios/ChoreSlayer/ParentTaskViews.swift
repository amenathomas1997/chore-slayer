import SwiftUI

// MARK: - Shared helpers

@ViewBuilder
func sectionHeader(_ text: String) -> some View {
    Text(text)
        .font(.subheadline.weight(.semibold))
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
}

struct SelectChip: View {
    let label: String
    let selected: Bool
    var a11yLabel: String? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(selected ? Color.brand : Color(.secondarySystemBackground))
                .foregroundStyle(selected ? Color.white : Color.primary)
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(selected ? Color.clear : Color.primary.opacity(0.25), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(a11yLabel ?? label)
        .accessibilityAddTraits(.isButton)
        .accessibilityAddTraits(selected ? .isSelected : .isButton)
    }
}

func scheduleSummary(_ t: ParentTask) -> String {
    let typ = t.type == "activity" ? "Activity" : "Chore"
    let time = t.timeOfDay.map { " · \($0)" } ?? ""
    return "\(typ) · \(daysSummary(t.daysOfWeek)) · \(t.timeSlot.capitalized)\(time)"
}

func daysSummary(_ mask: String) -> String {
    guard mask.count == 7 else { return "Some days" }
    switch mask {
    case "1111111": return "Every day"
    case "0111110": return "Weekdays"
    case "1000001": return "Weekends"
    default:
        let names = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"]
        let chars = Array(mask)
        let on = (0..<7).compactMap { chars[$0] == "1" ? names[$0] : nil }
        return on.isEmpty ? "No days" : on.joined(separator: " ")
    }
}

// MARK: - Tasks list

struct TasksView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        VStack(spacing: 12) {
            Text("Tasks & activities")
                .font(.title2.bold()).frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            if let c = model.managedChild {
                Text("for \(c.firstName)").font(.subheadline).foregroundStyle(Color.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button { model.addTaskStart() } label: {
                HStack { Image(systemName: "plus").accessibilityHidden(true); Text("Add task or activity"); Spacer() }
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityLabel("Add task or activity")

            if model.parentTasks.isEmpty && !model.busy {
                Text("No tasks yet.").foregroundStyle(Color.muted)
            }
            ForEach(model.parentTasks) { task in
                Button { model.editTaskStart(task) } label: {
                    HStack {
                        Text(task.emoji).font(.title).accessibilityHidden(true)
                        VStack(alignment: .leading) {
                            Text(task.title).font(.headline)
                            Text(scheduleSummary(task)).font(.footnote).foregroundStyle(Color.muted)
                            if !task.subtasks.isEmpty {
                                Text("↳ \(task.subtasks.count) follow-up\(task.subtasks.count == 1 ? "" : "s")")
                                    .font(.footnote).foregroundStyle(Color.muted)
                            }
                        }
                        Spacer()
                        Image(systemName: "pencil").foregroundStyle(Color.muted).accessibilityHidden(true)
                    }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(task.title). \(scheduleSummary(task))")
                .accessibilityHint("Edit")
                .accessibilityAddTraits(.isButton)
            }
        }
    }
}

// MARK: - Task editor

struct TaskEditorView: View {
    @ObservedObject var model: AppModel
    @State private var title = ""
    @State private var type = "chore"
    @State private var emoji = "✅"
    @State private var mask = "1111111"
    @State private var slot = "anytime"
    @State private var time = ""
    @State private var newSub = ""
    @State private var confirmDelete = false

    private let slots = ["morning", "midday", "afternoon", "evening", "anytime"]
    private let dow = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"]
    private let dowFull = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]

    private var existing: ParentTask? { model.editingTask }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(existing == nil ? "Add task / activity" : "Edit task")
                .font(.title2.bold()).accessibilityAddTraits(.isHeader)

            TextField("Title", text: $title).textFieldStyle(.roundedBorder).accessibilityLabel("Title")

            sectionHeader("Type")
            HStack {
                SelectChip(label: "✅ Chore", selected: type == "chore", a11yLabel: "Chore") { type = "chore" }
                SelectChip(label: "⭐ Activity", selected: type == "activity", a11yLabel: "Activity") { type = "activity" }
            }

            sectionHeader("Icon")
            TextField("Emoji", text: $emoji).textFieldStyle(.roundedBorder).accessibilityLabel("Emoji")

            sectionHeader("Repeats on")
            HStack(spacing: 6) {
                ForEach(0..<7, id: \.self) { i in
                    SelectChip(label: dow[i], selected: isDayOn(i), a11yLabel: dowFull[i]) { toggleDay(i) }
                }
            }
            HStack(spacing: 12) {
                Button("Every day") { mask = "1111111" }
                Button("Weekdays") { mask = "0111110" }
                Button("Weekends") { mask = "1000001" }
            }.font(.footnote)

            sectionHeader("Time of day")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(slots, id: \.self) { s in
                        SelectChip(label: s.capitalized, selected: slot == s) { slot = s }
                    }
                }
            }
            TextField("Specific time (HH:MM, optional)", text: $time)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numbersAndPunctuation)
                .accessibilityLabel("Specific time, optional")

            Button(existing == nil ? "Add" : "Save changes") {
                let cleanEmoji = emoji.trimmingCharacters(in: .whitespaces)
                Task {
                    await model.saveTask(
                        existing: existing,
                        title: title.trimmingCharacters(in: .whitespaces),
                        type: type,
                        emoji: cleanEmoji.isEmpty ? (type == "activity" ? "⭐" : "✅") : cleanEmoji,
                        daysOfWeek: mask, timeSlot: slot,
                        timeOfDay: time.trimmingCharacters(in: .whitespaces))
                }
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy || title.trimmingCharacters(in: .whitespaces).isEmpty)

            if let task = existing {
                Divider()
                sectionHeader("Follow-up checks")
                Text("Sub-steps that appear after this task is checked.")
                    .font(.footnote).foregroundStyle(Color.muted)
                ForEach(task.subtasks) { sub in
                    HStack {
                        Text("☑️ \(sub.title)").accessibilityLabel(sub.title)
                        Spacer()
                        Button("Remove") { Task { await model.removeSubtask(subId: sub.id) } }
                            .foregroundStyle(Color.danger)
                            .accessibilityLabel("Remove \(sub.title)")
                    }
                }
                HStack {
                    TextField("Add a follow-up check", text: $newSub)
                        .textFieldStyle(.roundedBorder).accessibilityLabel("Add a follow-up check")
                    Button("Add") {
                        let t = newSub.trimmingCharacters(in: .whitespaces)
                        Task { await model.addSubtask(parentId: task.id, title: t); newSub = "" }
                    }
                    .disabled(model.busy || newSub.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Button("Delete this task", role: .destructive) { confirmDelete = true }
                    .frame(maxWidth: .infinity, minHeight: 44).tint(.danger)
            } else {
                Text("Save first, then you can add follow-up checks.")
                    .font(.footnote).foregroundStyle(Color.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear { loadFields() }
        .alert("Delete this task?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) { if let t = existing { Task { await model.deleteTask(t) } } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the task and its follow-up checks. Past check-off history is kept.")
        }
    }

    private func isDayOn(_ i: Int) -> Bool {
        let chars = Array(mask.count == 7 ? mask : "1111111")
        return chars[i] == "1"
    }
    private func toggleDay(_ i: Int) {
        var chars = Array(mask.count == 7 ? mask : "1111111")
        chars[i] = chars[i] == "1" ? "0" : "1"
        mask = String(chars)
    }
    private func loadFields() {
        if let t = existing {
            title = t.title; type = t.type; emoji = t.emoji
            mask = t.daysOfWeek; slot = t.timeSlot; time = t.timeOfDay ?? ""
        } else {
            title = ""; type = "chore"; emoji = "✅"; mask = "1111111"; slot = "anytime"; time = ""
        }
        newSub = ""
    }
}
