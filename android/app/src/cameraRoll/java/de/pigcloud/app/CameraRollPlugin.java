package de.pigcloud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;
import de.pigcloud.bind.mobile.Enrolment;
import de.pigcloud.bind.mobile.Mobile;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "CameraRoll",
    permissions = {
        @Permission(alias = CameraRollPlugin.ALIAS_IMAGES, strings = { CameraRollPolicy.READ_MEDIA_IMAGES }),
        @Permission(alias = CameraRollPlugin.ALIAS_VIDEO, strings = { CameraRollPolicy.READ_MEDIA_VIDEO }),
        @Permission(alias = CameraRollPlugin.ALIAS_USER_SELECTED, strings = { CameraRollPolicy.READ_MEDIA_VISUAL_USER_SELECTED }),
        @Permission(alias = CameraRollPlugin.ALIAS_LEGACY, strings = { CameraRollPolicy.READ_EXTERNAL_STORAGE }),
        @Permission(alias = CameraRollPlugin.ALIAS_LOCATION, strings = { CameraRollPolicy.ACCESS_MEDIA_LOCATION }),
    }
)
public class CameraRollPlugin extends Plugin {

    static final String ALIAS_IMAGES = "mediaImages";
    static final String ALIAS_VIDEO = "mediaVideo";
    static final String ALIAS_USER_SELECTED = "mediaUserSelected";
    static final String ALIAS_LEGACY = "mediaLegacy";
    static final String ALIAS_LOCATION = "mediaLocation";

    static final String PREFS = "pigcloud_camera_roll";
    static final String PREF_UPLOAD_ENABLED = "upload_enabled";
    static final String PREF_ENDPOINT = "endpoint";
    static final String PREF_ACCOUNT = "account";
    static final String PREF_API_KEY_ID = "api_key_id";
    static final String PREF_KEY_EPOCH = "key_epoch";
    static final String PREF_STATE = "state";
    static final String PREF_PAUSE_REASON = "pause_reason";
    static final String PREF_REVOCATION_STRIKE_AT = "revocation_strike_at";
    static final String PREF_ATTEMPTS = "wake_attempts";
    static final String PREF_LAST_CHECK_AT = "last_check_at";
    private static final long DEFAULT_ENROL_TIMEOUT_SECONDS = 900;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private BackgroundKeyStore keys;
    private volatile Enrolment enrolment;

    @Override
    public void load() {
        keys = new BackgroundKeyStore(getContext());
        SharedPreferences prefs = prefs();
        if (prefs.getBoolean(PREF_UPLOAD_ENABLED, false)
            && isEnrolled(prefs.getString(PREF_ENDPOINT, ""), prefs.getString(PREF_ACCOUNT, ""))) {
            CameraRollWakeWorker.schedule(getContext(), 0L);
            CameraRollUploadWorker.ensurePeriodic(getContext());
        }
    }

    @PluginMethod
    public void capabilities(PluginCall call) {
        SharedPreferences prefs = prefs();
        String account = prefs.getString(PREF_ACCOUNT, "");
        String endpoint = prefs.getString(PREF_ENDPOINT, "");

        JSObject result = new JSObject();
        result.put("cameraRoll", true);
        result.put("platform", "capacitor");
        result.put("host", "capacitor");
        result.put("backgroundKeys", true);
        result.put("biometricGate", false);
        result.put("oneWay", true);
        result.put("wifiOnly", true);
        result.put("chargingOnly", true);
        result.put("queue", true);
        result.put("pairs", false);
        result.put("enrolled", isEnrolled(endpoint, account));
        result.put("account", account);
        result.put("keyEpoch", prefs.getLong(PREF_KEY_EPOCH, 0));
        result.put("uploadEnabled", prefs.getBoolean(PREF_UPLOAD_ENABLED, false));
        call.resolve(result);
    }

