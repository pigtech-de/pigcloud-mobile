package de.pigcloud.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

@CapacitorPlugin(
    name = "Push",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class PushPlugin extends Plugin {

    static final String EXTRA_ROUTE = "de.pigcloud.app.route";
    private static final String NOTIFICATIONS = "notifications";
    private static final String EVENT_NOTIFICATION_OPENED = "notificationOpened";

    private String pendingRoute;

    @Override
    public void load() {
        pendingRoute = routeFrom(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String route = routeFrom(intent);
        if (route == null) {
            return;
        }
        JSObject data = new JSObject();
        data.put("route", route);
        notifyListeners(EVENT_NOTIFICATION_OPENED, data, true);
    }

    private static String routeFrom(Intent intent) {
        if (intent == null) {
            return null;
        }
        String route = intent.getStringExtra(EXTRA_ROUTE);
        if (route != null) {
            intent.removeExtra(EXTRA_ROUTE);
        }
        return route;
    }

    @PluginMethod
    public void pendingRoute(PluginCall call) {
        JSObject result = new JSObject();
        result.put("route", pendingRoute);
        pendingRoute = null;
        call.resolve(result);
    }

    @PluginMethod
    public void register(PluginCall call) {
        if (FirebaseApp.getApps(getContext()).isEmpty()) {
            JSObject result = new JSObject();
            result.put("available", false);
            call.resolve(result);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState(NOTIFICATIONS) != PermissionState.GRANTED) {
            requestPermissionForAlias(NOTIFICATIONS, call, "notificationsCallback");
            return;
        }
        fetchToken(call);
    }

    @PermissionCallback
    private void notificationsCallback(PluginCall call) {
        if (getPermissionState(NOTIFICATIONS) != PermissionState.GRANTED) {
            call.reject("Notifications were denied", "permission_denied");
            return;
        }
        fetchToken(call);
    }

    private void fetchToken(PluginCall call) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            String token = task.isSuccessful() ? task.getResult() : null;
            if (token == null || token.isEmpty()) {
                call.reject("Could not get a push token", "token_failed", task.getException());
                return;
            }
            JSObject result = new JSObject();
            result.put("available", true);
            result.put("token", token);
            call.resolve(result);
        });
    }
}
