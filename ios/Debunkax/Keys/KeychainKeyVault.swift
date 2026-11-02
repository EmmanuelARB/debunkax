import DebunkaxCore
import Foundation
import Security

/// `KeyVault` on Keychain generic-password items. Keys are readable only while the device is
/// unlocked, never synced to iCloud and never migrated to another device. Keys are never logged.
final class KeychainKeyVault: KeyVault {
    static let service = "app.debunkax.keys"

    private let service: String

    init(service: String = KeychainKeyVault.service) {
        self.service = service
    }

    private func account(_ provider: Provider) -> String {
        "api_key_\(provider.rawValue.lowercased())"
    }

    private func baseQuery(_ provider: Provider) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(provider),
            kSecAttrSynchronizable as String: false,
        ]
    }

    func get(_ provider: Provider) async throws -> String? {
        var query = baseQuery(provider)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { return nil }
            return String(data: data, encoding: .utf8)
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError(status: status)
        }
    }

    func put(_ provider: Provider, key: String) async throws {
        let data = Data(key.utf8)
        // Replace whatever is there: delete, then add.
        let deleted = SecItemDelete(baseQuery(provider) as CFDictionary)
        guard deleted == errSecSuccess || deleted == errSecItemNotFound else {
            throw KeychainError(status: deleted)
        }
        var attributes = baseQuery(provider)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func remove(_ provider: Provider) async throws {
        let status = SecItemDelete(baseQuery(provider) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(status: status)
        }
    }
}

struct KeychainError: Error, LocalizedError {
    let status: OSStatus

    var errorDescription: String? { "Trousseau: erreur \(status)" }
}
