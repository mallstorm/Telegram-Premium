package org.telegram.messenger.telepremium.cloud;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CloudMigrationV1ToV2 {
    public interface Callback { void onComplete(String error); }

    private final int account;
    private final TelePremiumCloudPeerManager peerManager;
    private final TelegramCloudMetadataStore v2Store;
    private final SharedPreferences prefs;
    private LegacyCloudV1Store.Result legacyResult;
    private CloudSnapshot snapshot;
    private final JSONObject mapping = new JSONObject();

    public CloudMigrationV1ToV2(int account) {
        this.account = account;
        peerManager = TelePremiumCloudPeerManager.getInstance(account);
        v2Store = new TelegramCloudMetadataStore(account);
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("telepremium_cloud_migration_" + account, 0);
        try {
            JSONObject saved = new JSONObject(prefs.getString("mapping", "{}"));
            java.util.Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                mapping.put(key, saved.optInt(key));
            }
        } catch (Exception ignore) {}
    }

    public void migrateIfNeeded(Callback callback) {
        if (CloudMigrationState.COMPLETED.equals(prefs.getString("state", CloudMigrationState.NOT_STARTED))) {
            callback.onComplete(null);
            return;
        }
        new LegacyCloudV1Store(account).load((result, error) -> {
            if (error != null) {
                fail(callback, error);
                return;
            }
            legacyResult = result;
            snapshot = new CloudRecoveryEngine().recover(result.snapshots);
            if (snapshot == null) {
                state(CloudMigrationState.COMPLETED);
                callback.onComplete(null);
                return;
            }
            peerManager.ensure((dialogId, peerError) -> {
                if (peerError != null || dialogId == 0) {
                    fail(callback, peerError == null ? "Cloud service peer unavailable" : peerError);
                    return;
                }
                state(CloudMigrationState.CHANNEL_READY);
                copyPayloads(callback);
            });
        });
    }

    private void copyPayloads(Callback callback) {
        state(CloudMigrationState.PAYLOAD_COPY);
        Set<Integer> ids = new LinkedHashSet<>();
        for (CloudNode node : snapshot.nodes) {
            if (node.telegramMessageId != 0 && node.state != CloudNode.STATE_DELETED) ids.add((int) node.telegramMessageId);
        }
        ArrayList<Integer> queue = new ArrayList<>();
        for (Integer id : ids) if (!mapping.has(String.valueOf(id))) queue.add(id);
        copyNext(queue, 0, callback);
    }

    private void copyNext(ArrayList<Integer> queue, int index, Callback callback) {
        if (index >= queue.size()) {
            remapAndSave(callback);
            return;
        }
        int oldId = queue.get(index);
        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.from_peer = new TLRPC.TL_inputPeerSelf();
        req.to_peer = peerManager.getInputPeer();
        req.id.add(oldId);
        req.random_id.add(Utilities.random.nextLong());
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                fail(callback, error.text);
                return;
            }
            int newId = extractMessageId(response);
            if (newId == 0) {
                fail(callback, "Не удалось определить новый message id при миграции");
                return;
            }
            try {
                mapping.put(String.valueOf(oldId), newId);
                prefs.edit().putString("mapping", mapping.toString()).apply();
            } catch (Exception ignore) {}
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            }
            copyNext(queue, index + 1, callback);
        }));
    }

    private int extractMessageId(TLObject response) {
        if (!(response instanceof TLRPC.Updates)) return 0;
        TLRPC.Updates updates = (TLRPC.Updates) response;
        for (TLRPC.Update update : updates.updates) {
            if (update instanceof TLRPC.TL_updateNewChannelMessage) {
                return ((TLRPC.TL_updateNewChannelMessage) update).message.id;
            }
            if (update instanceof TLRPC.TL_updateNewMessage) {
                return ((TLRPC.TL_updateNewMessage) update).message.id;
            }
        }
        return 0;
    }

    private void remapAndSave(Callback callback) {
        for (CloudNode node : snapshot.nodes) {
            if (node.telegramMessageId == 0 || node.state == CloudNode.STATE_DELETED) continue;
            int mapped = mapping.optInt(String.valueOf(node.telegramMessageId), 0);
            if (mapped == 0) {
                fail(callback, "Не весь payload перенесён в Cloud V2");
                return;
            }
            node.telegramMessageId = mapped;
        }
        snapshot.generation++;
        snapshot.journalGeneration++;
        v2Store.save(snapshot, error -> {
            if (error != null) {
                fail(callback, error);
                return;
            }
            state(CloudMigrationState.SNAPSHOT_WRITTEN);
            CloudIntegrityChecker.Result integrity = new CloudIntegrityChecker().check(snapshot);
            if (!integrity.ok()) {
                fail(callback, "Cloud V2 integrity check failed");
                return;
            }
            state(CloudMigrationState.VERIFIED);
            cleanupLegacy(callback);
        });
    }

    private void cleanupLegacy(Callback callback) {
        state(CloudMigrationState.SELF_CLEANUP);
        LinkedHashSet<Integer> delete = new LinkedHashSet<>(legacyResult.metadataMessageIds);
        for (CloudNode node : new CloudRecoveryEngine().recover(legacyResult.snapshots).nodes) {
            if (node.telegramMessageId != 0) delete.add((int) node.telegramMessageId);
        }
        if (delete.isEmpty()) {
            complete(callback);
            return;
        }
        TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
        req.id.addAll(delete);
        req.revoke = true;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                prefs.edit().putBoolean("cleanup_pending", true).apply();
                fail(callback, error.text);
                return;
            }
            complete(callback);
        }));
    }

    private void complete(Callback callback) {
        prefs.edit().putString("state", CloudMigrationState.COMPLETED)
                .putBoolean("migration_completed", true)
                .putBoolean("cleanup_pending", false)
                .putInt("cloud_backend_version", 2)
                .apply();
        callback.onComplete(null);
    }

    private void state(String value) {
        prefs.edit().putString("state", value).apply();
    }

    private void fail(Callback callback, String error) {
        state(CloudMigrationState.FAILED_RETRYABLE);
        callback.onComplete(error == null ? "Cloud migration failed" : error);
    }
}
