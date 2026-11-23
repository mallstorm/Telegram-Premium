import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultCrypto;
import org.telegram.messenger.telepremium.cloud.vault.VaultAttemptLimiter;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderLock;
import org.telegram.messenger.telepremium.cloud.vault.VaultKeyResolver;
import org.telegram.messenger.telepremium.cloud.vault.VaultVisibility;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultLockTree;
import org.telegram.messenger.telepremium.cloud.vault.VaultSecretPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Per-folder cryptographic locks: key sealing, wrong-secret handling, sessions, lock tree, policy. */
public final class VaultFolderLockTest {
    private static int passed;
    private static final int FAST = VaultFolderLock.MIN_ITERATIONS;

    public static void main(String[] args) throws Exception {
        sealOpen();
        bindings();
        rewrap();
        folderKeyEncryptsFiles();
        defaultIterations();
        session();
        tree();
        nodeJson();
        policy();
        visibility();
        keyResolver();
        limiter();
        System.out.println("VaultFolderLockTest OK: " + passed + " checks");
    }

    private static char[] pin(String s) { return VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.PIN, s); }

    private static void sealOpen() throws Exception {
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        byte[] fk = VaultFolderLock.newFolderKey();
        VaultFolderLock.Record r = VaultFolderLock.seal(pin("483920"), "folder-1", master, fk, FAST);
        byte[] opened = VaultFolderLock.open(r, pin("483920"), "folder-1", master);
        check(Arrays.equals(fk, opened), "correct secret returns the folder key");
        check(r.keyId.equals(VaultFolderLock.keyId(fk)) && r.keyId.length() == 16, "key id is the public fingerprint");
        check(!r.encode().contains(hex(fk)), "folder key never appears in the encoded record");
        VaultFolderLock.Record parsed = VaultFolderLock.Record.parse(r.encode());
        check(Arrays.equals(fk, VaultFolderLock.open(parsed, pin("483920"), "folder-1", master)), "record survives encode/parse");
        mustFail(() -> VaultFolderLock.open(r, pin("483921"), "folder-1", master), VaultFolderLock.WrongSecretException.class, "wrong PIN");
        // Same characters, different kind -> different KDF input.
        mustFail(() -> VaultFolderLock.open(r, VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.PASSWORD, "483920"), "folder-1", master),
                VaultFolderLock.WrongSecretException.class, "PIN and password of equal text must not collide");
        VaultFolderLock.Record again = VaultFolderLock.seal(pin("483920"), "folder-1", master, fk, FAST);
        check(!Arrays.equals(r.salt, again.salt) && !Arrays.equals(r.envelope, again.envelope), "salt and nonce are fresh for every seal");
        // Tampering with any byte of the envelope or salt is detected.
        String enc = r.encode();
        String[] p = enc.split(":");
        String flippedEnv = p[0] + ":" + p[1] + ":" + p[2] + ":" + flipHex(p[3], 30) + ":" + p[4];
        mustFail(() -> VaultFolderLock.open(VaultFolderLock.Record.parse(flippedEnv), pin("483920"), "folder-1", master),
                VaultFolderLock.WrongSecretException.class, "tampered envelope");
        String flippedSalt = p[0] + ":" + p[1] + ":" + flipHex(p[2], 5) + ":" + p[3] + ":" + p[4];
        mustFail(() -> VaultFolderLock.open(VaultFolderLock.Record.parse(flippedSalt), pin("483920"), "folder-1", master),
                VaultFolderLock.WrongSecretException.class, "tampered salt");
        String lowered = p[0] + ":1000:" + p[2] + ":" + p[3] + ":" + p[4];
        mustFail(() -> VaultFolderLock.Record.parse(lowered), IllegalArgumentException.class, "iteration downgrade is rejected at parse time");
        String bumped = p[0] + ":" + (FAST + 1) + ":" + p[2] + ":" + p[3] + ":" + p[4];
        mustFail(() -> VaultFolderLock.open(VaultFolderLock.Record.parse(bumped), pin("483920"), "folder-1", master),
                VaultFolderLock.WrongSecretException.class, "iteration count is authenticated");
        mustFail(() -> VaultFolderLock.Record.parse("FL9:1:2:3:4"), IllegalArgumentException.class, "unknown version");
        mustFail(() -> VaultFolderLock.Record.parse(null), IllegalArgumentException.class, "null record");
    }

    private static void bindings() throws Exception {
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        byte[] fk = VaultFolderLock.newFolderKey();
        VaultFolderLock.Record r = VaultFolderLock.seal(pin("483920"), "folder-1", master, fk, FAST);
        mustFail(() -> VaultFolderLock.open(r, pin("483920"), "folder-2", master), VaultFolderLock.WrongSecretException.class,
                "record cannot be transplanted to another folder");
        byte[] otherMaster = TelePremiumVaultCrypto.newMasterKey();
        mustFail(() -> VaultFolderLock.open(r, pin("483920"), "folder-1", otherMaster), VaultFolderLock.WrongSecretException.class,
                "a leaked secret without the master key opens nothing");
        mustFail(() -> VaultFolderLock.seal(new char[0], "f", master, fk, FAST), IllegalArgumentException.class, "empty secret");
        mustFail(() -> VaultFolderLock.seal(pin("483920"), "f", new byte[16], fk, FAST), IllegalArgumentException.class, "short master");
        mustFail(() -> VaultFolderLock.seal(pin("483920"), "f", master, new byte[5], FAST), IllegalArgumentException.class, "short folder key");
    }

    private static void rewrap() throws Exception {
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        byte[] fk = VaultFolderLock.newFolderKey();
        VaultFolderLock.Record r = VaultFolderLock.seal(pin("483920"), "folder-1", master, fk, FAST);
        VaultFolderLock.Record n = VaultFolderLock.rewrap(r, pin("483920"), VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.PASSWORD, "correct horse staple 9"), "folder-1", master);
        check(n.keyId.equals(r.keyId), "changing the secret keeps the folder key (files need no re-encryption)");
        check(Arrays.equals(fk, VaultFolderLock.open(n, VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.PASSWORD, "correct horse staple 9"), "folder-1", master)), "new secret opens");
        mustFail(() -> VaultFolderLock.open(n, pin("483920"), "folder-1", master), VaultFolderLock.WrongSecretException.class, "old secret is dead after rewrap");
        mustFail(() -> VaultFolderLock.rewrap(r, pin("000001"), pin("123450"), "folder-1", master), VaultFolderLock.WrongSecretException.class, "rewrap needs the old secret");
        check(n.iterations >= VaultFolderLock.DEFAULT_ITERATIONS, "rewrap raises weak iteration counts to the default");
    }

    private static void folderKeyEncryptsFiles() throws Exception {
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        byte[] fk = VaultFolderLock.newFolderKey();
        byte[] plain = new byte[2 * 1024 * 1024 + 17];
        new java.security.SecureRandom().nextBytes(plain);
        byte[] enc = TelePremiumVaultCrypto.encryptBytes(plain, fk, TelePremiumVaultCrypto.Purpose.FILE);
        check(Arrays.equals(plain, TelePremiumVaultCrypto.decryptBytes(enc, fk, TelePremiumVaultCrypto.Purpose.FILE)), "file round-trips under the folder key");
        mustFail(() -> TelePremiumVaultCrypto.decryptBytes(enc, master, TelePremiumVaultCrypto.Purpose.FILE), javax.crypto.AEADBadTagException.class,
                "the installation master key (or a leaked recovery code) cannot open a locked folder's file");
        byte[] other = VaultFolderLock.newFolderKey();
        mustFail(() -> TelePremiumVaultCrypto.decryptBytes(enc, other, TelePremiumVaultCrypto.Purpose.FILE), javax.crypto.AEADBadTagException.class,
                "another folder's key cannot open it");
        byte[] masterEnc = TelePremiumVaultCrypto.encryptBytes(plain, master, TelePremiumVaultCrypto.Purpose.FILE);
        mustFail(() -> TelePremiumVaultCrypto.decryptBytes(masterEnc, fk, TelePremiumVaultCrypto.Purpose.FILE), javax.crypto.AEADBadTagException.class,
                "folder key cannot open master-encrypted files");
    }

    private static void defaultIterations() throws Exception {
        byte[] master = TelePremiumVaultCrypto.newMasterKey();
        byte[] fk = VaultFolderLock.newFolderKey();
        long t = System.nanoTime();
        VaultFolderLock.Record r = VaultFolderLock.seal(pin("483920"), "folder-1", master, fk);
        check(r.iterations == 600_000, "default is 600k PBKDF2 iterations");
        check(Arrays.equals(fk, VaultFolderLock.open(r, pin("483920"), "folder-1", master)), "default parameters round-trip");
        System.out.println("  (600k-iteration seal+open took " + (System.nanoTime() - t) / 1_000_000 + " ms on this machine)");
    }

    private static void session() {
        long[] now = {1_000};
        VaultFolderSession s = new VaultFolderSession(() -> now[0]);
        int[] events = {0};
        s.addListener(() -> events[0]++);
        byte[] fk = VaultFolderLock.newFolderKey();
        s.put("A", fk, 60_000);
        byte[] copy = s.get("A");
        check(Arrays.equals(fk, copy) && copy != s.get("A"), "session hands out private copies");
        Arrays.fill(copy, (byte) 0);
        check(Arrays.equals(fk, s.get("A")), "zeroing a returned copy does not damage the stored key");
        check(!s.isUnlocked("B"), "folders are independent: A unlocked does not unlock B");
        now[0] += 59_000;
        check(s.isUnlocked("A"), "still unlocked inside the timeout");
        s.touch("A");
        now[0] += 59_000;
        check(s.isUnlocked("A"), "touch slides the window");
        now[0] += 2_000;
        check(!s.isUnlocked("A") && s.get("A") == null, "expires after its own timeout");
        s.put("A", fk, 60_000);
        s.put("B", fk, 60_000);
        s.lock("A");
        check(!s.isUnlocked("A") && s.isUnlocked("B"), "lock(A) leaves B alone");
        s.lockAll();
        check(s.unlockedIds().isEmpty(), "lockAll clears everything");
        check(events[0] >= 4, "listeners are notified on state changes");
        s.setMaxTtlMs(30_000);
        s.put("C", fk, 10 * 60_000);
        now[0] += 31_000;
        check(!s.isUnlocked("C"), "the global cap bounds the per-folder timeout");
        mustFail(() -> s.put("D", new byte[3], 1000), IllegalArgumentException.class, "short key rejected");
    }

    private static CloudNode folder(String id, String parent) {
        CloudNode n = CloudNode.folder(parent, id);
        n.id = id;
        return n;
    }

    private static CloudNode file(String id, String parent, String keyRef) {
        CloudNode n = CloudNode.file(parent, id + ".jpg", "image/jpeg", 10, "a".repeat(64));
        n.id = id;
        n.encryptionVersion = 1;
        n.telegramMessageId = 100 + id.hashCode() % 50;
        n.keyRef = keyRef;
        return n;
    }

    private static void tree() throws Exception {
        List<CloudNode> nodes = new ArrayList<>();
        CloudNode root = folder("root", null);
        CloudNode photos = folder("photos", "root");
        CloudNode vacation = folder("vacation", "photos");
        CloudNode secret = folder("secret", "vacation");
        CloudNode docs = folder("docs", "root");
        nodes.add(root); nodes.add(photos); nodes.add(vacation); nodes.add(secret); nodes.add(docs);
        CloudNode a = file("a", "photos", null), b = file("b", "vacation", null), c = file("c", "secret", null), d = file("d", "docs", null);
        nodes.add(a); nodes.add(b); nodes.add(c); nodes.add(d);

        check(VaultLockTree.activeLockRoot(nodes, "photos") == null, "nothing is locked initially");
        check(VaultLockTree.filesNeedingRekey(nodes).isEmpty(), "no re-keying needed initially");

        photos.lock = "FL1:stub";
        check(VaultLockTree.activeLockRoot(nodes, "vacation") == photos, "protection is inherited by sub-folders");
        check(VaultLockTree.activeLockRoot(nodes, "docs") == null, "siblings stay open");
        check("photos".equals(VaultLockTree.desiredKeyRef(nodes, "secret")), "files below inherit the nearest lock");
        List<CloudNode> need = VaultLockTree.filesNeedingRekey(nodes);
        check(need.size() == 3 && need.contains(a) && need.contains(b) && need.contains(c) && !need.contains(d), "enabling protection queues exactly the files below the folder");

        secret.lock = "FL1:stub2";
        check("secret".equals(VaultLockTree.desiredKeyRef(nodes, "secret")), "a nested lock overrides its parent");
        check("photos".equals(VaultLockTree.desiredKeyRef(nodes, "vacation")), "sibling levels keep the parent lock");

        a.keyRef = "photos"; b.keyRef = "photos"; c.keyRef = "secret";
        check(VaultLockTree.filesNeedingRekey(nodes).isEmpty(), "converged state needs no work");
        check(VaultLockTree.lockRoots(nodes).size() == 2, "settings list shows both lock roots");

        // Move file b out of the protected tree: desired key becomes master.
        b.parentId = "docs";
        List<CloudNode> moved = VaultLockTree.filesNeedingRekey(nodes);
        check(moved.size() == 1 && moved.get(0) == b, "moving a file out queues a re-key to the master key");
        b.parentId = "vacation";

        // Removing protection: lockOff keeps the key resolvable until files are converted.
        photos.lockOff = true;
        check(VaultLockTree.lockHolder(nodes, "photos") == photos && VaultLockTree.activeLockRoot(nodes, "photos") == null, "lockOff: still a holder, no longer active");
        List<CloudNode> back = VaultLockTree.filesNeedingRekey(nodes);
        check(back.size() == 2 && back.contains(a) && back.contains(b), "unprotect queues the files, but not those under the nested lock");
        check(VaultLockTree.finishedUnlocks(nodes).isEmpty(), "lock record is kept while payloads still reference it");
        a.keyRef = null; b.keyRef = null;
        check(VaultLockTree.finishedUnlocks(nodes).contains(photos), "lock record may be dropped once nothing references it");
        check(VaultLockTree.filesBelow(nodes, "photos").size() == 3, "filesBelow walks the subtree");

        // Cycle safety.
        photos.parentId = "secret";
        check(VaultLockTree.activeLockRoot(nodes, "vacation") != null, "cyclic parent chains terminate");
    }

    private static void nodeJson() throws Exception {
        CloudNode f = folder("f", "root");
        f.lock = "FL1:100000:aa:bb:cc";
        f.lockOff = true;
        CloudNode r = CloudNode.fromJson(f.toJson());
        check("FL1:100000:aa:bb:cc".equals(r.lock) && r.lockOff, "folder lock fields survive the catalog JSON");
        CloudNode file = file("x", "f", "f");
        CloudNode rf = CloudNode.fromJson(file.toJson());
        check("f".equals(rf.keyRef), "file keyRef survives the catalog JSON");
        CloudNode plain = CloudNode.fromJson(file("y", "f", null).toJson());
        check(plain.keyRef == null && plain.lock == null && !plain.lockOff, "old catalogs without the fields load as unprotected");
    }

    private static void policy() {
        V p = new V();
        p.expect(VaultSecretPolicy.Kind.PIN, "12345", VaultSecretPolicy.Verdict.TOO_SHORT);
        p.expect(VaultSecretPolicy.Kind.PIN, "111111", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PIN, "123456", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PIN, "345678", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PIN, "876543", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PIN, "123123", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PIN, "12a456", VaultSecretPolicy.Verdict.INVALID_CHARS);
        p.expect(VaultSecretPolicy.Kind.PIN, "483920", VaultSecretPolicy.Verdict.OK);
        p.expect(VaultSecretPolicy.Kind.PIN, "9".repeat(33), VaultSecretPolicy.Verdict.TOO_LONG);
        p.expect(VaultSecretPolicy.Kind.PASSWORD, "short", VaultSecretPolicy.Verdict.TOO_SHORT);
        p.expect(VaultSecretPolicy.Kind.PASSWORD, "aaaaaaaaaaaa", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PASSWORD, "alllowercase", VaultSecretPolicy.Verdict.TRIVIAL);
        p.expect(VaultSecretPolicy.Kind.PASSWORD, "Tr0ub4dor&3x", VaultSecretPolicy.Verdict.OK);
        p.expect(VaultSecretPolicy.Kind.PASSWORD, "correct horse battery", VaultSecretPolicy.Verdict.OK);
        p.expect(VaultSecretPolicy.Kind.PATTERN, "0124", VaultSecretPolicy.Verdict.TOO_SHORT);
        p.expect(VaultSecretPolicy.Kind.PATTERN, "01245", VaultSecretPolicy.Verdict.WEAK_BUT_ALLOWED);
        p.expect(VaultSecretPolicy.Kind.PATTERN, "01240", VaultSecretPolicy.Verdict.INVALID_CHARS);
        p.expect(VaultSecretPolicy.Kind.PATTERN, "01234567", VaultSecretPolicy.Verdict.WEAK_BUT_ALLOWED);
        check(VaultSecretPolicy.isAcceptable(VaultSecretPolicy.Verdict.WEAK_BUT_ALLOWED) && !VaultSecretPolicy.isAcceptable(VaultSecretPolicy.Verdict.TRIVIAL), "weak patterns are allowed, trivial PINs are not");
        char[] s = VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.PIN, "483920");
        check(new String(s).equals("N:483920"), "secret encoding mixes in the kind");
    }

    private static final class V {
        void expect(VaultSecretPolicy.Kind k, String raw, VaultSecretPolicy.Verdict want) {
            VaultSecretPolicy.Verdict got = VaultSecretPolicy.check(k, raw);
            if (got != want) throw new AssertionError("Policy " + k + " '" + raw + "': expected " + want + " got " + got);
            passed++;
        }
    }

    // ---- visibility: closed folders do not exist for lists, search, counts, player -------------
    private static void visibility() {
        CloudNode root = folder("root", null);
        CloudNode vault = folder("vault", "root");
        vault.lock = "FL1:x";
        CloudNode inner = folder("inner", "vault");
        CloudNode open = folder("open", "root");
        CloudNode secretVideo = file("movie", "inner", "vault");
        secretVideo.name = "movie.mp4"; secretVideo.mimeType = "video/mp4";
        CloudNode secretApk = file("app", "vault", "vault");
        secretApk.name = "app.apk";
        CloudNode openVideo = file("clip", "open", null);
        CloudNode loose = file("loose", "root", null);
        List<CloudNode> all = new ArrayList<>(Arrays.asList(root, vault, inner, open, secretVideo, secretApk, openVideo, loose));
        VaultFolderSession s = new VaultFolderSession(() -> 1000L);

        VaultVisibility.View closed = new VaultVisibility.View(all, s);
        check(closed.visible(vault), "locked folder itself stays listed");
        check(closed.isClosedLock(vault), "locked folder flagged closed");
        check(!closed.visible(inner), "subfolder hidden");
        check(!closed.visible(secretVideo), "video in locked tree hidden (any file type)");
        check(!closed.visible(secretApk), "program in locked folder hidden");
        check(closed.visible(openVideo) && closed.visible(loose), "open data visible");
        check(closed.isProtected(secretVideo) && closed.isProtected(inner), "protected regardless of session");
        check(!closed.isProtected(openVideo) && !closed.isProtected(vault), "open data / lock holder itself not protected");
        check(closed.filter(all).size() == 5, "filter keeps root, vault, open, clip, loose");

        s.put("vault", new byte[32], 60_000);
        VaultVisibility.View opened = new VaultVisibility.View(all, s);
        check(opened.visible(secretVideo) && opened.visible(secretApk) && opened.visible(inner), "visible while open");
        check(opened.isProtected(secretVideo), "still protected (player library never lists it) while open");
        check(!opened.isClosedLock(vault), "open lock not closed");

        // payload still keyed to a closed lock is hidden even after being moved out
        CloudNode moved = file("moved", "loose", "vault");
        moved.parentId = "root";
        VaultFolderSession s2 = new VaultFolderSession(() -> 1000L);
        List<CloudNode> withMoved = new ArrayList<>(all); withMoved.add(moved);
        check(!new VaultVisibility.View(withMoved, s2).visible(moved), "keyRef pointing at a closed lock hides the file");
        check(new VaultVisibility.View(withMoved, s2).isProtected(moved), "keyRef counts as protected");

        // cycles must not hang
        CloudNode a = folder("a", "b"), b = folder("b", "a");
        check(new VaultVisibility.View(Arrays.asList(a, b), s2).visible(file("z", "a", null)), "cycle safe");
    }

    private static void keyResolver() throws Exception {
        CloudNode root = folder("root", null);
        CloudNode vault = folder("vault", "root");
        vault.lock = "FL1:x";
        List<CloudNode> nodes = new ArrayList<>(Arrays.asList(root, vault));
        VaultFolderSession s = new VaultFolderSession(() -> 0L);
        byte[] master = new byte[32]; Arrays.fill(master, (byte) 7);
        VaultKeyResolver.MasterSource ms = master::clone;

        VaultKeyResolver.Key plain = VaultKeyResolver.forUpload(nodes, "root", ms, s);
        check(plain.keyRef == null && Arrays.equals(plain.bytes, master), "upload to open area uses master");
        mustFail(() -> VaultKeyResolver.forUpload(nodes, "vault", ms, s), VaultKeyResolver.LockedException.class, "upload into closed folder");
        byte[] fk = new byte[32]; Arrays.fill(fk, (byte) 9);
        s.put("vault", fk, 60_000);
        VaultKeyResolver.Key locked = VaultKeyResolver.forUpload(nodes, "vault", ms, s);
        check("vault".equals(locked.keyRef) && Arrays.equals(locked.bytes, fk), "upload into open folder uses folder key");
        locked.wipe();
        check(s.isUnlocked("vault") && Arrays.equals(s.get("vault"), fk), "wiping the copy does not touch the session key");
        CloudNode f = file("f", "vault", "vault");
        check(Arrays.equals(VaultKeyResolver.forNode(f, ms, s).bytes, fk), "payload key for keyed node");
        s.lock("vault");
        mustFail(() -> VaultKeyResolver.forNode(f, ms, s), VaultKeyResolver.LockedException.class, "payload of closed folder");
        vault.lockOff = true;
        check(VaultKeyResolver.forUpload(nodes, "vault", ms, s).keyRef == null, "folder being unprotected takes new files under master");
    }

    private static void limiter() {
        final long[] now = {0L};
        final java.util.Map<String, Long> m = new java.util.HashMap<>();
        VaultAttemptLimiter l = new VaultAttemptLimiter(new VaultAttemptLimiter.Store() {
            public long getLong(String k, long d) { return m.getOrDefault(k, d); }
            public void putLong(String k, long v) { m.put(k, v); }
        }, () -> now[0]);
        for (int i = 0; i < VaultAttemptLimiter.FREE_TRIES - 1; i++) { l.recordFailure("x"); check(l.remainingMs("x") == 0, "free try " + i); }
        l.recordFailure("x");
        check(l.remainingMs("x") == VaultAttemptLimiter.BASE_DELAY_MS, "first delay 30 s");
        now[0] += 31_000; check(l.remainingMs("x") == 0, "delay expires");
        l.recordFailure("x");
        check(l.remainingMs("x") == VaultAttemptLimiter.BASE_DELAY_MS * 2, "delay doubles");
        for (int i = 0; i < 20; i++) l.recordFailure("x");
        check(l.remainingMs("x") == VaultAttemptLimiter.MAX_DELAY_MS, "delay capped at 15 min");
        check(l.remainingMs("other") == 0, "limits are per folder");
        l.recordSuccess("x");
        check(l.remainingMs("x") == 0 && l.triesLeftBeforeDelay("x") == VaultAttemptLimiter.FREE_TRIES, "success resets");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String flipHex(String h, int index) {
        char[] c = h.toCharArray();
        c[index] = c[index] == '0' ? '1' : '0';
        return new String(c);
    }

    interface Action { void run() throws Exception; }

    private static void mustFail(Action a, Class<? extends Throwable> type, String name) {
        try { a.run(); } catch (Throwable t) {
            if (type.isInstance(t)) { passed++; return; }
            throw new AssertionError("Wrong exception for " + name + ": " + t, t);
        }
        throw new AssertionError("Accepted: " + name);
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError("Failed: " + name);
        passed++;
    }
}
