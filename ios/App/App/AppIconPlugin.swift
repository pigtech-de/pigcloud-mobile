import Foundation
import UIKit
import Capacitor

@objc(AppIconPlugin)
public class AppIconPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AppIconPlugin"
    public let jsName = "AppIcon"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "select", returnType: CAPPluginReturnPromise)
    ]

    private static let alternates: [String: String?] = [
        "tinted": nil,
        "light": "AppIcon-Light",
        "dark": "AppIcon-Dark",
        "mocha": "AppIcon-Mocha",
        "piggy": "AppIcon-Piggy"
    ]

    @objc func select(_ call: CAPPluginCall) {
        guard let set = call.getString("set"), let target = AppIconPlugin.alternates[set] else {
            call.reject("unknown icon set")
            return
        }
        DispatchQueue.main.async {
            guard UIApplication.shared.supportsAlternateIcons else {
                call.reject("alternate icons unavailable")
                return
            }
            if UIApplication.shared.alternateIconName == target {
                call.resolve(["set": set, "changed": false])
                return
            }
            UIApplication.shared.setAlternateIconName(target) { error in
                if let error = error {
                    call.reject(error.localizedDescription)
                } else {
                    call.resolve(["set": set, "changed": true])
                }
            }
        }
    }
}
