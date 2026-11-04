package org.telegram.messenger.telepremium.cloud.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TelePremium Vault v1 authenticated, chunked encryption format.
 *
 * This is a pure-Java cryptographic building block; it DOES NOT manage keys,
 * activate E2EE, intercept Telegram uploads, or encrypt existing V2 snapshots.
 * Use only with a uniformly random 256-bit secret vault master key.
 *
 * Format: magic(8) | version(1) | salt(16) | nonce-prefix(8) |
 *         chunk-size(4) | chunks: length(4) + ciphertext/tag(length), ...
 * The stream ends with an authenticated, empty final chunk (16-byte tag).
 * All chunk counters, purpose and header are authenticated via GCM AAD.
 */
public final class TelePremiumVaultCrypto {
    private static final byte[] MAGIC = "TPVAULT1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int MASTER_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int HEADER_BYTES = 8 + 1 + SALT_BYTES + NONCE_PREFIX_BYTES + 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Purpose { FILE, INDEX, THUMBNAIL }

    private TelePremiumVaultCrypto() {}

    public static byte[] newMasterKey() {
        byte[] bytes = new byte[MASTER_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static boolean isVaultFile(byte[] prefix) {
        if (prefix == null || prefix.length < MAGIC.length) return false;
        int mismatch = 0;
        for (int i = 0; i < MAGIC.length; i++) mismatch |= prefix[i] ^ MAGIC[i];
        return mismatch == 0;
    }

    public static void encrypt(InputStream plain, OutputStream encrypted, byte[] master, Purpose purpose)
            throws GeneralSecurityException, IOException {
        validate(plain, encrypted, master, purpose);
        byte[] salt = random(SALT_BYTES);
        byte[] noncePrefix = random(NONCE_PREFIX_BYTES);
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream(HEADER_BYTES);
        DataOutputStream headerWriter = new DataOutputStream(headerBuffer);
        headerWriter.write(MAGIC);
        headerWriter.writeByte(VERSION);
        headerWriter.write(salt);
        headerWriter.write(noncePrefix);
        headerWriter.writeInt(CHUNK_SIZE);
        headerWriter.flush();
        byte[] header = headerBuffer.toByteArray();
        encrypted.write(header);

        byte[] subkey = hkdf(master, salt, purpose);
        try {
            DataOutputStream output = new DataOutputStream(encrypted);
            byte[] inputChunk = new byte[CHUNK_SIZE];
            long chunkIndex = 0;
            int n;
            while ((n = fillChunk(plain, inputChunk)) != -1) {
                if (chunkIndex >= 0xffffffffL) throw new IOException("Vault stream too large");
                byte[] cipherText = process(Cipher.ENCRYPT_MODE, subkey, noncePrefix, header,
                        chunkIndex++, false, inputChunk, n);
                output.writeInt(cipherText.length);
                output.write(cipherText);
            }
            if (chunkIndex >= 0xffffffffL) throw new IOException("Vault stream too large");
            byte[] terminator = process(Cipher.ENCRYPT_MODE, subkey, noncePrefix, header,
                    chunkIndex, true, new byte[0], 0);
            output.writeInt(terminator.length);
            output.write(terminator);
            output.flush();
        } finally {
            Arrays.fill(subkey, (byte) 0);
        }
    }

    /**
     * Decrypt into a disposable temporary sink. Callers MUST NOT publish/use the
     * output until this method returns successfully; a tag failure can occur late.
     */
    public static void decrypt(InputStream encrypted, OutputStream plain, byte[] master, Purpose purpose)
            throws GeneralSecurityException, IOException {
        validate(encrypted, plain, master, purpose);
        DataInputStream input = new DataInputStream(encrypted);
        byte[] header = new byte[HEADER_BYTES];
        input.readFully(header); // EOF is an error: refuse truncated header
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) throw new IOException("Unknown Vault envelope");
        }
        if ((header[8] & 0xff) != VERSION) throw new IOException("Unsupported Vault version");
        int configuredChunkSize = bytesToInt(header, HEADER_BYTES - 4);
        if (configuredChunkSize != CHUNK_SIZE) throw new IOException("Unsupported chunk size");
        byte[] salt = Arrays.copyOfRange(header, 9, 9 + SALT_BYTES);
        byte[] noncePrefix = Arrays.copyOfRange(header, 9 + SALT_BYTES, 9 + SALT_BYTES + NONCE_PREFIX_BYTES);
        byte[] subkey = hkdf(master, salt, purpose);
        try {
            long chunkIndex = 0;
            while (true) {
                if (chunkIndex >= 0xffffffffL) throw new IOException("Vault stream too large");
                int size = input.readInt(); // EOF means missing authenticated terminator
                if (size < TAG_BYTES || size > CHUNK_SIZE + TAG_BYTES) {
                    throw new IOException("Invalid Vault chunk size");
                }
                byte[] cipherText = new byte[size];
                input.readFully(cipherText);
                boolean isTerminator = size == TAG_BYTES;
                byte[] decrypted = process(Cipher.DECRYPT_MODE, subkey, noncePrefix, header,
                        chunkIndex++, isTerminator, cipherText, cipherText.length);
                if (isTerminator) {
                    if (decrypted.length != 0) throw new IOException("Invalid Vault terminator");
                    if (input.read() != -1) throw new IOException("Unexpected trailing Vault content");
                    plain.flush();
                    return;
                }
                if (decrypted.length == 0) throw new IOException("Unexpected zero-length chunk");
                plain.write(decrypted);
                Arrays.fill(decrypted, (byte) 0);
            }
        } catch (AEADBadTagException e) {
            throw e;
        } finally {
            Arrays.fill(subkey, (byte) 0);
        }
    }

