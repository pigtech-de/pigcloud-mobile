package de.pigcloud.app;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class BackgroundKeyStore {

    static final int PAYLOAD_VERSION = 1;

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_PREFIX = "pigcloud_camera_roll_v1|";
    private static final String STORE_DIR = "camera-roll";
    private static final String TRANSFORM =
        KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final int TAG_BITS = 128;

    static final class Enrolled {
        final String material;
        final String apiKey;
        final String apiKeyId;
        final String endpoint;
        final String account;
        final long keyEpoch;

        Enrolled(String material, String apiKey, String apiKeyId, String endpoint, String account, long keyEpoch) {
            this.material = material;
            this.apiKey = apiKey;
            this.apiKeyId = apiKeyId;
            this.endpoint = endpoint;
            this.account = account;
            this.keyEpoch = keyEpoch;
        }
    }

    private final Context context;

    BackgroundKeyStore(Context context) {
        this.context = context.getApplicationContext();
    }

    static String scope(String endpoint, String account) {
        String raw = (endpoint == null ? "" : endpoint) + "|" + (account == null ? "" : account);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return "default";
        }
    }

    boolean isEnrolled(String scope) {
        return blobFile(scope).exists();
    }

    void store(String scope, String endpoint, String account, long keyEpoch, String material, String apiKey, String apiKeyId)
        throws Exception {
        JSONObject secrets = new JSONObject();
        secrets.put("material", material);
        secrets.put("api_key", apiKey);
        secrets.put("api_key_id", apiKeyId == null ? "" : apiKeyId);

        SecretKey key = loadKey(scope, true);
        if (key == null) {
            throw new IllegalStateException("keystore_unavailable");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(header(endpoint, account, keyEpoch));
        byte[] sealed = cipher.doFinal(secrets.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject record = new JSONObject();
        record.put("v", PAYLOAD_VERSION);
        record.put("endpoint", endpoint == null ? "" : endpoint);
        record.put("account", account == null ? "" : account);
        record.put("key_epoch", keyEpoch);
        record.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        record.put("blob", Base64.encodeToString(sealed, Base64.NO_WRAP));

        File target = blobFile(scope);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("store_dir_unavailable");
        }
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(record.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IllegalStateException("store_write_failed");
        }
    }

    Enrolled load(String scope) throws Exception {
        File source = blobFile(scope);
        if (!source.exists()) {
            return null;
        }
        JSONObject record = new JSONObject(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        if (record.optInt("v") != PAYLOAD_VERSION) {
            clear(scope);
            return null;
        }
        SecretKey key = loadKey(scope, false);
        if (key == null) {
            clear(scope);
            return null;
        }
        String endpoint = record.optString("endpoint", "");
        String account = record.optString("account", "");
        long keyEpoch = record.optLong("key_epoch", 0);
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(TAG_BITS, Base64.decode(record.getString("iv"), Base64.NO_WRAP))
            );
            cipher.updateAAD(header(endpoint, account, keyEpoch));
            plain = cipher.doFinal(Base64.decode(record.getString("blob"), Base64.NO_WRAP));
        } catch (KeyPermanentlyInvalidatedException e) {
            clear(scope);
            return null;
        }
        JSONObject secrets = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        java.util.Arrays.fill(plain, (byte) 0);
        return new Enrolled(
            secrets.optString("material", ""),
            secrets.optString("api_key", ""),
            secrets.optString("api_key_id", ""),
            endpoint,
            account,
            keyEpoch
        );
    }

    private static byte[] header(String endpoint, String account, long keyEpoch) {
        String canonical = PAYLOAD_VERSION
            + "\u0000" + (endpoint == null ? "" : endpoint)
            + "\u0000" + (account == null ? "" : account)
            + "\u0000" + keyEpoch;
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    void clear(String scope) {
        File target = blobFile(scope);
        if (target.exists() && !target.delete()) {
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(new byte[0]);
            } catch (Exception ignored) {
            }
            target.delete();
        }
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            store.deleteEntry(ALIAS_PREFIX + scope);
        } catch (Exception ignored) {
        }
    }

    private File blobFile(String scope) {
        return new File(new File(context.getFilesDir(), STORE_DIR), scope + ".bin");
    }

    private SecretKey loadKey(String scope, boolean createIfMissing) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS_PREFIX + scope, null);
        if (existing != null || !createIfMissing) {
            return existing;
        }
        return generateKey(scope, hasStrongBox());
    }

    private boolean hasStrongBox() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            && context.getPackageManager().hasSystemFeature("android.hardware.strongbox_keystore");
    }

    private SecretKey generateKey(String scope, boolean strongBox) throws Exception {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                ALIAS_PREFIX + scope,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(false);
            }
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true);
            }
            generator.init(builder.build());
            return generator.generateKey();
        } catch (StrongBoxUnavailableException e) {
            return strongBox ? generateKey(scope, false) : null;
        }
    }
}
