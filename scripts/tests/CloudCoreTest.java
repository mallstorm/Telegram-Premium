import org.telegram.messenger.telepremium.cloud.CloudConflictResolver;
import org.telegram.messenger.telepremium.cloud.CloudIntegrityChecker;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.CloudRecoveryEngine;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;

import java.util.Arrays;

/**
 * Cloud V2 catalog core: snapshot encoding, multi-device merge, integrity checks,
 * recovery choice and SHA-256 deduplication references. Runs on a plain JVM.
 */
public final class CloudCoreTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        roundTrip();
        mergeKeepsIndependentEdits();
        mergeTombstoneWins();
        mergeDifferentCloudPrefersRemote();
        integrity();
        recovery();
        dedupReferences();
        System.out.println("CloudCoreTest OK: " + passed + " checks");
    }

    private static CloudSnapshot base() {
        CloudSnapshot s = new CloudSnapshot();
        s.cloudId = "cloud-1";
        s.generation = 5;
        s.updatedAt = 1000;
        CloudNode root = CloudNode.folder(null, "Облако");
        root.id = "root";
        s.nodes.add(root);
        return s;
    }

    private static CloudNode file(String id, String parent, String sha, long modified) {
        CloudNode n = CloudNode.file(parent, id + ".bin", "application/octet-stream", 42, sha);
        n.id = id;
        n.modifiedAt = modified;
        return n;
    }

    private static void roundTrip() throws Exception {
        CloudSnapshot s = base();
        CloudNode f = file("f1", "root", "ab".repeat(32), 10);
        f.favorite = true;
        f.encryptionVersion = 1;
        f.telegramDocumentId = 9_000_000_000L;
        f.name = "Отчёт \"Q3\"\n.pdf";
        s.nodes.add(f);
        CloudSnapshot d = CloudSnapshot.decode(s.encode());
        check(d.cloudId.equals("cloud-1") && d.generation == 5 && d.nodes.size() == 2, "snapshot header round trip");
        CloudNode r = d.nodes.get(0), g = d.nodes.get(1);
        check(r.parentId == null && "root".equals(r.id), "root keeps null parent");
        check(g.favorite && g.encryptionVersion == 1 && g.telegramDocumentId == 9_000_000_000L, "file flags and 64-bit ids survive");
        check(f.name.equals(g.name) && f.sha256.equals(g.sha256), "unicode / escaped name and hash survive");
        boolean rejected = false;
        try { CloudSnapshot.decode("{\"schemaVersion\":99,\"cloudId\":\"x\"}"); } catch (IllegalStateException e) { rejected = true; }
        check(rejected, "unknown schema rejected");
    }

    private static void mergeKeepsIndependentEdits() {
        CloudSnapshot local = base(), remote = base();
        local.nodes.add(file("a", "root", "11".repeat(32), 100));
        remote.nodes.add(file("b", "root", "22".repeat(32), 100));
        CloudNode localRename = file("c", "root", "33".repeat(32), 300);
        localRename.name = "new-name";
        CloudNode remoteOld = file("c", "root", "33".repeat(32), 200);
        local.nodes.add(localRename);
        remote.nodes.add(remoteOld);
        remote.generation = 7;
        CloudSnapshot m = new CloudConflictResolver().merge(local, remote);
        check(find(m, "a") != null && find(m, "b") != null, "both devices' new files kept");
        check("new-name".equals(find(m, "c").name), "newer edit wins per node");
        check(m.generation == 7, "merged generation is the maximum");
    }

    private static void mergeTombstoneWins() {
        CloudSnapshot local = base(), remote = base();
        CloudNode live = file("x", "root", "44".repeat(32), 100);
        CloudNode dead = file("x", "root", "44".repeat(32), 100);
        dead.state = CloudNode.STATE_DELETED;
        dead.deletedAt = 500;
        local.nodes.add(live);
        remote.nodes.add(dead);
        CloudSnapshot m = new CloudConflictResolver().merge(local, remote);
        check(find(m, "x").state == CloudNode.STATE_DELETED, "later deletion is not resurrected by a stale copy");
    }

    private static void mergeDifferentCloudPrefersRemote() {
        CloudSnapshot local = base(), remote = base();
        remote.cloudId = "cloud-2";
        check(new CloudConflictResolver().merge(local, remote) == remote, "foreign cloud id: remote wins, no mixing");
    }

    private static void integrity() {
        CloudSnapshot ok = base();
        CloudNode folder = CloudNode.folder("root", "docs");
        folder.id = "docs";
        ok.nodes.add(folder);
        ok.nodes.add(file("f", "docs", "55".repeat(32), 1));
        check(new CloudIntegrityChecker().check(ok).ok(), "valid tree passes");

        CloudSnapshot orphan = base();
        orphan.nodes.add(file("o", "missing", "66".repeat(32), 1));
        CloudIntegrityChecker.Result r = new CloudIntegrityChecker().check(orphan);
        check(!r.ok() && r.orphanNodes == 1, "orphan detected");

        CloudSnapshot dup = base();
        dup.nodes.add(file("d", "root", "77".repeat(32), 1));
        dup.nodes.add(file("d", "root", "77".repeat(32), 2));
        check(new CloudIntegrityChecker().check(dup).duplicateIds == 1, "duplicate id detected");

        CloudSnapshot cycle = base();
        CloudNode a = CloudNode.folder("b", "a"); a.id = "a";
        CloudNode b = CloudNode.folder("a", "b"); b.id = "b";
        cycle.nodes.addAll(Arrays.asList(a, b));
        check(new CloudIntegrityChecker().check(cycle).cycles > 0, "folder cycle detected");
    }

    private static void recovery() {
        CloudSnapshot older = base();
        CloudSnapshot newest = base(); newest.generation = 9;
        CloudSnapshot corrupt = base(); corrupt.generation = 12;
        corrupt.nodes.add(file("o", "missing", "88".repeat(32), 1));
        CloudSnapshot chosen = new CloudRecoveryEngine().recover(Arrays.asList(older, corrupt, null, newest));
        check(chosen == newest, "recovery picks the newest VALID generation");
        check(new CloudRecoveryEngine().recover(null) == null, "no candidates -> null");
    }

    private static void dedupReferences() {
        CloudSnapshot s = base();
        CloudNode a = file("a", "root", "99".repeat(32), 1); a.telegramDocumentId = 77;
        CloudNode b = file("b", "root", "99".repeat(32), 2); b.telegramDocumentId = 77;
        CloudNode gone = file("c", "root", "99".repeat(32), 3); gone.telegramDocumentId = 77; gone.state = CloudNode.STATE_DELETED;
        s.nodes.addAll(Arrays.asList(a, b, gone));
        int live = 0;
        for (CloudNode n : s.nodes) if (n.telegramDocumentId == 77 && n.state != CloudNode.STATE_DELETED) live++;
        check(live == 2, "two live nodes share one deduplicated payload, tombstone excluded");
    }

    private static CloudNode find(CloudSnapshot s, String id) {
        for (CloudNode n : s.nodes) if (id.equals(n.id)) return n;
        return null;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
        passed++;
    }
}
