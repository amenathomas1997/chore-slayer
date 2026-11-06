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

struct DailyReportView: View {
    @ObservedObject var model: AppModel
    @State private var mood = ""
    @State private var rating: Int?
    @State private var highlights = ""
    @State private var challenges = ""
    @State private var notes = ""
    @State private var goals = ""

    private var bundle: ReportBundle? { model.reportBundle }

    var body: some View {
        if let bundle {
            VStack(alignment: .leading, spacing: 12) {
                Text("Daily report").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Text(bundle.day).font(.subheadline).foregroundStyle(Color.muted)

                Text("Today: \(bundle.summary.counts.done)/\(bundle.summary.counts.total) tasks done · \(bundle.summary.counts.percent)%")
                    .font(.headline)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Today: \(bundle.summary.counts.done) of \(bundle.summary.counts.total) tasks done, \(bundle.summary.counts.percent) percent")

                TextField("Mood", text: $mood).textFieldStyle(.roundedBorder).accessibilityLabel("Mood")

                sectionHeader("Behavior (1–5)")
                HStack {
                    ForEach(1...5, id: \.self) { n in
                        SelectChip(label: "\(n)", selected: rating == n, a11yLabel: "Rating \(n) of 5") {
                            rating = (rating == n) ? nil : n
                        }
                    }
                }

                TextField("Highlights", text: $highlights, axis: .vertical).textFieldStyle(.roundedBorder).accessibilityLabel("Highlights")
                TextField("Challenges", text: $challenges, axis: .vertical).textFieldStyle(.roundedBorder).accessibilityLabel("Challenges")
                TextField("Notes", text: $notes, axis: .vertical).textFieldStyle(.roundedBorder).accessibilityLabel("Notes")
                TextField("Goals", text: $goals, axis: .vertical).textFieldStyle(.roundedBorder).accessibilityLabel("Goals")

                Button("Save report") {
                    Task {
                        await model.saveReport(
                            mood: mood.trimmingCharacters(in: .whitespaces), rating: rating,
                            highlights: highlights.trimmingCharacters(in: .whitespaces),
                            challenges: challenges.trimmingCharacters(in: .whitespaces),
                            notes: notes.trimmingCharacters(in: .whitespaces),
                            goals: goals.trimmingCharacters(in: .whitespaces))
                    }
                }
                .buttonStyle(.borderedProminent).frame(maxWidth: .infinity, minHeight: 48).disabled(model.busy)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onAppear { load(bundle) }
        } else {
            Text("Loading…")
        }
    }

    private func load(_ b: ReportBundle) {
        mood = b.report?.mood ?? ""
        rating = b.report?.behaviorRating
        highlights = b.report?.highlights ?? ""
        challenges = b.report?.challenges ?? ""
        notes = b.report?.notes ?? ""
        goals = b.report?.goals ?? ""
    }
}

// MARK: - BHIS report

struct BhisView: View {
    @ObservedObject var model: AppModel
    @State private var from = ""
    @State private var to = ""
    @State private var didInit = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BHIS report").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let c = model.managedChild {
                Text("for \(c.firstName) \(c.lastName)").font(.subheadline).foregroundStyle(Color.muted)
            }

            HStack {
                TextField("From (YYYY-MM-DD)", text: $from).textFieldStyle(.roundedBorder).accessibilityLabel("From date")
                TextField("To", text: $to).textFieldStyle(.roundedBorder).accessibilityLabel("To date")
            }
            HStack {
                Button("Last 7 days") { if !model.today.isEmpty { from = shiftDate(model.today, by: -6); to = model.today } }
                Button("Last 30 days") { if !model.today.isEmpty { from = shiftDate(model.today, by: -29); to = model.today } }
            }.font(.footnote)
            Button("Generate") {
                Task { await model.generateBhis(from: from.trimmingCharacters(in: .whitespaces), to: to.trimmingCharacters(in: .whitespaces)) }
            }
            .buttonStyle(.borderedProminent).frame(maxWidth: .infinity, minHeight: 48)
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
                        HStack {
                            Text(d.day); Spacer(); Text("\(d.completed)/\(d.scheduled) · \(d.percent)%")
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(d.day): \(d.completed) of \(d.scheduled) done, \(d.percent) percent")
                    }
                }
                if !r.taskFreq.isEmpty {
                    sectionHeader("How often each task was done")
                    ForEach(r.taskFreq) { t in
                        HStack {
                            Text("\(t.type == "activity" ? "⭐" : "✅") \(t.title)"); Spacer(); Text("×\(t.count)")
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(t.title), done \(t.count) time\(t.count == 1 ? "" : "s")")
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

    @ViewBuilder private func statsCard(_ r: BhisReport) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(r.range.from) → \(r.range.to) · \(r.range.days) days").font(.headline)
            statLine("Tasks completed", "\(r.stats.totalCompletions)")
            statLine("Days with activity", "\(r.stats.daysWithActivity) of \(r.range.days)")
            statLine("Active tasks", "\(r.stats.activeTaskCount)")
            statLine("Avg completion", "\(r.stats.avgCompletionPct)%")
            statLine("Avg behavior", r.stats.avgBehaviorRating.map { "\($0) / 5" } ?? "—")
            if !r.stats.moodCounts.isEmpty {
                statLine("Moods", r.stats.moodCounts.map { "\($0.key)×\($0.value)" }.joined(separator: "  "))
            }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder private func statLine(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(Color.muted); Spacer(); Text(value).fontWeight(.semibold) }
            .font(.subheadline)
            .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func noteCard(_ rep: DatedReport) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(rep.day).fontWeight(.semibold)
            if (rep.mood?.isEmpty == false) || rep.behaviorRating != nil {
                Text("Mood: \(rep.mood?.isEmpty == false ? rep.mood! : "—") · Behavior: \(rep.behaviorRating.map(String.init) ?? "—")")
                    .font(.footnote).foregroundStyle(Color.muted)
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
    for d in r.daily { s += "  \(d.day): \(d.completed)/\(d.scheduled) (\(d.percent)%)\n" }
    if !r.taskFreq.isEmpty {
        s += "\nTask frequency:\n"
        for t in r.taskFreq { s += "  \(t.title) ×\(t.count)\n" }
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
