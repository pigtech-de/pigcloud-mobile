package de.pigcloud.app;

import android.os.Bundle;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static volatile boolean foreground = false;

    static boolean isInForeground() {
        return foreground;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaSaverPlugin.class);
        registerPlugin(ShareReceiverPlugin.class);
        registerPlugin(PushPlugin.class);
        super.onCreate(savedInstanceState);
        enableWebAuthentication();
    }

    @Override
    public void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override
    public void onPause() {
        foreground = false;
        super.onPause();
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
