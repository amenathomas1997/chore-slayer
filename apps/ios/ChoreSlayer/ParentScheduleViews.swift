import SwiftUI

func adjustRowLabel(_ t: DayTaskSchedule) -> String {
    if t.skipped { return "\(t.title), skipped this day" }
    let chg = (t.overrideSlot != nil || t.overrideTime != nil) ? ", changed for this day" : ""
    return "\(t.title), \(t.effectiveSlot.capitalized)\(t.effectiveTime.map { " at \($0)" } ?? "")\(chg)"
}

struct AdjustDayView: View {
    @ObservedObject var model: AppModel
    @State private var day = ""
    @State private var didInit = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Adjust a day").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let c = model.managedChild {
                Text("for \(c.firstName)").font(.subheadline).foregroundStyle(Color.muted)
            }
            Text("Nudge a task's time or skip it — just for one day, without changing the recurring schedule.")
                .font(.footnote).foregroundStyle(Color.muted)

            TextField("Day (YYYY-MM-DD)", text: $day).textFieldStyle(.roundedBorder).accessibilityLabel("Day")
            HStack {
                Button("Yesterday") { if !model.today.isEmpty { day = shiftDate(model.today, by: -1) } }
                Button("Today") { if !model.today.isEmpty { day = model.today } }
                Button("Tomorrow") { if !model.today.isEmpty { day = shiftDate(model.today, by: 1) } }
            }.font(.footnote)
            Button("Show day") {
                Task { await model.loadDaySchedule(day: day.trimmingCharacters(in: .whitespaces)) }
            }
            .buttonStyle(.borderedProminent).frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy || day.isEmpty)

            if let sched = model.daySchedule {
                if sched.tasks.isEmpty && !model.busy {
                    Text("Nothing scheduled for \(sched.day).").foregroundStyle(Color.muted)
                }
                ForEach(sched.tasks) { t in
                    Button { model.editOverrideStart(t) } label: {
                        HStack {
                            Text(t.emoji).font(.title).accessibilityHidden(true)
                            VStack(alignment: .leading) {
                                Text(t.title).font(.headline)
                                if t.skipped {
                                    Text("Skipped this day").font(.footnote).foregroundStyle(Color.danger)
                                } else {
                                    Text("\(t.effectiveSlot.capitalized)\(t.effectiveTime.map { " · \($0)" } ?? "")\((t.overrideSlot != nil || t.overrideTime != nil) ? " · changed" : "")")
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
                    .accessibilityLabel(adjustRowLabel(t))
                    .accessibilityHint("Adjust")
                    .accessibilityAddTraits(.isButton)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            if !didInit {
                didInit = true
                day = model.overrideDay.isEmpty ? model.today : model.overrideDay
            }
        }
    }
}

struct OverrideEditorView: View {
    @ObservedObject var model: AppModel
    @State private var skip = false
    @State private var slot: String?   // nil = use recurring default
    @State private var time = ""
    @State private var didInit = false

    private let slots = ["morning", "midday", "afternoon", "evening", "anytime"]
    private var task: DayTaskSchedule? { model.editingOverride }

    var body: some View {
        if let task {
            VStack(alignment: .leading, spacing: 12) {
                Text("Adjust for \(model.overrideDay)").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Text("\(task.emoji) \(task.title)").font(.headline).accessibilityLabel(task.title)
                Text("Recurring default: \(task.defaultSlot.capitalized)\(task.defaultTime.map { " · \($0)" } ?? "")")
                    .font(.footnote).foregroundStyle(Color.muted)

                sectionHeader("Just for this day")
                SelectChip(label: skip ? "Skipped — won't show this day" : "Skip this day", selected: skip) { skip.toggle() }

                if !skip {
                    sectionHeader("Time slot")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            SelectChip(label: "Default (\(task.defaultSlot.capitalized))", selected: slot == nil) { slot = nil }
                            ForEach(slots, id: \.self) { s in
                                SelectChip(label: s.capitalized, selected: slot == s) { slot = s }
                            }
                        }
                    }
                    TextField("Time (HH:MM · blank = default)", text: $time)
                        .textFieldStyle(.roundedBorder).accessibilityLabel("Time for this day, optional")
                }

                Button("Save for this day") {
                    Task {
                        await model.saveOverride(
                            taskId: task.id,
                            timeSlot: skip ? "" : (slot ?? ""),
                            timeOfDay: skip ? "" : time.trimmingCharacters(in: .whitespaces),
                            skipped: skip)
                    }
                }
                .buttonStyle(.borderedProminent).frame(maxWidth: .infinity, minHeight: 48).disabled(model.busy)

                if task.overrideSlot != nil || task.overrideTime != nil || task.skipped {
                    Button("Reset to recurring default") {
                        Task { await model.clearOverride(taskId: task.id) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onAppear {
                if !didInit {
                    didInit = true
                    skip = task.skipped
                    slot = task.overrideSlot
                    time = task.overrideTime ?? ""
                }
            }
        } else {
            Text("No task selected.")
        }
    }
}
