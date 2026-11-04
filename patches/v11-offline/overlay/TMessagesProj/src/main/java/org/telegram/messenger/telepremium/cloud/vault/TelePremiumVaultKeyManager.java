package org.telegram.messenger.telepremium.cloud.vault;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
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

/**
 * A single randomly generated 256-bit master key per TelePremium installation,
 * shared deliberately across Telegram accounts. Never embed it into an APK.
 *
 * A local AndroidKeyStore-wrapped copy is only released during an unlocked
 * cloud security session; an independent passphrase-wrapped recovery copy
 * permits restoring the SAME master on another device. Neither copy is sent to
 * a Telegram channel. A 12+ character recovery passphrase is mandatory.
 *
 * The password KDF is PBKDF2-HMAC-SHA256 (600k iterations). Argon2id should
 * replace it only after a reviewed and tested native KDF dependency is added.
 */
public final class TelePremiumVaultKeyManager {
    private static final String PREFS = "telepremium_e2ee_vault_v1";
    private static final String ALIAS = "telepremium.vault.master.wrap.v1";
    private static final String WRAPPED_LOCAL = "wrapped_local";
    private static final String WRAPPED_RECOVERY = "wrapped_recovery";
    private static final String RECOVERY_SALT = "recovery_salt";
    private static final String ENABLED = "enabled";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TelePremiumVaultKeyManager() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Explicitly configured, never auto-activated by ordinary uploads. */
    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    public static boolean hasMasterKey(Context context) {
        return prefs(context).contains(WRAPPED_LOCAL) && prefs(context).contains(WRAPPED_RECOVERY);
    }

    /**
     * Initialization is explicit, requires an existing unlocked screen gate and
     * rejects weak/reused app pattern as the recovery secret. Do not call while
     * legacy plaintext files remain unconverted: enabling is a migration decision.
     */
    public static synchronized void initialize(Context context, char[] recoveryPassphrase) throws Exception {
        checkScreenGate(context);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw new IllegalStateException("Android 8+ is required for the Vault password KDF");
        if (hasMasterKey(context)) throw new IllegalStateException("Vault master key already exists");
        validatePassphrase(recoveryPassphrase);
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        byte[] salt = random(32);
        try {
            byte[] local = wrap(getOrCreateKey(), master);
            byte[] recovery = wrap(derivePasswordKey(recoveryPassphrase, salt), master);
            if (!prefs(context).edit()
                    .putString(WRAPPED_LOCAL, b64(local))
                    .putString(WRAPPED_RECOVERY, b64(recovery))
                    .putString(RECOVERY_SALT, b64(salt))
                    .putBoolean(ENABLED, false)
                    .commit()) throw new IllegalStateException("Unable to save Vault key securely");
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    /**
     * Starts a NEW private Vault V3 namespace on every account. Existing V2
     * plaintext messages are untouched and are NOT magically encrypted.
     * UI must warn before invoking this method; legacy migration is separate.
     */
    public static synchronized void activateNewVault(Context context) {
        checkScreenGate(context);
        if (!hasMasterKey(context)) throw new IllegalStateException("Set up Vault recovery first");
        if (!prefs(context).edit().putBoolean(ENABLED,true).commit())
            throw new IllegalStateException("Unable to enable Vault");
    }

    /** Does not erase the master key or V3 ciphertext; legacy V2 stays separate. */
    public static synchronized void deactivateVault(Context context) {
        checkScreenGate(context);
        prefs(context).edit().putBoolean(ENABLED,false).commit();
    }

    /** Caller must erase returned bytes and keep them off disk. */
    public static byte[] getMasterKeyForUnlockedSession(Context context) throws Exception {
        checkScreenGate(context);
        if (!hasMasterKey(context)) throw new IllegalStateException("Vault not configured");
        return unwrap(getOrCreateKey(), fromB64(prefs(context).getString(WRAPPED_LOCAL, null)));
    }

    /** A manually exported recovery phrase remains off Telegram/cloud messages. */
    public static synchronized void recoverOnThisDevice(Context context, char[] passphrase,
                                                         String saltB64, String envelopeB64) throws Exception {
        checkScreenGate(context);
        if (hasMasterKey(context)) throw new IllegalStateException("Vault already configured; refusing to replace it");
        validatePassphrase(passphrase);
        byte[] salt = fromB64(saltB64);
        if (salt.length != 32) throw new IllegalArgumentException("Invalid recovery salt");
        byte[] master = unwrap(derivePasswordKey(passphrase, salt), fromB64(envelopeB64));
        try {
            if (master.length != 32) throw new IllegalArgumentException("Invalid recovered key");
            byte[] local = wrap(getOrCreateKey(), master);
            if (!prefs(context).edit()
                    .putString(WRAPPED_LOCAL, b64(local))
                    .putString(WRAPPED_RECOVERY, envelopeB64)
                    .putString(RECOVERY_SALT, saltB64)
                    .putBoolean(ENABLED, false)
                    .commit()) throw new IllegalStateException("Unable to restore Vault master key");
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public static String exportRecoveryEnvelope(Context context) {
        checkScreenGate(context);
        if (!hasMasterKey(context)) throw new IllegalStateException("Vault not configured");
        SharedPreferences p = prefs(context);
        // This object is ciphertext but MUST be stored privately and treated as sensitive.
        return "TPVAULT-RECOVERY-1:" + p.getString(RECOVERY_SALT, "") + ":"
                + p.getString(WRAPPED_RECOVERY, "");
    }

    private static void checkScreenGate(Context context) {
        if (!TelePremiumCloudSecurityStore.isProtectionEnabled(context)
                || !TelePremiumCloudSecuritySession.isUnlocked(context))
            throw new IllegalStateException("Unlock protected CloudGram before accessing the Vault master key");
    }

    private static void validatePassphrase(char[] password) {
        if (password == null || password.length < 12 || password.length > 512)
            throw new IllegalArgumentException("Recovery passphrase must contain 12–512 characters");
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
