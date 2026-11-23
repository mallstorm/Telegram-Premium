package org.telegram.messenger.telepremium.cloud;

import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultPendingIndexStore;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultLockTree;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves payloads between key domains (master key <-> folder key) after a folder was protected,
 * unprotected, or files were moved across a lock boundary. One file at a time:
 * decrypt with the old key, encrypt and upload under the new key, point the catalog at the new
 * payload, then physically delete the old Telegram message and local replica.
 * Files whose old or new key is currently unavailable (folder closed) wait for the next kick.
 */
public final class VaultRekeyJob {
    private VaultRekeyJob() {}

    public interface Listener { void onState(int remaining, String error); }

    private static final boolean[] RUNNING = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /** Main thread. Safe to call repeatedly (after unlock, sync, protect, move). */
    public static void kick(int account, Listener listener) {
        if (account < 0 || account >= RUNNING.length) return;
        synchronized (RUNNING) { if (RUNNING[account]) return; RUNNING[account] = true; }
        step(account, new HashSet<String>(), listener);
    }

    public static boolean isRunning(int account) { synchronized (RUNNING) { return account >= 0 && account < RUNNING.length && RUNNING[account]; } }

    /** Number of payloads still waiting for a key switch (including ones blocked by a closed folder). */
    public static int pending(int account) {
        CloudSnapshot snapshot = TelePremiumCloudManager.getInstance(account).getSnapshot();
        return snapshot == null ? 0 : VaultLockTree.filesNeedingRekey(snapshot.nodes).size();
    }

    private static void done(int account, int left, String error, Listener l) {
        synchronized (RUNNING) { RUNNING[account] = false; }
        if (l != null) l.onState(left, error);
    }

    private static boolean keyAvailable(String keyRef) {
        return keyRef == null || VaultFolderSession.shared().isUnlocked(keyRef);
    }

    private static void step(final int account, final Set<String> failed, final Listener l) {
        final TelePremiumCloudManager manager = TelePremiumCloudManager.getInstance(account);
        final CloudSnapshot snapshot = manager.getSnapshot();
        if (snapshot == null || !manager.isRemoteReady()) { done(account, pending(account), null, l); return; }
        CloudNode next = null;
        for (CloudNode n : VaultLockTree.filesNeedingRekey(snapshot.nodes)) {
            if (failed.contains(n.id)) continue;
            String wanted = VaultLockTree.desiredKeyRef(snapshot.nodes, n.parentId);
            if (keyAvailable(n.keyRef) && keyAvailable(wanted)) { next = n; break; }
        }
        if (next == null) {
            final int left = VaultLockTree.filesNeedingRekey(snapshot.nodes).size();
            if (left == 0) manager.clearFinishedLocks(e -> done(account, 0, e, l));
            else done(account, left, failed.isEmpty() ? null : "Часть файлов ждёт открытия папки", l);
            return;
        }
        final CloudNode node = next;
        new CloudDownloadManager(account).download(node, (state, file, error) -> {
            if (error != null) { failed.add(node.id); step(account, failed, l); return; }
            if (state != CloudDownloadManager.OFFLINE_AVAILABLE || file == null) return;
            final CloudPayloadRepository repo = new CloudPayloadRepository(account);
            repo.importUri(Uri.fromFile(file), node.parentId, node.name, node.mimeType, (fresh, importError) -> {
                if (importError != null || fresh == null) {
                    file.delete(); failed.add(node.id); step(account, failed, l); return;
                }
                manager.swapPayload(node, fresh, swapError -> {
                    file.delete();
                    try { TelePremiumVaultPendingIndexStore.remove(account, fresh.id); } catch (Throwable ignore) {}
                    CloudTransferManager.getInstance(account).complete();
                    if (swapError != null) failed.add(node.id);
                    AndroidUtilities.runOnUIThread(() -> step(account, failed, l));
                });
            });
        });
    }
}
