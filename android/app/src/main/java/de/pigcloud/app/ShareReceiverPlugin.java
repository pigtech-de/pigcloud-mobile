package de.pigcloud.app;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@CapacitorPlugin(name = "ShareReceiver")
public class ShareReceiverPlugin extends Plugin {

    private static final int CHUNK_BYTES = 6 * 1024 * 1024;
    private static final String EVENT_SHARE_RECEIVED = "shareReceived";

    private final Map<String, Uri> pending = new LinkedHashMap<>();
    private final Map<String, InputStream> streams = new LinkedHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    @Override
    public void load() {
        collect(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (collect(intent)) {
            notifyListeners(EVENT_SHARE_RECEIVED, new JSObject(), true);
        }
    }

    private boolean collect(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                uris.add(uri);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                uris.addAll(list);
            }
        }
        if (uris.isEmpty()) {
            return false;
        }
        synchronized (pending) {
            for (Uri uri : uris) {
                pending.put("sh" + counter.incrementAndGet(), uri);
            }
        }
        intent.removeExtra(Intent.EXTRA_STREAM);
        return true;
    }

    @PluginMethod
    public void pending(PluginCall call) {
        JSArray files = new JSArray();
        synchronized (pending) {
            for (Map.Entry<String, Uri> entry : pending.entrySet()) {
                files.put(describe(entry.getKey(), entry.getValue()));
            }
        }
        JSObject result = new JSObject();
        result.put("files", files);
        call.resolve(result);
    }

    private JSObject describe(String id, Uri uri) {
        ContentResolver resolver = getContext().getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException ignored) {}
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        String mimeType = resolver.getType(uri);

        JSObject file = new JSObject();
        file.put("id", id);
        file.put("name", name == null ? "shared" : name);
        file.put("mimeType", mimeType == null ? "application/octet-stream" : mimeType);
        file.put("size", size);
        return file;
    }

    @PluginMethod
    public void readChunk(PluginCall call) {
        String id = call.getString("id");
        Uri uri;
        synchronized (pending) {
            uri = id == null ? null : pending.get(id);
        }
        if (uri == null) {
            call.reject("Unknown shared file", "no_file");
            return;
        }

        try {
            InputStream stream;
            synchronized (streams) {
                stream = streams.get(id);
                if (stream == null) {
                    stream = getContext().getContentResolver().openInputStream(uri);
                    if (stream == null) {
                        throw new IOException("No stream for " + uri);
                    }
                    streams.put(id, stream);
                }
            }

            byte[] buffer = new byte[CHUNK_BYTES];
            int filled = 0;
            int read;
            while (filled < CHUNK_BYTES && (read = stream.read(buffer, filled, CHUNK_BYTES - filled)) != -1) {
                filled += read;
            }
            boolean done = filled < CHUNK_BYTES;
            if (done) {
                release(id);
            }

            JSObject result = new JSObject();
            result.put("data", filled == 0 ? "" : Base64.encodeToString(buffer, 0, filled, Base64.NO_WRAP));
            result.put("done", done);
            call.resolve(result);
        } catch (IOException | SecurityException e) {
            release(id);
            call.reject("Could not read the shared file", "read_failed", e);
        }
    }

    @PluginMethod
    public void release(PluginCall call) {
        String id = call.getString("id");
        if (id != null) {
            release(id);
        }
        call.resolve();
    }

    private void release(String id) {
        InputStream stream;
        synchronized (streams) {
            stream = streams.remove(id);
        }
        synchronized (pending) {
            pending.remove(id);
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {}
        }
    }

    @Override
    protected void handleOnDestroy() {
        List<String> ids;
        synchronized (pending) {
            ids = new ArrayList<>(pending.keySet());
        }
        for (String id : ids) {
            release(id);
        }
    }
}
