import SwiftUI

// MARK: - Date helper

/// Shift a yyyy-MM-dd date string by a number of days (UTC, locale-independent).
func shiftDate(_ ymd: String, by days: Int) -> String {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "UTC")!
    let f = DateFormatter()
    f.calendar = cal
    f.timeZone = cal.timeZone
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyy-MM-dd"
    guard let d = f.date(from: ymd), let n = cal.date(byAdding: .day, value: days, to: d) else { return ymd }
    return f.string(from: n)
}

// MARK: - Daily report

/// "Main box checked, 1 of 2 follow-ups done" and the like.
func followUpStatus(_ t: DayTask) -> String {
    let n = t.subtasks.count
    let done = t.subtasks.filter(\.done).count
    if n == 0 { return t.done ? "Done" : "Not done" }
    if t.complete { return "Done, all \(n) follow-up\(n == 1 ? "" : "s") done" }
    if t.done { return "\(done) of \(n) follow-up\(n == 1 ? "" : "s") done" }
    return "Not done, \(n) follow-up check\(n == 1 ? "" : "s")"
}

struct DailyReportView: View {
    @ObservedObject var model: AppModel
    /// Shown under "Save report" when saving failed.
    @State private var saveError: String?

    private var bundle: ReportBundle? { model.reportBundle }

