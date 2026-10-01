package de.pigcloud.app;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.concurrent.Executor;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

@CapacitorPlugin(name = "KeyVault")
public class KeyVaultPlugin extends Plugin {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "pigcloud_app_lock_v2";
    private static final String VAULT_FILE = "app-lock-vault.json";
    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;
    private static final int STORE_TOKEN_BYTES = 16;

    private static OAEPParameterSpec oaepSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
    }

    private volatile boolean locked = true;
    private volatile String storeToken = null;

    private String mintStoreToken() {
        byte[] raw = new byte[STORE_TOKEN_BYTES];
        new SecureRandom().nextBytes(raw);
        storeToken = Base64.encodeToString(raw, Base64.NO_WRAP);
        return storeToken;
    }

    private boolean consumeStoreToken(String offered) {
        String expected = storeToken;
        if (expected == null || offered == null) return false;
        boolean ok = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            offered.getBytes(StandardCharsets.UTF_8)
        );
        if (ok) storeToken = null;
        return ok;
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        locked = true;
    }

    @Override
    protected void handleOnStop() {
        super.handleOnStop();
        locked = true;
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", BiometricManager.from(getContext()).canAuthenticate(AUTHENTICATORS)
            == BiometricManager.BIOMETRIC_SUCCESS);
        result.put("strongBox", hasStrongBox());
        result.put("hasVault", vaultFile().exists());
        result.put("locked", locked);
        call.resolve(result);
    }

    @PluginMethod
    public void hasVault(PluginCall call) {
        boolean present = vaultFile().exists();
        JSObject result = new JSObject();
        result.put("present", present);
        result.put("locked", locked);
        if (!present) {
            result.put("storeToken", mintStoreToken());
        }
        call.resolve(result);
    }

    @PluginMethod
    public void store(PluginCall call) {
        String data = call.getString("data");
        if (data == null || data.isEmpty()) {
            call.reject("bad_input");
            return;
        }
        if (!consumeStoreToken(call.getString("token"))) {
            call.reject("store_not_allowed");
            return;
        }
        SecretKey dataKey = null;
        byte[] encoded = null;
        try {
            PublicKey pub = loadOrCreatePublicKey();
            if (pub == null) {
                call.reject("unavailable");
                return;
            }
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            dataKey = gen.generateKey();

            Cipher aes = Cipher.getInstance(AES_TRANSFORM);
            aes.init(Cipher.ENCRYPT_MODE, dataKey);
            byte[] ct = aes.doFinal(data.getBytes(StandardCharsets.UTF_8));

            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.ENCRYPT_MODE, pub, oaepSpec());
            encoded = dataKey.getEncoded();
            byte[] wrappedKey = rsa.doFinal(encoded);

            JSONObject vault = new JSONObject();
            vault.put("v", 1);
            vault.put("key", Base64.encodeToString(wrappedKey, Base64.NO_WRAP));
            vault.put("iv", Base64.encodeToString(aes.getIV(), Base64.NO_WRAP));
            vault.put("ct", Base64.encodeToString(ct, Base64.NO_WRAP));
            writeVault(vault.toString());
            call.resolve();
        } catch (KeyPermanentlyInvalidatedException e) {
            clearVault();
            call.reject("key_invalidated");
        } catch (Exception e) {
            call.reject("store_failed");
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            destroyKey(dataKey);
        }
    }

    @PluginMethod
    public void unlock(final PluginCall call) {
        if (!locked) {
            call.reject("not_locked");
            return;
        }
        final JSONObject vault;
        final Cipher rsa;
        try {
            String raw = readVault();
            if (raw == null) {
                call.reject("no_key");
                return;
            }
            vault = new JSONObject(raw);
            PrivateKey priv = loadPrivateKey();
            if (priv == null) {
                call.reject("no_key");
                return;
            }
            rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.DECRYPT_MODE, priv, oaepSpec());
        } catch (KeyPermanentlyInvalidatedException e) {
            clearVault();
            call.reject("key_invalidated");
            return;
        } catch (Exception e) {
            call.reject("unwrap_failed");
            return;
        }

        final FragmentActivity activity = (FragmentActivity) getActivity();
        final Executor executor = ContextCompat.getMainExecutor(getContext());
        activity.runOnUiThread(() -> {
            BiometricPrompt prompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    byte[] dataKeyBytes = null;
                    byte[] plain = null;
                    SecretKey dataKey = null;
                    try {
                        Cipher authed = result.getCryptoObject().getCipher();
                        dataKeyBytes = authed.doFinal(Base64.decode(vault.getString("key"), Base64.NO_WRAP));
                        dataKey = new SecretKeySpec(dataKeyBytes, "AES");
                        Cipher aes = Cipher.getInstance(AES_TRANSFORM);
                        aes.init(
                            Cipher.DECRYPT_MODE,
                            dataKey,
                            new GCMParameterSpec(TAG_BITS, Base64.decode(vault.getString("iv"), Base64.NO_WRAP))
                        );
                        plain = aes.doFinal(Base64.decode(vault.getString("ct"), Base64.NO_WRAP));
                        JSObject out = new JSObject();
                        out.put("data", new String(plain, StandardCharsets.UTF_8));
                        out.put("storeToken", mintStoreToken());
                        locked = false;
                        call.resolve(out);
                    } catch (Exception e) {
                        call.reject("unwrap_failed");
                    } finally {
                        if (dataKeyBytes != null) Arrays.fill(dataKeyBytes, (byte) 0);
                        if (plain != null) Arrays.fill(plain, (byte) 0);
                        destroyKey(dataKey);
                    }
                }

                @Override
                public void onAuthenticationError(int code, CharSequence message) {
                    if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        call.reject("cancelled");
                    } else {
                        call.reject("auth_failed");
                    }
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getContext().getString(R.string.app_lock_prompt_title))
                .setSubtitle(getContext().getString(R.string.app_lock_prompt_subtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setNegativeButtonText(getContext().getString(R.string.app_lock_prompt_cancel))
                .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(rsa));
        });
    }

    @PluginMethod
    public void clear(PluginCall call) {
        clearVault();
        deleteKey();
        storeToken = null;
        call.resolve();
    }

    private static void destroyKey(SecretKey key) {
        if (key == null) return;
        try {
            key.destroy();
        } catch (Exception e) {
            return;
        }
    }

    private File vaultFile() {
        return new File(getContext().getFilesDir(), VAULT_FILE);
    }

    private void writeVault(String json) throws Exception {
        Files.write(vaultFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private String readVault() throws Exception {
        File file = vaultFile();
        if (!file.exists()) return null;
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void clearVault() {
        File file = vaultFile();
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private boolean hasStrongBox() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            && getContext().getPackageManager().hasSystemFeature("android.hardware.strongbox_keystore");
    }

    private PublicKey loadOrCreatePublicKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            java.security.cert.Certificate cert = store.getCertificate(KEY_ALIAS);
            if (cert != null) return cert.getPublicKey();
        }
        return generateKeyPair(hasStrongBox());
    }

    private PrivateKey loadPrivateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return (PrivateKey) store.getKey(KEY_ALIAS, null);
    }

    private PublicKey generateKeyPair(boolean strongBox) throws Exception {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setKeySize(2048)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            }
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true);
            }
            generator.initialize(builder.build());
            return generator.generateKeyPair().getPublic();
        } catch (StrongBoxUnavailableException e) {
            return strongBox ? generateKeyPair(false) : null;
        }
    }

    private void deleteKey() {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            store.deleteEntry(KEY_ALIAS);
        } catch (Exception e) {
        }
    }
}
