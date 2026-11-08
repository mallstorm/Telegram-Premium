package org.telegram.messenger.telepremium.cloud.vault;

import android.content.SharedPreferences;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.cloud.CloudConflictResolver;
import org.telegram.messenger.telepremium.cloud.CloudIntegrityChecker;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudPeerManager;
import org.telegram.messenger.telepremium.cloud.TelegramCloudMetadataStore;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Authenticated V3 catalogs only. A failed read never creates an empty replacement. */
public final class TelePremiumVaultIndexStore {
    public static final String CAPTION = "#TPV3I";
    private final int account;
    private final TelePremiumCloudPeerManager peer;
    private boolean saving;

    public TelePremiumVaultIndexStore(int account) {
        this.account = account;
        peer = TelePremiumCloudPeerManager.getVaultInstance(account);
    }

    private SharedPreferences checkpoint() {
        // Bind checkpoints to the Telegram identity, not the reusable account slot.
        SharedPreferences current = ApplicationLoader.applicationContext.getSharedPreferences(
                "telepremium_vault_index_checkpoint_u" + UserConfig.getInstance(account).getClientUserId(), 0);
        if (!current.contains("generation")) {
            // Preserve the old rollback guard only when its remembered V3 channel
            // is the channel rediscovered for this Telegram identity.
            SharedPreferences oldPeer = ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_peer_" + account, 0);
            long channel = peer.getChannelId();
            if (channel != 0 && channel == oldPeer.getLong("channel_id", 0)) {
                SharedPreferences old = ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_index_checkpoint_" + account, 0);
                long generation = old.getLong("generation", 0);
                if (generation > 0 && !current.edit().putLong("generation", generation).commit()) return old;
            }
        }
        return current;
    }

    public void loadLatest(TelegramCloudMetadataStore.LoadCallback callback) {
        loadGenerations((list, error) -> callback.onLoaded(best(list), error));
    }

    private static CloudSnapshot best(List<CloudSnapshot> list) {
        CloudSnapshot best = null;
        for (CloudSnapshot s : list)
            if (best == null || s.generation > best.generation ||
                    (s.generation == best.generation && s.updatedAt > best.updatedAt)) best = s;
        return best;
    }