    public static byte[] encryptBytes(byte[] plain, byte[] master, Purpose purpose)
            throws GeneralSecurityException, IOException {
        if (plain == null) throw new IllegalArgumentException("plain");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encrypt(new ByteArrayInputStream(plain), out, master, purpose);
        return out.toByteArray();
    }

    public static byte[] decryptBytes(byte[] encrypted, byte[] master, Purpose purpose)
            throws GeneralSecurityException, IOException {
        if (encrypted == null) throw new IllegalArgumentException("encrypted");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decrypt(new ByteArrayInputStream(encrypted), out, master, purpose);
        return out.toByteArray();
    }

    private static void validate(InputStream in, OutputStream out, byte[] master, Purpose purpose) {
        if (in == null || out == null || master == null || master.length != MASTER_BYTES || purpose == null)
            throw new IllegalArgumentException("Invalid Vault arguments (32-byte random master required)");
    }

    private static byte[] random(int n) {
        byte[] result = new byte[n];
        RANDOM.nextBytes(result);
        return result;
    }

    private static byte[] hkdf(byte[] master, byte[] salt, Purpose purpose) throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(master);
        try {
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] info = ("TelePremium Vault 1/" + purpose.name()).getBytes(StandardCharsets.UTF_8);
            hmac.update(info);
            hmac.update((byte) 0x01);
            return hmac.doFinal();
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    private static byte[] process(int mode, byte[] key, byte[] noncePrefix, byte[] header,
                                  long index, boolean terminator, byte[] data, int length)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(noncePrefix, 0, nonce, 0, NONCE_PREFIX_BYTES);
        nonce[8] = (byte) (index >>> 24);
        nonce[9] = (byte) (index >>> 16);
        nonce[10] = (byte) (index >>> 8);
        nonce[11] = (byte) index;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(new byte[] {
                (byte) (index >>> 56), (byte) (index >>> 48), (byte) (index >>> 40),
                (byte) (index >>> 32), (byte) (index >>> 24), (byte) (index >>> 16),
                (byte) (index >>> 8), (byte) index, (byte) (terminator ? 1 : 0)
        });
        return cipher.doFinal(data, 0, length);
    }

    private static int bytesToInt(byte[] b, int offset) {
        return (b[offset] & 0xff) << 24 | (b[offset + 1] & 0xff) << 16 |
                (b[offset + 2] & 0xff) << 8 | (b[offset + 3] & 0xff);
    }

    private static int fillChunk(InputStream input, byte[] buffer) throws IOException {
        int count = 0;
        while (count < buffer.length) {
            int n = input.read(buffer, count, buffer.length - count);
            if (n < 0) break;
            if (n == 0) {
                int one = input.read();
                if (one < 0) break;
                buffer[count++] = (byte) one;
            } else count += n;
        }
        return count == 0 ? -1 : count;
    }
}
