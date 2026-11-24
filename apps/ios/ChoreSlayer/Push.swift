import SwiftUI
import UIKit
import UserNotifications

/// Receives the APNs device token and shows notifications while the app is open.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // Refresh the token on every launch if notifications were already allowed.
        Task { await Push.shared.refreshIfAllowed() }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Push.shared.received(token: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Push.shared.failed(error)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
}

/// Push permission and the APNs token for this device. What the device should
/// receive (a kid's reminders, parent alerts) is remembered here too, so a new
/// token can be registered again with the same choices.
@MainActor
final class Push {
    static let shared = Push()

    private var waiters: [CheckedContinuation<String?, Never>] = []

    /// Called with (newToken, oldToken) whenever iOS hands out a different token.
    var onTokenChange: ((String, String?) -> Void)?

    var token: String? {
        get { UserDefaults.standard.string(forKey: "push_token") }
        set { UserDefaults.standard.set(newValue, forKey: "push_token") }
    }
    /// The kid whose reminders this device gets (nil = none).
    var childId: String? {
        get { UserDefaults.standard.string(forKey: "push_child") }
        set { UserDefaults.standard.set(newValue, forKey: "push_child") }
    }
    var parentAlerts: Bool {
        get { UserDefaults.standard.bool(forKey: "push_parent") }
        set { UserDefaults.standard.set(newValue, forKey: "push_parent") }
    }

    /// Debug builds from Xcode get sandbox tokens; TestFlight and the App Store get production ones.
    nonisolated static var sandbox: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// Ask for permission (shows the iOS prompt the first time) and return the
    /// device token, or nil if the person said no.
    func requestToken() async -> String? {
        let granted = (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        guard granted else { return nil }
        return await fetchToken()
    }

    func refreshIfAllowed() async {
        let s = await authorizationStatus()
        if s == .authorized || s == .provisional || s == .ephemeral {
            _ = await fetchToken()
        }
    }

    private func fetchToken() async -> String? {
        await withCheckedContinuation { (c: CheckedContinuation<String?, Never>) in
            waiters.append(c)
            UIApplication.shared.registerForRemoteNotifications()
            // Don't wait forever (no network, simulator without push, …).
            Task {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                self.resolve(self.token)
            }
        }
    }

    nonisolated func received(token new: String) {
        Task { @MainActor in
            let old = self.token
            self.token = new
            if old != new { self.onTokenChange?(new, old) }
            self.resolve(new)
        }
    }

    nonisolated func failed(_ error: Error) {
        Task { @MainActor in self.resolve(self.token) }
    }

    private func resolve(_ value: String?) {
        let w = waiters
        waiters = []
        w.forEach { $0.resume(returning: value) }
    }

    /// Forget everything (device unlinked from its family).
    func clearChoices() {
        childId = nil
        parentAlerts = false
    }
}

/// Kid board card: turn on chore reminders for this kid on this device.
struct KidReminderCard: View {
    @ObservedObject var model: AppModel

    var body: some View {
        if model.kidRemindersOn {
            Label("Reminders are on for this device.", systemImage: "bell.fill")
                .font(.footnote)
                .foregroundStyle(Color.muted)
                .frame(maxWidth: .infinity)
                .padding(.top, 4)
        } else if model.pushAllowed != false {
            VStack(alignment: .leading, spacing: 8) {
                Text("Chore reminders").font(.headline).accessibilityAddTraits(.isHeader)
                Text("Get a reminder on this device when each chore is due.")
                    .font(.subheadline)
                Button {
                    Task { await model.enableKidReminders() }
                } label: {
                    Label("Remind me when it's time", systemImage: "bell")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy)
            }
            .padding(14)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
            .padding(.top, 8)
        }
    }
}

/// Parent options → Notifications.
struct NotificationsView: View {
    @ObservedObject var model: AppModel
    @State private var parentAlerts = Push.shared.parentAlerts

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Notifications")
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
            Text("Get alerts on this device:")
            VStack(alignment: .leading, spacing: 6) {
                Text("• when a kid finishes everything on their list for the day")
                Text("• once in the evening if chores are still left to do")
            }
            Toggle("Send me parent alerts", isOn: $parentAlerts)
                .font(.headline)
                .frame(minHeight: 44)
            Text("Kids turn on their own chore reminders from their board after they sign in.")
                .font(.footnote)
                .foregroundStyle(Color.muted)
            if model.pushAllowed == false {
                Text("Notifications are turned off for Chore Slayer. Turn them on in the Settings app first.")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.red)
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
            Button("Save") { Task { await model.saveParentAlerts(parentAlerts) } }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, minHeight: 48)
                .disabled(model.busy)
        }
        .task { await model.refreshPushAllowed() }
    }
}
