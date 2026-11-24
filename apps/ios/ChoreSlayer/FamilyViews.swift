import SwiftUI
import UIKit

/// First run: create a new family, or link this device to an existing one.
/// Mirrors the web app's "Welcome to Chore Slayer" screen.
struct ConnectDeviceView: View {
    @ObservedObject var model: AppModel
    enum Mode: String, CaseIterable, Identifiable {
        case create = "✨ Create a family"
        case connect = "🔗 Connect this device"
        var id: String { rawValue }
    }
    @State private var mode: Mode = .create

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Welcome to Chore Slayer")
                .font(.title2.bold())
                .screenHeading()
            Text("Set up your family to get started.")
                .foregroundStyle(Color.muted)
            Picker("Set up", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("Create a family, or connect this device")

            switch mode {
            case .create: CreateFamilyForm(model: model)
            case .connect: ConnectFamilyForm(model: model)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CreateFamilyForm: View {
    @ObservedObject var model: AppModel
    @State private var name = ""
    @State private var pin = ""
    @State private var pin2 = ""
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Create your family")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text("Choose a parent PIN (4–8 digits). You’ll get a Family Code to add more devices, sign in on the web, or recover.")
                .font(.footnote).foregroundStyle(Color.muted)
            TextField("Family name (optional)", text: $name)
                .textFieldStyle(.roundedBorder)
                .textContentType(.familyName)
            PinField(title: "Parent PIN", pin: $pin, isNew: true)
            PinField(title: "Confirm PIN", pin: $pin2, isNew: true)
            if let problem {
                Text(problem).foregroundStyle(Color.errorInk).font(.footnote)
            }
            Button("Create family") {
                guard pin.count >= 4 else { problem = "PIN must be 4 to 8 digits."; return }
                guard pin == pin2 else { problem = "The two PINs do not match."; return }
                problem = nil
                Task { await model.createFamily(name: name.trimmingCharacters(in: .whitespaces), pin: pin) }
            }
            .primaryButton()
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy)
        }
        .onChange(of: problem) { msg in
            if let msg { UIAccessibility.post(notification: .announcement, argument: msg) }
        }
    }
}

private struct ConnectFamilyForm: View {
    @ObservedObject var model: AppModel
    @State private var code = ""
    @State private var pin = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Connect this device")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text("Enter your Family Code and parent PIN to link this device to an existing family.")
                .font(.footnote).foregroundStyle(Color.muted)
            TextField("Family code", text: $code)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            PinField(title: "Parent PIN", pin: $pin, isNew: false)
            Button("Connect") {
                Task { await model.connectDevice(code: code.trimmingCharacters(in: .whitespaces), pin: pin) }
            }
            .primaryButton()
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy || code.trimmingCharacters(in: .whitespaces).isEmpty || pin.count < 4)
        }
    }
}

/// A numeric, hidden PIN field limited to 8 digits.
struct PinField: View {
    let title: String
    @Binding var pin: String
    let isNew: Bool
    var body: some View {
        SecureField(title, text: $pin)
            .textFieldStyle(.roundedBorder)
            .keyboardType(.numberPad)
            .textContentType(isNew ? .newPassword : .password)
            .onChange(of: pin) { newValue in
                let digits = newValue.filter(\.isNumber)
                if digits != newValue || digits.count > 8 {
                    pin = String(digits.prefix(8))
                }
            }
    }
}

/// Shows the family code, like the web app's "Your Family Code" box.
struct FamilyCodeSheet: View {
    let code: String
    let onDone: () -> Void
    @State private var copied = false
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Text("Your Family Code")
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text("Save this Family Code somewhere safe. You’ll need it to add another device, sign in on the web, or recover your account:")
                    .multilineTextAlignment(.center)
                Text(code)
                    .font(.system(.largeTitle, design: .monospaced).weight(.heavy))
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
                    .accessibilityLabel("Family code: " + code.map { String($0) }.joined(separator: " "))
                Button(copied ? "Copied" : "Copy code") {
                    UIPasteboard.general.string = code
                    copied = true
                    Announcer.say("Family code copied")
                }
                .frame(minHeight: 44)
                Text("From now on, your PIN unlocks the parent area on this device.")
                    .font(.footnote).foregroundStyle(Color.muted)
                    .multilineTextAlignment(.center)
                Button("Got it", action: onDone)
                    .primaryButton()
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .padding(24)
        }
    }
}

struct ChangePinView: View {
    @ObservedObject var model: AppModel
    @State private var current = ""
    @State private var new1 = ""
    @State private var new2 = ""
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Change PIN")
                .font(.title2.bold())
                .screenHeading()
            PinField(title: "Current PIN", pin: $current, isNew: false)
            PinField(title: "New PIN", pin: $new1, isNew: true)
            PinField(title: "Confirm new PIN", pin: $new2, isNew: true)
            if let problem {
                Text(problem).foregroundStyle(Color.errorInk).font(.footnote)
            }
            Button("Change PIN") {
                guard new1.count >= 4 else { problem = "New PIN must be 4–8 digits."; return }
                guard new1 == new2 else { problem = "The two new PINs do not match."; return }
                problem = nil
                Task { await model.changePin(current: current, new: new1) }
            }
            .primaryButton()
            .frame(maxWidth: .infinity, minHeight: 48)
            .disabled(model.busy || current.count < 4)
        }
        .onChange(of: problem) { msg in
            if let msg { UIAccessibility.post(notification: .announcement, argument: msg) }
        }
    }
}

