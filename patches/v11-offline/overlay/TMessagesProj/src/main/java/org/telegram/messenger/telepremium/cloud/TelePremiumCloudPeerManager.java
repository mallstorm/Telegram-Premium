package org.telegram.messenger.telepremium.cloud;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public final class TelePremiumCloudPeerManager implements NotificationCenter.NotificationCenterDelegate {
    public static final String STORAGE_TITLE = "TelePremium Cloud Storage";
    public static final String STORAGE_MARKER = "TELEPREMIUM_CLOUD_STORAGE_V2";
    public static final String VAULT_TITLE = "TelePremium Vault";
    public static final String VAULT_MARKER = "TP_VAULT_V3";

    private static final TelePremiumCloudPeerManager[] INSTANCES = new TelePremiumCloudPeerManager[UserConfig.MAX_ACCOUNT_COUNT];
    private static final TelePremiumCloudPeerManager[] VAULT_INSTANCES = new TelePremiumCloudPeerManager[UserConfig.MAX_ACCOUNT_COUNT];

    public interface Callback {
        void onReady(long dialogId, String error);
    }

    public static synchronized TelePremiumCloudPeerManager getInstance(int account) {
        if (INSTANCES[account] == null) {
            INSTANCES[account] = new TelePremiumCloudPeerManager(account,false);
        }
        return INSTANCES[account];
    }

    public static synchronized TelePremiumCloudPeerManager getVaultInstance(int account) {
        if (VAULT_INSTANCES[account] == null) VAULT_INSTANCES[account] = new TelePremiumCloudPeerManager(account,true);
        return VAULT_INSTANCES[account];
    }

    private final boolean vaultMode;
    private String storageTitle() { return vaultMode ? VAULT_TITLE : STORAGE_TITLE; }
    private String storageMarker() { return vaultMode ? VAULT_MARKER : STORAGE_MARKER; }
    private final int account;
    private long channelId;
    private long accessHash;
    private boolean creating;
    private final ArrayList<Callback> callbacks = new ArrayList<>();

    private TelePremiumCloudPeerManager(int account, boolean vaultMode) {
        this.account = account;
        this.vaultMode = vaultMode;
        SharedPreferences p = prefs();
        channelId = p.getLong("channel_id", 0);
        accessHash = p.getLong("access_hash", 0);
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences((vaultMode?"telepremium_vault_peer_":"telepremium_cloud_peer_") + account, 0);
    }

    public long getChannelId() {
        if (channelId == 0) discoverLoadedChannel();
        return channelId;
    }

    public long getDialogId() {
        long id = getChannelId();
        return id == 0 ? 0 : -id;
    }

    public TLRPC.InputPeer getInputPeer() {
        if (channelId == 0) return null;
        TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
        peer.channel_id = channelId;
        peer.access_hash = accessHash;
        return peer;
    }

    public TLRPC.InputChannel getInputChannel() {
        if (channelId == 0) return null;
        TLRPC.TL_inputChannel input = new TLRPC.TL_inputChannel();
        input.channel_id = channelId;
        input.access_hash = accessHash;
        return input;
    }

    public void ensure(Callback callback) {
        if (callback != null) callbacks.add(callback);
        if (getChannelId() != 0 && accessHash != 0) {
            finish(null);
            return;
        }
        if (creating) return;
        if (discoverLoadedChannel()) {
            finish(null);
            return;
        }
        creating = true;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.chatDidCreated);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.chatDidFailCreate);
        try {
            MessagesController.getInstance(account).createChat(
                    storageTitle(),
                    new ArrayList<>(),
                    storageMarker(),
                    ChatObject.CHAT_TYPE_CHANNEL,
                    false,
                    null,
                    null,
                    -1,
                    null
            );
        } catch (Throwable t) {
            finish(t.getMessage() == null ? "Не удалось создать Cloud Storage" : t.getMessage());
        }
    }

    private boolean discoverLoadedChannel() {
        try {
            for (TLRPC.Chat chat : MessagesController.getInstance(account).getChats().values()) {
                if (chat != null && storageTitle().equals(chat.title) && ChatObject.isChannel(chat) && chat.creator) {
                    remember(chat);
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return channelId != 0 && accessHash != 0;
    }

    private void remember(TLRPC.Chat chat) {
        channelId = chat.id;
        accessHash = chat.access_hash;
        prefs().edit()
                .putLong("channel_id", channelId)
                .putLong("access_hash", accessHash)
                .putBoolean("service_peer", true)
                .apply();
    }

    private void finish(String error) {
        creating = false;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.chatDidCreated);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.chatDidFailCreate);
        ArrayList<Callback> copy = new ArrayList<>(callbacks);
        callbacks.clear();
        long did = getDialogId();
        for (Callback cb : copy) cb.onReady(did, error);
    }

    @Override
    public void didReceivedNotification(int id, int accountId, Object... args) {
        if (!creating || accountId != account) return;
        if (id == NotificationCenter.chatDidFailCreate) {
            finish("Не удалось создать служебное облако");
            return;
        }
        if (id == NotificationCenter.chatDidCreated && args.length > 0 && args[0] instanceof Long) {
            long createdId = (Long) args[0];
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(createdId);
            if (chat != null && storageTitle().equals(chat.title)) {
                remember(chat);
                finish(null);
            }
        }
    }
}
