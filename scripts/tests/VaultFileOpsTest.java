import org.telegram.messenger.telepremium.cloud.CloudFileHasher;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultCrypto;
import org.telegram.messenger.telepremium.cloud.vault.VaultFileOps;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.SecureRandom;

/** Vault file pipeline: encrypt-to-file, verified decrypt, tamper and hash mismatch handling. */
public final class VaultFileOpsTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("tpvault-test").toFile();
        byte[] key = TelePremiumVaultCrypto.newMasterKey();
        byte[] plain = new byte[3 * 1024 * 1024 + 123];
        new SecureRandom().nextBytes(plain);
        File plainFile = new File(dir, "plain.bin");
        Files.write(plainFile.toPath(), plain);
        String expectedSha = CloudFileHasher.sha256(plainFile);

        File enc = new File(dir, "payload.tpv");
        VaultFileOps.UploadResult r = VaultFileOps.encryptToFile(new ByteArrayInputStream(plain), enc, key);
        check(r.originalSize == plain.length && r.originalSha256.equals(expectedSha), "upload result reports plaintext size and SHA-256");
        check(r.encryptedSize == enc.length() && r.encryptedSize > plain.length, "ciphertext written with envelope overhead");

        File out = VaultFileOps.decryptToVerifiedFile(enc, new File(dir, "dec"), "../evil name.pdf", key, expectedSha, plain.length);
        check(java.util.Arrays.equals(plain, Files.readAllBytes(out.toPath())), "verified decrypt restores the file");
        check(out.getParentFile().equals(new File(dir, "dec")) && !out.getName().contains("/"), "unsafe name cannot escape the cache dir");

        mustFail(() -> VaultFileOps.decryptToVerifiedFile(enc, new File(dir, "dec"), "x", key, "00".repeat(32), plain.length), "wrong expected hash");
        mustFail(() -> VaultFileOps.decryptToVerifiedFile(enc, new File(dir, "dec"), "x", key, expectedSha, plain.length + 1), "wrong expected size");
        mustFail(() -> VaultFileOps.decryptToVerifiedFile(enc, new File(dir, "dec"), "x", TelePremiumVaultCrypto.newMasterKey(), expectedSha, plain.length), "wrong key");
        try (RandomAccessFile raf = new RandomAccessFile(enc, "rw")) { raf.seek(2000); int b = raf.read(); raf.seek(2000); raf.write(b ^ 1); }
        mustFail(() -> VaultFileOps.decryptToVerifiedFile(enc, new File(dir, "dec"), "x", key, expectedSha, plain.length), "tampered ciphertext");
        String[] left = new File(dir, "dec").list((d, name) -> name.endsWith(".partial"));
        check(left != null && left.length == 0, "no partial plaintext left behind after failures");
        System.out.println("VaultFileOpsTest OK: " + passed + " checks");
    }

    interface Action { void run() throws Exception; }

    private static void mustFail(Action action, String name) {
        try { action.run(); } catch (Exception expected) { passed++; return; }
        throw new AssertionError("Accepted: " + name);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
        passed++;
    }
}
