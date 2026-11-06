import Foundation
import Security

/// Fixed server + the parent bearer token (stored in the Keychain).
final class Session {
    /// The app always talks to this server (a cPanel subdomain with api.php at the root).
    static let defaultBase = "https://chores.a-t-365.com/api.php"

    var baseURL: String { Self.defaultBase }

    // In-memory cache as the source of truth for the current run, mirrored to the
    // Keychain for persistence across launches. The memory cache matters because an
    // *unsigned Simulator build* has no keychain entitlement, so `SecItemAdd` fails
    // (errSecMissingEntitlement) — without this the token would silently never persist.
    private var inMemoryToken: String?

    var token: String? {
        get { inMemoryToken ?? Keychain.read("parent_token") }
        set {
            inMemoryToken = newValue
            if let newValue { Keychain.save("parent_token", newValue) }
            else { Keychain.delete("parent_token") }
        }
    }
}

/// Minimal Keychain wrapper for a single generic-password item per key.
enum Keychain {
    private static func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "ChoreSlayer",
            kSecAttrAccount as String: key,
        ]
    }

    static func save(_ key: String, _ value: String) {
        var q = baseQuery(key)
        SecItemDelete(q as CFDictionary)
        q[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(q as CFDictionary, nil)
    }

    static func read(_ key: String) -> String? {
        var q = baseQuery(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ key: String) {
        SecItemDelete(baseQuery(key) as CFDictionary)
    }
}
