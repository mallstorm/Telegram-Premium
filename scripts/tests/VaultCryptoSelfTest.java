import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultCrypto;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultCrypto.Purpose;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Pure-Java regression test for the TelePremium Vault envelope (no Android deps).
 * Run by CI before the Gradle build:
 *   javac -d out <VaultCrypto.java> scripts/tests/VaultCryptoSelfTest.java && java -cp out VaultCryptoSelfTest
 */
public final class VaultCryptoSelfTest {
    private static final int CHUNK = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static int passed;

    public static void main(String[] args) throws Exception {
        byte[] key = TelePremiumVaultCrypto.newMasterKey();
        int[] sizes = {0, 1, 15, 16, 17, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + CHUNK / 2};
        for (int size : sizes) {
            byte[] plain = random(size);
            for (Purpose purpose : Purpose.values()) {
                byte[] enc = TelePremiumVaultCrypto.encryptBytes(plain, key, purpose);
                check(TelePremiumVaultCrypto.isVaultFile(enc), "magic for size " + size);
                check(Arrays.equals(plain, TelePremiumVaultCrypto.decryptBytes(enc, key, purpose)),
                        "round trip size=" + size + " purpose=" + purpose);
            }
        }

        byte[] plain = random(2 * CHUNK + 777);
        byte[] enc = TelePremiumVaultCrypto.encryptBytes(plain, key, Purpose.FILE);
        check(!Arrays.equals(enc, TelePremiumVaultCrypto.encryptBytes(plain, key, Purpose.FILE)), "fresh salt/nonce per file");

        mustFail("wrong key", enc, TelePremiumVaultCrypto.newMasterKey(), Purpose.FILE);
        mustFail("wrong purpose", enc, key, Purpose.INDEX);
        mustFail("bit flip in header", flip(enc, 10), key, Purpose.FILE);
        mustFail("bit flip in first chunk", flip(enc, 45 + 100), key, Purpose.FILE);
        mustFail("bit flip in last byte", flip(enc, enc.length - 1), key, Purpose.FILE);
        mustFail("truncated terminator", Arrays.copyOf(enc, enc.length - 20), key, Purpose.FILE);
        mustFail("truncated header", Arrays.copyOf(enc, 20), key, Purpose.FILE);
        byte[] trailing = Arrays.copyOf(enc, enc.length + 1);
        mustFail("trailing data", trailing, key, Purpose.FILE);
        mustFail("chunks swapped", swapFirstTwoChunks(enc), key, Purpose.FILE);

        boolean rejected = false;
        try { TelePremiumVaultCrypto.encryptBytes(plain, new byte[16], Purpose.FILE); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "short master key rejected");

        System.out.println("VaultCryptoSelfTest OK: " + passed + " checks");
    }

    private static void mustFail(String name, byte[] data, byte[] key, Purpose purpose) {
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            TelePremiumVaultCrypto.decrypt(new ByteArrayInputStream(data), sink, key, purpose);
        } catch (Exception expected) {
            passed++;
            return;
        }
        throw new AssertionError("Tampered input accepted: " + name);
    }

    private static byte[] swapFirstTwoChunks(byte[] enc) {
        int header = 8 + 1 + 16 + 8 + 4;
        int len = CHUNK + 16;
        byte[] out = enc.clone();
        int first = header, second = header + 4 + len;
        System.arraycopy(enc, second, out, first, 4 + len);
        System.arraycopy(enc, first, out, second, 4 + len);
        return out;
    }

    private static byte[] flip(byte[] in, int index) {
        byte[] out = in.clone();
        out[index] ^= 0x01;
        return out;
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
        passed++;
    }
}
