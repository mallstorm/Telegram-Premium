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
        if (VAULT_INSTANCES[account] == null || VAULT_INSTANCES[account].epoch != TelePremiumCloudResetState.epoch() || VAULT_INSTANCES[account].ownerUserId != UserConfig.getInstance(account).getClientUserId())
            VAULT_INSTANCES[account] = new TelePremiumCloudPeerManager(account);
        return VAULT_INSTANCES[account];
    }

    private String storageTitle() { return VAULT_TITLE; }
    private String storageMarker() {
        return TelePremiumCloudResetState.CREATING.equals(TelePremiumCloudResetState.phase())
                ? VAULT_MARKER + ":" + TelePremiumCloudResetState.operation() : VAULT_MARKER;
    }
    private final long epoch = TelePremiumCloudResetState.epoch();
    private boolean active() { return TelePremiumCloudResetState.allowed(epoch) && ownerUserId == UserConfig.getInstance(account).getClientUserId(); }
    public static synchronized boolean busy() {
        for (TelePremiumCloudPeerManager p : VAULT_INSTANCES) if (p != null && p.creating) return true;
        return false;
    }
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
        if (!active()) return 0;
        return channelId;
    }

    public long getDialogId() {
        long id = getChannelId();
        return id == 0 ? 0 : -id;
    }

    public TLRPC.InputPeer getInputPeer() {
        if (!active() || channelId == 0) return null;
        TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
        peer.channel_id = channelId;
        peer.access_hash = accessHash;
        return peer;
    }

    public TLRPC.InputChannel getInputChannel() {
        if (!active() || channelId == 0) return null;
        TLRPC.TL_inputChannel input = new TLRPC.TL_inputChannel();
        input.channel_id = channelId;
        input.access_hash = accessHash;
        return input;
    }

    public void ensure(Callback callback) {
        if (!active()) { if (callback != null) callback.onReady(0, TelePremiumCloudResetState.MESSAGE); return; }
        if (callback != null) callbacks.add(callback);
        if (getChannelId() != 0 && accessHash != 0) {
            finish(null);
            return;
        }
        if (creating) return;
        creating = true;
        // Authenticated markers, including renamed/archived channels, are safer than title matches.
        TelePremiumCloudResetScanner.scanAccount(account, (plan, error) -> {
            if (!active()) { finish(TelePremiumCloudResetState.MESSAGE); return; }
            if (error != null) { finish(error); return; }
            TelePremiumCloudResetPlan.Target found = null;
            for (TelePremiumCloudResetPlan.Target t : plan.targets) {
                boolean marker = TelePremiumCloudResetState.CREATING.equals(TelePremiumCloudResetState.phase())
                        ? storageMarker().equals(t.about) : VAULT_MARKER.equals(t.about) || t.about.startsWith(VAULT_MARKER + ":");
                if (!marker || isRetired(t.channelId)) continue;
                if (found != null) { finish("Найдено несколько Vault. Откройте защиту облака и проверьте список перед пересозданием"); return; }
                found = t;
            }
            if (found == null) { createVault(); return; }
            channelId = found.channelId; accessHash = found.accessHash;
            prefs().edit().putLong("channel_id", channelId).putLong("access_hash", accessHash).putBoolean("service_peer", true).apply();
            finish(null);
        });
    }

    private void createVault() {
        if (!active()) { finish(TelePremiumCloudResetState.MESSAGE); return; }
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

    private boolean isRetired(long id) {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_retired_cloud_u" + ownerUserId, 0).getBoolean("c" + id, false);
    }

    private void remember(TLRPC.Chat chat) {
        if (!active()) return;
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
        long did = active() ? getDialogId() : 0;
        for (Callback cb : copy) cb.onReady(did, error);
    }

    @Override
    public void didReceivedNotification(int id, int accountId, Object... args) {
        if (!creating || accountId != account) return;
        if (!active()) { finish(TelePremiumCloudResetState.MESSAGE); return; }
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
