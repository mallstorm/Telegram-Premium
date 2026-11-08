package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Read-only discovery. A matching title alone is never deletion authority. */
public final class TelePremiumCloudResetScanner {
    public interface Callback { void complete(TelePremiumCloudResetPlan plan, String error); }
    public interface FullCallback { void complete(TLRPC.Chat chat, String about, String error); }
    private final TelePremiumCloudResetPlan plan = new TelePremiumCloudResetPlan();
    private final Callback callback;
    private final LinkedHashMap<Long, TLRPC.Chat> candidates = new LinkedHashMap<>();
    private final Set<Long> scannedUsers = new HashSet<>();
    private int accountIndex;
    private long onlyUser;
    private TelePremiumCloudResetScanner(Callback callback) { this.callback = callback; }

    public static ArrayList<TelePremiumCloudResetPlan.Account> accounts() {
        ArrayList<TelePremiumCloudResetPlan.Account> result = new ArrayList<>();
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            UserConfig config = UserConfig.getInstance(slot);
            if (!config.isClientActivated() || config.getClientUserId() <= 0) continue;
            TLRPC.User u = config.getCurrentUser();
            String name = u == null ? "" : ((u.first_name == null ? "" : u.first_name) + " " + (u.last_name == null ? "" : u.last_name)).trim();
            result.add(new TelePremiumCloudResetPlan.Account(slot, config.getClientUserId(), name));
        }
        return result;
    }
    public static void scan(Callback callback) {
        TelePremiumCloudResetScanner scanner = new TelePremiumCloudResetScanner(callback);
        scanner.plan.accounts.addAll(accounts());
        if (scanner.plan.accounts.isEmpty()) { callback.complete(null, "Нет подключённых Telegram-аккаунтов"); return; }
        scanner.nextAccount();
    }
    public static void scanAccount(int account, Callback callback) {
        TelePremiumCloudResetScanner scanner = new TelePremiumCloudResetScanner(callback);
        scanner.plan.accounts.addAll(accounts());
        scanner.onlyUser = UserConfig.getInstance(account).getClientUserId();
        if (scanner.onlyUser <= 0) { callback.complete(null, "Telegram-аккаунт отключён"); return; }
        scanner.nextAccount();
    }
    public static int slot(long user) {
        for (TelePremiumCloudResetPlan.Account a : accounts()) if (a.userId == user) return a.slot;
        return -1;
    }
    public static boolean sameScope(TelePremiumCloudResetPlan plan) {
        Set<Long> approved = new HashSet<>(), current = new HashSet<>();
        for (TelePremiumCloudResetPlan.Account a : plan.accounts) approved.add(a.userId);
        for (TelePremiumCloudResetPlan.Account a : accounts()) current.add(a.userId);
        return approved.equals(current);
    }
    private void nextAccount() {
        if (!sameScope(plan)) { fail("Состав Telegram-аккаунтов изменился. Верните исходные аккаунты и повторите"); return; }
        while (accountIndex < plan.accounts.size() && ((onlyUser != 0 && plan.accounts.get(accountIndex).userId != onlyUser) || !scannedUsers.add(plan.accounts.get(accountIndex).userId))) accountIndex++;
        if (accountIndex >= plan.accounts.size()) { callback.complete(plan, null); return; }
        candidates.clear(); dialogs(0, 0, 0, new TLRPC.TL_inputPeerEmpty(), 0);
    }
    private int account() { return slot(plan.accounts.get(accountIndex).userId); }
    private void dialogs(int folder, int date, int id, TLRPC.InputPeer peer, int page) {
        if (account() < 0) { fail("Telegram-аккаунт отключён"); return; }
        final int account = account();
        TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
        req.flags |= 2; req.folder_id = folder; req.limit = 100; req.exclude_pinned = page > 0;
        req.offset_date = date; req.offset_id = id; req.offset_peer = peer;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!sameScope(plan) || slot(plan.accounts.get(accountIndex).userId) != account) { fail("Состав аккаунтов изменился во время проверки"); return; }
            if (error != null || !(response instanceof TLRPC.messages_Dialogs)) { fail(error == null ? "Не удалось получить полный список каналов" : error.text); return; }
            TLRPC.messages_Dialogs result = (TLRPC.messages_Dialogs) response;
            MessagesController mc = MessagesController.getInstance(account);
            mc.putUsers(result.users, false); mc.putChats(result.chats, false);
            long owner = plan.accounts.get(accountIndex).userId;
            for (TLRPC.Dialog d : result.dialogs) if (d.peer != null && d.peer.channel_id != 0) plan.observedChannels.add(owner + ":" + d.peer.channel_id);
            for (TLRPC.Chat chat : result.chats) plan.observedChannels.add(owner + ":" + chat.id);
            for (TLRPC.Chat chat : result.chats)
                if (chat.creator && chat.broadcast && !chat.megagroup && chat.access_hash != 0) candidates.put(chat.id, chat);
            if (result.dialogs.size() >= req.limit) {
                TLRPC.Dialog last = null; TLRPC.Message top = null;
                for (int i = result.dialogs.size() - 1; i >= 0 && top == null; i--) {
                    TLRPC.Dialog d = result.dialogs.get(i); if (d.peer == null) continue;
                    for (TLRPC.Message m : result.messages) {
                        if (m.id == d.top_message && m.peer_id != null && m.peer_id.channel_id == d.peer.channel_id
                                && m.peer_id.chat_id == d.peer.chat_id && m.peer_id.user_id == d.peer.user_id) { last = d; top = m; break; }
                    }
                }
                if (top == null || page >= 99 || (top.date == date && top.id == id)) { fail("Список каналов проверен не полностью; удаление запрещено"); return; }
                dialogs(folder, top.date, top.id, mc.getInputPeer(last.peer), page + 1);
            } else if (folder == 0) dialogs(1, 0, 0, new TLRPC.TL_inputPeerEmpty(), 0);
            else inspect(new ArrayList<>(candidates.values()), 0);
        }));
    }
    private boolean known(long user, long channel) {
        for (TelePremiumCloudResetPlan.Account a : plan.accounts) {
            if (a.userId != user) continue;
            for (String name : new String[]{"telepremium_cloud_peer_" + a.slot, "telepremium_vault_peer_" + a.slot, "telepremium_vault_peer_u" + user})
                if (ApplicationLoader.applicationContext.getSharedPreferences(name, 0).getLong("channel_id", 0) == channel) return true;
        }
        return false;
    }
    private void inspect(ArrayList<TLRPC.Chat> chats, int index) {
        if (index == chats.size()) { accountIndex++; nextAccount(); return; }
        final long user = plan.accounts.get(accountIndex).userId;
        TLRPC.Chat candidate = chats.get(index);
        full(account(), candidate.id, candidate.access_hash, (chat, about, error) -> {
            if (error != null) { fail(error); return; }
            if (!sameScope(plan)) { fail("Состав аккаунтов изменился во время проверки"); return; }
            boolean bound = known(user, chat.id);
            if (TelePremiumCloudResetPlan.isManaged(chat.creator, chat.broadcast, chat.megagroup, about, bound))
                plan.targets.add(new TelePremiumCloudResetPlan.Target(user, chat.id, chat.access_hash, chat.title, about, bound));
            else plan.ignoredChannels++;
            inspect(chats, index + 1);
        });
    }
    public static void full(int account, long id, long hash, FullCallback callback) {
        if (account < 0) { callback.complete(null, null, "Telegram-аккаунт отключён"); return; }
        final long owner = UserConfig.getInstance(account).getClientUserId();
        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        TLRPC.TL_inputChannel input = new TLRPC.TL_inputChannel(); input.channel_id = id; input.access_hash = hash; req.channel = input;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (UserConfig.getInstance(account).getClientUserId() != owner) { callback.complete(null, null, "Telegram-аккаунт изменился"); return; }
            if (error != null || !(response instanceof TLRPC.TL_messages_chatFull)) { callback.complete(null, null, error == null ? "Не удалось проверить канал" : error.text); return; }
            TLRPC.TL_messages_chatFull full = (TLRPC.TL_messages_chatFull) response;
            TLRPC.Chat found = null;
            for (TLRPC.Chat c : full.chats) if (c.id == id) found = c;
            if (found == null || full.full_chat == null || full.full_chat.id != id) { callback.complete(null, null, "Неполное подтверждение канала"); return; }
            MessagesController.getInstance(account).putChats(full.chats, false);
            callback.complete(found, full.full_chat.about == null ? "" : full.full_chat.about, null);
        }));
    }
    private void fail(String error) { callback.complete(null, error == null || error.isEmpty() ? "Проверка каналов не завершена" : error); }
}
