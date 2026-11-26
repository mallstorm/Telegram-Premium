package org.telegram.messenger.telepremium;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-protected container for exported sessions. No Android dependencies. */
public final class SessionCrypto {
    public static final byte[] MAGIC = "TPSESS01".getBytes(StandardCharsets.US_ASCII);
    public static final int ITERATIONS = 150000;
    public static final int MAX_FILE = 48 * 1024 * 1024, MAX_ENTRY = 16 * 1024 * 1024, MAX_ENTRIES = 512;
    private SessionCrypto() {}

    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[]{0, 0, 0, 1});
        byte[] u = mac.doFinal();
        byte[] t = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < t.length; j++) t[j] ^= u[j];
        }
        return t;
    }

    public static byte[] pack(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    public static Map<String, byte[]> unpack(byte[] zipBytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.contains("..") || name.startsWith("/") || name.contains("\\") || files.size() >= MAX_ENTRIES)
                    throw new IllegalArgumentException("Unsafe entry");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    data.write(buffer, 0, n);
                    total += n;
                    if (data.size() > MAX_ENTRY || total > MAX_FILE) throw new IllegalArgumentException("Too large");
                }
                files.put(name, data.toByteArray());
            }
        }
        return files;
    }

    public static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16], iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        byte[] key = pbkdf2(new String(password).getBytes(StandardCharsets.UTF_8), salt, ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(MAGIC);
        byte[] encrypted = cipher.doFinal(plain);
        ByteBuffer out = ByteBuffer.allocate(MAGIC.length + 4 + 16 + 12 + encrypted.length);
        out.put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).put(encrypted);
        java.util.Arrays.fill(key, (byte) 0);
        return out.array();
    }

    /** @throws SecurityException wrong password or damaged file; IllegalArgumentException not a session file */
    public static byte[] decrypt(byte[] file, char[] password) throws Exception {
        if (file == null || file.length < MAGIC.length + 4 + 16 + 12 + 16 || file.length > MAX_FILE) throw new IllegalArgumentException("Not a session file");
        ByteBuffer in = ByteBuffer.wrap(file);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("Not a session file");
        int iterations = in.getInt();
        if (iterations < 10000 || iterations > 2000000) throw new IllegalArgumentException("Not a session file");
        byte[] salt = new byte[16], iv = new byte[12];
        in.get(salt);
        in.get(iv);
        byte[] encrypted = new byte[in.remaining()];
        in.get(encrypted);
        byte[] key = pbkdf2(new String(password).getBytes(StandardCharsets.UTF_8), salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(MAGIC);
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Wrong password or damaged file");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }
}
