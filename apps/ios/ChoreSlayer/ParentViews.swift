import SwiftUI

struct ParentLoginView: View {
    @ObservedObject var model: AppModel
    @State private var pin = ""
    var body: some View {
        VStack(spacing: 12) {
            Text("Parent sign-in")
                .font(.title2.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            SecureField("PIN", text: $pin)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)
                .onChange(of: pin) { newValue in
                    let digits = newValue.filter(\.isNumber)
                    if digits != newValue || digits.count > 8 {
                        pin = String(digits.prefix(8))
                    }
                }
            Button("Sign in") { Task { await model.parentLogin(pin: pin) } }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, minHeight: 48)
                .disabled(model.busy || pin.count < 4)
        }
    }
}

struct ChildrenView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        VStack(spacing: 12) {
            Text("Children")
                .font(.title2.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            Button { model.addChildStart() } label: {
                HStack { Text("➕").accessibilityHidden(true); Text("Add a child"); Spacer() }
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityLabel("Add a child")
            if model.children.isEmpty && !model.busy {
                Text("No children yet.").foregroundStyle(Color.muted)
            }
            ForEach(model.children) { child in
                Button { model.openChildHub(child) } label: {
                    HStack {
                        Text(child.emoji).font(.title).accessibilityHidden(true)
                        VStack(alignment: .leading) {
                            Text("\(child.firstName) \(child.lastName)").font(.headline)
                            Text("Tap to manage").font(.footnote).foregroundStyle(Color.muted)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Color.muted).accessibilityHidden(true)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(child.firstName) \(child.lastName)")
                .accessibilityHint("Opens management options")
                .accessibilityAddTraits(.isButton)
            }
        }
    }
}

struct ChildHubView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        VStack(spacing: 12) {
            if let c = model.managedChild {
                Text("\(c.emoji) \(c.firstName) \(c.lastName)")
                    .font(.title2.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel("\(c.firstName) \(c.lastName)")
                    .accessibilityAddTraits(.isHeader)
                HubButton(emoji: "📋", title: "Today's board") {
                    if let c = model.managedChild { Task { await model.openBoard(c) } }
                }
                HubButton(emoji: "✅", title: "Tasks & activities") { Task { await model.openTasks() } }
                HubButton(emoji: "📝", title: "Daily report") { Task { await model.openReport() } }
                HubButton(emoji: "📊", title: "BHIS report") { model.openBhis() }
                HubButton(emoji: "📅", title: "Adjust a day") { Task { await model.openAdjustDay() } }
                HubButton(emoji: "✏️", title: "Edit child") { model.editChildStart() }
            }
        }
    }
}

struct HubButton: View {
    let emoji: String
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Text(emoji).accessibilityHidden(true)
                Text(title).font(.body.weight(.semibold))
                Spacer()
            }
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, 4)
        }
        .buttonStyle(.borderedProminent)
        .accessibilityLabel(title)
    }
}
