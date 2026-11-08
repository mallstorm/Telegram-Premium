package org.telegram.messenger.telepremium.cloud.vault;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** V3 encryption is automatic. One random 256-bit installation key is wrapped
 * with AndroidKeyStore. Screen protection and password-backed recovery are
 * optional. Existing keys and legacy recovery envelopes remain compatible.
 * Neither the master key nor its recovery code is uploaded to Telegram.
 */
public final class TelePremiumVaultKeyManager {
    private static final String PREFS = "telepremium_e2ee_vault_v1";
    private static final String ALIAS = "telepremium.vault.master.wrap.v1";
    private static final String WRAPPED_LOCAL = "wrapped_local";
    private static final String WRAPPED_RECOVERY = "wrapped_recovery";
    private static final String RECOVERY_SALT = "recovery_salt";
    private static final String ENABLED = "enabled";
    private static final String KEY_USED = "key_used";
    private static final String KEY_ID = "master_fingerprint";
    private static final String RECOVERY_CONFIRMED = "recovery_confirmed";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TelePremiumVaultKeyManager() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** V3 is the only backend, including for previously disabled installations. */
    public static boolean isEnabled(Context context) {
        return true;
    }

    public static boolean hasMasterKey(Context context) {
        return prefs(context).contains(WRAPPED_LOCAL);
    }

    public static boolean hasPasswordRecovery(Context context) {
        return prefs(context).contains(WRAPPED_RECOVERY) && prefs(context).contains(RECOVERY_SALT);
    }

    /** First use needs no setup. Existing or damaged keys are never regenerated. */
    public static synchronized void ensureMasterKey(Context context) throws Exception {
        checkScreenGate(context);
        if (hasMasterKey(context)) return;
        if (prefs(context).contains(WRAPPED_RECOVERY) || prefs(context).getBoolean(KEY_USED, false))
            throw new IllegalStateException("Восстановите существующий Master Key по коду восстановления");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            throw new IllegalStateException("Vault требует Android 6 или новее");
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        try {
            if (!prefs(context).edit().putString(WRAPPED_LOCAL, b64(wrap(getOrCreateKey(), master)))
                    .putString(KEY_ID, fingerprint(master))
                    .putBoolean(KEY_USED, false).putBoolean(RECOVERY_CONFIRMED, false).commit())
                throw new IllegalStateException("Не удалось сохранить ключ Vault");
        } finally { Arrays.fill(master, (byte) 0); }
    }

    public static synchronized void markKeyUsed(Context context) throws Exception {
        if (!prefs(context).edit().putBoolean(KEY_USED, true).commit())
            throw new IllegalStateException("Не удалось сохранить состояние ключа Vault");
    }

    /** Only the explicit, persisted all-account reset may rotate an existing key. Idempotent after a crash. */
    public static synchronized void rotateAfterCloudReset(Context context, String operation) throws Exception {
        if (!TelePremiumCloudResetState.mayRotate(operation)) throw new IllegalStateException("Reset is not ready for key rotation");
        checkScreenOnly(context);
        SharedPreferences active = prefs(context);
        if (operation.equals(active.getString("last_reset_id", null))) {
            if (!active.edit().putString("last_reset_id", operation).commit()) throw new IllegalStateException("Не удалось подтвердить сохранение нового ключа");
            return;
        }
        SharedPreferences archive = context.getSharedPreferences("telepremium_vault_retired_keys_v1", 0);
        if (!archive.getBoolean(operation + ".saved", false)) {
            SharedPreferences.Editor backup = archive.edit();
            for (String field : new String[]{WRAPPED_LOCAL, WRAPPED_RECOVERY, RECOVERY_SALT, KEY_ID})
                if (active.contains(field)) backup.putString(operation + "." + field, active.getString(field, null));
            if (!backup.putBoolean(operation + ".saved", true).commit())
                throw new IllegalStateException("Не удалось сохранить прежний зашифрованный ключ");
        } else if (!archive.edit().putBoolean(operation + ".saved", true).commit()) {
            // A previous failed commit may have changed memory without reaching disk.
            throw new IllegalStateException("Не удалось подтвердить сохранение прежнего ключа");
        }
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        try {
            if (!active.edit().putString(WRAPPED_LOCAL, b64(wrap(getOrCreateKey(), master)))
                    .putString(KEY_ID, fingerprint(master)).putString("last_reset_id", operation)
                    .remove(WRAPPED_RECOVERY).remove(RECOVERY_SALT)
                    .putBoolean(KEY_USED, false).putBoolean(RECOVERY_CONFIRMED, false).commit())
                throw new IllegalStateException("Не удалось сохранить новый ключ Vault");
        } finally { Arrays.fill(master, (byte) 0); }
    }

