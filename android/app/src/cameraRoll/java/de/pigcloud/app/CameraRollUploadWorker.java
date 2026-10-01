package de.pigcloud.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.pigcloud.bind.mobile.Session;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CameraRollUploadWorker extends Worker {

    static final String WORK_NAME = "pigcloud-camera-roll-upload";
    static final String PERIODIC_WORK_NAME = "pigcloud-camera-roll-upload-periodic";
    static final String ROOT_FOLDER = "/Camera roll";
    static final String CHANNEL_ID = "camera_roll_backup";
    static final int NOTIFICATION_ID = 7301;

    static final String PREF_REQUIRES_CHARGING = "requires_charging";
    static final String PREF_DEVICE_FOLDER = "device_folder";
    static final String PREF_ENSURED_FOLDERS = "ensured_folders";
    static final String PREF_QUEUE_STATE = "queue_state";
    static final String PREF_QUEUE_REASON = "queue_reason";
    static final String PREF_QUEUE_RESUME_AT = "queue_resume_at";
    static final String PREF_QUEUE_ATTEMPTS = "queue_attempts";
    static final String PREF_QUOTA_STOPPED = "quota_stopped";
    static final String PREF_QUOTA_CODE = "quota_code";
    static final String PREF_ACCESS = "media_access";
    static final String PREF_FIRST_SYNC_DONE = "first_sync_done";
    static final String PREF_FG_LOG = "foreground_log";
    static final String PREF_RATE_LIMITED_UNTIL = "rate_limited_until";
    static final String PREF_PACE_LAST_START = "pace_last_start";
    static final String PREF_MEASURED_RATE = "measured_bytes_per_second";
    static final String PREF_GAVE_UP_RETRY_AT = "gave_up_retry_at";

    static final String STOP_RATE_LIMITED = "rate_limited";

    static final String QUEUE_IDLE = "idle";
    static final String QUEUE_SCANNING = "scanning";
    static final String QUEUE_UPLOADING = "uploading";
    static final String QUEUE_PAUSED = "paused";
    static final String QUEUE_QUOTA = "quotaReached";
    static final String QUEUE_NO_ACCESS = "noAccess";

    private static final long CONTINUE_DELAY_MILLIS = 5_000L;
    private static final long SESSION_BUSY_DELAY_MILLIS = 15_000L;
    private static final String SOURCE_COPY_DIR = "camera-roll-source";
    private static final long PERIODIC_HOURS = 1L;

    private volatile Session session;

    public CameraRollUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static Constraints constraints(SharedPreferences prefs) {
        return new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(prefs.getBoolean(PREF_REQUIRES_CHARGING, false))
            .build();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(CameraRollPlugin.PREFS, Context.MODE_PRIVATE);
    }

    static void backUpNow(Context context) {
        SharedPreferences prefs = prefs(context);
        String quotaCode = prefs.getString(PREF_QUOTA_CODE, "");
        CameraRollAssetStore store = CameraRollAssetStore.get(context);
        if (prefs.getBoolean(PREF_QUOTA_STOPPED, false) && quotaCode != null && !quotaCode.isEmpty()) {
            store.retryRefused(quotaCode);
        }
        store.retryGaveUp();
        prefs
            .edit()
            .putBoolean(PREF_QUOTA_STOPPED, false)
            .putString(PREF_QUOTA_CODE, "")
            .putLong(PREF_QUEUE_RESUME_AT, 0L)
            .putInt(PREF_QUEUE_ATTEMPTS, 0)
            .apply();
        enqueue(context, 0L, ExistingWorkPolicy.REPLACE);
        ensurePeriodic(context);
    }

    static void kick(Context context) {
        enqueue(context, 0L, ExistingWorkPolicy.KEEP);
        ensurePeriodic(context);
    }

    private static void continueLater(Context context, long delayMillis) {
        enqueue(context, delayMillis, ExistingWorkPolicy.APPEND_OR_REPLACE);
    }

    private static void enqueue(Context context, long delayMillis, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CameraRollUploadWorker.class)
            .setConstraints(constraints(prefs(context)))
            .setInitialDelay(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request);
    }

    static void ensurePeriodic(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(CameraRollUploadWorker.class, PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(prefs(context)))
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    static void cancelAll(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(WORK_NAME);
        manager.cancelUniqueWork(PERIODIC_WORK_NAME);
    }

    @Override
    public void onStopped() {
        Session live = session;
        if (live != null) {
            live.cancel();
        }
        super.onStopped();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String holder = CameraRollWakeWorker.acquireSession(CameraRollWakeWorker.OWNER_UPLOAD);
        if (holder != null) {
            if (CameraRollWakeWorker.OWNER_WAKE.equals(holder)) {
                continueLater(context, SESSION_BUSY_DELAY_MILLIS);
            }
            return Result.success();
        }
        try {
            return run();
        } finally {
            if (CameraRollWakeWorker.releaseSession(CameraRollWakeWorker.OWNER_UPLOAD)) {
                CameraRollWakeWorker.schedule(context, 0L);
            }
        }
    }

    private Result run() {
        Context context = getApplicationContext();
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(CameraRollPlugin.PREF_UPLOAD_ENABLED, false)) {
            return Result.success();
        }
        CameraRollAssetStore store = CameraRollAssetStore.get(context);
        store.requeueInterrupted();
        sweepSourceCopies(context);
        long started = System.currentTimeMillis();
        if (CameraRollPolicy.dailyRetryDue(prefs.getLong(PREF_GAVE_UP_RETRY_AT, 0L), started)) {
            store.retryGaveUp();
            prefs.edit().putLong(PREF_GAVE_UP_RETRY_AT, started).apply();
        }

        String access = CameraRollScanner.access(context);
        prefs.edit().putString(PREF_ACCESS, access).apply();
        if (CameraRollPolicy.ACCESS_NONE.equals(access)) {
            state(prefs, QUEUE_NO_ACCESS, "");
            return Result.success();
        }

        String endpoint = prefs.getString(CameraRollPlugin.PREF_ENDPOINT, "");
        String account = prefs.getString(CameraRollPlugin.PREF_ACCOUNT, "");
        BackgroundKeyStore keys = new BackgroundKeyStore(context);
        String scope = BackgroundKeyStore.scope(endpoint, account);
        BackgroundKeyStore.Enrolled enrolled;
        try {
            enrolled = keys.load(scope);
        } catch (Exception unreadable) {
            CameraRollWakeWorker.schedule(context, 0L);
            return Result.success();
        }
        if (enrolled == null) {
            return Result.success();
        }

        try {
            session = CameraRollWakeWorker.openSession(context, enrolled);
            session.sweepStagingExcept(new JSONArray(store.heldCursors()).toString());
            if (!"continue".equals(CameraRollWakeWorker.verify(context, prefs, keys, scope, enrolled, session))) {
                return Result.success();
            }

            state(prefs, QUEUE_SCANNING, "");
            access = CameraRollScanner.scan(context, prefs, store);
            prefs.edit().putString(PREF_ACCESS, access).apply();

            if (prefs.getBoolean(PREF_QUOTA_STOPPED, false)) {
                state(prefs, QUEUE_QUOTA, prefs.getString(PREF_QUOTA_CODE, ""));
                return Result.success();
            }
            long now = System.currentTimeMillis();
            long rateLimitedUntil = prefs.getLong(PREF_RATE_LIMITED_UNTIL, 0L);
            if (rateLimitedUntil > now) {
                state(prefs, QUEUE_PAUSED, STOP_RATE_LIMITED);
                continueLater(context, rateLimitedUntil - now);
                return Result.success();
            }
            if (prefs.getLong(PREF_QUEUE_RESUME_AT, 0L) > now) {
                return Result.success();
            }

            int backlog = store.counts().pending;
            boolean foreground = CameraRollPolicy.wantsForeground(
                backlog,
                prefs.getBoolean(PREF_FIRST_SYNC_DONE, false),
                store.pendingLargerThan(CameraRollPolicy.BACKGROUND_MAX_BYTES),
                CameraRollPolicy.foregroundLeft(prefs.getString(PREF_FG_LOG, ""), now)
            ) && startForeground(context);

            state(prefs, QUEUE_UPLOADING, "");
            String stop;
            do {
                long batchStart = System.currentTimeMillis();
                long left = CameraRollPolicy.foregroundLeft(prefs.getString(PREF_FG_LOG, ""), batchStart);
                long batchEnd = batchStart + CameraRollPolicy.batchMillis(foreground, left);
                if (foreground) {
                    chargeForeground(prefs, batchStart, batchEnd);
                }
                try {
                    stop = drain(context, prefs, store, batchEnd, CameraRollPolicy.maxBytes(foreground));
                } finally {
                    if (foreground) {
                        chargeForeground(prefs, batchStart, System.currentTimeMillis());
                    }
                }
            } while (foreground && "batch_done".equals(stop) && !isStopped() && CameraRollPolicy.foregroundLeft(prefs.getString(PREF_FG_LOG, ""), System.currentTimeMillis()) >= CameraRollPolicy.MIN_FOREGROUND_BATCH_MILLIS);
            return settle(context, prefs, store, stop);
        } catch (Exception sessionUnavailable) {
            return backOff(context, prefs, "session_unavailable", 0);
        } finally {
            Session live = session;
            session = null;
            if (live != null) {
                live.close();
            }
        }
    }

    private String drain(Context context, SharedPreferences prefs, CameraRollAssetStore store, long deadline, long maxBytes) throws Exception {
        String deviceFolder = deviceFolder(prefs);
        while (!isStopped()) {
            if (System.currentTimeMillis() >= deadline) {
                return "batch_done";
            }
            long wait = CameraRollPolicy.paceDelay(prefs.getLong(PREF_PACE_LAST_START, 0L), System.currentTimeMillis());
            if (wait > 0L) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return "cancelled";
                }
                continue;
            }
            CameraRollAssetStore.Asset asset = store.nextPending(maxBytes, System.currentTimeMillis());
            if (asset == null) {
                return "";
            }
            prefs.edit().putLong(PREF_PACE_LAST_START, System.currentTimeMillis()).apply();
            store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_START, null);
            String stop = uploadOne(context, prefs, store, asset, deviceFolder);
            if (stop != null) {
                return stop;
            }
        }
        return "cancelled";
    }

    private String uploadOne(Context context, SharedPreferences prefs, CameraRollAssetStore store, CameraRollAssetStore.Asset asset, String deviceFolder)
        throws Exception {
        Uri uri = CameraRollScanner.contentUri(asset.platformId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CameraRollScanner.granted(context, CameraRollPolicy.ACCESS_MEDIA_LOCATION)) {
            uri = MediaStore.setRequireOriginal(uri);
        }
        File copy = null;
        try (ParcelFileDescriptor source = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (source == null) {
                store.transition(asset.platformId, CameraRollPolicy.EV_UNREADABLE, null);
                return null;
            }
            String localPath;
            String hash = asset.contentHash;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                localPath = "/proc/self/fd/" + source.getFd();
                if (hash == null) {
                    hash = sha256(new FileInputStream(source.getFileDescriptor()), null);
                }
            } else {
                copy = new File(new File(context.getCacheDir(), SOURCE_COPY_DIR), asset.platformId.replace(':', '_'));
                copy.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(copy)) {
                    hash = sha256(new FileInputStream(source.getFileDescriptor()), out);
                }
                localPath = copy.getAbsolutePath();
            }

            ContentValues hashed = new ContentValues();
            hashed.put("content_hash", hash);
            store.update(asset.platformId, hashed);
            CameraRollAssetStore.Asset twin = store.uploadedWithHash(hash, asset.platformId);
            if (twin != null) {
                ContentValues values = new ContentValues();
                values.put("node_id", twin.nodeId);
                values.putNull("chunk_cursor");
                store.transition(asset.platformId, CameraRollPolicy.EV_DUPLICATE, values);
                return null;
            }

            String remoteDir = asset.relativeDir.isEmpty() ? deviceFolder : deviceFolder + "/" + asset.relativeDir;
            String folderStop = ensureFolder(prefs, store, asset, remoteDir);
            if (folderStop != null) {
                return folderStop;
            }
            String fileName = asset.displayName;
            if (store.remotePathTaken(remoteDir + "/" + fileName, asset.platformId)) {
                fileName = CameraRollPolicy.disambiguate(asset.displayName, asset.platformId.replace(':', '-'));
            }
            ContentValues named = new ContentValues();
            named.put("remote_path", remoteDir + "/" + fileName);
            store.update(asset.platformId, named);

            JSONObject request = new JSONObject();
            request.put("local_path", localPath);
            request.put("remote_dir", remoteDir);
            request.put("file_name", fileName);
            if (asset.chunkCursor != null) {
                request.put("cursor", asset.chunkCursor);
            }
            long uploadStart = SystemClock.elapsedRealtime();
            JSONObject result = new JSONObject(session.upload(request.toString(), null));
            if (result.optBoolean("ok") && asset.chunkCursor == null) {
                long rate = CameraRollPolicy.blendRate(prefs.getLong(PREF_MEASURED_RATE, 0L), asset.size, SystemClock.elapsedRealtime() - uploadStart);
                prefs.edit().putLong(PREF_MEASURED_RATE, rate).apply();
            }
            return applyResult(context, prefs, store, asset, result);
        } catch (java.io.FileNotFoundException | SecurityException | IllegalArgumentException gone) {
            store.transition(asset.platformId, CameraRollPolicy.EV_UNREADABLE, null);
            return null;
        } catch (java.io.IOException readFailed) {
            strike(store, asset, CameraRollPolicy.IO_ERROR, null);
            return null;
        } finally {
            if (copy != null) {
                copy.delete();
            }
        }
    }

    private String ensureFolder(SharedPreferences prefs, CameraRollAssetStore store, CameraRollAssetStore.Asset asset, String remoteDir) throws Exception {
        Set<String> ensured = new HashSet<>(prefs.getStringSet(PREF_ENSURED_FOLDERS, new HashSet<>()));
        if (ensured.contains(remoteDir)) {
            return null;
        }
        JSONObject folder = new JSONObject(session.ensureFolder(remoteDir));
        if (folder.optBoolean("ok")) {
            ensured.add(remoteDir);
            prefs.edit().putStringSet(PREF_ENSURED_FOLDERS, ensured).apply();
            return null;
        }
        return applyResult(getApplicationContext(), prefs, store, asset, folder);
    }

    private String applyResult(Context context, SharedPreferences prefs, CameraRollAssetStore store, CameraRollAssetStore.Asset asset, JSONObject result) throws Exception {
        if (result.optBoolean("ok")) {
            ContentValues values = new ContentValues();
            values.put("node_id", result.optString("node_id", ""));
            values.putNull("chunk_cursor");
            values.put("retry_count", 0);
            values.putNull("last_error_code");
            store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_OK, values);
            prefs.edit().putInt(PREF_QUEUE_ATTEMPTS, 0).apply();
            return null;
        }
        String errorClass = result.optString("error_class", "transient");
        String errorCode = result.optString("error_code", "");
        String cursor = result.optString("cursor", "");
        ContentValues values = new ContentValues();
        values.put("last_error_code", errorCode.isEmpty() ? errorClass : errorCode);
        if (FOLDER_GONE.equals(errorCode) && asset.retryCount < FOLDER_RETRIES) {
            forgetFolders(prefs);
            values.put("retry_count", asset.retryCount + 1);
            if (!cursor.isEmpty()) {
                values.put("chunk_cursor", cursor);
            }
            store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_RETRY, values);
            return null;
        }
        switch (errorClass) {
            case "quota":
                values.putNull("chunk_cursor");
                store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_REFUSED, values);
                discard(cursor);
                prefs.edit().putBoolean(PREF_QUOTA_STOPPED, true).putString(PREF_QUOTA_CODE, errorCode.isEmpty() ? "storage_limit" : errorCode).apply();
                return "quota";
            case "permanent":
                values.putNull("chunk_cursor");
                store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_REFUSED, values);
                discard(cursor);
                return null;
            case "local":
                values.putNull("chunk_cursor");
                store.transition(asset.platformId, CameraRollPolicy.EV_UNREADABLE, values);
                discard(cursor);
                return null;
            case STOP_RATE_LIMITED: {
                long until = CameraRollPolicy.rateLimitedUntil(
                    System.currentTimeMillis(),
                    result.optLong("retry_after_seconds", 0L),
                    (long) (Math.random() * CameraRollPolicy.RATE_LIMIT_JITTER_MILLIS)
                );
                if (!cursor.isEmpty()) {
                    values.put("chunk_cursor", cursor);
                }
                values.put("not_before", until);
                store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_RETRY, values);
                prefs.edit().putLong(PREF_RATE_LIMITED_UNTIL, until).apply();
                return STOP_RATE_LIMITED;
            }
            default:
                lastRetryAfterSeconds = result.optInt("retry_after_seconds", 0);
                if (CameraRollPolicy.counts(errorClass)) {
                    strike(store, asset, errorCode.isEmpty() ? errorClass : errorCode, cursor);
                    return errorClass;
                }
                if (!cursor.isEmpty()) {
                    values.put("chunk_cursor", cursor);
                }
                store.transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_RETRY, values);
                return errorClass;
        }
    }

    private int lastRetryAfterSeconds;

    private static final String FOLDER_GONE = "invalid_upload_dir";
    private static final int FOLDER_RETRIES = 2;

    private static void forgetFolders(SharedPreferences prefs) {
        prefs.edit().remove(PREF_ENSURED_FOLDERS).apply();
    }

    private void discard(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return;
        }
        try {
            session.discardCursor(cursor);
        } catch (Exception alreadyGone) {
        }
    }

    private void strike(CameraRollAssetStore store, CameraRollAssetStore.Asset asset, String code, String cursor) {
        if (store.strike(asset, code, cursor, System.currentTimeMillis())) {
            discard(cursor);
        }
    }

    private static void sweepSourceCopies(Context context) {
        File[] left = new File(context.getCacheDir(), SOURCE_COPY_DIR).listFiles();
        if (left == null) {
            return;
        }
        for (File copy : left) {
            copy.delete();
        }
    }

    private Result settle(Context context, SharedPreferences prefs, CameraRollAssetStore store, String stop) {
        switch (stop) {
            case "": {
                prefs.edit().putInt(PREF_QUEUE_ATTEMPTS, 0).apply();
                long now = System.currentTimeMillis();
                long parkedUntil = store.parkedUntil(now);
                if (parkedUntil > 0L) {
                    state(prefs, QUEUE_PAUSED, STOP_RATE_LIMITED);
                    continueLater(context, parkedUntil - now);
                    return Result.success();
                }
                if (store.counts().pending > 0) {
                    state(prefs, QUEUE_PAUSED, "needs_foreground");
                    return Result.success();
                }
                prefs.edit().putBoolean(PREF_FIRST_SYNC_DONE, true).apply();
                state(prefs, QUEUE_IDLE, "");
                return Result.success();
            }
            case "batch_done":
                state(prefs, QUEUE_UPLOADING, "");
                continueLater(context, CONTINUE_DELAY_MILLIS);
                return Result.success();
            case "cancelled":
                state(prefs, QUEUE_IDLE, "");
                return Result.success();
            case "quota":
                state(prefs, QUEUE_QUOTA, prefs.getString(PREF_QUOTA_CODE, "storage_limit"));
                return Result.success();
            case "daily_limit": {
                long resumeAt = System.currentTimeMillis() + CameraRollPolicy.untilNextUtcDay(System.currentTimeMillis());
                prefs.edit().putLong(PREF_QUEUE_RESUME_AT, resumeAt).apply();
                state(prefs, QUEUE_PAUSED, "daily_limit");
                continueLater(context, resumeAt - System.currentTimeMillis());
                return Result.success();
            }
            case "unauthorized":
                state(prefs, QUEUE_PAUSED, "unauthorized");
                CameraRollWakeWorker.schedule(context, 0L);
                return Result.success();
            case STOP_RATE_LIMITED: {
                long wait = Math.max(0L, prefs.getLong(PREF_RATE_LIMITED_UNTIL, 0L) - System.currentTimeMillis());
                state(prefs, QUEUE_PAUSED, STOP_RATE_LIMITED);
                continueLater(context, wait);
                return Result.success();
            }
            default:
                return backOff(context, prefs, stop, lastRetryAfterSeconds);
        }
    }

    private static Result backOff(Context context, SharedPreferences prefs, String reason, int retryAfterSeconds) {
        int attempts = prefs.getInt(PREF_QUEUE_ATTEMPTS, 0) + 1;
        long delay = CameraRollWakeWorker.backoffMillis(attempts, retryAfterSeconds);
        prefs.edit().putInt(PREF_QUEUE_ATTEMPTS, attempts).putLong(PREF_QUEUE_RESUME_AT, System.currentTimeMillis() + delay).apply();
        state(prefs, QUEUE_PAUSED, reason);
        continueLater(context, delay);
        return Result.success();
    }

    private static void state(SharedPreferences prefs, String state, String reason) {
        prefs.edit().putString(PREF_QUEUE_STATE, state).putString(PREF_QUEUE_REASON, reason == null ? "" : reason).apply();
    }

    static String deviceFolder(SharedPreferences prefs) {
        String stored = prefs.getString(PREF_DEVICE_FOLDER, "");
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        String model = CameraRollScanner.safeName(Build.MODEL, CameraRollScanner.KIND_IMAGE, 0L).replace('/', '_');
        String suffix = String.format(Locale.ROOT, "%04x", new java.security.SecureRandom().nextInt(0x10000));
        String folder = ROOT_FOLDER + "/" + model + " " + suffix;
        prefs.edit().putString(PREF_DEVICE_FOLDER, folder).apply();
        return folder;
    }

    private boolean startForeground(Context context) {
        try {
            setForegroundAsync(foregroundInfo(context)).get();
            return true;
        } catch (Exception notAllowed) {
            return false;
        }
    }

    static ForegroundInfo foregroundInfo(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, context.getString(R.string.camera_roll_channel_name), NotificationManager.IMPORTANCE_LOW)
            );
        }
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.camera_roll_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    private static void chargeForeground(SharedPreferences prefs, long startedAt, long endedAt) {
        String log = CameraRollPolicy.recordForeground(prefs.getString(PREF_FG_LOG, ""), startedAt, endedAt, System.currentTimeMillis());
        prefs.edit().putString(PREF_FG_LOG, log).commit();
    }

    private static String sha256(InputStream in, OutputStream copy) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            if (copy != null) {
                copy.write(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }
}