    @PluginMethod
    public void status(PluginCall call) {
        SharedPreferences prefs = prefs();
        String account = prefs.getString(PREF_ACCOUNT, "");
        String endpoint = prefs.getString(PREF_ENDPOINT, "");
        boolean enrolled = isEnrolled(endpoint, account);
        boolean uploadEnabled = enrolled && prefs.getBoolean(PREF_UPLOAD_ENABLED, false);
        String stored = prefs.getString(PREF_STATE, "");
        String state;
        if (!enrolled) {
            state = CameraRollWakeWorker.STATE_REENROL_NEEDED.equals(stored) ? CameraRollWakeWorker.STATE_REENROL_NEEDED : "notEnrolled";
        } else if (uploadEnabled && CameraRollWakeWorker.STATE_PAUSED.equals(stored)) {
            state = CameraRollWakeWorker.STATE_PAUSED;
        } else {
            state = CameraRollWakeWorker.STATE_IDLE;
        }

        JSObject result = new JSObject();
        result.put("enrolled", enrolled);
        result.put("uploadEnabled", uploadEnabled);
        result.put("account", account);
        result.put("apiKeyId", prefs.getString(PREF_API_KEY_ID, ""));
        result.put("keyEpoch", prefs.getLong(PREF_KEY_EPOCH, 0));
        result.put("state", state);
        result.put("pauseReason", prefs.getString(PREF_PAUSE_REASON, ""));
        result.put("lastCheckedAt", prefs.getLong(PREF_LAST_CHECK_AT, 0L));

        CameraRollAssetStore assets = CameraRollAssetStore.get(getContext());
        CameraRollAssetStore.Counts counts = assets.counts();
        JSObject countsJson = new JSObject();
        countsJson.put("pending", counts.pending);
        countsJson.put("uploaded", counts.uploaded);
        countsJson.put("failed", counts.failed);
        result.put("counts", countsJson);
        result.put("pendingBytes", assets.pendingBytes());
        result.put("measuredBytesPerSecond", prefs.getLong(CameraRollUploadWorker.PREF_MEASURED_RATE, 0L));
        result.put("uploadsPerHour", CameraRollPolicy.PACE_UPLOADS_PER_HOUR);
        result.put("rateLimitedUntil", prefs.getLong(CameraRollUploadWorker.PREF_RATE_LIMITED_UNTIL, 0L));
        result.put("queueState", prefs.getString(CameraRollUploadWorker.PREF_QUEUE_STATE, CameraRollUploadWorker.QUEUE_IDLE));
        result.put("queueReason", prefs.getString(CameraRollUploadWorker.PREF_QUEUE_REASON, ""));
        result.put("quotaReached", prefs.getBoolean(CameraRollUploadWorker.PREF_QUOTA_STOPPED, false));
        result.put("access", CameraRollScanner.access(getContext()));
        result.put("network", network());
        result.put("charging", charging());
        result.put("requiresCharging", prefs.getBoolean(CameraRollUploadWorker.PREF_REQUIRES_CHARGING, false));
        call.resolve(result);
    }

    @PluginMethod
    public void backUpNow(PluginCall call) {
        SharedPreferences prefs = prefs();
        if (!prefs.getBoolean(PREF_UPLOAD_ENABLED, false) || !isEnrolled(prefs.getString(PREF_ENDPOINT, ""), prefs.getString(PREF_ACCOUNT, ""))) {
            call.reject("not_enrolled");
            return;
        }
        CameraRollUploadWorker.backUpNow(getContext());
        call.resolve();
    }

    @PluginMethod
    public void setRequiresCharging(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", Boolean.FALSE);
        boolean wanted = enabled != null && enabled;
        SharedPreferences prefs = prefs();
        prefs.edit().putBoolean(CameraRollUploadWorker.PREF_REQUIRES_CHARGING, wanted).apply();
        if (prefs.getBoolean(PREF_UPLOAD_ENABLED, false)) {
            CameraRollUploadWorker.ensurePeriodic(getContext());
        }
        JSObject result = new JSObject();
        result.put("requiresCharging", wanted);
        call.resolve(result);
    }

