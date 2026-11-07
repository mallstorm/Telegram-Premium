package org.telegram.messenger.telepremium.cloud;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

public final class TelePremiumCloudPeerManager implements NotificationCenter.NotificationCenterDelegate {
    public static final String VAULT_TITLE = "TelePremium Vault";
    public static final String VAULT_MARKER = "TP_VAULT_V3";

    private static final TelePremiumCloudPeerManager[] VAULT_INSTANCES = new TelePremiumCloudPeerManager[UserConfig.MAX_ACCOUNT_COUNT];

    public interface Callback {
        void onReady(long dialogId, String error);
    }

    public static synchronized TelePremiumCloudPeerManager getInstance(int account) {
        return getVaultInstance(account);
    }

    public static synchronized TelePremiumCloudPeerManager getVaultInstance(int account) {
        if (VAULT_INSTANCES[account] == null || VAULT_INSTANCES[account].ownerUserId != UserConfig.getInstance(account).getClientUserId())
            VAULT_INSTANCES[account] = new TelePremiumCloudPeerManager(account);
        return VAULT_INSTANCES[account];
    }

    private String storageTitle() { return VAULT_TITLE; }
    private String storageMarker() { return VAULT_MARKER; }
    private final int account;
    private final long ownerUserId;
    private long channelId;
    private long accessHash;
    private boolean creating;
    private final ArrayList<Callback> callbacks = new ArrayList<>();

    private TelePremiumCloudPeerManager(int account) {
        this.account = account;
        this.ownerUserId = UserConfig.getInstance(account).getClientUserId();
        SharedPreferences p = prefs();
        channelId = p.getLong("channel_id", 0);
        accessHash = p.getLong("access_hash", 0);
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_peer_u" + ownerUserId, 0);
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
        // After reinstall, the channel may not yet be loaded in MessagesController.
        // Ask Telegram before creating a replacement namespace.
        discoverRemote(0, 0, new TLRPC.TL_inputPeerEmpty(), 0);
    }

    private void discoverRemote(int offsetDate, int offsetId, TLRPC.InputPeer offsetPeer, int page) {
        TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
        req.limit = 100;
        req.exclude_pinned = page > 0;
        req.offset_date = offsetDate;
        req.offset_id = offsetId;
        req.offset_peer = offsetPeer;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.messages_Dialogs)) {
                finish(error == null ? "Не удалось проверить существующий канал Vault" : error.text);
                return;
            }
            TLRPC.messages_Dialogs result = (TLRPC.messages_Dialogs) response;
            MessagesController.getInstance(account).putChats(result.chats, false);
            MessagesController.getInstance(account).putUsers(result.users, false);
            if (discoverLoadedChannel()) { finish(null); return; }
            if (result.dialogs.size() < req.limit) { createVault(); return; }
            TLRPC.Dialog last = result.dialogs.get(result.dialogs.size() - 1);
            TLRPC.Message top = null;
            for (TLRPC.Message m : result.messages) {
                if (m.id != last.top_message || m.peer_id == null || last.peer == null) continue;
                if (m.peer_id.channel_id == last.peer.channel_id && m.peer_id.chat_id == last.peer.chat_id
                        && m.peer_id.user_id == last.peer.user_id) { top = m; break; }
            }
            if (top == null || page >= 99 || (top.date == offsetDate && top.id == offsetId)) {
                finish("Проверка каналов Vault не завершена; повторите подключение");
                return;
            }
            discoverRemote(top.date, top.id, MessagesController.getInstance(account).getInputPeer(last.peer), page + 1);
        }));
    }

    private void createVault() {
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
