package de.pigcloud.app;

import android.os.Bundle;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaSaverPlugin.class);
        super.onCreate(savedInstanceState);
        enableWebAuthentication();
    }

    private void enableWebAuthentication() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            return;
        }
        WebSettingsCompat.setWebAuthenticationSupport(
            getBridge().getWebView().getSettings(),
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
        );
    }
}
