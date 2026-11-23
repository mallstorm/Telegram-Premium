package org.telegram.messenger.telepremium.cloud.vault;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Fingerprint / face unlock that is cryptographically bound to the device: the folder key is wrapped by a
 * hardware Keystore key that can be used only right after a successful biometric check (CryptoObject).
 * Enrolling a new fingerprint invalidates it; the user then falls back to the secret.
 */
public final class VaultFolderBiometric {
    private VaultFolderBiometric() {}

    public interface KeyCallback { void onKey(byte[] folderKey); void onError(String message); }
    public interface DoneCallback { void onDone(String errorOrNull); }

    private static final String PREFS = "tp_folder_bio";
    private static final String ALIAS_PREFIX = "tp_fl_bio_";

    public static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && context.getPackageManager().hasSystemFeature("android.hardware.fingerprint");
    }

    public static boolean isEnrolled(Context context, String lockId) {
        return supported(context) && prefs(context).contains(lockId);
    }

    public static void forget(Context context, String lockId) {
        prefs(context).edit().remove(lockId).apply();
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS_PREFIX + lockId); }
        catch (Throwable ignore) {}
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static SecretKey createKey(String lockId) throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS_PREFIX + lockId,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 24) spec.setInvalidatedByBiometricEnrollment(true);
        gen.init(spec.build());
        return gen.generateKey();
    }

    private static SecretKey loadKey(String lockId) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return (SecretKey) ks.getKey(ALIAS_PREFIX + lockId, null);
    }

    private static void prompt(Activity activity, Cipher cipher, String title, String cancel,
                               CancellationSignal signal, BiometricPrompt.AuthenticationCallback callback) {
        BiometricPrompt p = new BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setNegativeButton(cancel, activity.getMainExecutor(), (d, w) -> callback.onAuthenticationError(
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, cancel))
                .build();
        p.authenticate(new BiometricPrompt.CryptoObject(cipher), signal, activity.getMainExecutor(), callback);
    }

    /** The folder must be open (key known). Stores the wrapped key only after a successful biometric check. */
    public static void enroll(Activity activity, String lockId, byte[] folderKey, String title, String cancel, DoneCallback done) {
        if (!supported(activity)) { done.onDone("Биометрия недоступна"); return; }
        try {
            forget(activity, lockId);
            SecretKey key = createKey(lockId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            final byte[] fk = folderKey.clone();
            prompt(activity, cipher, title, cancel, new CancellationSignal(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    try {
                        Cipher c = result.getCryptoObject().getCipher();
                        byte[] ct = c.doFinal(fk);
                        String blob = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
                        prefs(activity).edit().putString(lockId, blob).apply();
                        Arrays.fill(fk, (byte) 0);
                        done.onDone(null);
                    } catch (Throwable t) { Arrays.fill(fk, (byte) 0); forget(activity, lockId); done.onDone("Не удалось включить биометрию"); }
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    Arrays.fill(fk, (byte) 0); forget(activity, lockId); done.onDone(msg == null ? "Отменено" : msg.toString());
                }
            });
        } catch (Throwable t) { forget(activity, lockId); done.onDone("Не удалось включить биометрию"); }
    }

    public static CancellationSignal unlock(Activity activity, String lockId, String title, String cancel, KeyCallback callback) {
        CancellationSignal signal = new CancellationSignal();
        try {
            String blob = prefs(activity).getString(lockId, null);
            if (blob == null) { callback.onError("Биометрия не настроена"); return signal; }
            String[] parts = blob.split(":");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            final byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
            SecretKey key = loadKey(lockId);
            if (key == null) { forget(activity, lockId); callback.onError("Ключ биометрии недействителен — введите пароль"); return signal; }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            prompt(activity, cipher, title, cancel, signal, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    try { callback.onKey(result.getCryptoObject().getCipher().doFinal(ct)); }
                    catch (Throwable t) { callback.onError("Не удалось расшифровать ключ"); }
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                        callback.onError(msg == null ? "Ошибка биометрии" : msg.toString());
                }
            });
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            forget(activity, lockId);
            callback.onError("Отпечатки изменились — введите пароль и включите биометрию заново");
        } catch (Throwable t) {
            callback.onError("Биометрия недоступна — введите пароль");
        }
        return signal;
    }
}