    var body: some View {
        if let bundle {
            let c = bundle.summary.counts
            VStack(alignment: .leading, spacing: 12) {
                Text("Daily report").font(.title2.bold()).screenHeading()
                Text(displayDay(bundle.day)).font(.subheadline).foregroundStyle(Color.muted)

                AdaptiveStack(spacing: 10) {
                    StatTile(value: "\(c.done) of \(c.total)", label: "Tasks fully done",
                             spoken: "\(c.done) of \(c.total) tasks fully done")
                    StatTile(value: "\(c.percent)%", label: "Of all checks done",
                             spoken: "\(c.percent) percent of all checks done")
                }

                if !bundle.summary.tasks.isEmpty {
                    sectionHeader("Tasks")
                    ForEach(bundle.summary.tasks) { t in
                        reportTaskRow(t)
                    }
                }

                // Fields live in AppModel.reportDraft so the idle lock doesn't lose them.
                LabeledTextField(title: "Mood", text: $model.reportDraft.mood)

                sectionHeader("Behavior rating (1 to 5)")
                HStack {
                    ForEach(1...5, id: \.self) { n in
                        SelectChip(label: "\(n)", selected: model.reportDraft.rating == n, a11yLabel: "\(n) of 5") {
                            model.reportDraft.rating = (model.reportDraft.rating == n) ? nil : n
                            model.markParentActivity()
                        }
                    }
                }
                .choiceGroup("Behavior rating")

                LabeledTextField(title: "Highlights", text: $model.reportDraft.highlights, axis: .vertical)
                LabeledTextField(title: "Challenges", text: $model.reportDraft.challenges, axis: .vertical)
                LabeledTextField(title: "Notes", text: $model.reportDraft.notes, axis: .vertical)
                LabeledTextField(title: "Goals", text: $model.reportDraft.goals, axis: .vertical)

                Button("Save report") {
                    Task {
                        let ok = await model.saveReport()
                        saveError = ok ? nil : (model.error ?? "The report wasn't saved.")
                    }
                }
                .primaryButton().frame(maxWidth: .infinity, minHeight: 48).disabled(model.busy)
                if let saveError {
                    Text("Error: \(saveError) Your text is still here; try saving again.")
                        .font(.footnote.weight(.semibold)).foregroundStyle(Color.danger)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onChange(of: model.reportDraft) { _ in model.markParentActivity() }
        } else {
            Text("Loading…")
        }
    }

    /// One task and, nested under it, its follow-up checks (like "See my day").
    @ViewBuilder private func reportTaskRow(_ t: DayTask) -> some View {
        let status = followUpStatus(t)
        HStack(alignment: .top) {
            Image(systemName: t.complete ? "checkmark.square.fill" : (t.done ? "minus.square.fill" : "square"))
                .accessibilityHidden(true)
            Text(t.title)
            Spacer()
            Text(status).font(.footnote.weight(.semibold)).foregroundStyle(Color.muted)
                .multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(t.title): \(status)")
        ForEach(Array(t.subtasks.enumerated()), id: \.element.id) { i, s in
            HStack {
                Image(systemName: s.done ? "checkmark.square.fill" : "square").accessibilityHidden(true)
                Text(s.title)
                Spacer()
                Text(s.done ? "Done" : "Not yet").font(.footnote).foregroundStyle(Color.muted)
            }
            .font(.subheadline)
            .padding(.leading, 28)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Follow-up \(i + 1) of \(t.subtasks.count) for \(t.title): \(s.title), \(s.done ? "done" : "not yet")")
        }
    }
}

// MARK: - BHIS report

/// "Wipe table, chore: main box checked 5 times, fully done 3 times, 2 follow-up checks"
func taskFreqSpoken(_ t: TaskFreq) -> String {
    func times(_ n: Int) -> String { n == 1 ? "1 time" : "\(n) times" }
    let typ = t.type == "activity" ? "activity" : "chore"
    let subs = t.followUps ?? 0
    guard subs > 0 else { return "\(t.title), \(typ): done \(times(t.count))" }
    let full = t.fullCount.map { ", fully done \(times($0))" } ?? ""
    return "\(t.title), \(typ): main box checked \(times(t.count))\(full), \(subs) follow-up check\(subs == 1 ? "" : "s")"
}

func bhisDaySpoken(_ d: BhisDay) -> String {
    let partly = (d.partly ?? 0) > 0 ? ", \(d.partly!) partly" : ""
    return "\(displayDay(d.day)): \(d.completed) of \(d.scheduled) done\(partly), \(d.percent) percent"
}

struct BhisView: View {
    @ObservedObject var model: AppModel
    @State private var from = ""
    @State private var to = ""
    @State private var didInit = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BHIS report").font(.title2.bold()).screenHeading()
            if let c = model.managedChild {
                Text("for \(c.firstName) \(c.lastName)").font(.subheadline).foregroundStyle(Color.muted)
            }

            DatePicker("From", selection: dayBinding($from, fallback: model.today), displayedComponents: .date)
                .frame(minHeight: 44)
            DatePicker("To", selection: dayBinding($to, fallback: model.today), displayedComponents: .date)
                .frame(minHeight: 44)
            AdaptiveStack {
                Button("Last 7 days") { if !model.today.isEmpty { from = shiftDate(model.today, by: -6); to = model.today } }
                Button("Last 30 days") { if !model.today.isEmpty { from = shiftDate(model.today, by: -29); to = model.today } }
            }.font(.footnote)
            Button("Generate") {
                Task { await model.generateBhis(from: from, to: to) }
            }
            .primaryButton().frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy || from.isEmpty || to.isEmpty)

            if let r = model.bhisReport {
                ShareLink(item: bhisShareText(r)) {
                    HStack { Image(systemName: "square.and.arrow.up").accessibilityHidden(true); Text("Share as text") }
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.bordered)
                .accessibilityLabel("Share report as text")

                statsCard(r)
                if !r.daily.isEmpty {
                    sectionHeader("Daily")
                    ForEach(r.daily) { d in
                        AdaptiveStack(spacing: 4) {
                            Text(displayDay(d.day))
                            if !dynamicLarge { Spacer() }
                            Text("\(d.completed)/\(d.scheduled) · \(d.percent)%"
                                 + ((d.partly ?? 0) > 0 ? " · \(d.partly!) partly" : ""))
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(bhisDaySpoken(d))
                    }
                }
                if !r.taskFreq.isEmpty {
                    sectionHeader("How often each task was done")
                    if r.taskFreq.contains(where: { ($0.followUps ?? 0) > 0 }) {
                        Text("For tasks with follow-up checks: \"main box\" counts days the task itself was checked, \"fully done\" counts days every follow-up was done too.")
                            .font(.footnote).foregroundStyle(Color.muted)
                    }
                    ForEach(r.taskFreq) { t in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).fontWeight(.semibold)
                            if (t.followUps ?? 0) > 0 {
                                Text("Main box ×\(t.count)"
                                     + (t.fullCount.map { " · Fully done ×\($0)" } ?? "")
                                     + " · \(t.followUps!) follow-up check\(t.followUps == 1 ? "" : "s")")
                                    .foregroundStyle(Color.muted)
                            } else {
                                Text("Done ×\(t.count)").foregroundStyle(Color.muted)
                            }
                        }
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(taskFreqSpoken(t))
                    }
                }
                if !r.reports.isEmpty {
                    sectionHeader("Daily notes")
                    ForEach(r.reports) { rep in noteCard(rep) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            guard !didInit, !model.today.isEmpty else { return }
            didInit = true
            to = model.today
            from = shiftDate(model.today, by: -6)
            Task { await model.generateBhis(from: from, to: to) }
        }
    }

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private var dynamicLarge: Bool { dynamicTypeSize.isAccessibilitySize }

    @ViewBuilder private func statsCard(_ r: BhisReport) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(displayDay(r.range.from)) to \(displayDay(r.range.to)), \(r.range.days) days").font(.headline)
            statLine("Tasks completed", "\(r.stats.totalCompletions)")
            statLine("Days with activity", "\(r.stats.daysWithActivity) of \(r.range.days)")
            statLine("Active tasks", "\(r.stats.activeTaskCount)")
            statLine("Avg completion", "\(r.stats.avgCompletionPct)%")
            statLine("Avg behavior", r.stats.avgBehaviorRating.map { "\($0) / 5" } ?? "—",
                     spoken: r.stats.avgBehaviorRating.map { "\($0) out of 5" } ?? "none")
            if !r.stats.moodCounts.isEmpty {
                statLine("Moods", r.stats.moodCounts.map { "\($0.key)×\($0.value)" }.joined(separator: "  "),
                         spoken: r.stats.moodCounts.map { "\($0.key) \($0.value)" }.joined(separator: ", "))
            }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder private func statLine(_ label: String, _ value: String, spoken: String? = nil) -> some View {
        AdaptiveStack(spacing: 2) {
            Text(label).foregroundStyle(Color.muted)
            if !dynamicLarge { Spacer() }
            Text(value).fontWeight(.semibold)
        }
        .font(.subheadline)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(spoken ?? value)")
    }

    @ViewBuilder private func noteCard(_ rep: DatedReport) -> some View {
        let hasMood = rep.mood?.isEmpty == false
        VStack(alignment: .leading, spacing: 2) {
            Text(displayDay(rep.day)).fontWeight(.semibold)
            if hasMood || rep.behaviorRating != nil {
                Text("Mood: \(hasMood ? rep.mood! : "—") · Behavior: \(rep.behaviorRating.map(String.init) ?? "—")")
                    .font(.footnote).foregroundStyle(Color.muted)
                    .accessibilityLabel("Mood: \(hasMood ? rep.mood! : "none"), behavior: \(rep.behaviorRating.map { "\($0) of 5" } ?? "none")")
            }
            noteLine("Highlights", rep.highlights)
            noteLine("Challenges", rep.challenges)
            noteLine("Notes", rep.notes)
            noteLine("Goals", rep.goals)
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func noteLine(_ label: String, _ value: String?) -> some View {
        if let v = value, !v.isEmpty {
            Text("\(label): \(v)").font(.footnote)
        }
    }
}

func bhisShareText(_ r: BhisReport) -> String {
    var s = "BHIS report — \(r.child?.name ?? "")\n"
    s += "\(r.range.from) to \(r.range.to) (\(r.range.days) days)\n\n"
    s += "Tasks completed: \(r.stats.totalCompletions)\n"
    s += "Days with activity: \(r.stats.daysWithActivity)/\(r.range.days)\n"
    s += "Active tasks: \(r.stats.activeTaskCount)\n"
    s += "Avg completion: \(r.stats.avgCompletionPct)%\n"
    s += "Avg behavior: \(r.stats.avgBehaviorRating.map { "\($0)/5" } ?? "—")\n"
    if !r.stats.moodCounts.isEmpty {
        s += "Moods: " + r.stats.moodCounts.map { "\($0.key) ×\($0.value)" }.joined(separator: ", ") + "\n"
    }
    s += "\nDaily:\n"
    for d in r.daily {
        let partly = (d.partly ?? 0) > 0 ? ", \(d.partly!) partly" : ""
        s += "  \(d.day): \(d.completed)/\(d.scheduled) (\(d.percent)%)\(partly)\n"
    }
    if !r.taskFreq.isEmpty {
        s += "\nTask frequency:\n"
        for t in r.taskFreq {
            if let subs = t.followUps, subs > 0 {
                s += "  \(t.title): main box ×\(t.count)\(t.fullCount.map { ", fully done ×\($0)" } ?? "") (\(subs) follow-up check\(subs == 1 ? "" : "s"))\n"
            } else {
                s += "  \(t.title) ×\(t.count)\n"
            }
        }
    }
    if !r.reports.isEmpty {
        s += "\nDaily notes:\n"
        for rep in r.reports {
            s += "  \(rep.day) — mood \(rep.mood?.isEmpty == false ? rep.mood! : "—"), behavior \(rep.behaviorRating.map(String.init) ?? "—")\n"
            for (k, v) in [("Highlights", rep.highlights), ("Challenges", rep.challenges), ("Notes", rep.notes), ("Goals", rep.goals)] {
                if let v, !v.isEmpty { s += "    \(k): \(v)\n" }
            }
        }
    }
    return s
}
