package de.pigcloud.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Window;

import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Theme")
public class ThemePlugin extends Plugin {

    static final String PREFS = "pigcloud_appearance";
    static final String KEY_MODE = "mode";
    static final String KEY_BASE = "base";
    static final String KEY_BACKGROUND = "background";
    static final String MODE_FIXED = "fixed";
    static final String BASE_LIGHT = "light";
    static final String BASE_DARK = "dark";

    static String launchBase(Context context) {
        SharedPreferences stored = prefs(context);
        if (!MODE_FIXED.equals(stored.getString(KEY_MODE, null))) {
            return null;
        }
        String base = stored.getString(KEY_BASE, null);
        if (BASE_LIGHT.equals(base)) {
            return BASE_LIGHT;
        }
        if (BASE_DARK.equals(base)) {
            return BASE_DARK;
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int parseColor(String value, int fallback) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
            return fallback;
        }
        try {
            return Color.parseColor(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    @PluginMethod
    public void set(PluginCall call) {
        String base = call.getString("base");
        String background = call.getString("background");
        if (!BASE_LIGHT.equals(base) && !BASE_DARK.equals(base)) {
            call.reject("unknown theme base");
            return;
        }
        if (background == null || !background.matches("#[0-9a-fA-F]{6}")) {
            call.reject("invalid background colour");
            return;
        }
        String mode = MODE_FIXED.equals(call.getString("mode")) ? MODE_FIXED : "auto";
        prefs(getContext()).edit().putString(KEY_MODE, mode).putString(KEY_BASE, base)
            .putString(KEY_BACKGROUND, background).apply();
        applySystemBars(base, parseColor(call.getString("navigation"), parseColor(background, Color.BLACK)));
        JSObject result = new JSObject();
        result.put("base", base);
        call.resolve(result);
    }

    private void applySystemBars(String base, int navigation) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        boolean lightBars = BASE_LIGHT.equals(base);
        activity.runOnUiThread(() -> {
            Window window = activity.getWindow();
            window.setNavigationBarColor(navigation);
            WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(window, window.getDecorView());
            controller.setAppearanceLightNavigationBars(lightBars);
            controller.setAppearanceLightStatusBars(lightBars);
        });
    }
}
