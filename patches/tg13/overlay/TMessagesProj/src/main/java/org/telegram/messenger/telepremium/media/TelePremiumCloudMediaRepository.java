package org.telegram.messenger.telepremium.media;

import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelegramCloudMetadataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only media projection over the existing Cloud V2 metadata model. */
public final class TelePremiumCloudMediaRepository {
    public interface Callback { void onLoaded(List<TelePremiumMediaItem> items, String error); }

    public void load(int account, Callback callback) {
        new TelegramCloudMetadataStore(account).loadLatest((snapshot, error) -> {
            ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
            if (snapshot != null) {
                for (CloudNode node : snapshot.nodes) {
                    if (node == null || node.type != CloudNode.TYPE_FILE || node.state != CloudNode.STATE_ACTIVE || node.telegramMessageId == 0) continue;
                    String mime = node.mimeType;
                    if (!isSupportedMedia(node.name, mime)) continue;
                    TelePremiumMediaItem item = TelePremiumMediaItem.cloud(account, node.id, node.telegramMessageId, node.telegramDocumentId,
                            node.name, mime, 0, node.size);
                    item.favorite = node.favorite;
                    item.encryptionVersion=node.encryptionVersion;
                    item.sha256=node.sha256;
                    item.localBackupId=node.localBackupId;
                    out.add(item);
                }
            }
            callback.onLoaded(out, error);
        });
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
