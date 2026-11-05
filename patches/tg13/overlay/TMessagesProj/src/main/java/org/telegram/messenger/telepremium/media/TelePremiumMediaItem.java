package org.telegram.messenger.telepremium.media;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Unified media descriptor used by device, Cloud and network playback. */
public final class TelePremiumMediaItem {
    public static final int SOURCE_DEVICE = 0;
    public static final int SOURCE_CLOUD = 1;
    public static final int SOURCE_NETWORK = 2;

    public static final int TYPE_AUDIO = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_STREAM = 2;

    public String id = UUID.randomUUID().toString();
    public int sourceType;
    public int mediaType;
    public String title;
    public String artist;
    public String album;
    public String mimeType;
    public String uri;
    public String artworkUri;
    public String cloudNodeId;
    public int cloudAccount = -1;
    public long telegramMessageId;
    public long telegramDocumentId;
    public long duration;
    public long size;
    public int encryptionVersion;
    public String sha256;
    public String localBackupId;
    /** Device folder relative to the storage root ("Music", "Movies/Clips"); not persisted. */
    public transient String folder;
    /** Multipart Cloud payload (see CloudNode#partMessageIds); null for single-document files. */
    public long[] partMessageIds;
    public long[] partDocumentIds;
    public long[] partSizes;
    public String[] partSha256;

    public boolean isMultipart() { return partMessageIds != null && partMessageIds.length > 1; }

    public void setParts(org.telegram.messenger.telepremium.cloud.CloudNode node) {
        if (node == null || !node.isMultipart()) { partMessageIds = null; partDocumentIds = null; partSizes = null; partSha256 = null; return; }
        partMessageIds = node.partMessageIds.clone();
        partDocumentIds = node.partDocumentIds.clone();
        partSizes = node.partSizes.clone();
        partSha256 = node.partSha256.clone();
    }

    public void copyPartsTo(org.telegram.messenger.telepremium.cloud.CloudNode node) {
        if (node == null || !isMultipart()) return;
        node.allocateParts(partMessageIds.length);
        for (int i = 0; i < partMessageIds.length; i++) {
            node.partMessageIds[i] = partMessageIds[i];
            node.partDocumentIds[i] = partDocumentIds == null ? 0 : partDocumentIds[i];
            node.partSizes[i] = partSizes == null ? 0 : partSizes[i];
            node.partSha256[i] = partSha256 == null ? null : partSha256[i];
        }
    }
    public boolean live;
    public boolean favorite;
    public long resumePosition;

    public static TelePremiumMediaItem device(String uri, String title, String mime, long duration, long size) {
        TelePremiumMediaItem item = new TelePremiumMediaItem();
        item.sourceType = SOURCE_DEVICE;
        item.uri = uri;
        item.id = stableId("device", uri);
        item.title = TextUtils.isEmpty(title) ? "Медиа" : title;
        item.mimeType = mime;
        item.duration = duration;
        item.size = size;
        item.mediaType = inferType(title, mime, false);
        return item;
    }

    public static TelePremiumMediaItem network(String uri, String title, String mime, boolean live) {
        TelePremiumMediaItem item = new TelePremiumMediaItem();
        item.sourceType = SOURCE_NETWORK;
        item.uri = uri;
        item.id = stableId("network", uri);
        item.title = TextUtils.isEmpty(title) ? uri : title;
        item.mimeType = mime;
        item.live = live;
        item.mediaType = inferType(title == null ? uri : title, mime, live);
        return item;
    }

    public static TelePremiumMediaItem cloud(int account, String nodeId, long messageId, long documentId,
                                             String title, String mime, long duration, long size) {
        TelePremiumMediaItem item = new TelePremiumMediaItem();
        item.sourceType = SOURCE_CLOUD;
        item.cloudAccount = account;
        item.cloudNodeId = nodeId;
        item.telegramMessageId = messageId;
        item.telegramDocumentId = documentId;
        String cloudKey = !TextUtils.isEmpty(nodeId) ? account + ":node:" + nodeId : account + ":message:" + messageId;
        item.id = stableId("cloud", cloudKey);
        item.title = TextUtils.isEmpty(title) ? "Cloud media" : title;
        item.mimeType = mime;
        item.duration = duration;
        item.size = size;
        item.mediaType = inferType(title, mime, false);
        return item;
    }

