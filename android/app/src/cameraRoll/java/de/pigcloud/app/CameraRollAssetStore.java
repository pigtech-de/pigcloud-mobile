package de.pigcloud.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class CameraRollAssetStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "camera_roll.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "assets";

    private static volatile CameraRollAssetStore instance;

    static final class Asset {

        String platformId;
        String kind;
        String relativeDir;
        String displayName;
        long dateModified;
        long size;
        String contentHash;
        String nodeId;
        String remotePath;
        String status;
        String chunkCursor;
        int retryCount;
        String lastErrorCode;
        long firstStrikeAt;
    }

    static final class Scanned {

        String platformId;
        String kind;
        String relativeDir;
        String displayName;
        long dateModified;
        long size;
    }

    static final class Counts {

        int pending;
        int uploaded;
        int failed;
        int unknown;
    }

    static CameraRollAssetStore get(Context context) {
        CameraRollAssetStore local = instance;
        if (local == null) {
            synchronized (CameraRollAssetStore.class) {
                local = instance;
                if (local == null) {
                    local = new CameraRollAssetStore(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private CameraRollAssetStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " ("
                + "platform_id TEXT PRIMARY KEY NOT NULL, "
                + "kind TEXT NOT NULL, "
                + "relative_dir TEXT NOT NULL, "
                + "display_name TEXT NOT NULL, "
                + "date_modified INTEGER NOT NULL, "
                + "size INTEGER NOT NULL, "
                + "content_hash TEXT, "
                + "node_id TEXT, "
                + "remote_path TEXT, "
                + "status TEXT NOT NULL, "
                + "chunk_cursor TEXT, "
                + "retry_count INTEGER NOT NULL DEFAULT 0, "
                + "last_error_code TEXT, "
                + "seen_pass INTEGER NOT NULL DEFAULT 0, "
                + "not_before INTEGER NOT NULL DEFAULT 0, "
                + "first_strike_at INTEGER NOT NULL DEFAULT 0, "
                + "updated_at INTEGER NOT NULL)"
        );
        db.execSQL("CREATE INDEX assets_status ON " + TABLE + " (status, retry_count, date_modified)");
        db.execSQL("CREATE INDEX assets_hash ON " + TABLE + " (content_hash)");
        db.execSQL("CREATE INDEX assets_remote_path ON " + TABLE + " (remote_path)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN not_before INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN first_strike_at INTEGER NOT NULL DEFAULT 0");
        }
    }

    synchronized void recordSeen(Scanned seen, long pass) {
        SQLiteDatabase db = getWritableDatabase();
        Asset existing = find(db, seen.platformId);
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("kind", seen.kind);
        values.put("relative_dir", seen.relativeDir);
        values.put("display_name", seen.displayName);
        values.put("date_modified", seen.dateModified);
        values.put("size", seen.size);
        values.put("seen_pass", pass);
        values.put("updated_at", now);
        if (existing == null) {
            values.put("platform_id", seen.platformId);
            values.put("status", CameraRollPolicy.PENDING);
            db.insert(TABLE, null, values);
            return;
        }
        boolean changed = existing.dateModified != seen.dateModified || existing.size != seen.size;
        String event = null;
        if (changed) {
            event = CameraRollPolicy.EV_CHANGED;
            values.putNull("content_hash");
            values.putNull("chunk_cursor");
            values.put("retry_count", 0);
        } else if (CameraRollPolicy.UNKNOWN.equals(existing.status)) {
            event = existing.nodeId != null ? CameraRollPolicy.EV_RESTORED_UPLOADED : CameraRollPolicy.EV_RESTORED;
        }
        if (event != null) {
            values.put("status", CameraRollPolicy.next(existing.status, event));
        }
        db.update(TABLE, values, "platform_id = ?", new String[] { seen.platformId });
    }

    synchronized int markVanished(long pass, String access) {
        SQLiteDatabase db = getWritableDatabase();
        int moved = 0;
        List<Asset> missing = new ArrayList<>();
        try (
            Cursor cursor = db.query(
                TABLE,
                null,
                "seen_pass <> ? AND status <> ?",
                new String[] { Long.toString(pass), CameraRollPolicy.TOMBSTONE },
                null,
                null,
                null
            )
        ) {
            while (cursor.moveToNext()) {
                missing.add(read(cursor));
            }
        }
        for (Asset asset : missing) {
            String event = CameraRollPolicy.vanishEvent(access, asset.nodeId != null);
            String to = CameraRollPolicy.next(asset.status, event);
            if (to.equals(asset.status)) {
                continue;
            }
            ContentValues values = new ContentValues();
            values.put("status", to);
            values.putNull("chunk_cursor");
            values.put("updated_at", System.currentTimeMillis());
            db.update(TABLE, values, "platform_id = ?", new String[] { asset.platformId });
            moved++;
        }
        return moved;
    }

    synchronized boolean pendingLargerThan(long bytes) {
        try (
            Cursor cursor = getReadableDatabase()
                .query(TABLE, new String[] { "platform_id" }, "status = ? AND size > ?", new String[] { CameraRollPolicy.PENDING, Long.toString(bytes) }, null, null, null, "1")
        ) {
            return cursor.moveToNext();
        }
    }

    synchronized Asset nextPending(long maxBytes, long now) {
        SQLiteDatabase db = getReadableDatabase();
        try (
            Cursor cursor = db.query(
                TABLE,
                null,
                maxBytes > 0 ? "status = ? AND not_before <= ? AND size <= ?" : "status = ? AND not_before <= ?",
                maxBytes > 0
                    ? new String[] { CameraRollPolicy.PENDING, Long.toString(now), Long.toString(maxBytes) }
                    : new String[] { CameraRollPolicy.PENDING, Long.toString(now) },
                null,
                null,
                "retry_count ASC, date_modified ASC",
                "1"
            )
        ) {
            return cursor.moveToNext() ? read(cursor) : null;
        }
    }

    synchronized long parkedUntil(long now) {
        try (
            Cursor cursor = getReadableDatabase()
                .rawQuery(
                    "SELECT MIN(not_before) FROM " + TABLE + " WHERE status = ? AND not_before > ?",
                    new String[] { CameraRollPolicy.PENDING, Long.toString(now) }
                )
        ) {
            return cursor.moveToNext() && !cursor.isNull(0) ? cursor.getLong(0) : 0L;
        }
    }

    synchronized long pendingBytes() {
        try (
            Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT SUM(size) FROM " + TABLE + " WHERE status IN ('pending', 'uploading')", null)
        ) {
            return cursor.moveToNext() && !cursor.isNull(0) ? cursor.getLong(0) : 0L;
        }
    }

    synchronized void transition(String platformId, String event, ContentValues extra) {
        SQLiteDatabase db = getWritableDatabase();
        Asset asset = find(db, platformId);
        if (asset == null) {
            return;
        }
        ContentValues values = extra == null ? new ContentValues() : new ContentValues(extra);
        values.put("status", CameraRollPolicy.next(asset.status, event));
        values.put("updated_at", System.currentTimeMillis());
        db.update(TABLE, values, "platform_id = ?", new String[] { platformId });
    }

    synchronized void update(String platformId, ContentValues values) {
        getWritableDatabase().update(TABLE, values, "platform_id = ?", new String[] { platformId });
    }

    synchronized void requeueInterrupted() {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        for (Asset asset : byStatus(db, CameraRollPolicy.UPLOADING)) {
            strike(asset, CameraRollPolicy.INTERRUPTED, asset.chunkCursor, now);
        }
    }

    synchronized boolean strike(Asset asset, String code, String cursor, long now) {
        int strikes = CameraRollPolicy.strikes(asset.lastErrorCode, asset.retryCount, code);
        long firstAt = CameraRollPolicy.firstStrikeAt(asset.lastErrorCode, asset.firstStrikeAt, code, now);
        ContentValues values = new ContentValues();
        values.put("retry_count", strikes);
        values.put("first_strike_at", firstAt);
        if (CameraRollPolicy.givesUp(strikes, code, firstAt, now)) {
            values.put("last_error_code", CameraRollPolicy.GAVE_UP_PREFIX + code);
            values.putNull("chunk_cursor");
            transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_REFUSED, values);
            return true;
        }
        values.put("last_error_code", code);
        if (cursor != null && !cursor.isEmpty()) {
            values.put("chunk_cursor", cursor);
        }
        transition(asset.platformId, CameraRollPolicy.EV_UPLOAD_RETRY, values);
        return false;
    }

    synchronized int retryRefused(String errorCode) {
        return retryFailed(code -> errorCode.equals(code));
    }

    synchronized int retryGaveUp() {
        return retryFailed(code -> code != null && code.startsWith(CameraRollPolicy.GAVE_UP_PREFIX));
    }

    private int retryFailed(java.util.function.Predicate<String> matches) {
        SQLiteDatabase db = getWritableDatabase();
        int moved = 0;
        for (Asset asset : byStatus(db, CameraRollPolicy.FAILED_PERMANENT)) {
            if (!matches.test(asset.lastErrorCode)) {
                continue;
            }
            ContentValues values = new ContentValues();
            values.putNull("last_error_code");
            values.put("retry_count", 0);
            values.put("first_strike_at", 0);
            transition(asset.platformId, CameraRollPolicy.EV_RETRY_REQUESTED, values);
            moved++;
        }
        return moved;
    }

    synchronized Asset uploadedWithHash(String hash, String exceptId) {
        SQLiteDatabase db = getReadableDatabase();
        try (
            Cursor cursor = db.query(
                TABLE,
                null,
                "content_hash = ? AND node_id IS NOT NULL AND platform_id <> ?",
                new String[] { hash, exceptId },
                null,
                null,
                null,
                "1"
            )
        ) {
            return cursor.moveToNext() ? read(cursor) : null;
        }
    }

    synchronized boolean remotePathTaken(String remotePath, String platformId) {
        SQLiteDatabase db = getReadableDatabase();
        try (
            Cursor cursor = db.query(
                TABLE,
                new String[] { "platform_id" },
                "remote_path = ? AND platform_id <> ?",
                new String[] { remotePath, platformId },
                null,
                null,
                null,
                "1"
            )
        ) {
            return cursor.moveToNext();
        }
    }

    synchronized List<String> heldCursors() {
        List<String> held = new ArrayList<>();
        try (
            Cursor cursor = getReadableDatabase()
                .query(TABLE, new String[] { "chunk_cursor" }, "chunk_cursor IS NOT NULL AND status = ?", new String[] { CameraRollPolicy.PENDING }, null, null, null)
        ) {
            while (cursor.moveToNext()) {
                held.add(cursor.getString(0));
            }
        }
        return held;
    }

    synchronized Counts counts() {
        Counts counts = new Counts();
        try (
            Cursor cursor = getReadableDatabase()
                .rawQuery(
                    "SELECT "
                        + "SUM(CASE WHEN status IN ('pending', 'uploading') THEN 1 ELSE 0 END) AS pending_count, "
                        + "SUM(CASE WHEN node_id IS NOT NULL THEN 1 ELSE 0 END) AS uploaded_count, "
                        + "SUM(CASE WHEN status = 'failed_permanent' THEN 1 ELSE 0 END) AS failed_count, "
                        + "SUM(CASE WHEN status = 'unknown' THEN 1 ELSE 0 END) AS unknown_count "
                        + "FROM " + TABLE,
                    null
                )
        ) {
            if (cursor.moveToNext()) {
                counts.pending = cursor.getInt(0);
                counts.uploaded = cursor.getInt(1);
                counts.failed = cursor.getInt(2);
                counts.unknown = cursor.getInt(3);
            }
        }
        return counts;
    }

    private List<Asset> byStatus(SQLiteDatabase db, String status) {
        List<Asset> rows = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, null, "status = ?", new String[] { status }, null, null, null)) {
            while (cursor.moveToNext()) {
                rows.add(read(cursor));
            }
        }
        return rows;
    }

    private Asset find(SQLiteDatabase db, String platformId) {
        try (Cursor cursor = db.query(TABLE, null, "platform_id = ?", new String[] { platformId }, null, null, null)) {
            return cursor.moveToNext() ? read(cursor) : null;
        }
    }

    private static Asset read(Cursor cursor) {
        Asset asset = new Asset();
        asset.platformId = cursor.getString(cursor.getColumnIndexOrThrow("platform_id"));
        asset.kind = cursor.getString(cursor.getColumnIndexOrThrow("kind"));
        asset.relativeDir = cursor.getString(cursor.getColumnIndexOrThrow("relative_dir"));
        asset.displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"));
        asset.dateModified = cursor.getLong(cursor.getColumnIndexOrThrow("date_modified"));
        asset.size = cursor.getLong(cursor.getColumnIndexOrThrow("size"));
        asset.contentHash = cursor.getString(cursor.getColumnIndexOrThrow("content_hash"));
        asset.nodeId = cursor.getString(cursor.getColumnIndexOrThrow("node_id"));
        asset.remotePath = cursor.getString(cursor.getColumnIndexOrThrow("remote_path"));
        asset.status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
        asset.chunkCursor = cursor.getString(cursor.getColumnIndexOrThrow("chunk_cursor"));
        asset.retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count"));
        asset.lastErrorCode = cursor.getString(cursor.getColumnIndexOrThrow("last_error_code"));
        asset.firstStrikeAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_strike_at"));
        return asset;
    }
}
