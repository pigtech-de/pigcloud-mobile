package de.pigcloud.app;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

final class CameraRollScanner {

    static final String KIND_IMAGE = "image";
    static final String KIND_VIDEO = "video";

    static final String PREF_SCAN_CURSOR = "scan_cursor";
    static final String PREF_SCAN_MEDIA_VERSION = "scan_media_version";
    static final String PREF_SCAN_LAST_FULL_AT = "scan_last_full_at";
    static final String PREF_SCAN_ACCESS = "scan_access";
    static final String PREF_SCAN_PASS = "scan_pass";

    private CameraRollScanner() {}

    static String access(Context context) {
        return CameraRollPolicy.access(
            Build.VERSION.SDK_INT,
            granted(context, CameraRollPolicy.READ_MEDIA_IMAGES),
            granted(context, CameraRollPolicy.READ_MEDIA_VIDEO),
            granted(context, CameraRollPolicy.READ_MEDIA_VISUAL_USER_SELECTED),
            granted(context, CameraRollPolicy.READ_EXTERNAL_STORAGE)
        );
    }

    static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    static String scan(Context context, SharedPreferences prefs, CameraRollAssetStore store) {
        String access = access(context);
        if (CameraRollPolicy.ACCESS_NONE.equals(access)) {
            return access;
        }
        int sdk = Build.VERSION.SDK_INT;
        long now = System.currentTimeMillis();
        String mediaVersion = sdk >= Build.VERSION_CODES.Q ? MediaStore.getVersion(context) : "";
        boolean full = CameraRollPolicy.needsFullScan(
            prefs.getLong(PREF_SCAN_LAST_FULL_AT, 0L),
            now,
            prefs.getString(PREF_SCAN_MEDIA_VERSION, null),
            mediaVersion,
            prefs.getString(PREF_SCAN_ACCESS, null),
            access
        );
        boolean generation = CameraRollPolicy.usesGeneration(sdk);
        long cursor = full ? 0L : prefs.getLong(PREF_SCAN_CURSOR, 0L);
        long pass = prefs.getLong(PREF_SCAN_PASS, 0L) + 1;

        long highest = cursor;
        for (String kind : new String[] { KIND_IMAGE, KIND_VIDEO }) {
            highest = Math.max(highest, list(context, store, kind, generation, full, cursor, pass));
        }

        SharedPreferences.Editor edit = prefs
            .edit()
            .putLong(PREF_SCAN_PASS, pass)
            .putLong(PREF_SCAN_CURSOR, CameraRollPolicy.advanceCursor(cursor, highest))
            .putString(PREF_SCAN_MEDIA_VERSION, mediaVersion)
            .putString(PREF_SCAN_ACCESS, access);
        if (full) {
            store.markVanished(pass, access);
            edit.putLong(PREF_SCAN_LAST_FULL_AT, now);
        }
        edit.apply();
        return access;
    }

    static Uri collection(String kind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return KIND_VIDEO.equals(kind)
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return KIND_VIDEO.equals(kind) ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    static Uri contentUri(String platformId) {
        int colon = platformId.indexOf(':');
        String kind = platformId.substring(0, colon);
        long id = Long.parseLong(platformId.substring(colon + 1));
        return ContentUris.withAppendedId(collection(kind), id);
    }

    private static long list(Context context, CameraRollAssetStore store, String kind, boolean generation, boolean full, long cursor, long pass) {
        int sdk = Build.VERSION.SDK_INT;
        List<String> projection = new ArrayList<>();
        projection.add(MediaStore.MediaColumns._ID);
        projection.add(MediaStore.MediaColumns.DISPLAY_NAME);
        projection.add(MediaStore.MediaColumns.SIZE);
        projection.add(MediaStore.MediaColumns.DATE_MODIFIED);
        projection.add(sdk >= Build.VERSION_CODES.Q ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
        if (generation) {
            projection.add(MediaStore.MediaColumns.GENERATION_MODIFIED);
        }
        String originColumnName = sdk >= Build.VERSION_CODES.Q ? MediaStore.MediaColumns.OWNER_PACKAGE_NAME : MediaStore.MediaColumns.DATA;
        projection.add(originColumnName);
        String selection = null;
        String[] args = null;
        if (!full) {
            selection = generation ? MediaStore.MediaColumns.GENERATION_MODIFIED + " > ?" : MediaStore.MediaColumns.DATE_MODIFIED + " >= ?";
            args = new String[] { Long.toString(cursor) };
        }

        long highest = cursor;
        try (
            Cursor rows = context
                .getContentResolver()
                .query(collection(kind), projection.toArray(new String[0]), selection, args, MediaStore.MediaColumns._ID + " ASC")
        ) {
            if (rows == null) {
                return highest;
            }
            int idColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int dirColumn = rows.getColumnIndexOrThrow(projection.get(4));
            int generationColumn = generation ? rows.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED) : -1;
            int originColumn = rows.getColumnIndexOrThrow(originColumnName);
            String self = context.getPackageName();
            boolean q = sdk >= Build.VERSION_CODES.Q;
            while (rows.moveToNext()) {
                long id = rows.getLong(idColumn);
                highest = Math.max(highest, generation ? rows.getLong(generationColumn) : rows.getLong(dateColumn));
                String dir = safeDir(rows.getString(dirColumn));
                String origin = rows.getString(originColumn);
                if (CameraRollPolicy.ownMedia(q ? dir : null, q ? null : origin, q ? origin : null, self)) {
                    continue;
                }
                CameraRollAssetStore.Scanned seen = new CameraRollAssetStore.Scanned();
                seen.platformId = kind + ":" + id;
                seen.kind = kind;
                seen.displayName = safeName(rows.getString(nameColumn), kind, id);
                seen.relativeDir = dir;
                seen.size = rows.getLong(sizeColumn);
                seen.dateModified = rows.getLong(dateColumn);
                store.recordSeen(seen, pass);
            }
        }
        return highest;
    }

    static String safeName(String name, String kind, long id) {
        String cleaned = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return (KIND_VIDEO.equals(kind) ? "VID_" : "IMG_") + id;
        }
        return cleaned;
    }

    static String safeDir(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder dir = new StringBuilder();
        for (String segment : raw.split("[/\\\\]")) {
            String cleaned = segment.trim();
            if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
                continue;
            }
            if (dir.length() > 0) {
                dir.append('/');
            }
            dir.append(cleaned);
        }
        return dir.toString();
    }
}
