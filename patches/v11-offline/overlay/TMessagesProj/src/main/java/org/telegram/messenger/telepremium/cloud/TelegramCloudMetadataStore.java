package org.telegram.messenger.telepremium.cloud;
// Legacy migration marker: #TELEPREMIUM_CLOUD_V1 is handled only by LegacyCloudV1Store.

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultIndexStore;

import java.util.ArrayList;
import java.util.List;

public final class TelegramCloudMetadataStore {
    public static final String PREFIX = "#TELEPREMIUM_CLOUD_V2 ";

    private final int account;
    private final TelePremiumCloudPeerManager peerManager;
    private final TelePremiumVaultIndexStore vaultStore;

    public interface LoadCallback { void onLoaded(CloudSnapshot snapshot, String error); }
    public interface SaveCallback { void onSaved(String error); }
    public interface HistoryCallback { void onLoaded(List<CloudSnapshot> snapshots, String error); }

    public TelegramCloudMetadataStore(int account) {
        this.account = account;
        boolean secured=TelePremiumVaultKeyManager.isEnabled(ApplicationLoader.applicationContext);
        this.peerManager = secured ? TelePremiumCloudPeerManager.getVaultInstance(account) : TelePremiumCloudPeerManager.getInstance(account);
        this.vaultStore = secured ? new TelePremiumVaultIndexStore(account) : null;
    }

    public void loadLatest(LoadCallback callback) {
        if(vaultStore!=null){vaultStore.loadLatest(callback);return;}
        peerManager.ensure((dialogId, peerError) -> {
            if (peerError != null) {
                callback.onLoaded(null, peerError);
                return;
            }
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peerManager.getInputPeer();
            req.limit = 100;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                CloudSnapshot best = null;
                String failure = error == null ? null : error.text;
                if (error == null && response instanceof TLRPC.messages_Messages) {
                    for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                        if (m.message == null || !m.message.startsWith(PREFIX)) continue;
                        try {
                            CloudSnapshot candidate = CloudSnapshot.decode(m.message.substring(PREFIX.length()));
                            if (best == null || candidate.generation > best.generation ||
                                    candidate.generation == best.generation && candidate.updatedAt > best.updatedAt) {
                                best = candidate;
                            }
                        } catch (Exception tpError) { org.telegram.messenger.FileLog.e(tpError); }
                    }
                }
                CloudSnapshot result = best;
                AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result, failure));
            });
        });
    }

    public void loadGenerations(HistoryCallback callback) {
        if(vaultStore!=null){vaultStore.loadGenerations(callback);return;}
        peerManager.ensure((dialogId, peerError) -> {
            if (peerError != null) {
                callback.onLoaded(new ArrayList<>(), peerError);
                return;
            }
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peerManager.getInputPeer();
            req.limit = 100;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                ArrayList<CloudSnapshot> out = new ArrayList<>();
                if (error == null && response instanceof TLRPC.messages_Messages) {
                    for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                        if (m.message == null || !m.message.startsWith(PREFIX)) continue;
                        try {
                            out.add(CloudSnapshot.decode(m.message.substring(PREFIX.length())));
                        } catch (Exception tpError) { org.telegram.messenger.FileLog.e(tpError); }
                    }
                }
                AndroidUtilities.runOnUIThread(() -> callback.onLoaded(out, error == null ? null : error.text));
            });
        });
    }

    public void save(CloudSnapshot snapshot, SaveCallback callback) {
        if(vaultStore!=null){
            snapshot.updatedAt=System.currentTimeMillis();
            vaultStore.save(snapshot,callback);
            return;
        }
        peerManager.ensure((dialogId, peerError) -> {
            if (peerError != null) {
                callback.onSaved(peerError);
                return;
            }
            try {
                snapshot.updatedAt = System.currentTimeMillis();
                TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
                req.peer = peerManager.getInputPeer();
                req.message = PREFIX + snapshot.encode();
                req.random_id = org.telegram.messenger.Utilities.random.nextLong();
                req.no_webpage = true;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                        AndroidUtilities.runOnUIThread(() -> callback.onSaved(error == null ? null : error.text)));
            } catch (Exception e) {
                callback.onSaved(e.getMessage());
            }
        });
    }
}