    public boolean isVideo() { return mediaType == TYPE_VIDEO; }
    public boolean isAudio() { return mediaType == TYPE_AUDIO || mediaType == TYPE_STREAM; }

    public String sourceLabel() {
        switch (sourceType) {
            case SOURCE_CLOUD: return "TelePremium Cloud";
            case SOURCE_NETWORK: return live ? "Онлайн-поток" : "Сеть";
            default: return "Устройство";
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("sourceType", sourceType).put("mediaType", mediaType)
                .put("title", title).put("artist", artist).put("album", album).put("mimeType", mimeType)
                .put("uri", uri).put("artworkUri", artworkUri).put("cloudNodeId", cloudNodeId)
                .put("cloudAccount", cloudAccount).put("telegramMessageId", telegramMessageId)
                .put("telegramDocumentId", telegramDocumentId).put("duration", duration).put("size", size)
                .put("live", live).put("favorite", favorite).put("resumePosition", resumePosition)
                .put("encryptionVersion",encryptionVersion).put("sha256",sha256).put("localBackupId",localBackupId);
        if (isMultipart()) {
            org.telegram.messenger.telepremium.cloud.CloudNode tmp = new org.telegram.messenger.telepremium.cloud.CloudNode();
            copyPartsTo(tmp);
            o.put("parts", tmp.toJson().optJSONArray("parts"));
        }
        return o;
    }

    public static TelePremiumMediaItem fromJson(JSONObject o) {
        TelePremiumMediaItem item = new TelePremiumMediaItem();
        item.sourceType = o.optInt("sourceType", SOURCE_DEVICE);
        item.mediaType = o.optInt("mediaType", TYPE_AUDIO);
        item.title = o.optString("title", "Медиа");
        item.artist = nullable(o.optString("artist", null));
        item.album = nullable(o.optString("album", null));
        item.mimeType = nullable(o.optString("mimeType", null));
        item.uri = nullable(o.optString("uri", null));
        item.artworkUri = nullable(o.optString("artworkUri", null));
        item.cloudNodeId = nullable(o.optString("cloudNodeId", null));
        item.cloudAccount = o.optInt("cloudAccount", -1);
        item.telegramMessageId = o.optLong("telegramMessageId");
        item.telegramDocumentId = o.optLong("telegramDocumentId");
        item.duration = o.optLong("duration");
        item.size = o.optLong("size");
        item.encryptionVersion = o.optInt("encryptionVersion",0);
        item.sha256=nullable(o.optString("sha256",null));
        item.localBackupId=nullable(o.optString("localBackupId",null));
        if (o.optJSONArray("parts") != null) {
            try {
                JSONObject wrapper = new JSONObject().put("parts", o.optJSONArray("parts"));
                item.setParts(org.telegram.messenger.telepremium.cloud.CloudNode.fromJson(wrapper));
            } catch (JSONException ignore) {
            }
        }
        item.live = o.optBoolean("live");
        item.favorite = o.optBoolean("favorite");
        item.resumePosition = o.optLong("resumePosition");
        String storedId = nullable(o.optString("id", null));
        item.id = storedId == null ? stableIdFor(item) : storedId;
        return item;
    }

    public static int inferType(String name, String mime, boolean live) {
        if (live) return TYPE_STREAM;
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("video/")) return TYPE_VIDEO;
        if (m.startsWith("audio/")) return TYPE_AUDIO;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov")
                || n.endsWith(".avi") || n.endsWith(".m4v") || n.endsWith(".3gp") || n.endsWith(".ts")
                || n.endsWith(".m2ts")) return TYPE_VIDEO;
        return TYPE_AUDIO;
    }

    private static String stableIdFor(TelePremiumMediaItem item) {
        if (item.sourceType == SOURCE_CLOUD) {
            String key = !TextUtils.isEmpty(item.cloudNodeId)
                    ? item.cloudAccount + ":node:" + item.cloudNodeId
                    : item.cloudAccount + ":message:" + item.telegramMessageId;
            return stableId("cloud", key);
        }
        if (item.sourceType == SOURCE_NETWORK) return stableId("network", item.uri);
        return stableId("device", item.uri);
    }

    private static String stableId(String prefix, String value) {
        String raw = prefix + ":" + (value == null ? "" : value);
        return prefix + ":" + UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullable(String value) { return TextUtils.isEmpty(value) || "null".equals(value) ? null : value; }
}
