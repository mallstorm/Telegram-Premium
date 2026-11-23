package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import java.util.HashSet;
import java.util.Set;

/** Explicit, resumable destruction of the reviewed service channels across all signed-in users. */
public final class TelePremiumCloudResetCoordinator {
    public interface Callback { void complete(String error); }
    private static boolean running;
    private final TelePremiumCloudResetPlan plan;
    private final Callback callback;
    private TelePremiumCloudResetCoordinator(TelePremiumCloudResetPlan plan, Callback callback) { this.plan = plan; this.callback = callback; }
    public static boolean isRunning() { return running; }

    /** A pending deletion can be re-reviewed to include newly found channels or renamed targets. */
    public static void review(TelePremiumCloudResetScanner.Callback callback) {
        if (running || TelePremiumCloudPeerManager.busy()) { callback.complete(null, "Дождитесь завершения текущего подключения Vault и повторите"); return; }
        if (!TelePremiumCloudResetState.pending()) { TelePremiumCloudResetScanner.scan(callback); return; }
        final TelePremiumCloudResetPlan stored;
        try { stored = load(); } catch (Exception e) { callback.complete(null, message(e)); return; }
        if (!TelePremiumCloudResetState.DELETING.equals(stored.phase)) { callback.complete(stored, null); return; }
        TelePremiumCloudResetScanner.scan((found, error) -> {
            if (error != null) { callback.complete(null, error); return; }
            if (!TelePremiumCloudResetScanner.sameScope(stored)) { callback.complete(null, "Верните исходный состав Telegram-аккаунтов"); return; }
            Set<String> completed = new HashSet<>();
            for (TelePremiumCloudResetPlan.Target t : stored.targets) if (t.state != TelePremiumCloudResetPlan.PENDING) completed.add(t.identity());
            // Keep durable receipts. Pending targets absent from dialogs still need explicit absence verification.
            Set<String> fresh = new HashSet<>();
            for (TelePremiumCloudResetPlan.Target t : found.targets) fresh.add(t.identity());
            for (int i = stored.targets.size() - 1; i >= 0; i--)
                if (stored.targets.get(i).state == TelePremiumCloudResetPlan.PENDING && fresh.contains(stored.targets.get(i).identity())) stored.targets.remove(i);
            for (TelePremiumCloudResetPlan.Target t : found.targets) if (!completed.contains(t.identity())) stored.targets.add(t);
            callback.complete(stored, null);
        });
    }

    public static void startReviewed(TelePremiumCloudResetPlan reviewed, Callback callback) {
        if (running) { callback.complete("Пересоздание уже выполняется"); return; }
        try {
            // Snapshot the exact preview so later UI changes cannot expand the deletion scope.
            TelePremiumCloudResetPlan frozen = TelePremiumCloudResetPlan.decode(reviewed.encode());
            if (!TelePremiumCloudResetScanner.sameScope(frozen) || TelePremiumCloudPeerManager.busy())
                throw new IllegalStateException("Состав аккаунтов или подключение Vault изменились. Повторите проверку");
            if (!TelePremiumCloudResetState.pending()) {
                if (!TelePremiumCloudResetState.DELETING.equals(frozen.phase) || !frozen.readyUsers.isEmpty()) throw new IllegalStateException("Неверный новый план пересоздания");
                for (TelePremiumCloudResetPlan.Target t : frozen.targets) if (t.state != TelePremiumCloudResetPlan.PENDING) throw new IllegalStateException("Нет подтверждения удаления нового списка");
                TelePremiumCloudResetState.begin(frozen.id, frozen.encode());
            }
            else {
                TelePremiumCloudResetPlan saved = load();
                if (!saved.id.equals(frozen.id) || !saved.phase.equals(frozen.phase)) throw new IllegalStateException("План пересоздания изменился");
                // Never let a refreshed preview forget a server deletion receipt.
                for (TelePremiumCloudResetPlan.Target t : saved.targets) if (t.state != TelePremiumCloudResetPlan.PENDING) {
                    boolean kept = false;
                    for (TelePremiumCloudResetPlan.Target n : frozen.targets) if (n.identity().equals(t.identity()) && n.state == t.state) kept = true;
                    if (!kept) throw new IllegalStateException("Потеряно подтверждение удаления");
                }
                TelePremiumCloudResetState.save(frozen.id, frozen.phase, frozen.encode(), false);
            }
            running = true;
            CloudPayloadRepository.cancelAllForReset();
            CloudDownloadManager.cancelAllForReset();
            new TelePremiumCloudResetCoordinator(frozen, callback).next();
        } catch (Exception e) { running = false; callback.complete(message(e)); }
    }