    @PluginMethod
    public void requestMediaAccess(PluginCall call) {
        List<String> aliases = new ArrayList<>();
        for (String permission : CameraRollPolicy.permissions(Build.VERSION.SDK_INT)) {
            aliases.add(aliasFor(permission));
        }
        requestPermissionForAliases(aliases.toArray(new String[0]), call, "mediaAccessCallback");
    }

    @PermissionCallback
    private void mediaAccessCallback(PluginCall call) {
        String access = CameraRollScanner.access(getContext());
        if (!CameraRollPolicy.ACCESS_NONE.equals(access) && prefs().getBoolean(PREF_UPLOAD_ENABLED, false)) {
            CameraRollUploadWorker.kick(getContext());
        }
        JSObject result = new JSObject();
        result.put("access", access);
        call.resolve(result);
    }

    static String aliasFor(String permission) {
        switch (permission) {
            case CameraRollPolicy.READ_MEDIA_IMAGES:
                return ALIAS_IMAGES;
            case CameraRollPolicy.READ_MEDIA_VIDEO:
                return ALIAS_VIDEO;
            case CameraRollPolicy.READ_MEDIA_VISUAL_USER_SELECTED:
                return ALIAS_USER_SELECTED;
            case CameraRollPolicy.ACCESS_MEDIA_LOCATION:
                return ALIAS_LOCATION;
            default:
                return ALIAS_LEGACY;
        }
    }

    private String network() {
        ConnectivityManager manager = getContext().getSystemService(ConnectivityManager.class);
        if (manager == null || manager.getActiveNetwork() == null) {
            return "none";
        }
        return manager.isActiveNetworkMetered() ? "metered" : "unmetered";
    }

    private boolean charging() {
        BatteryManager battery = getContext().getSystemService(BatteryManager.class);
        return battery != null && battery.isCharging();
    }

    @PluginMethod
    public void setUploadEnabled(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", Boolean.FALSE);
        boolean wanted = enabled != null && enabled;
        SharedPreferences prefs = prefs();
        if (wanted && !isEnrolled(prefs.getString(PREF_ENDPOINT, ""), prefs.getString(PREF_ACCOUNT, ""))) {
            call.reject("not_enrolled");
            return;
        }
        prefs.edit().putBoolean(PREF_UPLOAD_ENABLED, wanted).apply();
        if (wanted) {
            CameraRollWakeWorker.schedule(getContext(), 0L);
            CameraRollUploadWorker.ensurePeriodic(getContext());
        } else {
            CameraRollWakeWorker.cancel(getContext());
            CameraRollUploadWorker.cancelAll(getContext());
        }
        JSObject result = new JSObject();
        result.put("uploadEnabled", wanted);
        call.resolve(result);
    }

    @PluginMethod
    public void enrolBegin(final PluginCall call) {
        final String endpoint = call.getString("endpoint", "");
        final String deviceLabel = call.getString("deviceLabel", "");
        if (endpoint == null || endpoint.isEmpty()) {
            call.reject("bad_input");
            return;
        }
        worker.execute(() -> {
            try {
                closeEnrolment();
                JSONObject config = new JSONObject();
                config.put("endpoint", endpoint);
                config.put("config_dir", new File(getContext().getFilesDir(), "pigcloud").getAbsolutePath());
                enrolment = Mobile.newEnrolment(config.toString());
                JSONObject request = new JSONObject(enrolment.request(deviceLabel == null ? "" : deviceLabel));

                JSObject result = new JSObject();
                result.put("userCode", request.optString("user_code"));
                result.put("verificationUri", request.optString("verification_uri"));
                result.put("verificationUriComplete", request.optString("verification_uri_complete"));
                result.put("commitment", request.optString("commitment"));
                result.put("interval", request.optInt("interval"));
                result.put("expiresIn", request.optInt("expires_in"));
                call.resolve(result);
            } catch (Exception e) {
                closeEnrolment();
                call.reject("enrol_failed");
            }
        });
    }