    public void loadGenerations(TelegramCloudMetadataStore.HistoryCallback callback) {
        final byte[] master;
        try { master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext); }
        catch (Exception e) { callback.onLoaded(new ArrayList<>(), failure(e.getMessage(), "Не удалось открыть ключ Vault")); return; }
        peer.ensure((dialogId, error) -> {
            if (error != null || peer.getInputPeer() == null) {
                loadLocal(master, error == null ? "Канал Vault недоступен" : error, callback);
                return;
            }
            findIndexes(master, 0, 0, false, new ArrayList<>(), callback);
        });
    }

    private void findIndexes(byte[] master, int offset, int page, boolean seenPayload,
                             ArrayList<TLRPC.Message> indexes, TelegramCloudMetadataStore.HistoryCallback callback) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer.getInputPeer();
        req.limit = 100;
        req.offset_id = offset;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                loadLocal(master, error == null ? "История Vault недоступна" : failure(error.text, "История Vault недоступна"), callback);
                return;
            }
            List<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
            boolean payload = seenPayload;
            int oldest = Integer.MAX_VALUE;
            for (TLRPC.Message m : messages) {
                if (m == null) continue;
                if (m.id > 0) oldest = Math.min(oldest, m.id);
                if (m.media == null || m.media.document == null || m.message == null) continue;
                if (CAPTION.equals(m.message) || m.message.startsWith(CAPTION + ":")) indexes.add(m);
                else if (m.message.startsWith("#TPV3F:") || m.message.startsWith("#TPPART:")) payload = true;
            }
            if (indexes.size() < 8 && messages.size() == req.limit) {
                if (page >= 99 || oldest == Integer.MAX_VALUE || (offset != 0 && oldest >= offset)) {
                    loadLocal(master, "Не удалось полностью проверить историю Vault; запись приостановлена", callback);
                } else findIndexes(master, oldest, page + 1, payload, indexes, callback);
                return;
            }
            indexes.sort((a, b) -> Integer.compare(b.id, a.id));
            if (indexes.size() > 8) indexes.subList(8, indexes.size()).clear();
            if (indexes.isEmpty()) {
                if (payload || checkpoint().getLong("generation", 0) > 0) {
                    loadLocal(master, "Каталог Vault отсутствует; данные сохранены, требуется восстановление", callback);
                } else {
                    // A first upload may have timed out after its durable journal was written.
                    loadLocal(master, null, (local, warning) -> reconcile(new ArrayList<>(), local, warning, callback));
                }
                return;
            }
            loadNext(indexes, 0, new ArrayList<>(), master, null, callback);
        }));
    }

    private void loadNext(List<TLRPC.Message> messages, int index, ArrayList<CloudSnapshot> decoded,
                          byte[] master, String newerFailure, TelegramCloudMetadataStore.HistoryCallback callback) {
        if (index == messages.size()) {
            CloudSnapshot remote = best(decoded);
            if (newerFailure != null) {
                loadLocal(master, newerFailure, (local, warning) -> {
                    local.addAll(decoded);
                    callback.onLoaded(local, warning);
                });
                return;
            }
            if (remote == null) {
                loadLocal(master, "Не найден читаемый каталог Vault; восстановите исходный ключ", callback);
                return;
            }
            long known = checkpoint().getLong("generation", 0);
            String knownCloud = checkpoint().getString("cloud_id", null);
            if (knownCloud != null && !knownCloud.equals(remote.cloudId)) {
                loadLocal(master, "Серверный каталог относится к другому Vault; запись приостановлена", (local, warning) -> {
                    local.addAll(decoded);
                    callback.onLoaded(local, warning);
                });
                return;
            }
            if (known > remote.generation) {
                loadLocal(master, "Обнаружен откат или удаление каталога Vault; запись приостановлена", (local, warning) -> {
                    local.addAll(decoded);
                    callback.onLoaded(local, warning);
                });
                return;
            }
            if (!checkpoint().edit().putLong("generation", remote.generation).putString("cloud_id", remote.cloudId).commit()) {
                loadLocal(master, "Не удалось сохранить подтверждение каталога Vault", (local, warning) -> {
                    local.addAll(decoded);
                    callback.onLoaded(local, warning);
                });
                return;
            }
            loadLocal(master, null, (local, warning) -> reconcile(decoded, local, warning, callback));
            return;
        }
        loadCandidate(messages, index, decoded, master, newerFailure, false, callback);
    }

    private void loadCandidate(List<TLRPC.Message> messages, int index, ArrayList<CloudSnapshot> decoded,
                               byte[] master, String newerFailure, boolean refreshed,
                               TelegramCloudMetadataStore.HistoryCallback callback) {
        TLRPC.Message message = messages.get(index);
        fetchDocument(message, refreshed, (file, error) -> {
            if (error != null) {
                String failure = decoded.isEmpty() && newerFailure == null ? error : newerFailure;
                loadNext(messages, index + 1, decoded, master, failure, callback);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                CloudSnapshot snapshot;
                try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    TelePremiumVaultCrypto.decrypt(in, out, master, TelePremiumVaultCrypto.Purpose.INDEX);
                    snapshot = CloudSnapshot.decode(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    validateSnapshot(snapshot);
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!refreshed) {
                            // Evict a damaged transport cache once. Never erase the private journal.
                            loadCandidate(messages, index, decoded, master, newerFailure, true, callback);
                        } else {
                            String failure = decoded.isEmpty() && newerFailure == null ? describeFailure(e) : newerFailure;
                            loadNext(messages, index + 1, decoded, master, failure, callback);
                        }
                    });
                    return;
                }
                try { cacheVerifiedIndex(file, snapshot); }
                catch (Exception e) { FileLog.e(e); } // Remote data remains usable if local storage is full.
                AndroidUtilities.runOnUIThread(() -> {
                    decoded.add(snapshot);
                    loadNext(messages, index + 1, decoded, master, newerFailure, callback);
                });
            });
        });
    }

    private static String describeFailure(Exception e) {
        if (e instanceof javax.crypto.AEADBadTagException)
            return "Индекс Vault не прошёл проверку AES-GCM: нужен исходный ключ либо неповреждённая копия";
        if (e instanceof IOException) return "Не удалось прочитать файл индекса Vault: " + e.getClass().getSimpleName();
        return "Неверный каталог Vault: " + e.getClass().getSimpleName();
    }

    private static String failure(String message, String fallback) {
        return message == null || message.isEmpty() ? fallback : message;
    }

    private void validateSnapshot(CloudSnapshot snapshot) throws Exception {
        if (snapshot.ownerUserId != UserConfig.getInstance(account).getClientUserId())
            throw new SecurityException("Vault account owner mismatch");
        if (snapshot.generation <= 0 || snapshot.cloudId == null || snapshot.cloudId.isEmpty()
                || !new CloudIntegrityChecker().check(snapshot).ok())
            throw new IOException("Invalid Vault catalog structure");
        for (CloudNode node : snapshot.nodes)
            if (node.type == CloudNode.TYPE_FILE && node.state != CloudNode.STATE_DELETED && node.encryptionVersion != 1)
                throw new IOException("Plaintext files are not allowed in Vault V3");
    }

    /** Only pending local changes ahead of a verified server checkpoint may be auto-published. */
    private void reconcile(ArrayList<CloudSnapshot> remote, List<CloudSnapshot> local, String warning,
                           TelegramCloudMetadataStore.HistoryCallback callback) {
        if (warning != null) { local.addAll(remote); callback.onLoaded(local, warning); return; }
        CloudSnapshot server = best(remote), pending = best(local);
        if (pending == null || (server != null && pending.generation <= server.generation)) {
            callback.onLoaded(remote, null);
            return;
        }
        if (server != null && !pending.cloudId.equals(server.cloudId)) {
            local.addAll(remote);
            callback.onLoaded(local, "Локальный и серверный каталоги относятся к разным Vault");
            return;
        }
        CloudSnapshot merged = new CloudConflictResolver().merge(pending, server);
        merged.generation = Math.max(pending.generation, server == null ? 0 : server.generation) + 1;
        merged.updatedAt = System.currentTimeMillis();
        save(merged, error -> {
            ArrayList<CloudSnapshot> result = new ArrayList<>(remote);
            result.add(merged);
            callback.onLoaded(result, error);
        });
    }

    private void loadLocal(byte[] master, String warning, TelegramCloudMetadataStore.HistoryCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<CloudSnapshot> snapshots = new ArrayList<>();
            File[] journals = journals();
            for (int i = 0; i < Math.min(journals.length, 10); i++) {
                try (FileInputStream in = new FileInputStream(journals[i]); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    TelePremiumVaultCrypto.decrypt(in, out, master, TelePremiumVaultCrypto.Purpose.INDEX);
                    CloudSnapshot snapshot = CloudSnapshot.decode(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    validateSnapshot(snapshot);
                    snapshots.add(snapshot);
                } catch (Exception e) { FileLog.e(e); }
            }
            Arrays.fill(master, (byte) 0);
            String resultWarning = snapshots.isEmpty() && journals.length > 0 && warning == null
                    ? "Локальный каталог Vault не читается; восстановите исходный ключ или резервную копию" : warning;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(snapshots, resultWarning));
        });
    }

    private File[] journals() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "tpvault_journal/a" + account);
        File[] files = dir.listFiles((d, name) -> name.startsWith("g") && name.endsWith(".tpv"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(TelePremiumVaultIndexStore::journalGeneration)
                .thenComparingLong(File::lastModified).reversed());
        return files;
    }

    private static long journalGeneration(File file) {
        try { return Long.parseLong(file.getName().substring(1, file.getName().indexOf('_'))); }
        catch (RuntimeException e) { return 0; }
    }

    private void cacheVerifiedIndex(File file, CloudSnapshot snapshot) throws Exception {
        TelePremiumVaultKeyManager.markKeyUsed(ApplicationLoader.applicationContext);
        // Cache a received catalog as well, so a second device can subsequently work offline.
        File target = new File(journalDirectory(), "g" + snapshot.generation + "_remote.tpv");
        File partial = new File(target.getPath() + ".partial");
        try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) if (n > 0) out.write(buffer, 0, n);
            out.getFD().sync();
        } catch (Exception e) { partial.delete(); throw e; }
        if (!partial.renameTo(target)) { partial.delete(); throw new IOException("Cannot commit Vault cache"); }
        pruneJournal();
    }

    private interface FileCallback { void onReady(File file, String error); }

    private void fetchDocument(TLRPC.Message message, boolean refresh, FileCallback callback) {
        TLRPC.Document d = message.media.document;
        File cached = FileLoader.getInstance(account).getPathToAttach(d, true);
        File regular = FileLoader.getInstance(account).getPathToAttach(d, false);
        if (!refresh) {
            if (cached != null && cached.isFile()) { callback.onReady(cached, null); return; }
            if (regular != null && regular.isFile()) { callback.onReady(regular, null); return; }
        } else {
            if (cached != null && cached.isFile() && !cached.delete()) { callback.onReady(null, "Не удалось обновить кэш индекса Vault"); return; }
            if (regular != null && !regular.equals(cached) && regular.isFile() && !regular.delete()) {
                callback.onReady(null, "Не удалось обновить кэш индекса Vault"); return;
            }
        }
        NotificationCenter nc = NotificationCenter.getInstance(account);
        final class Download implements NotificationCenter.NotificationCenterDelegate, Runnable {
            boolean done;
            void finish(File file, String error) {
                if (done) return; done = true;
                nc.removeObserver(this, NotificationCenter.fileLoaded);
                nc.removeObserver(this, NotificationCenter.fileLoadFailed);
                AndroidUtilities.cancelRunOnUIThread(this);
                callback.onReady(file, error);
            }
            @Override public void run() { finish(null, "Истекло время загрузки индекса Vault"); }
            @Override public void didReceivedNotification(int id, int a, Object... args) {
                if (a != account || args.length == 0 || !FileLoader.getAttachFileName(d).equals(args[0])) return;
                if (id == NotificationCenter.fileLoaded) {
                    File ready = args.length > 1 && args[1] instanceof File ? (File) args[1] :
                            FileLoader.getInstance(account).getPathToAttach(d, true);
                    finish(ready, ready != null && ready.isFile() ? null : "Файл индекса Vault отсутствует");
                } else if (id == NotificationCenter.fileLoadFailed) finish(null, "Ошибка загрузки индекса Vault");
            }
        }
        Download download = new Download();
        nc.addObserver(download, NotificationCenter.fileLoaded);
        nc.addObserver(download, NotificationCenter.fileLoadFailed);
        AndroidUtilities.runOnUIThread(download, 120_000);
        FileLoader.getInstance(account).loadFile(d, message, FileLoader.PRIORITY_NORMAL, 0);
    }

    public void save(CloudSnapshot snapshot, TelegramCloudMetadataStore.SaveCallback callback) {
        if (saving) { callback.onSaved("Синхронизация Vault уже выполняется"); return; }
        final byte[] master;
        try {
            validateSnapshot(snapshot);
            String knownCloud = checkpoint().getString("cloud_id", null);
            if (knownCloud != null && !knownCloud.equals(snapshot.cloudId))
                throw new SecurityException("Нельзя заменить существующий каталог другим Vault");
            master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
        } catch (Exception e) { callback.onSaved(failure(e.getMessage(), "Не удалось открыть ключ или проверить каталог Vault")); return; }
        saving = true;
        final long generation = snapshot.generation;
        Utilities.globalQueue.postRunnable(() -> {
            File partial = null;
            try {
                TelePremiumVaultKeyManager.markKeyUsed(ApplicationLoader.applicationContext);
                byte[] raw = snapshot.encode().getBytes(StandardCharsets.UTF_8);
                File journal = new File(journalDirectory(), "g" + generation + "_" + VaultFileOps.randomFileName("idx_"));
                partial = new File(journal.getPath() + ".partial");
                try (FileOutputStream out = new FileOutputStream(partial)) {
                    TelePremiumVaultCrypto.encrypt(new ByteArrayInputStream(raw), out, master, TelePremiumVaultCrypto.Purpose.INDEX);
                    out.getFD().sync();
                } finally { Arrays.fill(raw, (byte) 0); }
                if (!partial.renameTo(journal)) throw new IOException("Cannot commit Vault journal");
                AndroidUtilities.runOnUIThread(() -> peer.ensure((dialogId, error) -> {
                    if (error != null || dialogId == 0) { saving = false; callback.onSaved(error == null ? "Канал Vault недоступен" : error); return; }
                    sendEncryptedIndex(journal, generation, snapshot.cloudId, dialogId, callback);
                }));
            } catch (Exception e) {
                if (partial != null) partial.delete();
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> { saving = false; callback.onSaved("Не удалось сохранить зашифрованный каталог Vault"); });
            } finally { Arrays.fill(master, (byte) 0); }
        });
    }

    private File journalDirectory() throws IOException {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "tpvault_journal/a" + account);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create private Vault journal");
        return dir;
    }

    private void sendEncryptedIndex(File encrypted, long generation, String cloudId, long dialogId, TelegramCloudMetadataStore.SaveCallback callback) {
        final File upload;
        try { upload = VaultTransportStaging.stageEncryptedIndex(ApplicationLoader.applicationContext, encrypted); }
        catch (Exception e) { saving = false; callback.onSaved("Не удалось подготовить индекс Vault к отправке"); return; }
        final String caption = CAPTION + ":" + VaultFileOps.randomFileName("i_").substring(2, 14);
        NotificationCenter nc = NotificationCenter.getInstance(account);
        final class Upload implements NotificationCenter.NotificationCenterDelegate, Runnable {
            boolean done;
            void finish(String error) {
                if (done) return; done = true; saving = false;
                nc.removeObserver(this, NotificationCenter.messageReceivedByServer);
                AndroidUtilities.cancelRunOnUIThread(this);
                upload.delete();
                if (error == null) {
                    if (!checkpoint().edit().putLong("generation", Math.max(generation, checkpoint().getLong("generation", 0)))
                            .putString("cloud_id", cloudId).commit()) {
                        callback.onSaved("Каталог отправлен, но локальное подтверждение не сохранено; требуется проверка синхронизации");
                        return;
                    }
                    pruneJournal();
                }
                callback.onSaved(error);
            }
            @Override public void run() { finish("Подтверждение загрузки индекса Vault не получено; повторим синхронизацию"); }
            @Override public void didReceivedNotification(int id, int a, Object... args) {
                if (done || a != account || id != NotificationCenter.messageReceivedByServer ||
                        args.length < 3 || !(args[2] instanceof TLRPC.Message)) return;
                TLRPC.Message m = (TLRPC.Message) args[2];
                if (m.media == null || m.media.document == null || m.peer_id == null || !caption.equals(m.message)) return;
                long did = m.peer_id.channel_id != 0 ? -m.peer_id.channel_id :
                        m.peer_id.chat_id != 0 ? -m.peer_id.chat_id : m.peer_id.user_id;
                if (did == dialogId) finish(null);
            }
        }
        Upload operation = new Upload();
        nc.addObserver(operation, NotificationCenter.messageReceivedByServer);
        AndroidUtilities.runOnUIThread(operation, 120_000);
        try {
            SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), upload.getAbsolutePath(),
                    upload.getAbsolutePath(), null, caption, "application/octet-stream", dialogId,
                    null, null, null, null, null, false, 0, null, null, false);
        } catch (RuntimeException e) { FileLog.e(e); operation.finish("Не удалось отправить индекс Vault"); }
    }

    public void restoreLatestLocalIndex(TelegramCloudMetadataStore.SaveCallback callback) {
        if (saving) { callback.onSaved("Синхронизация Vault уже выполняется"); return; }
        final byte[] master;
        try { master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext); }
        catch (Exception e) { callback.onSaved(failure(e.getMessage(), "Не удалось открыть ключ Vault")); return; }
        loadLocal(master, null, (local, warning) -> {
            CloudSnapshot snapshot = best(local);
            if (snapshot == null) { callback.onSaved("Читаемый локальный каталог Vault не найден"); return; }
            snapshot.generation = Math.max(snapshot.generation, checkpoint().getLong("generation", 0)) + 1;
            snapshot.updatedAt = System.currentTimeMillis();
            save(snapshot, callback);
        });
    }

    private void pruneJournal() {
        File[] files = journals();
        for (int i = 10; i < files.length; i++) files[i].delete();
    }
}
