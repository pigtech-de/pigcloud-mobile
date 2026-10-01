package de.pigcloud.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.pigcloud.bind.mobile.Mobile;
import de.pigcloud.bind.mobile.Session;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class CameraRollWakeWorker extends Worker {

    static final String WORK_NAME = "pigcloud-camera-roll-wake";
    static final long BASE_BACKOFF_MILLIS = 30_000L;
    static final long MAX_BACKOFF_MILLIS = 6L * 60 * 60 * 1000;
    static final String STATE_IDLE = "idle";
    static final String STATE_PAUSED = "paused";
    static final String STATE_REENROL_NEEDED = "reenrolNeeded";
    static final String OWNER_WAKE = "wake";
    static final String OWNER_UPLOAD = "upload";

    private static final Object SESSION_LOCK = new Object();
    private static String sessionOwner;
    private static boolean wakeDeferred;

    public CameraRollWakeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static void schedule(Context context, long delayMillis) {
        enqueue(context, delayMillis, ExistingWorkPolicy.REPLACE);
    }

    static void rearm(Context context, long delayMillis) {
        enqueue(context, delayMillis, ExistingWorkPolicy.APPEND_OR_REPLACE);
    }

    private static void enqueue(Context context, long delayMillis, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CameraRollWakeWorker.class)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }

    static long backoffMillis(int attempts, int retryAfterSeconds) {
        long base;
        if (retryAfterSeconds > 0) {
            base = Math.min(retryAfterSeconds * 1000L, MAX_BACKOFF_MILLIS);
        } else {
            int shift = Math.max(0, Math.min(attempts - 1, 20));
            base = Math.min(BASE_BACKOFF_MILLIS << shift, MAX_BACKOFF_MILLIS);
        }
        long spread = base / 5;
        long jitter = (long) ((Math.random() * 2 - 1) * spread);
        return Math.max(1000L, base + jitter);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(CameraRollPlugin.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(CameraRollPlugin.PREF_UPLOAD_ENABLED, false)) {
            return Result.success();
        }
        String endpoint = prefs.getString(CameraRollPlugin.PREF_ENDPOINT, "");
        String account = prefs.getString(CameraRollPlugin.PREF_ACCOUNT, "");
        BackgroundKeyStore keys = new BackgroundKeyStore(context);
        String scope = BackgroundKeyStore.scope(endpoint, account);
        long strikeAt = prefs.getLong(CameraRollPlugin.PREF_REVOCATION_STRIKE_AT, 0L);

        BackgroundKeyStore.Enrolled enrolled;
        try {
            enrolled = keys.load(scope);
        } catch (Exception unreadable) {
            return pause(context, prefs, "store_unreadable", strikeAt, 0);
        }
        if (enrolled == null) {
            return Result.success();
        }
        if (acquireSession(OWNER_WAKE) != null) {
            return Result.success();
        }

        Session session = null;
        try {
            session = openSession(context, enrolled);
            verify(context, prefs, keys, scope, enrolled, session);
            return Result.success();
        } catch (Exception sessionUnavailable) {
            return pause(context, prefs, "session_unavailable", strikeAt, 0);
        } finally {
            if (session != null) {
                session.close();
            }
            releaseSession(OWNER_WAKE);
        }
    }

    static String acquireSession(String owner) {
        synchronized (SESSION_LOCK) {
            if (sessionOwner != null) {
                if (OWNER_WAKE.equals(owner)) {
                    wakeDeferred = true;
                }
                return sessionOwner;
            }
            sessionOwner = owner;
            return null;
        }
    }

    static boolean releaseSession(String owner) {
        synchronized (SESSION_LOCK) {
            if (owner.equals(sessionOwner)) {
                sessionOwner = null;
            }
            boolean owed = wakeDeferred && !OWNER_WAKE.equals(owner);
            if (owed) {
                wakeDeferred = false;
            }
            return owed;
        }
    }

    static Session openSession(Context context, BackgroundKeyStore.Enrolled enrolled) throws Exception {
        JSONObject config = new JSONObject();
        config.put("endpoint", enrolled.endpoint);
        config.put("api_key", enrolled.apiKey);
        config.put("config_dir", new File(context.getFilesDir(), "pigcloud").getAbsolutePath());
        config.put("cache_dir", new File(context.getCacheDir(), "pigcloud-upload").getAbsolutePath());
        Session session = Mobile.newSession(config.toString());
        try {
            session.unlockBackground(enrolled.material);
        } catch (Exception unusable) {
            session.close();
            throw unusable;
        }
        return session;
    }

    static String verify(Context context, SharedPreferences prefs, BackgroundKeyStore keys, String scope, BackgroundKeyStore.Enrolled enrolled, Session session) throws Exception {
        long strikeAt = prefs.getLong(CameraRollPlugin.PREF_REVOCATION_STRIKE_AT, 0L);
        JSONObject request = new JSONObject();
        request.put("key_epoch", enrolled.keyEpoch);
        request.put("key_identifier", enrolled.apiKeyId);
        request.put("revocation_strike_at", strikeAt);
        JSONObject verdict = new JSONObject(session.checkKeys(request.toString()));
        apply(context, prefs, keys, scope, verdict);
        return verdict.optString("outcome", "pause");
    }

    private static Result apply(Context context, SharedPreferences prefs, BackgroundKeyStore keys, String scope, JSONObject verdict) {
        String outcome = verdict.optString("outcome", "pause");
        long now = System.currentTimeMillis();
        if ("wipe".equals(outcome)) {
            CameraRollUploadWorker.cancelAll(context);
            keys.clear(scope);
            prefs.edit()
                .putBoolean(CameraRollPlugin.PREF_UPLOAD_ENABLED, false)
                .remove(CameraRollPlugin.PREF_API_KEY_ID)
                .remove(CameraRollPlugin.PREF_KEY_EPOCH)
                .putString(CameraRollPlugin.PREF_STATE, STATE_REENROL_NEEDED)
                .putString(CameraRollPlugin.PREF_PAUSE_REASON, verdict.optString("reason", ""))
                .putLong(CameraRollPlugin.PREF_REVOCATION_STRIKE_AT, 0L)
                .putInt(CameraRollPlugin.PREF_ATTEMPTS, 0)
                .putLong(CameraRollPlugin.PREF_LAST_CHECK_AT, now)
                .apply();
            return Result.success();
        }
        if ("continue".equals(outcome)) {
            prefs.edit()
                .putString(CameraRollPlugin.PREF_STATE, STATE_IDLE)
                .putString(CameraRollPlugin.PREF_PAUSE_REASON, "")
                .putLong(CameraRollPlugin.PREF_REVOCATION_STRIKE_AT, 0L)
                .putInt(CameraRollPlugin.PREF_ATTEMPTS, 0)
                .putLong(CameraRollPlugin.PREF_LAST_CHECK_AT, now)
                .apply();
            CameraRollUploadWorker.kick(context);
            return Result.success();
        }
        return pause(
            context,
            prefs,
            verdict.optString("reason", "unreachable"),
            verdict.optLong("revocation_strike_at", 0L),
            verdict.optInt("retry_after_seconds", 0)
        );
    }

    private static Result pause(Context context, SharedPreferences prefs, String reason, long strikeAt, int retryAfterSeconds) {
        int attempts = prefs.getInt(CameraRollPlugin.PREF_ATTEMPTS, 0) + 1;
        prefs.edit()
            .putString(CameraRollPlugin.PREF_STATE, STATE_PAUSED)
            .putString(CameraRollPlugin.PREF_PAUSE_REASON, reason)
            .putLong(CameraRollPlugin.PREF_REVOCATION_STRIKE_AT, strikeAt)
            .putInt(CameraRollPlugin.PREF_ATTEMPTS, attempts)
            .putLong(CameraRollPlugin.PREF_LAST_CHECK_AT, System.currentTimeMillis())
            .apply();
        rearm(context, backoffMillis(attempts, retryAfterSeconds));
        return Result.success();
    }
}
