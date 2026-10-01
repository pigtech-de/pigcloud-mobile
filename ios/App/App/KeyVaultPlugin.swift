import Foundation
import UIKit
import Capacitor
import LocalAuthentication
import Security

@objc(KeyVaultPlugin)
public class KeyVaultPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "KeyVaultPlugin"
    public let jsName = "KeyVault"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "store", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasVault", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unlock", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clear", returnType: CAPPluginReturnPromise)
    ]

    private let service = "de.pigcloud.app.applock"
    private let account = "e2ee-private-v1"
    private var locked = true
    private var storeToken: String?

    private func mintStoreToken() -> String {
        var raw = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, raw.count, &raw)
        let token = Data(raw).base64EncodedString()
        storeToken = token
        return token
    }

    private func consumeStoreToken(_ offered: String?) -> Bool {
        guard let expected = storeToken, let offered = offered else { return false }
        guard expected.count == offered.count else { return false }
        var diff: UInt8 = 0
        for (a, b) in zip(expected.utf8, offered.utf8) { diff |= a ^ b }
        if diff == 0 {
            storeToken = nil
            return true
        }
        return false
    }

    override public func load() {
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(raiseLock),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(raiseLock),
            name: UIScene.didEnterBackgroundNotification,
            object: nil
        )
    }

    @objc private func raiseLock() {
        locked = true
    }

    private var promptText: String {
        NSLocalizedString(
            "app_lock_prompt_subtitle",
            tableName: nil,
            bundle: .main,
            value: "Unlock to reach your files",
            comment: "Face ID prompt shown when the app lock is raised"
        )
    }

    @objc func isAvailable(_ call: CAPPluginCall) {
        let context = LAContext()
        var error: NSError?
        let can = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        call.resolve(["available": can, "strongBox": true, "hasVault": itemExists()])
    }

    @objc func store(_ call: CAPPluginCall) {
        guard let data = call.getString("data"), let bytes = data.data(using: .utf8) else {
            call.reject("bad_input")
            return
        }
        if !consumeStoreToken(call.getString("token")) {
            call.reject("store_not_allowed")
            return
        }
        var acError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .biometryCurrentSet,
            &acError
        ) else {
            call.reject("unavailable")
            return
        }

        deleteItem()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: bytes,
            kSecAttrAccessControl as String: access
        ]
        if SecItemAdd(query as CFDictionary, nil) == errSecSuccess {
            call.resolve()
        } else {
            call.reject("store_failed")
        }
    }

    @objc func hasVault(_ call: CAPPluginCall) {
        let present = itemExists()
        var result: [String: Any] = ["present": present, "locked": locked]
        if !present {
            result["storeToken"] = mintStoreToken()
        }
        call.resolve(result)
    }

    @objc func unlock(_ call: CAPPluginCall) {
        guard locked else {
            call.reject("not_locked")
            return
        }
        let context = LAContext()
        context.localizedFallbackTitle = ""
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecUseAuthenticationContext as String: context,
            kSecUseOperationPrompt as String: promptText
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data, let text = String(data: data, encoding: .utf8) else {
                call.reject("unwrap_failed")
                return
            }
            locked = false
            call.resolve(["data": text, "storeToken": mintStoreToken()])
        case errSecItemNotFound:
            call.reject("no_key")
        case errSecUserCanceled:
            call.reject("cancelled")
        case errSecAuthFailed, errSecInteractionNotAllowed:
            deleteItem()
            call.reject("key_invalidated")
        default:
            call.reject("unwrap_failed")
        }
    }

    @objc func clear(_ call: CAPPluginCall) {
        deleteItem()
        storeToken = nil
        call.resolve()
    }

    private func itemExists() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: false,
            kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail
        ]
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    private func deleteItem() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
