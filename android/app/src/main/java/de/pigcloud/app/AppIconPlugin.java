package de.pigcloud.app;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "AppIcon")
public class AppIconPlugin extends Plugin {

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("tinted", ".LauncherLight");
        ALIASES.put("light", ".LauncherLight");
        ALIASES.put("dark", ".LauncherDark");
        ALIASES.put("mocha", ".LauncherMocha");
        ALIASES.put("piggy", ".LauncherPiggy");
    }

    @PluginMethod
    public void select(PluginCall call) {
        String set = call.getString("set");
        String wanted = set == null ? null : ALIASES.get(set);
        if (wanted == null) {
            call.reject("unknown icon set");
            return;
        }
        String packageName = getContext().getPackageName();
        PackageManager packageManager = getContext().getPackageManager();
        java.util.List<String> ordered = new java.util.ArrayList<>();
        ordered.add(wanted);
        for (String alias : ALIASES.values()) {
            if (!alias.equals(wanted) && !ordered.contains(alias)) {
                ordered.add(alias);
            }
        }
        boolean changed = false;
        for (String alias : ordered) {
            int state = alias.equals(wanted)
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            ComponentName component = new ComponentName(packageName, packageName + alias);
            if (packageManager.getComponentEnabledSetting(component) == state) {
                continue;
            }
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
            changed = true;
        }
        com.getcapacitor.JSObject result = new com.getcapacitor.JSObject();
        result.put("set", set);
        result.put("changed", changed);
        call.resolve(result);
    }
}
