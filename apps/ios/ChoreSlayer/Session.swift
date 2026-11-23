import Foundation
import Security

/// Fixed server, the family this device is linked to, and the parent sign-in.
final class Session {
    /// The app always talks to this server (a cPanel subdomain with api.php at the root).
    static let defaultBase = "https://chores.a-t-365.com/api.php"

    var baseURL: String {
        #if DEBUG
        // Debug builds only: point at a test server with the CHORE_API_BASE
        // environment variable. Release / TestFlight builds always use the live server.
        if let override = ProcessInfo.processInfo.environment["CHORE_API_BASE"], !override.isEmpty {
            return override
        }
        #endif
        return Self.defaultBase
    }

    init() {
        // Older builds kept the parent token in the Keychain across launches.
        // The parent sign-in now ends when the app closes, so remove any old copy.
        Keychain.delete("parent_token")
    }

    /// The parent sign-in lives only in memory, so closing the app (or the
    /// 10-minute idle lock) ends it and a kid can't open the app into the
    /// parent area later.
    var token: String?

    /// The linked family is remembered across launches. It is not secret on its
    /// own: parent sign-in still needs the PIN.
    var family: Family? {
        get {
            guard let data = UserDefaults.standard.data(forKey: "family") else { return nil }
            return try? JSONDecoder().decode(Family.self, from: data)
        }
        set {
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: "family")
            } else {
                UserDefaults.standard.removeObject(forKey: "family")
            }
        }
    }
}

/// Minimal Keychain wrapper, kept only to clean up items older builds saved.
enum Keychain {
    private static func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "ChoreSlayer",
            kSecAttrAccount as String: key,
        ]
    }

    static func delete(_ key: String) {
        SecItemDelete(baseQuery(key) as CFDictionary)
    }
}