    /** Optional password backup of the same automatically provisioned key. */
    public static synchronized void initialize(Context context, char[] recoveryPassphrase) throws Exception {
        checkScreenGate(context);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw new IllegalStateException("Android 8+ is required for the Vault password KDF");
        validatePassphrase(recoveryPassphrase);
        byte[] master = getMasterKeyForUnlockedSession(context);
        byte[] salt = random(32);
        try {
            byte[] local = wrap(getOrCreateKey(), master);
            byte[] recovery = wrap(derivePasswordKey(recoveryPassphrase, salt), master);
            if (!prefs(context).edit()
                    .putString(WRAPPED_LOCAL, b64(local))
                    .putString(WRAPPED_RECOVERY, b64(recovery))
                    .putString(RECOVERY_SALT, b64(salt))
                    .putBoolean(ENABLED, true)
                    .putBoolean(RECOVERY_CONFIRMED, false)
                    .commit()) throw new IllegalStateException("Unable to save Vault key securely");
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    /** Caller must erase returned bytes and keep them off disk. */
    public static byte[] getMasterKeyForUnlockedSession(Context context) throws Exception {
        ensureMasterKey(context);
        byte[] master = unwrap(getOrCreateKey(), fromB64(prefs(context).getString(WRAPPED_LOCAL, null)));
        try {
            if (!prefs(context).contains(KEY_ID) && !prefs(context).edit().putString(KEY_ID, fingerprint(master)).commit())
                throw new IllegalStateException("Не удалось сохранить состояние ключа Vault");
            return master;
        } catch (Exception e) { Arrays.fill(master, (byte) 0); throw e; }
    }

    /** A manually exported recovery phrase remains off Telegram/cloud messages. */
    public static synchronized void recoverOnThisDevice(Context context, char[] passphrase,
                                                         String saltB64, String envelopeB64) throws Exception {
        if (TelePremiumCloudResetState.pending()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
        checkScreenGate(context);
        validatePassphrase(passphrase);
        byte[] salt = fromB64(saltB64);
        if (salt.length != 32) throw new IllegalArgumentException("Invalid recovery salt");
        byte[] master = unwrap(derivePasswordKey(passphrase, salt), fromB64(envelopeB64));
        try {
            if (master.length != 32) throw new IllegalArgumentException("Invalid recovered key");
            // This also repairs a lost AndroidKeyStore wrapper on legacy installs.
            boolean sameBackup = saltB64.equals(prefs(context).getString(RECOVERY_SALT, null))
                    && envelopeB64.equals(prefs(context).getString(WRAPPED_RECOVERY, null));
            if (!sameBackup) requireSafeRecovery(context, master);
            byte[] local = wrap(getOrCreateKey(), master);
            if (!prefs(context).edit()
                    .putString(WRAPPED_LOCAL, b64(local))
                    .putString(KEY_ID, fingerprint(master))
                    .putString(WRAPPED_RECOVERY, envelopeB64)
                    .putString(RECOVERY_SALT, saltB64)
                    .putBoolean(ENABLED, true)
                    .putBoolean(KEY_USED, true)
                    .putBoolean(RECOVERY_CONFIRMED, true)
                    .commit()) throw new IllegalStateException("Unable to restore Vault master key");
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    /** True once the user confirmed that the recovery code is stored outside this device. */
    public static boolean isRecoveryConfirmed(Context context) {
        return prefs(context).getBoolean(RECOVERY_CONFIRMED, false);
    }

    public static void setRecoveryConfirmed(Context context, boolean confirmed) {
        prefs(context).edit().putBoolean(RECOVERY_CONFIRMED, confirmed).commit();
    }

    /**
     * Checks that the passphrase still opens the stored recovery envelope and yields the
     * same master key as the local AndroidKeyStore copy. Nothing is written.
     */
    public static boolean verifyRecoveryPassphrase(Context context, char[] passphrase) throws Exception {
        checkScreenGate(context);
        if (!hasPasswordRecovery(context)) return false;
        SharedPreferences p = prefs(context);
        byte[] salt = fromB64(p.getString(RECOVERY_SALT, null));
        byte[] fromRecovery = null;
        byte[] fromLocal = null;
        try {
            try {
                fromRecovery = unwrap(derivePasswordKey(passphrase, salt), fromB64(p.getString(WRAPPED_RECOVERY, null)));
            } catch (javax.crypto.AEADBadTagException wrongPassphrase) {
                return false;
            }
            fromLocal = unwrap(getOrCreateKey(), fromB64(p.getString(WRAPPED_LOCAL, null)));
            return java.security.MessageDigest.isEqual(fromRecovery, fromLocal);
        } finally {
            if (fromRecovery != null) Arrays.fill(fromRecovery, (byte) 0);
            if (fromLocal != null) Arrays.fill(fromLocal, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public static String exportRecoveryEnvelope(Context context) throws Exception {
        checkScreenGate(context);
        ensureMasterKey(context);
        if (!hasPasswordRecovery(context)) {
            byte[] master = getMasterKeyForUnlockedSession(context);
            try { return "TPVAULT-KEY-1:" + b64(master) + ":" + recoveryChecksum(master); }
            finally { Arrays.fill(master, (byte) 0); }
        }
        SharedPreferences p = prefs(context);
        // This object is ciphertext but MUST be stored privately and treated as sensitive.
        return "TPVAULT-RECOVERY-1:" + p.getString(RECOVERY_SALT, "") + ":"
                + p.getString(WRAPPED_RECOVERY, "");
    }

    private static void checkScreenGate(Context context) {
        if (!TelePremiumCloudResetState.allowed(TelePremiumCloudResetState.epoch()))
            throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
        checkScreenOnly(context);
    }

    private static void checkScreenOnly(Context context) {
        if (TelePremiumCloudSecurityStore.isProtectionEnabled(context)
                && !TelePremiumCloudSecuritySession.isUnlocked(context))
            throw new IllegalStateException("Unlock protected CloudGram before accessing the Vault master key");
    }

    private static void validatePassphrase(char[] password) {
        if (password == null || password.length < 12 || password.length > 512)
            throw new IllegalArgumentException("Recovery passphrase must contain 12–512 characters");
    }

    /** Accept existing password-backed codes and the new standalone secret key. */
    public static synchronized void recoverFromCode(Context context, String code, char[] password) throws Exception {
        if (TelePremiumCloudResetState.pending()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
        checkScreenGate(context);
        String[] parts = code.trim().split(":", 3);
        if (parts.length == 3 && "TPVAULT-RECOVERY-1".equals(parts[0])) {
            recoverOnThisDevice(context, password, parts[1], parts[2]);
            return;
        }
        if (parts.length != 3 || !"TPVAULT-KEY-1".equals(parts[0]))
            throw new IllegalArgumentException("Неверный формат кода восстановления");
        byte[] master = fromB64(parts[1]);
        try {
            if (master.length != 32 || !recoveryChecksum(master).equals(parts[2]))
                throw new IllegalArgumentException("Код восстановления повреждён");
            requireSafeRecovery(context, master);
            if (!prefs(context).edit().putString(WRAPPED_LOCAL, b64(wrap(getOrCreateKey(), master)))
                    .putString(KEY_ID, fingerprint(master))
                    .remove(WRAPPED_RECOVERY).remove(RECOVERY_SALT)
                    .putBoolean(KEY_USED, true).putBoolean(RECOVERY_CONFIRMED, true).commit())
                throw new IllegalStateException("Не удалось восстановить Master Key");
        } finally { Arrays.fill(master, (byte) 0); }
    }

    private static void requireSafeRecovery(Context context, byte[] recovered) throws Exception {
        if (!hasMasterKey(context)) return;
        byte[] current;
        try { current = getMasterKeyForUnlockedSession(context); }
        catch (Exception unavailableWrapper) {
            if (fingerprint(recovered).equals(prefs(context).getString(KEY_ID, null))) return;
            throw new IllegalStateException("Код не соответствует сохранённому ключу Vault", unavailableWrapper);
        }
        boolean same;
        try { same = java.security.MessageDigest.isEqual(current, recovered); }
        finally { Arrays.fill(current, (byte) 0); }
        // A fresh device may already have an unused automatically created key.
        // Legacy configured installations are conservatively considered used.
        if (!same && prefs(context).getBoolean(KEY_USED, hasPasswordRecovery(context)))
            throw new IllegalStateException("На устройстве уже есть другой ключ с данными Vault; замена запрещена");
    }

    private static String recoveryChecksum(byte[] master) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(master);
        StringBuilder result = new StringBuilder(8);
        for (int i = 0; i < 4; i++) result.append(String.format(java.util.Locale.US, "%02x", digest[i] & 255));
        Arrays.fill(digest, (byte) 0);
        return result.toString();
    }

    private static String fingerprint(byte[] master) throws Exception {
        return b64(java.security.MessageDigest.getInstance("SHA-256").digest(master));
    }

    private static byte[] random(int len) {
        byte[] bytes = new byte[len]; RANDOM.nextBytes(bytes); return bytes;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry existing = store.getEntry(ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS).setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }

    private static SecretKey derivePasswordKey(char[] passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS);
        try { return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded(), "AES");
        } finally { spec.clearPassword(); }
    }

    private static byte[] wrap(SecretKey wrappingKey, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
        cipher.updateAAD("TPVAULT-MASTER-WRAP-V1".getBytes(StandardCharsets.US_ASCII));
        byte[] nonce = cipher.getIV();
        byte[] enc = cipher.doFinal(plaintext);
        byte[] result = new byte[nonce.length + enc.length];
        System.arraycopy(nonce, 0, result, 0, nonce.length);
        System.arraycopy(enc, 0, result, nonce.length, enc.length);
        return result;
    }

    private static byte[] unwrap(SecretKey wrappingKey, byte[] envelope) throws Exception {
        if (envelope == null || envelope.length < 12 + 16 + 1) throw new IllegalArgumentException("Invalid key envelope");
        byte[] nonce = Arrays.copyOf(envelope, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, nonce));
        cipher.updateAAD("TPVAULT-MASTER-WRAP-V1".getBytes(StandardCharsets.US_ASCII));
        return cipher.doFinal(envelope, 12, envelope.length - 12);
    }

    private static String b64(byte[] in) { return Base64.encodeToString(in, Base64.NO_WRAP); }
    private static byte[] fromB64(String in) {
        if (in == null || in.isEmpty()) throw new IllegalArgumentException("Missing Vault envelope");
        return Base64.decode(in, Base64.NO_WRAP);
    }
}
