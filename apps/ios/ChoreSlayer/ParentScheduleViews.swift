import SwiftUI

func followUpChecks(_ n: Int) -> String { n == 1 ? "1 follow-up check" : "\(n) follow-up checks" }

func adjustRowLabel(_ t: DayTaskSchedule) -> String {
    let n = t.subtaskCount ?? 0
    if t.skipped {
        return "\(t.title), skipped this day" + (n > 0 ? ", with its \(followUpChecks(n))" : "")
    }
    let chg = (t.overrideSlot != nil || t.overrideTime != nil) ? ", changed for this day" : ""
    let subs = n > 0 ? ", \(followUpChecks(n))" : ""
    return "\(t.title), \(t.effectiveSlot.capitalized)\(t.effectiveTime.map { " at \(displayTime($0))" } ?? "")\(chg)\(subs)"
}

struct AdjustDayView: View {
    @ObservedObject var model: AppModel
    @State private var day = ""
    @State private var didInit = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Adjust a day").font(.title2.bold()).screenHeading()
            if let c = model.managedChild {
                Text("for \(c.firstName)").font(.subheadline).foregroundStyle(Color.muted)
            }
            Text("Nudge a task's time or skip it — just for one day, without changing the recurring schedule.")
                .font(.footnote).foregroundStyle(Color.muted)

            DatePicker("Day", selection: dayBinding($day, fallback: model.today), displayedComponents: .date)
                .frame(minHeight: 44)
            AdaptiveStack {
                Button("Yesterday") { if !model.today.isEmpty { day = shiftDate(model.today, by: -1) } }
                Button("Today") { if !model.today.isEmpty { day = model.today } }
                Button("Tomorrow") { if !model.today.isEmpty { day = shiftDate(model.today, by: 1) } }
            }.font(.footnote)
            Button("Show day") {
                Task { await model.loadDaySchedule(day: day) }
            }
            .primaryButton().frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy || day.isEmpty)

            if let sched = model.daySchedule {
                if sched.tasks.isEmpty && !model.busy {
                    Text("Nothing scheduled for \(displayDay(sched.day)).").foregroundStyle(Color.muted)
                }
                ForEach(sched.tasks) { t in
                    Button { model.editOverrideStart(t) } label: {
                        HStack {
                            Text(t.emoji).font(.title).accessibilityHidden(true)
                            VStack(alignment: .leading) {
                                Text(t.title).font(.headline)
                                if t.skipped {
                                    Text((t.subtaskCount ?? 0) > 0 ? "Skipped this day, with its \(followUpChecks(t.subtaskCount!))" : "Skipped this day")
                                        .font(.footnote).foregroundStyle(Color.danger)
                                } else {
                                    Text("\(t.effectiveSlot.capitalized)\(t.effectiveTime.map { " · \(displayTime($0))" } ?? "")\((t.overrideSlot != nil || t.overrideTime != nil) ? " · changed" : "")")
                                        .font(.footnote).foregroundStyle(Color.muted)
                                    if let n = t.subtaskCount, n > 0 {
                                        Text(followUpChecks(n)).font(.footnote).foregroundStyle(Color.muted)
                                    }
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private let slots = ["morning", "midday", "afternoon", "evening", "anytime"]
    private var task: DayTaskSchedule? { model.editingOverride }

    var body: some View {
        if let task {
            VStack(alignment: .leading, spacing: 12) {
                Text("Adjust for \(displayDay(model.overrideDay))").font(.title2.bold()).screenHeading()
                Text("\(task.emoji) \(task.title)").font(.headline).accessibilityLabel(task.title)
                Text("Recurring default: \(task.defaultSlot.capitalized)\(task.defaultTime.map { " · \(displayTime($0))" } ?? "")")
                    .font(.footnote).foregroundStyle(Color.muted)
                    .accessibilityLabel("Recurring default: \(task.defaultSlot.capitalized)\(task.defaultTime.map { " at \(displayTime($0))" } ?? "")")
                if let n = task.subtaskCount, n > 0 {
                    Text("Has \(followUpChecks(n)).").font(.footnote).foregroundStyle(Color.muted)
                }

                sectionHeader("Just for this day")
                // A switch with a fixed name; what it means is said separately.
                Toggle("Skip this day", isOn: $skip).frame(minHeight: 44)
                if skip {
                    Text("Won't show on this day."
                         + ((task.subtaskCount ?? 0) > 0 ? " Its \(followUpChecks(task.subtaskCount!)) \(task.subtaskCount == 1 ? "is" : "are") skipped too." : ""))
                        .font(.footnote).foregroundStyle(Color.muted)
                }

                if !skip {
                    sectionHeader("Time slot")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: dynamicTypeSize.isAccessibilitySize ? 220 : 110), spacing: 6)],
                              alignment: .leading, spacing: 6) {
                        SelectChip(label: "Default (\(task.defaultSlot.capitalized))", selected: slot == nil) { slot = nil }
                        ForEach(slots, id: \.self) { s in
                            SelectChip(label: s.capitalized, selected: slot == s) { slot = s }
                        }
                    }
                    .choiceGroup("Time slot")
                    Toggle("Set a time for this day", isOn: Binding(
                        get: { !time.isEmpty },
                        set: { time = $0 ? (task.defaultTime ?? defaultTime(forSlot: slot ?? task.defaultSlot)) : "" }))
                        .frame(minHeight: 44)
                    if time.isEmpty {
                        Text("Off: uses the recurring time.").font(.footnote).foregroundStyle(Color.muted)
                    } else {
                        DatePicker("Time", selection: timeBinding($time), displayedComponents: .hourAndMinute)
                            .frame(minHeight: 44)
                    }
                }

                Button("Save for this day") {
                    Task {
                        await model.saveOverride(
                            taskId: task.id,
                            timeSlot: skip ? "" : (slot ?? ""),
                            timeOfDay: skip ? "" : time,
                            skipped: skip)
                    }
                }
                .primaryButton().frame(maxWidth: .infinity, minHeight: 48).disabled(model.busy)

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
