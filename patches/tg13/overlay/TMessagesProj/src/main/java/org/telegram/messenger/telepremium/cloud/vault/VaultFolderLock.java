package org.telegram.messenger.telepremium.cloud.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-folder cryptographic lock ("FL1"). Pure Java, no Android dependencies.
 *
 * <p>A protected folder owns a random 256-bit <b>folder key</b>. Every file stored under that folder
 * is encrypted with the folder key (the existing {@link TelePremiumVaultCrypto} envelope is reused
 * unchanged: it simply receives the folder key instead of the installation master key). The folder
 * key itself is never stored in the clear. It is sealed in a lock record that lives inside the
 * already encrypted catalog:
 *
 * <pre>
 *   pw  = PBKDF2-HMAC-SHA256(secret, salt, iterations)            // the user's PIN / password / pattern
 *   kek = HMAC-SHA256(key = pw, "TPFL1/KEK" 00 lockId 00 master)   // binds the record to the folder AND to the master key
 *   env = AES-256-GCM(kek, nonce, aad = "TPFL1" 00 lockId 00 iterations, folderKey)
 * </pre>
 *
 * Consequences:
 * <ul>
 *   <li>There is <b>no stored verifier</b> of the secret: a wrong secret simply fails the GCM tag.</li>
 *   <li>Offline guessing needs both the catalog/master key (Keystore or recovery code) and a full
 *       PBKDF2 run per guess; the Telegram channel alone yields nothing.</li>
 *   <li>Leaking the master key (recovery code) does not open locked folders, and an attacker who
 *       can run code inside the app still needs the folder secret.</li>
 *   <li>A record cannot be moved to another folder: the lock id is bound into KEK and AAD.</li>
 * </ul>
 */
public final class VaultFolderLock {
    public static final String VERSION = "FL1";
    public static final int KEY_BYTES = 32;
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int MIN_ITERATIONS = 100_000;
    public static final int MAX_ITERATIONS = 5_000_000;
    private static final int SALT_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int KEY_ID_HEX = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Thrown when the supplied secret does not open the folder key. */
    public static final class WrongSecretException extends GeneralSecurityException {
        public WrongSecretException() { super("Wrong folder secret"); }
    }

    /** Immutable, serialisable lock record (stored on the folder node as a plain string). */
    public static final class Record {
        public final int iterations;
        public final byte[] salt;
        public final byte[] envelope;
        public final String keyId;

        private Record(int iterations, byte[] salt, byte[] envelope, String keyId) {
            this.iterations = iterations;
            this.salt = salt;
            this.envelope = envelope;
            this.keyId = keyId;
        }

        public String encode() {
            return VERSION + ":" + iterations + ":" + hex(salt) + ":" + hex(envelope) + ":" + keyId;
        }

        public static Record parse(String encoded) {
            if (encoded == null) throw new IllegalArgumentException("Missing lock record");
            String[] p = encoded.split(":", -1);
            if (p.length != 5 || !VERSION.equals(p[0])) throw new IllegalArgumentException("Unsupported lock record");
            int iterations;
            try { iterations = Integer.parseInt(p[1]); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid lock record"); }
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw new IllegalArgumentException("Invalid lock iterations");
            byte[] salt = unhex(p[2]);
            byte[] envelope = unhex(p[3]);
            if (salt.length != SALT_BYTES) throw new IllegalArgumentException("Invalid lock salt");
            if (envelope.length != NONCE_BYTES + KEY_BYTES + TAG_BYTES) throw new IllegalArgumentException("Invalid lock envelope");
            if (p[4].length() != KEY_ID_HEX) throw new IllegalArgumentException("Invalid lock key id");
            return new Record(iterations, salt, envelope, p[4]);
        }
    }

    private VaultFolderLock() {}

    public static byte[] newFolderKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    /** Seals {@code folderKey} under {@code secret}. The caller keeps ownership of every array. */
    public static Record seal(char[] secret, String lockId, byte[] master, byte[] folderKey, int iterations)
            throws GeneralSecurityException {
        requireArgs(secret, lockId, master);
        if (folderKey == null || folderKey.length != KEY_BYTES) throw new IllegalArgumentException("folder key");
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw new IllegalArgumentException("iterations");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] kek = deriveKek(secret, salt, iterations, lockId, master);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad(lockId, iterations));
            byte[] sealed = cipher.doFinal(folderKey);
            byte[] envelope = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, envelope, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, envelope, NONCE_BYTES, sealed.length);
            return new Record(iterations, salt, envelope, keyId(folderKey));
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    public static Record seal(char[] secret, String lockId, byte[] master, byte[] folderKey) throws GeneralSecurityException {
        return seal(secret, lockId, master, folderKey, DEFAULT_ITERATIONS);
    }

    /** Returns the folder key (caller must zero it) or throws {@link WrongSecretException}. */
    public static byte[] open(Record record, char[] secret, String lockId, byte[] master) throws GeneralSecurityException {
        requireArgs(secret, lockId, master);
        if (record == null) throw new IllegalArgumentException("record");
        byte[] kek = deriveKek(secret, record.salt, record.iterations, lockId, master);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_BYTES * 8, record.envelope, 0, NONCE_BYTES));
            cipher.updateAAD(aad(lockId, record.iterations));
            byte[] key = cipher.doFinal(record.envelope, NONCE_BYTES, record.envelope.length - NONCE_BYTES);
            if (!keyId(key).equals(record.keyId)) {
                Arrays.fill(key, (byte) 0);
                throw new WrongSecretException();
            }
            return key;
        } catch (AEADBadTagException e) {
            throw new WrongSecretException();
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** Re-seals the same folder key under a new secret (files are NOT re-encrypted). */
    public static Record rewrap(Record record, char[] oldSecret, char[] newSecret, String lockId, byte[] master)
            throws GeneralSecurityException {
        byte[] key = open(record, oldSecret, lockId, master);
        try {
            return seal(newSecret, lockId, master, key, Math.max(record.iterations, DEFAULT_ITERATIONS));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Stable public fingerprint of a folder key; reveals nothing usable about it. */
    public static String keyId(byte[] folderKey) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("TPFL1/KEYID".getBytes(StandardCharsets.US_ASCII));
        sha.update((byte) 0);
        byte[] digest = sha.digest(folderKey);
        try { return hex(digest).substring(0, KEY_ID_HEX); }
        finally { Arrays.fill(digest, (byte) 0); }
    }

    private static byte[] deriveKek(char[] secret, byte[] salt, int iterations, String lockId, byte[] master)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, 256);
        byte[] pw = null;
        try {
            pw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw, "HmacSHA256"));
            mac.update("TPFL1/KEK".getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            mac.update(lockId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(master);
            return mac.doFinal();
        } finally {
            spec.clearPassword();
            if (pw != null) Arrays.fill(pw, (byte) 0);
        }
    }

    private static byte[] aad(String lockId, int iterations) {
        return ("TPFL1\u0000" + lockId + "\u0000" + iterations).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireArgs(char[] secret, String lockId, byte[] master) {
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("secret");
        if (lockId == null || lockId.isEmpty()) throw new IllegalArgumentException("lockId");
        if (master == null || master.length != KEY_BYTES) throw new IllegalArgumentException("master");
    }

    static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(Character.forDigit((x >>> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return b.toString();
    }

    static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0) throw new IllegalArgumentException("hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("hex");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    /** Lower-case, locale-independent helper used by callers that need a stable id string. */
    public static String normalize(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
}
