import SwiftUI
import Combine
import UIKit

struct RootView: View {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showFamilyCode = false
    // Checked often so the one-minute warning and the lock land on time.
    private let idleTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
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
                    .id(Self.topID)
                }
                // Each screen starts at the top, and VoiceOver hears that the screen changed.
                .onChange(of: model.screen) { _ in
                    proxy.scrollTo(Self.topID, anchor: .top)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        UIAccessibility.post(notification: .screenChanged, argument: nil)
                    }
                }
            }
            // Any tap counts as parent activity for the 10-minute idle lock.
            // (Typing and VoiceOver focus are counted in AppModel.)
            .simultaneousGesture(TapGesture().onEnded { model.markParentActivity() })
            // VoiceOver two-finger scrub goes back, like the Back button.
            .accessibilityAction(.escape) { if showsBack { model.back() } }
            .navigationTitle(screenTitle)
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
                            Button("Family code") { model.markParentActivity(); showFamilyCode = true }
                            Button("Notifications") { model.openNotifications() }
                            Button("Change PIN") { model.openChangePin() }
                            Button("Delete family…", role: .destructive) { model.openDeleteFamily() }
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
        .alert("Still there?", isPresented: Binding(
            get: { model.idleWarning },
            set: { if !$0 && model.idleWarning { model.stayParentSignedIn() } })) {
            Button("Stay signed in") { model.stayParentSignedIn() }
        } message: {
            Text("The parent area locks in 60 seconds.")
        }
        .onReceive(idleTimer) { _ in model.checkParentIdle() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.checkParentIdle() }
        }
        .onChange(of: model.notice) { newValue in
            if let msg = newValue { Announcer.say(msg, after: statusDelay) }
        }
        .onChange(of: model.error) { newValue in
            // Announce error status messages to VoiceOver (WCAG 4.1.3).
            if let msg = newValue { Announcer.say("Error: \(msg)", after: statusDelay) }
        }
    }

    private static let topID = "screen-top"

    /// Right after a screen change, wait until the new screen's heading has been read.
    private var statusDelay: Double {
        Date().timeIntervalSince(model.screenChangedAt) < 1 ? 1.2 : 0.1
    }

    /// The navigation bar title for the current screen.
    private var screenTitle: String {
        switch model.screen {
        case .connecting, .role: return "Chore Slayer"
        case .connectDevice: return "Welcome"
        case .kidLogin: return "Kid sign-in"
        case .kidBoard: return model.child.map { "\($0.firstName)'s chores" } ?? "Chores"
        case .kidSummary: return "My day"
        case .changePin: return "Change PIN"
        case .deleteFamily: return "Delete family"
        case .notifications: return "Notifications"
        case .parentLogin: return "Parent sign-in"
        case .parentHome: return "Children"
        case .childHub: return model.managedChild?.firstName ?? "Child"
        case .childEditor: return model.editingChild == nil ? "Add a child" : "Edit child"
        case .tasks: return "Tasks"
        case .taskEditor: return model.editingTask == nil ? "Add task" : "Edit task"
        case .dailyReport: return "Daily report"
        case .bhis: return "BHIS report"
        case .adjustDay: return "Adjust a day"
        case .overrideEditor: return "Adjust for one day"
        }
    }

    @ViewBuilder private var screenContent: some View {
        switch model.screen {
        case .connecting: ConnectingView(model: model)
        case .connectDevice: ConnectDeviceView(model: model)
        case .kidSummary: KidSummaryView(model: model)
        case .changePin: ChangePinView(model: model)
        case .deleteFamily: DeleteFamilyView(model: model)
        case .notifications: NotificationsView(model: model)
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
