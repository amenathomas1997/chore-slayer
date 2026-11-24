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
                .background(selected ? Color.brandFill : Color(.secondarySystemBackground))
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
                .screenHeading()
            if let c = model.managedChild {
                Text("for \(c.firstName)").font(.subheadline).foregroundStyle(Color.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button { model.addTaskStart() } label: {
                HStack { Image(systemName: "plus").accessibilityHidden(true); Text("Add task or activity"); Spacer() }
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .primaryButton()
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
                .accessibilityLabel("\(task.title). \(scheduleSummary(task))"
                    + (task.subtasks.isEmpty ? "" : ". \(task.subtasks.count) follow-up check\(task.subtasks.count == 1 ? "" : "s")"))
                .accessibilityHint("Edit")
                .accessibilityAddTraits(.isButton)
            }
        }
    }
}

// MARK: - Task editor

struct TaskEditorView: View {
    @ObservedObject var model: AppModel
    @State private var confirmDelete = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// Keyboard focus: kept in the "add" field after adding a follow-up.
    @FocusState private var keyboardFocus: SubField?
    /// VoiceOver focus: moved to the next row (or the add field) after a removal.
    @AccessibilityFocusState private var voiceOverFocus: SubField?

    private let slots = ["morning", "midday", "afternoon", "evening", "anytime"]
    private let dow = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"]
    private let dowFull = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]

    private var existing: ParentTask? { model.editingTask }
    /// The editor's fields live in AppModel so the idle lock doesn't lose them.
    private var draft: Binding<TaskDraft> { $model.taskDraft }
    private var trimmedTitle: String { model.taskDraft.title.trimmingCharacters(in: .whitespaces) }
    private var trimmedNewSub: String { model.taskDraft.newSub.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(existing == nil ? "Add task / activity" : "Edit task")
                .font(.title2.bold()).screenHeading()

            TextField("Title", text: draft.title).textFieldStyle(.roundedBorder).accessibilityLabel("Title")

            sectionHeader("Type")
            AdaptiveStack(spacing: 8) {
                SelectChip(label: "✅ Chore", selected: model.taskDraft.type == "chore", a11yLabel: "Chore") { model.taskDraft.type = "chore" }
                SelectChip(label: "⭐ Activity", selected: model.taskDraft.type == "activity", a11yLabel: "Activity") { model.taskDraft.type = "activity" }
            }

            sectionHeader("Icon")
            TextField("Emoji", text: draft.emoji).textFieldStyle(.roundedBorder).accessibilityLabel("Emoji")

            sectionHeader("Repeats on")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: dynamicTypeSize.isAccessibilitySize ? 120 : 44), spacing: 6)],
                      alignment: .leading, spacing: 6) {
                ForEach(0..<7, id: \.self) { i in
                    SelectChip(label: dow[i], selected: isDayOn(i), a11yLabel: dowFull[i]) { toggleDay(i) }
                }
            }
            AdaptiveStack {
                Button("Every day") { model.taskDraft.mask = "1111111" }
                Button("Weekdays") { model.taskDraft.mask = "0111110" }
                Button("Weekends") { model.taskDraft.mask = "1000001" }
            }.font(.footnote)

            sectionHeader("Time of day")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(slots, id: \.self) { s in
                        SelectChip(label: s.capitalized, selected: model.taskDraft.slot == s) { model.taskDraft.slot = s }
                    }
                }
            }
            TextField("Specific time (HH:MM, optional)", text: draft.time)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numbersAndPunctuation)
                .accessibilityLabel("Specific time, optional")

            // Follow-up checks come before "Save changes", so moving top to
            // bottom reaches them before leaving the editor.
            if let task = existing {
                Divider()
                followUps(task)
            }

            Button(existing == nil ? "Add" : "Save changes") { Task { await model.saveTask() } }
                .primaryButton()
                .frame(maxWidth: .infinity, minHeight: 48)
                .disabled(model.busy || trimmedTitle.isEmpty)

            if existing != nil {
                Button("Delete this task", role: .destructive) { confirmDelete = true }
                    .frame(maxWidth: .infinity, minHeight: 44).tint(.danger)
            } else {
                Text("After you add the task, you can add follow-up checks here.")
                    .font(.footnote).foregroundStyle(Color.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: model.taskDraft) { _ in model.markParentActivity() }
        .alert("Delete this task?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) { if let t = existing { Task { await model.deleteTask(t) } } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the task and its follow-up checks. Past check-off history is kept.")
        }
    }

    @ViewBuilder private func followUps(_ task: ParentTask) -> some View {
        let n = task.subtasks.count
        sectionHeader("Follow-up checks")
        Text("Sub-steps that appear after this task is checked. Adding, renaming and removing save straight away.")
            .font(.footnote).foregroundStyle(Color.muted)
        ForEach(Array(task.subtasks.enumerated()), id: \.element.id) { i, sub in
            SubtaskRow(
                index: i, count: n, sub: sub,
                title: Binding(
                    get: { model.taskDraft.subTitles[sub.id] ?? sub.title },
                    set: { model.taskDraft.subTitles[sub.id] = $0 }),
                busy: model.busy,
                onSave: { t in Task { await model.renameSubtask(subId: sub.id, title: t) } },
                onRemove: { remove(sub, at: i, in: task) },
                keyboardFocus: $keyboardFocus,
                voiceOverFocus: $voiceOverFocus)
        }
        AdaptiveStack(spacing: 8) {
            TextField("Add a follow-up check", text: draft.newSub)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("New follow-up check \(n + 1)")
                .submitLabel(.done)
                .focused($keyboardFocus, equals: .newSub)
                .accessibilityFocused($voiceOverFocus, equals: .newSub)
                .onSubmit { addSub(task) }
            Button("Add") { addSub(task) }
                .frame(minHeight: 44)
                .accessibilityLabel("Add follow-up check")
                .disabled(model.busy || trimmedNewSub.isEmpty)
        }
    }

    private func addSub(_ task: ParentTask) {
        let t = trimmedNewSub
        guard !t.isEmpty, !model.busy else { return }
        Task {
            // The field is cleared by the model only when the save worked.
            _ = await model.addSubtask(parentId: task.id, title: t)
            keyboardFocus = .newSub
        }
    }

    private func remove(_ sub: ParentSub, at i: Int, in task: ParentTask) {
        let next: SubField = i + 1 < task.subtasks.count ? .sub(task.subtasks[i + 1].id) : .newSub
        Task {
            if await model.removeSubtask(subId: sub.id) {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { voiceOverFocus = next }
            }
        }
    }

    private func isDayOn(_ i: Int) -> Bool {
        let m = model.taskDraft.mask
        let chars = Array(m.count == 7 ? m : "1111111")
        return chars[i] == "1"
    }
    private func toggleDay(_ i: Int) {
        let m = model.taskDraft.mask
        var chars = Array(m.count == 7 ? m : "1111111")
        chars[i] = chars[i] == "1" ? "0" : "1"
        model.taskDraft.mask = String(chars)
    }
}

