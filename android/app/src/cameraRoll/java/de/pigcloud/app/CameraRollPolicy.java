package de.pigcloud.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CameraRollPolicy {

    static final String READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    static final String READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO";
    static final String READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    static final String READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";
    static final String ACCESS_MEDIA_LOCATION = "android.permission.ACCESS_MEDIA_LOCATION";

    static final String ACCESS_FULL = "full";
    static final String ACCESS_PARTIAL = "partial";
    static final String ACCESS_NONE = "none";

    static final String PENDING = "pending";
    static final String UPLOADING = "uploading";
    static final String UPLOADED = "uploaded";
    static final String FAILED_PERMANENT = "failed_permanent";
    static final String UNKNOWN = "unknown";
    static final String TOMBSTONE = "tombstone";

    static final String EV_CHANGED = "changed";
    static final String EV_RESTORED = "restored";
    static final String EV_RESTORED_UPLOADED = "restored_uploaded";
    static final String EV_VANISHED_UPLOADED = "vanished_uploaded";
    static final String EV_VANISHED_FULL = "vanished_full";
    static final String EV_VANISHED_PARTIAL = "vanished_partial";
    static final String EV_UPLOAD_START = "upload_start";
    static final String EV_UPLOAD_OK = "upload_ok";
    static final String EV_UPLOAD_RETRY = "upload_retry";
    static final String EV_UPLOAD_REFUSED = "upload_refused";
    static final String EV_UNREADABLE = "unreadable";
    static final String EV_DUPLICATE = "duplicate";
    static final String EV_RETRY_REQUESTED = "retry_requested";

    static final String[][] TRANSITIONS = {
        {PENDING, EV_CHANGED, PENDING},
        {UPLOADING, EV_CHANGED, PENDING},
        {UPLOADED, EV_CHANGED, PENDING},
        {FAILED_PERMANENT, EV_CHANGED, PENDING},
        {UNKNOWN, EV_CHANGED, PENDING},
        {UNKNOWN, EV_RESTORED, PENDING},
        {UNKNOWN, EV_RESTORED_UPLOADED, UPLOADED},
        {PENDING, EV_VANISHED_UPLOADED, TOMBSTONE},
        {UPLOADING, EV_VANISHED_UPLOADED, TOMBSTONE},
        {UPLOADED, EV_VANISHED_UPLOADED, TOMBSTONE},
        {FAILED_PERMANENT, EV_VANISHED_UPLOADED, TOMBSTONE},
        {UNKNOWN, EV_VANISHED_UPLOADED, TOMBSTONE},
        {PENDING, EV_VANISHED_FULL, UNKNOWN},
        {UPLOADING, EV_VANISHED_FULL, UNKNOWN},
        {FAILED_PERMANENT, EV_VANISHED_FULL, UNKNOWN},
        {PENDING, EV_VANISHED_PARTIAL, UNKNOWN},
        {UPLOADING, EV_VANISHED_PARTIAL, UNKNOWN},
        {UPLOADED, EV_VANISHED_PARTIAL, UNKNOWN},
        {FAILED_PERMANENT, EV_VANISHED_PARTIAL, UNKNOWN},
        {PENDING, EV_UPLOAD_START, UPLOADING},
        {UPLOADING, EV_UPLOAD_OK, UPLOADED},
        {UPLOADING, EV_UPLOAD_RETRY, PENDING},
        {UPLOADING, EV_UPLOAD_REFUSED, FAILED_PERMANENT},
        {UPLOADING, EV_UNREADABLE, UNKNOWN},
        {PENDING, EV_UNREADABLE, UNKNOWN},
        {PENDING, EV_DUPLICATE, UPLOADED},
        {UPLOADING, EV_DUPLICATE, UPLOADED},
        {FAILED_PERMANENT, EV_RETRY_REQUESTED, PENDING},
    };

    static final int MAX_CONCURRENT_UPLOADS = 1;

    static final int MAX_IDENTICAL_FAILURES = 5;
    static final long GIVE_UP_SPAN_MILLIS = 24L * 60 * 60 * 1000;
    static final long GAVE_UP_RETRY_MILLIS = 24L * 60 * 60 * 1000;
    static final String GAVE_UP_PREFIX = "gave_up:";
    static final String CLASS_TRANSIENT = "transient";
    static final String IO_ERROR = "io_error";
    static final String INTERRUPTED = "interrupted";

    static final String OWN_DOWNLOADS = "Download/PigCloud";

    static final long PACE_UPLOADS_PER_HOUR = 2400;
    static final long PACE_SPACING_MILLIS = 60L * 60 * 1000 / PACE_UPLOADS_PER_HOUR;
    static final long RATE_LIMIT_MIN_MILLIS = 60L * 1000;
    static final long RATE_LIMIT_MAX_MILLIS = 60L * 60 * 1000;
    static final long RATE_LIMIT_JITTER_MILLIS = 30L * 1000;

    static final long FOREGROUND_BUDGET_MILLIS = 5L * 60 * 60 * 1000;
    static final long FOREGROUND_WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    static final long FOREGROUND_BATCH_MILLIS = 60L * 60 * 1000;
    static final long BACKGROUND_BATCH_MILLIS = 8L * 60 * 1000;
    static final long MIN_FOREGROUND_BATCH_MILLIS = 5L * 60 * 1000;
    static final int FIRST_SYNC_BACKLOG = 50;
    static final long BACKGROUND_MAX_BYTES = 200L * 1024 * 1024;
    static final long FULL_SCAN_INTERVAL_MILLIS = 24L * 60 * 60 * 1000;

    private CameraRollPolicy() {}

    static String next(String from, String event) {
        for (String[] row : TRANSITIONS) {
            if (row[0].equals(from) && row[1].equals(event)) {
                return row[2];
            }
        }
        return from;
    }

    static String[] permissions(int sdk) {
        List<String> wanted = new ArrayList<>();
        if (sdk >= 33) {
            wanted.add(READ_MEDIA_IMAGES);
            wanted.add(READ_MEDIA_VIDEO);
        } else {
            wanted.add(READ_EXTERNAL_STORAGE);
        }
        if (sdk >= 34) {
            wanted.add(READ_MEDIA_VISUAL_USER_SELECTED);
        }
        if (sdk >= 29) {
            wanted.add(ACCESS_MEDIA_LOCATION);
        }
        return wanted.toArray(new String[0]);
    }

    static String access(int sdk, boolean images, boolean video, boolean userSelected, boolean legacy) {
        if (sdk >= 33) {
            if (images && video) {
                return ACCESS_FULL;
            }
            if (images || video || (sdk >= 34 && userSelected)) {
                return ACCESS_PARTIAL;
            }
            return ACCESS_NONE;
        }
        return legacy ? ACCESS_FULL : ACCESS_NONE;
    }

    static String vanishEvent(String access, boolean uploaded) {
        if (!ACCESS_FULL.equals(access)) {
            return EV_VANISHED_PARTIAL;
        }
        return uploaded ? EV_VANISHED_UPLOADED : EV_VANISHED_FULL;
    }

    static boolean usesGeneration(int sdk) {
        return sdk >= 30;
    }

    static boolean needsFullScan(long lastFullScanAt, long now, String storedVersion, String mediaVersion, String storedAccess, String access) {
        if (lastFullScanAt <= 0 || now - lastFullScanAt >= FULL_SCAN_INTERVAL_MILLIS || now < lastFullScanAt) {
            return true;
        }
        if (storedVersion == null || !storedVersion.equals(mediaVersion)) {
            return true;
        }
        return storedAccess == null || !storedAccess.equals(access);
    }

    static long advanceCursor(long cursor, long seen) {
        return Math.max(cursor, seen);
    }

    static long foregroundLeft(String log, long now) {
        long used = 0L;
        long windowStart = now - FOREGROUND_WINDOW_MILLIS;
        for (long[] span : spans(log)) {
            long start = Math.max(span[0], windowStart);
            long end = Math.min(span[1], now);
            if (end > start) {
                used += end - start;
            }
        }
        return Math.max(0L, FOREGROUND_BUDGET_MILLIS - used);
    }

    static String recordForeground(String log, long start, long end, long now) {
        StringBuilder kept = new StringBuilder();
        for (long[] span : spans(log)) {
            if (span[0] != start && span[1] > now - FOREGROUND_WINDOW_MILLIS) {
                kept.append(span[0]).append('-').append(span[1]).append(';');
            }
        }
        kept.append(start).append('-').append(Math.max(start, end)).append(';');
        return kept.toString();
    }

    private static List<long[]> spans(String log) {
        List<long[]> spans = new ArrayList<>();
        if (log == null) {
            return spans;
        }
        for (String entry : log.split(";")) {
            int dash = entry.indexOf('-');
            if (dash <= 0) {
                continue;
            }
            try {
                spans.add(new long[] { Long.parseLong(entry.substring(0, dash)), Long.parseLong(entry.substring(dash + 1)) });
            } catch (NumberFormatException skipped) {
                continue;
            }
        }
        return spans;
    }

    static long batchMillis(boolean foreground, long foregroundLeft) {
        if (!foreground) {
            return BACKGROUND_BATCH_MILLIS;
        }
        return Math.min(FOREGROUND_BATCH_MILLIS, foregroundLeft);
    }

    static boolean wantsForeground(int backlog, boolean firstSyncDone, boolean largePending, long foregroundLeft) {
        return (backlog >= FIRST_SYNC_BACKLOG || !firstSyncDone || largePending) && backlog > 0 && foregroundLeft >= MIN_FOREGROUND_BATCH_MILLIS;
    }

    static long maxBytes(boolean foreground) {
        return foreground ? 0L : BACKGROUND_MAX_BYTES;
    }

    static String disambiguate(String displayName, String platformId) {
        int dot = displayName.lastIndexOf('.');
        if (dot <= 0) {
            return displayName + " (" + platformId + ")";
        }
        return displayName.substring(0, dot) + " (" + platformId + ")" + displayName.substring(dot);
    }

    static boolean counts(String errorClass) {
        return CLASS_TRANSIENT.equals(errorClass);
    }

    static int strikes(String previousCode, int previousCount, String code) {
        return code.equals(previousCode) ? previousCount + 1 : 1;
    }

    static long firstStrikeAt(String previousCode, long previousFirstAt, String code, long now) {
        return code.equals(previousCode) && previousFirstAt > 0 ? previousFirstAt : now;
    }

    static boolean givesUp(int strikes, String code, long firstStrikeAt, long now) {
        if (strikes < MAX_IDENTICAL_FAILURES) {
            return false;
        }
        return IO_ERROR.equals(code) || now - firstStrikeAt >= GIVE_UP_SPAN_MILLIS;
    }

    static boolean dailyRetryDue(long lastRetryAt, long now) {
        return lastRetryAt <= 0 || now < lastRetryAt || now - lastRetryAt >= GAVE_UP_RETRY_MILLIS;
    }

    static boolean ownMedia(String relativeDir, String dataPath, String ownerPackage, String selfPackage) {
        String own = OWN_DOWNLOADS.toLowerCase(Locale.ROOT);
        if (relativeDir != null) {
            String dir = relativeDir.toLowerCase(Locale.ROOT);
            if (dir.equals(own) || dir.startsWith(own + "/")) {
                return true;
            }
        }
        if (dataPath != null && dataPath.toLowerCase(Locale.ROOT).contains("/" + own + "/")) {
            return true;
        }
        return ownerPackage != null && ownerPackage.equals(selfPackage);
    }

    static long paceDelay(long lastStart, long now) {
        if (lastStart <= 0L || now < lastStart) {
            return 0L;
        }
        return Math.max(0L, lastStart + PACE_SPACING_MILLIS - now);
    }

    static long rateLimitedUntil(long now, long retryAfterSeconds, long jitterMillis) {
        long wait = Math.min(Math.max(retryAfterSeconds * 1000L, RATE_LIMIT_MIN_MILLIS), RATE_LIMIT_MAX_MILLIS);
        return now + wait + Math.min(Math.max(jitterMillis, 0L), RATE_LIMIT_JITTER_MILLIS);
    }

    static long blendRate(long previous, long bytes, long millis) {
        if (bytes <= 0L || millis <= 0L) {
            return previous;
        }
        long sample = bytes * 1000L / millis;
        if (previous <= 0L) {
            return sample;
        }
        return (previous * 3L + sample) / 4L;
    }

    static long untilNextUtcDay(long now) {
        long day = 24L * 60 * 60 * 1000;
        return day - (now % day) + 60_000L;
    }
}