/// Account deletion: removes the family, children, tasks and history for good.
struct DeleteFamilyView: View {
    @ObservedObject var model: AppModel
    @State private var pin = ""
    @State private var confirming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Delete family")
                .font(.title2.bold())
                .screenHeading()
            Text("This permanently deletes your family account, every child, all tasks, history and reports, on every device and on the web. It cannot be undone.")
            PinField(title: "Parent PIN", pin: $pin, isNew: false)
            Button("Delete everything", role: .destructive) { confirming = true }
                .primaryButton(.dangerFill)
                .frame(maxWidth: .infinity, minHeight: 48)
                .disabled(model.busy || pin.count < 4)
        }
        .confirmationDialog("Delete this family and all its data?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Delete forever", role: .destructive) {
                Task { await model.deleteFamily(pin: pin) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}

/// "See my day": the kid's celebration summary, like the web app's.
struct KidSummaryView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        if let board = model.board, let child = model.child {
            let c = board.counts
            let pct = min(max(c.percent, 0), 100)
            let stars = max(0, min(5, Int((Double(pct) / 20).rounded())))
            VStack(spacing: 14) {
                Text("\(child.firstName)'s Day")
                    .font(.title2.bold())
                    .screenHeading()
                Text(board.day).font(.footnote).foregroundStyle(Color.muted)

                ZStack {
                    Circle().stroke(Color(.systemGray5), lineWidth: 13)
                    Circle().trim(from: 0, to: CGFloat(pct) / 100)
                        .stroke(Color.brand, style: StrokeStyle(lineWidth: 13, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Text("\(pct)%").font(.title.bold())
                }
                .frame(width: 150, height: 150)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(pct) percent of steps done")

                Text(Self.cheer(pct)).font(.headline).multilineTextAlignment(.center)
                Text(String(repeating: "★", count: stars) + String(repeating: "☆", count: 5 - stars))
                    .font(.title2).foregroundStyle(.orange)
                    .accessibilityLabel("\(stars) out of 5 stars")

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    StatTile(value: "\(c.done)", label: "Finished")
                    StatTile(value: "\(c.total - c.done)", label: "Still to do")
                    if let ch = c.chores { StatTile(value: "\(ch.done)/\(ch.total)", label: "Chores") }
                    if let ac = c.activities { StatTile(value: "\(ac.done)/\(ac.total)", label: "Activities") }
                    if let st = c.steps, st.total > 0 { StatTile(value: "\(st.done)/\(st.total)", label: "Follow-up checks") }
                }

                VStack(alignment: .leading, spacing: 8) {
                    if board.tasks.isEmpty {
                        Text("Nothing on the list today.").foregroundStyle(Color.muted)
                    }
                    ForEach(board.tasks) { t in
                        let partial = !t.complete && t.done
                        let stepsTotal = 1 + t.subtasks.count
                        let stepsDone = (t.done ? 1 : 0) + t.subtasks.filter(\.done).count
                        let status = t.complete ? "Done" : (partial ? "\(stepsDone) of \(stepsTotal) steps done" : "Not yet")
                        HStack(alignment: .top) {
                            Text(t.complete ? "✅" : (partial ? "🟡" : "⬜")).accessibilityHidden(true)
                            Text(t.emoji).accessibilityHidden(true)
                            Text(t.title)
                            Spacer()
                            Text(status)
                                .font(.footnote.weight(.semibold)).foregroundStyle(Color.muted)
                                .multilineTextAlignment(.trailing)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(t.title), \(status)")
                        ForEach(t.subtasks) { s in
                            HStack {
                                Text(s.done ? "✅" : "⬜").accessibilityHidden(true)
                                Text(s.title).font(.subheadline)
                                Spacer()
                                Text(s.done ? "Done" : "Not yet")
                                    .font(.footnote).foregroundStyle(Color.muted)
                            }
                            .padding(.leading, 28)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel("Follow-up for \(t.title): \(s.title), \(s.done ? "done" : "not yet")")
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button("← Back to my chores") { model.back() }
                    .accessibilityLabel("Back to my chores")
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .frame(maxWidth: .infinity)
        } else {
            Text("Loading…")
        }
    }

    static func cheer(_ pct: Int) -> String {
        if pct >= 100 { return "Amazing! You finished everything! 🏆" }
        if pct >= 75 { return "Awesome work — almost there! 🌟" }
        if pct >= 50 { return "Great job, halfway and beyond! 💪" }
        if pct >= 25 { return "Good start — keep going! 🙌" }
        if pct > 0 { return "You've begun — you can do it! 👍" }
        return "Let's get started when you're ready! 🌱"
    }
}

private struct StatTile: View {
    let value: String
    let label: String
    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.bold())
            Text(label).font(.footnote).foregroundStyle(Color.muted)
        }
        .frame(maxWidth: .infinity, minHeight: 64)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}
