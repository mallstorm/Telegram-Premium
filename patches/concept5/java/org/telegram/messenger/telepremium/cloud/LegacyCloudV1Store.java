package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

final class LegacyCloudV1Store {
    static final String PREFIX = "#TELEPREMIUM_CLOUD_V1 ";

    static final class Result {
        final ArrayList<CloudSnapshot> snapshots = new ArrayList<>();
        final ArrayList<Integer> metadataMessageIds = new ArrayList<>();
    }

    interface Callback { void onLoaded(Result result, String error); }

    private final int account;

    LegacyCloudV1Store(int account) { this.account = account; }

    void load(Callback callback) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            Result result = new Result();
            if (error == null && response instanceof TLRPC.messages_Messages) {
                List<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
                for (TLRPC.Message m : messages) {
                    if (m.message == null || !m.message.startsWith(PREFIX)) continue;
                    try {
                        result.snapshots.add(CloudSnapshot.decode(m.message.substring(PREFIX.length())));
                        result.metadataMessageIds.add(m.id);
                    } catch (Exception ignore) {}
                }
            }
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result, error == null ? null : error.text));
        });
    }
}
