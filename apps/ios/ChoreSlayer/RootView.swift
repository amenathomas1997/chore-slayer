import SwiftUI
import UIKit

struct RootView: View {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showFamilyCode = false
    private let idleTimer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let err = model.error {
                        ErrorBanner(message: err) { model.error = nil }
                    }
                    if let note = model.notice {
                        NoticeBanner(message: note) { model.notice = nil }
                    }
                    if model.busy {
                        HStack {
                            Spacer()
                            ProgressView().accessibilityLabel("Loading")
                            Spacer()
                        }
                    }
                    screenContent
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // Any tap counts as parent activity for the 10-minute idle lock.
            .simultaneousGesture(TapGesture().onEnded { model.markParentActivity() })
            .navigationTitle("Chore Slayer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if showsBack {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { model.back() } label: {
                            Image(systemName: "chevron.backward")
                        }
                        .accessibilityLabel("Back")
                    }
                }
                if model.isParent {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("🧒 Kid sign-in") { model.handToKid() }
                            .accessibilityLabel("Kid sign-in: sign the parent out and let a kid sign in")
                        Menu {
                            Button("Family code") { showFamilyCode = true }
                            Button("Change PIN") { model.openChangePin() }
                            Button("Log out") { model.logout() }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                        .accessibilityLabel("Parent options")
                    }
                } else if model.screen == .kidBoard || model.screen == .kidSummary {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Switch user") { model.kidSwitchUser() }
                            .accessibilityLabel("Switch user or log out")
                    }
                }
            }
        }
        .tint(.brand)
        .task {
            if model.statusInfo == nil { await model.connect() }
        }
        .sheet(isPresented: $showFamilyCode) {
            FamilyCodeSheet(code: model.family?.code ?? "—") { showFamilyCode = false }
        }
        .sheet(item: Binding(
            get: { model.newFamilyCode.map { IdentifiedCode(code: $0) } },
            set: { if $0 == nil { model.newFamilyCode = nil } })) { item in
            FamilyCodeSheet(code: item.code) { model.newFamilyCode = nil }
                .interactiveDismissDisabled()
        }
        .onReceive(idleTimer) { _ in model.checkParentIdle() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.checkParentIdle() }
        }
        .onChange(of: model.notice) { newValue in
            if let msg = newValue {
                UIAccessibility.post(notification: .announcement, argument: msg)
            }
        }
        .onChange(of: model.error) { newValue in
            // Announce error status messages to VoiceOver (WCAG 4.1.3).
            if let msg = newValue {
                UIAccessibility.post(notification: .announcement, argument: "Error: \(msg)")
            }
        }
    }

    @ViewBuilder private var screenContent: some View {
        switch model.screen {
        case .connecting: ConnectingView(model: model)
        case .connectDevice: ConnectDeviceView(model: model)
        case .kidSummary: KidSummaryView(model: model)
        case .changePin: ChangePinView(model: model)
        case .role: RoleView(model: model)
        case .kidLogin: KidLoginView(model: model)
        case .kidBoard: BoardView(model: model)
        case .parentLogin: ParentLoginView(model: model)
        case .parentHome: ChildrenView(model: model)
        case .childHub: ChildHubView(model: model)
        case .childEditor: ChildEditorView(model: model)
        case .tasks: TasksView(model: model)
        case .taskEditor: TaskEditorView(model: model)
        case .dailyReport: DailyReportView(model: model)
        case .bhis: BhisView(model: model)
        case .adjustDay: AdjustDayView(model: model)
        case .overrideEditor: OverrideEditorView(model: model)
        }
    }

    private var showsBack: Bool {
        switch model.screen {
        case .connecting, .connectDevice, .role: return false
        case .kidLogin, .parentLogin: return true
        case .parentHome: return false
        default: return true
        }
    }
}

private struct IdentifiedCode: Identifiable {
    let code: String
    var id: String { code }
}

struct NoticeBanner: View {
    let message: String
    let onDismiss: () -> Void
    var body: some View {
        HStack(alignment: .top) {
            Text(message)
            Spacer()
            Button("Dismiss", action: onDismiss)
        }
        .padding(12)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

struct ErrorBanner: View {
    let message: String
    let onDismiss: () -> Void
    var body: some View {
        HStack(alignment: .top) {
            Text("Error: \(message)").foregroundStyle(Color.errorInk)
            Spacer()
            Button("Dismiss", action: onDismiss)
        }
        .padding(12)
        .background(Color.errorBg)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}