    @PluginMethod
    public void enrolAwait(final PluginCall call) {
        final Enrolment pending = enrolment;
        if (pending == null) {
            call.reject("no_enrolment");
            return;
        }
        final String endpoint = call.getString("endpoint", "");
        final String account = call.getString("account", "");
        final Integer epoch = call.getInt("keyEpoch", 0);
        final Integer seconds = call.getInt("timeoutSeconds", (int) DEFAULT_ENROL_TIMEOUT_SECONDS);
        final long keyEpoch = epoch == null ? 0L : epoch.longValue();
        final long timeout = seconds == null ? DEFAULT_ENROL_TIMEOUT_SECONDS : seconds.longValue();

        worker.execute(() -> {
            try {
                JSONObject outcome = new JSONObject(pending.await(timeout));
                if (!outcome.optBoolean("ok")) {
                    closeEnrolment();
                    call.reject("enrol_refused");
                    return;
                }
                final long sealedEpoch = outcome.optLong("key_epoch", keyEpoch);
                try {
                    keys.store(
                        BackgroundKeyStore.scope(endpoint, account),
                        endpoint,
                        account,
                        sealedEpoch,
                        outcome.optString("material"),
                        outcome.optString("api_key"),
                        outcome.optString("api_key_id")
                    );
                } catch (Exception storeFailed) {
                    pending.release(outcome.optString("api_key"), outcome.optString("api_key_id"));
                    throw storeFailed;
                }
                prefs()
                    .edit()
                    .putString(PREF_ENDPOINT, endpoint == null ? "" : endpoint)
                    .putString(PREF_ACCOUNT, account == null ? "" : account)
                    .putString(PREF_API_KEY_ID, outcome.optString("api_key_id"))
                    .putLong(PREF_KEY_EPOCH, sealedEpoch)
                    .putBoolean(PREF_UPLOAD_ENABLED, true)
                    .putString(PREF_STATE, CameraRollWakeWorker.STATE_IDLE)
                    .putString(PREF_PAUSE_REASON, "")
                    .putLong(PREF_REVOCATION_STRIKE_AT, 0L)
                    .putInt(PREF_ATTEMPTS, 0)
                    .apply();
                closeEnrolment();
                CameraRollWakeWorker.schedule(getContext(), 0L);

                JSObject result = new JSObject();
                result.put("enrolled", true);
                result.put("account", account);
                result.put("apiKeyId", outcome.optString("api_key_id"));
                call.resolve(result);
            } catch (Exception e) {
                closeEnrolment();
                call.reject("enrol_failed");
            }
        });
    }

    @PluginMethod
    public void enrolCancel(PluginCall call) {
        Enrolment pending = enrolment;
        if (pending != null) {
            pending.cancel();
        }
        worker.execute(this::closeEnrolment);
        call.resolve();
    }

    @PluginMethod
    public void revoke(PluginCall call) {
        SharedPreferences prefs = prefs();
        String endpoint = call.getString("endpoint", prefs.getString(PREF_ENDPOINT, ""));
        String account = call.getString("account", prefs.getString(PREF_ACCOUNT, ""));
        CameraRollWakeWorker.cancel(getContext());
        CameraRollUploadWorker.cancelAll(getContext());
        keys.clear(BackgroundKeyStore.scope(endpoint, account));
        prefs.edit()
            .putBoolean(PREF_UPLOAD_ENABLED, false)
            .remove(PREF_API_KEY_ID)
            .remove(PREF_KEY_EPOCH)
            .remove(PREF_STATE)
            .remove(PREF_PAUSE_REASON)
            .remove(PREF_REVOCATION_STRIKE_AT)
            .remove(PREF_ATTEMPTS)
            .apply();

        JSObject result = new JSObject();
        result.put("enrolled", false);
        call.resolve(result);
    }

    @Override
    protected void handleOnDestroy() {
        closeEnrolment();
        worker.shutdownNow();
        super.handleOnDestroy();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private boolean isEnrolled(String endpoint, String account) {
        return keys != null && keys.isEnrolled(BackgroundKeyStore.scope(endpoint, account));
    }

    private void closeEnrolment() {
        Enrolment pending = enrolment;
        enrolment = null;
        if (pending != null) {
            pending.close();
        }
    }
}
