package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.webkit.MimeTypeMap;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudFormat;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Media projection of the same authenticated V3 catalog used by the Cloud screen. */
public final class TelePremiumCloudMediaRepository {
    public interface Callback { void onLoaded(List<TelePremiumMediaItem> items, String error); }

    public static List<Integer> accounts() {
        ArrayList<Integer> result = new ArrayList<>(); Set<Long> users = new HashSet<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() > 0 && users.add(config.getClientUserId())) result.add(a);
        }
        return result;
    }
    public static String accountName(int account) {
        org.telegram.tgnet.TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        String name = user == null ? "" : ((user.first_name == null ? "" : user.first_name) + " " + (user.last_name == null ? "" : user.last_name)).trim();
        return name.isEmpty() ? Long.toString(UserConfig.getInstance(account).getClientUserId()) : name;
    }
    /** account == -1 means all connected Telegram users. No account is silently selected. */
    public void load(int account, Callback callback) {
        final long epoch = TelePremiumCloudResetState.epoch();
        Context context = ApplicationLoader.applicationContext;
        if (!TelePremiumCloudResetState.allowed(epoch)) { callback.onLoaded(new ArrayList<>(), TelePremiumCloudResetState.MESSAGE); return; }
        if (!TelePremiumCloudSecuritySession.isUnlocked(context)) { callback.onLoaded(new ArrayList<>(), "Разблокируйте облако для просмотра медиатеки"); return; }
        List<Integer> active = accounts();
        if (account >= 0) active.removeIf(a -> a != account);
        if (active.isEmpty()) { callback.onLoaded(new ArrayList<>(), "Нет подключённого Telegram-аккаунта"); return; }
        final int[] left = {active.size()};
        final ArrayList<TelePremiumMediaItem> items = new ArrayList<>();
        final ArrayList<String> errors = new ArrayList<>();
        for (int a : active) {
            final long user = UserConfig.getInstance(a).getClientUserId();
            final TelePremiumCloudManager manager = TelePremiumCloudManager.getInstance(a);
            manager.bootstrap(error -> {
                if (TelePremiumCloudResetState.allowed(epoch) && TelePremiumCloudSecuritySession.isUnlocked(context)
                        && UserConfig.getInstance(a).getClientUserId() == user) {
                    CloudSnapshot snapshot = manager.getSnapshot();
                    if (snapshot != null && snapshot.ownerUserId == user) items.addAll(project(a, user, epoch, snapshot));
                    if (error != null) errors.add(accountName(a) + ": " + error);
                } else errors.add("Доступ к облаку изменился; обновите медиатеку");
                if (--left[0] == 0) {
                    if (!TelePremiumCloudResetState.allowed(epoch) || !TelePremiumCloudSecuritySession.isUnlocked(context)) {
                        callback.onLoaded(new ArrayList<>(), "Разблокируйте облако и обновите медиатеку"); return;
                    }
                    int previousCount = items.size();
                    items.removeIf(item -> !UserConfig.getInstance(item.cloudAccount).isClientActivated()
                            || UserConfig.getInstance(item.cloudAccount).getClientUserId() != item.cloudUserId);
                    if (items.size() != previousCount) errors.add("Состав аккаунтов изменился; обновите медиатеку");
                    callback.onLoaded(items, errors.isEmpty() ? null : String.join("\n", errors));
                }
            });
        }
    }
    static List<TelePremiumMediaItem> project(int account, long user, long epoch, CloudSnapshot snapshot) {
        ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
        if (snapshot == null || snapshot.ownerUserId != user) return out;
        for (CloudNode node : snapshot.nodes) {
            if (node == null || node.type != CloudNode.TYPE_FILE || node.state != CloudNode.STATE_ACTIVE
                    || node.encryptionVersion != 1 || node.telegramMessageId <= 0 || node.telegramDocumentId == 0) continue;
            String original = node.originalName == null ? node.name : node.originalName;
            String mime = TelePremiumCloudFormat.resolveMime(node.name, node.mimeType, ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
            if (!isSupportedMedia(node.name, mime)) mime = TelePremiumCloudFormat.resolveMime(original, node.mimeType, ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
            if (!isSupportedMedia(node.name, mime) && !isSupportedMedia(original, mime)) continue;
            TelePremiumMediaItem item = TelePremiumMediaItem.cloud(account, node.id, node.telegramMessageId,
                    node.telegramDocumentId, node.name, mime, 0, node.size);
            item.cloudUserId = user; item.cloudEpoch = epoch;
            item.artist = accountName(account);
            item.favorite = node.favorite; item.encryptionVersion = node.encryptionVersion;
            item.sha256 = node.sha256; item.localBackupId = node.localBackupId;
            item.setParts(node); out.add(item);
        }
        return out;
    }
    private static boolean isSupportedMedia(String name, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("audio/") || m.startsWith("video/") || m.contains("mpegurl") || m.contains("dash")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".amr")
                || n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mkv") || n.endsWith(".webm")
                || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".3gp") || n.endsWith(".ts")
                || n.endsWith(".m2ts") || n.endsWith(".m3u8") || n.endsWith(".mpd");
    }
}
