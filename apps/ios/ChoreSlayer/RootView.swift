import SwiftUI
import UIKit

struct RootView: View {
    @StateObject private var model = AppModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let err = model.error {
                        ErrorBanner(message: err) { model.error = nil }
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
                if model.isParent && model.screen != .role {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Log out") { model.logout() }
                    }
                }
            }
        }
        .tint(.brand)
        .task {
            if model.statusInfo == nil { await model.connect() }
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
        case .connecting, .role: return false
        default: return true
        }
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