    private static TelePremiumCloudResetPlan load() throws Exception {
        TelePremiumCloudResetPlan p = TelePremiumCloudResetPlan.decode(TelePremiumCloudResetState.plan());
        if (!p.id.equals(TelePremiumCloudResetState.operation()) || !p.phase.equals(TelePremiumCloudResetState.phase()))
            throw new IllegalStateException("Состояние пересоздания повреждено; старый ключ сохранён");
        return p;
    }
    private boolean scope() {
        if (!TelePremiumCloudResetScanner.sameScope(plan)) { finish("Состав Telegram-аккаунтов изменился. Верните исходные аккаунты и продолжите"); return false; }
        return true;
    }
    private void next() {
        if (!scope()) return;
        try { for (TelePremiumCloudResetPlan.Target t : plan.targets) if (t.state != TelePremiumCloudResetPlan.PENDING) retire(t); }
        catch (Exception e) { finish(message(e)); return; }
        if (TelePremiumCloudResetState.CREATING.equals(plan.phase)) { createNext(); return; }
        if (TelePremiumCloudResetState.CLEARING.equals(plan.phase)) { verifyEmpty(this::clearAndRotate); return; }
        for (TelePremiumCloudResetPlan.Target t : plan.targets)
            if (t.state == TelePremiumCloudResetPlan.PENDING) { verifyAndDelete(t); return; }
        verifyEmpty(() -> {
            try { transition(TelePremiumCloudResetState.CLEARING, false); clearAndRotate(); }
            catch (Exception e) { finish(message(e)); }
        });
    }
    private void verifyAndDelete(TelePremiumCloudResetPlan.Target t) {
        final int slot = TelePremiumCloudResetScanner.slot(t.userId);
        TelePremiumCloudResetScanner.full(slot, t.channelId, t.accessHash, (chat, about, error) -> {
            if (!scope()) return;
            if (error != null) {
                if ("CHANNEL_INVALID".equals(error) || "CHANNEL_PRIVATE".equals(error)) confirmAbsent(t);
                else finish(error);
                return;
            }
            if (TelePremiumCloudResetScanner.slot(t.userId) != slot || chat.access_hash == 0 || !t.title.equals(chat.title) || !t.about.equals(about)
                    || !TelePremiumCloudResetPlan.isManaged(chat.creator, chat.broadcast, chat.megagroup, about, t.knownBinding)) {
                finish("Канал изменился после просмотра списка. Повторите проверку и подтвердите новый список"); return;
            }
            TLRPC.TL_channels_deleteChannel req = new TLRPC.TL_channels_deleteChannel();
            TLRPC.TL_inputChannel input = new TLRPC.TL_inputChannel(); input.channel_id = t.channelId; input.access_hash = chat.access_hash; req.channel = input;
            ConnectionsManager.getInstance(slot).sendRequest(req, (response, requestError) -> AndroidUtilities.runOnUIThread(() -> {
                if (requestError != null || !(response instanceof TLRPC.Updates)) { finish(requestError == null ? "Нет подтверждения удаления канала" : requestError.text); return; }
                if (!scope()) return;
                if (TelePremiumCloudResetScanner.slot(t.userId) == slot) MessagesController.getInstance(slot).processUpdates((TLRPC.Updates) response, false);
                receipt(t, TelePremiumCloudResetPlan.DELETED);
            }));
        });
    }
    private void confirmAbsent(TelePremiumCloudResetPlan.Target target) {
        TelePremiumCloudResetScanner.scan((found, error) -> {
            if (error != null) { finish(error); return; }
            if (!scope()) return;
            if (found.observedChannels.contains(target.identity())) { finish("Канал существует, но Telegram не разрешил его проверить"); return; }
            // A complete authenticated dialogs scan confirmed no owned managed channel with this identity.
            receipt(target, TelePremiumCloudResetPlan.ABSENT);
        });
    }
    private void receipt(TelePremiumCloudResetPlan.Target t, int state) {
        try {
            t.state = state; persist();
            retire(t);
            next();
        } catch (Exception e) { finish(message(e)); }
    }
    private void retire(TelePremiumCloudResetPlan.Target t) throws Exception {
        Context c = ApplicationLoader.applicationContext;
        if (!c.getSharedPreferences("telepremium_retired_cloud_u" + t.userId, 0).edit().putBoolean("c" + t.channelId, true).commit())
            throw new IllegalStateException("Не удалось сохранить удалённый канал");
        for (TelePremiumCloudResetPlan.Account a : TelePremiumCloudResetScanner.accounts()) if (a.userId == t.userId) {
            MessagesController mc = MessagesController.getInstance(a.slot);
            mc.getChats().remove(t.channelId);
            // Local-only dialog cleanup: no second remote history/delete request.
            mc.deleteDialog(-t.channelId, 3);
        }
    }
    private void verifyEmpty(Runnable then) {
        if (!plan.deletionComplete()) { finish("Не все удаления подтверждены"); return; }
        TelePremiumCloudResetScanner.scan((found, error) -> {
            if (error != null) { finish(error); return; }
            if (!scope()) return;
            if (!found.targets.isEmpty()) {
                try { if (TelePremiumCloudResetState.CLEARING.equals(plan.phase)) transition(TelePremiumCloudResetState.DELETING, false); }
                catch (Exception e) { finish(message(e)); return; }
                finish("Найдены дополнительные служебные каналы. Повторите проверку списка перед продолжением"); return;
            }
            then.run();
        });
    }
    private void clearAndRotate() {
        if (!scope()) return;
        // All encryption jobs use this queue: drain them before removing journals and rotating the key.
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (!TelePremiumCloudResetScanner.sameScope(plan)) throw new IllegalStateException("Состав аккаунтов изменился");
                Context c = ApplicationLoader.applicationContext;
                java.util.ArrayList<TelePremiumCloudResetPlan.Account> cleanup = TelePremiumCloudResetScanner.accounts();
                Set<Integer> slots = new HashSet<>();
                for (TelePremiumCloudResetPlan.Account a : cleanup) slots.add(a.slot);
                for (TelePremiumCloudResetPlan.Account a : plan.accounts) if (slots.add(a.slot)) cleanup.add(a);
                for (TelePremiumCloudResetPlan.Account a : cleanup) {
                    for (String prefs : new String[]{"telepremium_cloud_peer_" + a.slot, "telepremium_vault_peer_" + a.slot,
                            "telepremium_vault_peer_u" + a.userId, "telepremium_vault_index_checkpoint_" + a.slot,
                            "telepremium_vault_index_checkpoint_u" + a.userId, "telepremium_cloud_migration_" + a.slot,
                            "tp_cloud_transfer_" + a.slot, "tp_cloud_multipart_" + a.slot})
                        if (!c.getSharedPreferences(prefs, 0).edit().clear().commit()) throw new IllegalStateException("Не удалось очистить локальное состояние облака");
                    TelePremiumCloudResetFiles.remove(c.getFilesDir(), "tpvault_journal/a" + a.slot);
                    TelePremiumCloudResetFiles.remove(c.getFilesDir(), "tpvault_pending_index_v1/a" + a.slot);
                }
                for (String dir : new String[]{"tpvault_dec", "tpvault_dec_parts", VaultSessionGuard.LOCKED_CACHE_DIR}) TelePremiumCloudResetFiles.remove(c.getCacheDir(), dir);
                org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession.shared().lockAll();
                for (String lockPrefs : new String[]{"tp_folder_locks", "tp_folder_bio"}) c.getSharedPreferences(lockPrefs, 0).edit().clear().apply();
                for (String dir : new String[]{"telepremium_vault_outbox", "telepremium_cloud_outbox", "telepremium_cloud_joined"}) TelePremiumCloudResetFiles.remove(c.getExternalCacheDir(), dir);
                if (!TelePremiumCloudResetScanner.sameScope(plan)) throw new IllegalStateException("Состав аккаунтов изменился");
                TelePremiumVaultKeyManager.rotateAfterCloudReset(c, plan.id);
                AndroidUtilities.runOnUIThread(() -> {
                    try { transition(TelePremiumCloudResetState.CREATING, true); createNext(); }
                    catch (Exception e) { finish(message(e)); }
                });
            } catch (Exception e) { AndroidUtilities.runOnUIThread(() -> finish(message(e))); }
        });
    }
    private void createNext() {
        if (!scope()) return;
        for (TelePremiumCloudResetPlan.Account a : plan.accounts) if (!plan.readyUsers.contains(a.userId)) {
            int slot = TelePremiumCloudResetScanner.slot(a.userId);
            TelePremiumCloudManager manager = TelePremiumCloudManager.getInstance(slot);
            manager.bootstrap(error -> {
                if (!scope()) return;
                if (error != null || !manager.isRemoteReady()) { finish(error == null ? "Новый Vault ещё не синхронизирован" : error); return; }
                try { plan.readyUsers.add(a.userId); persist(); createNext(); }
                catch (Exception e) { finish(message(e)); }
            });
            return;
        }
        try { TelePremiumCloudResetState.complete(plan.id); finish(null); }
        catch (Exception e) { finish(message(e)); }
    }
    private void persist() throws Exception { TelePremiumCloudResetState.save(plan.id, plan.phase, plan.encode(), false); }
    private void transition(String phase, boolean advance) throws Exception {
        String previous = plan.phase; plan.phase = phase;
        try { TelePremiumCloudResetState.save(plan.id, phase, plan.encode(), advance); }
        catch (Exception e) { plan.phase = previous; throw e; }
    }
    private void finish(String error) { running = false; callback.complete(error); }
    private static String message(Exception e) { return e.getMessage() == null || e.getMessage().isEmpty() ? "Пересоздание приостановлено; повторите в настройках" : e.getMessage(); }
}
