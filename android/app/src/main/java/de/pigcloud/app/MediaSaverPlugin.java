package de.pigcloud.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@CapacitorPlugin(
    name = "MediaSaver",
    permissions = { @Permission(alias = "legacyStorage", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }) }
)
public class MediaSaverPlugin extends Plugin {

    private static final String LEGACY_STORAGE = "legacyStorage";
    private static final String SUBDIR = "PigCloud";
    private static final String DEFAULT_MIME = "application/octet-stream";

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    private static final class Session {

        OutputStream stream;
        Uri uri;
        File file;
    }

    @PluginMethod
    public void saveStart(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && getPermissionState(LEGACY_STORAGE) != PermissionState.GRANTED) {
            requestPermissionForAlias(LEGACY_STORAGE, call, "legacyStorageCallback");
            return;
        }
        openSession(call);
    }

    @PermissionCallback
    private void legacyStorageCallback(PluginCall call) {
        if (getPermissionState(LEGACY_STORAGE) != PermissionState.GRANTED) {
            call.reject("Storage permission was denied", "permission_denied");
            return;
        }
        openSession(call);
    }

    private void openSession(PluginCall call) {
        String filename = sanitizeFilename(call.getString("filename"));
        String mimeType = call.getString("mimeType");
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = DEFAULT_MIME;
        }

        try {
            Session session = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? openMediaStoreSession(filename, mimeType)
                : openLegacySession(filename);
            String id = "ms" + counter.incrementAndGet();
            sessions.put(id, session);

            JSObject result = new JSObject();
            result.put("id", id);
            call.resolve(result);
        } catch (IOException e) {
            call.reject("Could not open the file for writing", "save_failed", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private Session openMediaStoreSession(String filename, String mimeType) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore refused the entry");
        }

        OutputStream stream;
        try {
            stream = resolver.openOutputStream(uri);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        if (stream == null) {
            resolver.delete(uri, null, null);
            throw new IOException("MediaStore returned no output stream");
        }

        Session session = new Session();
        session.uri = uri;
        session.stream = stream;
        return session;
    }

    private Session openLegacySession(String filename) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir.getAbsolutePath());
        }

        Session session = new Session();
        session.file = uniqueFile(dir, filename);
        session.stream = new FileOutputStream(session.file);
        return session;
    }

    @PluginMethod
    public void saveChunk(PluginCall call) {
        String id = call.getString("id");
        Session session = id == null ? null : sessions.get(id);
        if (session == null) {
            call.reject("Unknown save session", "no_session");
            return;
        }

        String data = call.getString("data");
        if (data == null) {
            call.reject("Missing chunk data", "bad_chunk");
            return;
        }

        byte[] bytes;
        try {
            bytes = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("Chunk was not valid base64", "bad_chunk", e);
            return;
        }

        try {
            synchronized (session) {
                session.stream.write(bytes);
            }
            call.resolve();
        } catch (IOException e) {
            sessions.remove(id);
            discard(session);
            call.reject("Could not write the chunk", "save_failed", e);
        }
    }

    @PluginMethod
    public void saveFinish(PluginCall call) {
        String id = call.getString("id");
        Session session = id == null ? null : sessions.remove(id);
        if (session == null) {
            call.reject("Unknown save session", "no_session");
            return;
        }

        try {
            synchronized (session) {
                session.stream.flush();
                session.stream.close();
            }
        } catch (IOException e) {
            discard(session);
            call.reject("Could not finish the file", "save_failed", e);
            return;
        }

        String uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && session.uri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContext().getContentResolver().update(session.uri, values, null, null);
            uri = session.uri.toString();
        } else {
            MediaScannerConnection.scanFile(getContext(), new String[] { session.file.getAbsolutePath() }, null, null);
            uri = Uri.fromFile(session.file).toString();
        }

        JSObject result = new JSObject();
        result.put("uri", uri);
        call.resolve(result);
    }

    @PluginMethod
    public void saveAbort(PluginCall call) {
        String id = call.getString("id");
        Session session = id == null ? null : sessions.remove(id);
        if (session != null) {
            discard(session);
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        for (Session session : sessions.values()) {
            discard(session);
        }
        sessions.clear();
    }

    private void discard(Session session) {
        try {
            synchronized (session) {
                session.stream.close();
            }
        } catch (IOException ignored) {}

        if (session.uri != null) {
            try {
                getContext().getContentResolver().delete(session.uri, null, null);
            } catch (RuntimeException ignored) {}
        } else if (session.file != null) {
            session.file.delete();
        }
    }

    private static File uniqueFile(File dir, String filename) {
        File candidate = new File(dir, filename);
        if (!candidate.exists()) {
            return candidate;
        }

        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    private static String sanitizeFilename(String raw) {
        String name = raw == null ? "" : raw.trim();
        name = name.replaceAll("[/\\\\]", "_");
        name = name.replaceAll("[\\x00-\\x1F\\x7F]", "");
        if (name.equals(".") || name.equals("..")) {
            name = "";
        }
        return name.isEmpty() ? "download" : name;
    }
}