private enum SubField: Hashable {
    case sub(String)
    case newSub
}

/// One follow-up check: rename it in place, or remove it. Its text lives in the
/// task draft, so "Save changes" saves a rename that wasn't saved here.
private struct SubtaskRow: View {
    let index: Int
    let count: Int
    let sub: ParentSub
    @Binding var title: String
    let busy: Bool
    let onSave: (String) -> Void
    let onRemove: () -> Void
    var keyboardFocus: FocusState<SubField?>.Binding
    var voiceOverFocus: AccessibilityFocusState<SubField?>.Binding

    private var position: String { "Follow-up check \(index + 1) of \(count)" }
    private var trimmed: String { title.trimmingCharacters(in: .whitespaces) }
    private var changed: Bool { !trimmed.isEmpty && trimmed != sub.title }

    var body: some View {
        AdaptiveStack(spacing: 8) {
            HStack {
                Text("☑️").accessibilityHidden(true)
                TextField(position, text: $title)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(position)
                    .focused(keyboardFocus, equals: .sub(sub.id))
                    .accessibilityFocused(voiceOverFocus, equals: .sub(sub.id))
                    .onSubmit { save() }
            }
            HStack(spacing: 16) {
                if changed {
                    Button("Save") { save() }
                        .frame(minHeight: 44)
                        .accessibilityLabel("Save follow-up check \(index + 1)")
                        .disabled(busy)
                }
                Button("Remove") { onRemove() }
                    .frame(minHeight: 44)
                    .foregroundStyle(Color.danger)
                    .accessibilityLabel("Remove follow-up check \(index + 1): \(sub.title)")
                    .disabled(busy)
            }
        }
    }

    private func save() {
        guard changed else { return }
        onSave(trimmed)
    }
}
