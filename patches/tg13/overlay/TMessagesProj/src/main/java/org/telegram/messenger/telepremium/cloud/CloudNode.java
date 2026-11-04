package org.telegram.messenger.telepremium.cloud;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class CloudNode {
    public static final int TYPE_FOLDER = 0;
    public static final int TYPE_FILE = 1;
    public static final int STATE_ACTIVE = 0;
    public static final int STATE_TRASHED = 1;
    /** Persistent tombstone. It prevents an older cloud generation from resurrecting a deleted node. */
    public static final int STATE_DELETED = 2;

    public String id;
    public String parentId;
    public int type;
    public String name;
    public long createdAt;
    public long modifiedAt;
    public long deletedAt;
    public int state;
    public boolean favorite;
    public String mimeType;
    public long size;
    public long telegramMessageId;
    public long telegramDocumentId;
    public String sha256;
    public String originalName;
    public int offlineState;
    /** Zero = legacy plaintext, one = TPVAULT1 authenticated binary attachment. */
    public int encryptionVersion;
    /** Reserved for independent backup replica references (not yet provisioned). */
    public long backupMessageId;
    public long backupDocumentId;
    /** Random local encrypted replica handle, never a filesystem path. */
    public String localBackupId;
    /**
     * Multipart payload: a file larger than the account's Telegram document limit is stored as
     * several consecutive byte ranges, one Telegram document each. Null for single-document files;
     * when set, {@link #telegramMessageId}/{@link #telegramDocumentId} mirror part 0.
     */
    public long[] partMessageIds;
    public long[] partDocumentIds;
    /** Plaintext byte length of each part (their sum equals {@link #size}). */
    public long[] partSizes;
    /** Plaintext SHA-256 of each part (required to authenticate Vault parts). */
    public String[] partSha256;

    public boolean isMultipart() { return partMessageIds != null && partMessageIds.length > 1; }

    public int partCount() { return partMessageIds == null ? (telegramMessageId != 0 ? 1 : 0) : partMessageIds.length; }

    public void allocateParts(int count) {
        partMessageIds = new long[count];
        partDocumentIds = new long[count];
        partSizes = new long[count];
        partSha256 = new String[count];
    }

    public void copyPartsFrom(CloudNode source) {
        if (source == null || source.partMessageIds == null) { partMessageIds = null; partDocumentIds = null; partSizes = null; partSha256 = null; return; }
        partMessageIds = source.partMessageIds.clone();
        partDocumentIds = source.partDocumentIds == null ? new long[partMessageIds.length] : source.partDocumentIds.clone();
        partSizes = source.partSizes == null ? new long[partMessageIds.length] : source.partSizes.clone();
        partSha256 = source.partSha256 == null ? new String[partMessageIds.length] : source.partSha256.clone();
    }

    public static CloudNode file(String parentId, String name, String mimeType, long size, String sha256) {
        CloudNode n = new CloudNode();
        n.id = UUID.randomUUID().toString(); n.parentId = parentId; n.type = TYPE_FILE; n.name = name;
        n.originalName = name; n.mimeType = mimeType; n.size = size; n.sha256 = sha256;
        n.createdAt = n.modifiedAt = System.currentTimeMillis();
        return n;
    }

    public static CloudNode folder(String parentId, String name) {
        CloudNode n = new CloudNode();
        n.id = UUID.randomUUID().toString();
        n.parentId = parentId;
        n.type = TYPE_FOLDER;
        n.name = name;
        n.createdAt = n.modifiedAt = System.currentTimeMillis();
        return n;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("parentId", parentId); o.put("type", type); o.put("name", name);
        o.put("createdAt", createdAt); o.put("modifiedAt", modifiedAt); o.put("deletedAt", deletedAt);
        o.put("state", state); o.put("favorite", favorite); o.put("mimeType", mimeType);
        o.put("size", size); o.put("telegramMessageId", telegramMessageId); o.put("telegramDocumentId", telegramDocumentId);
        o.put("sha256", sha256); o.put("originalName", originalName); o.put("offlineState", offlineState);
        o.put("encryptionVersion", encryptionVersion); o.put("backupMessageId", backupMessageId); o.put("backupDocumentId", backupDocumentId);
        o.put("localBackupId",localBackupId);
        if (partMessageIds != null) {
            JSONArray parts = new JSONArray();
            for (int i = 0; i < partMessageIds.length; i++) {
                JSONObject part = new JSONObject();
                part.put("m", partMessageIds[i]);
                part.put("d", partDocumentIds == null ? 0 : partDocumentIds[i]);
                part.put("s", partSizes == null ? 0 : partSizes[i]);
                if (partSha256 != null && partSha256[i] != null) part.put("h", partSha256[i]);
                parts.put(part);
            }
            o.put("parts", parts);
        }
        return o;
    }

    public static CloudNode fromJson(JSONObject o) {
        CloudNode n = new CloudNode();
        n.id=o.optString("id"); n.parentId=o.optString("parentId", null); n.type=o.optInt("type");
        n.name=o.optString("name"); n.createdAt=o.optLong("createdAt"); n.modifiedAt=o.optLong("modifiedAt");
        n.deletedAt=o.optLong("deletedAt"); n.state=o.optInt("state"); n.favorite=o.optBoolean("favorite");
        n.mimeType=o.optString("mimeType", null); n.size=o.optLong("size");
        n.telegramMessageId=o.optLong("telegramMessageId"); n.telegramDocumentId=o.optLong("telegramDocumentId");
        n.sha256=o.optString("sha256", null); n.originalName=o.optString("originalName", null); n.offlineState=o.optInt("offlineState");
        n.encryptionVersion=o.optInt("encryptionVersion", 0); n.backupMessageId=o.optLong("backupMessageId"); n.backupDocumentId=o.optLong("backupDocumentId");
        n.localBackupId=o.optString("localBackupId",null);
        JSONArray parts = o.optJSONArray("parts");
        if (parts != null && parts.length() > 0) {
            n.allocateParts(parts.length());
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                n.partMessageIds[i] = part.optLong("m");
                n.partDocumentIds[i] = part.optLong("d");
                n.partSizes[i] = part.optLong("s");
                n.partSha256[i] = part.has("h") ? part.optString("h", null) : null;
            }
        }
        return n;
    }
}

