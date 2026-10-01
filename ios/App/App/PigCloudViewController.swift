import UIKit
import Capacitor

class PigCloudViewController: CAPBridgeViewController {

    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(MediaSaverPlugin())
        bridge?.registerPluginInstance(KeyVaultPlugin())
        bridge?.registerPluginInstance(AppIconPlugin())
        bridge?.registerPluginInstance(ThemePlugin())
    }
}
