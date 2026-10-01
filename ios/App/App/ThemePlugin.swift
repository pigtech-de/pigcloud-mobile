import Foundation
import UIKit
import Capacitor

@objc(ThemePlugin)
public class ThemePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ThemePlugin"
    public let jsName = "Theme"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "set", returnType: CAPPluginReturnPromise)
    ]

    static let modeKey = "pigcloud_theme_mode"
    static let baseKey = "pigcloud_theme_base"
    static let backgroundKey = "pigcloud_theme_background"
    static let lightGround = color(from: "#F1F2F8")!
    static let darkGround = color(from: "#121212")!

    static func storedStyle() -> UIUserInterfaceStyle {
        guard UserDefaults.standard.string(forKey: modeKey) == "fixed" else { return .unspecified }
        switch UserDefaults.standard.string(forKey: baseKey) {
        case "light": return .light
        case "dark": return .dark
        default: return .unspecified
        }
    }

    static func storedBackground() -> UIColor {
        if storedStyle() != .unspecified, let fixed = color(from: UserDefaults.standard.string(forKey: backgroundKey)) {
            return fixed
        }
        return UIColor { traits in traits.userInterfaceStyle == .dark ? darkGround : lightGround }
    }

    static func color(from hex: String?) -> UIColor? {
        guard let hex = hex, hex.count == 7, hex.hasPrefix("#"),
              let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }

    @objc func set(_ call: CAPPluginCall) {
        let base = call.getString("base") ?? ""
        let background = call.getString("background")
        guard base == "light" || base == "dark", ThemePlugin.color(from: background) != nil else {
            call.reject("invalid theme report")
            return
        }
        UserDefaults.standard.set(call.getString("mode") == "fixed" ? "fixed" : "auto", forKey: ThemePlugin.modeKey)
        UserDefaults.standard.set(base, forKey: ThemePlugin.baseKey)
        UserDefaults.standard.set(background, forKey: ThemePlugin.backgroundKey)
        DispatchQueue.main.async {
            let window = self.bridge?.viewController?.view.window
            window?.overrideUserInterfaceStyle = ThemePlugin.storedStyle()
            window?.backgroundColor = ThemePlugin.storedBackground()
            call.resolve(["base": base])
        }
    }
}
