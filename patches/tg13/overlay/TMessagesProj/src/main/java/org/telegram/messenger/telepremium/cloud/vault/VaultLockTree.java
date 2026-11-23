package org.telegram.messenger.telepremium.cloud.vault;

import org.telegram.messenger.telepremium.cloud.CloudNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure catalog logic: which lock protects a node, and which payloads still need re-keying. */
public final class VaultLockTree {
    private VaultLockTree() {}

    private static Map<String, CloudNode> index(List<CloudNode> nodes) {
        Map<String, CloudNode> byId = new HashMap<>();
        for (CloudNode n : nodes) if (n.id != null) byId.put(n.id, n);
        return byId;
    }

    /**
     * Nearest folder (starting at {@code folderId} and walking up) that currently holds a lock.
     * A folder whose protection is being removed ({@code lockOff}) does not count for NEW content,
     * but is still found by {@link #lockHolder} so existing payloads remain readable.
     */
    public static CloudNode activeLockRoot(List<CloudNode> nodes, String folderId) {
        return walk(index(nodes), folderId, false);
    }

    /** Like {@link #activeLockRoot} but also returns folders in the middle of removing their lock. */
    public static CloudNode lockHolder(List<CloudNode> nodes, String folderId) {
        return walk(index(nodes), folderId, true);
    }

    /** The lock record that owns {@code lockId}, regardless of removal state. */
    public static CloudNode lockFolder(List<CloudNode> nodes, String lockId) {
        if (lockId == null) return null;
        for (CloudNode n : nodes) if (lockId.equals(n.id) && n.type == CloudNode.TYPE_FOLDER && n.lock != null) return n;
        return null;
    }

    private static CloudNode walk(Map<String, CloudNode> byId, String folderId, boolean includeRemoving) {
        Set<String> seen = new HashSet<>();
        String cur = folderId;
        while (cur != null && seen.add(cur)) {
            CloudNode n = byId.get(cur);
            if (n == null) return null;
            if (n.type == CloudNode.TYPE_FOLDER && n.lock != null && n.state != CloudNode.STATE_DELETED
                    && (includeRemoving || !n.lockOff)) return n;
            cur = n.parentId;
        }
        return null;
    }

    /** Lock id that SHOULD encrypt a file placed in {@code parentId}; null means the master key. */
    public static String desiredKeyRef(List<CloudNode> nodes, String parentId) {
        CloudNode root = activeLockRoot(nodes, parentId);
        return root == null ? null : root.id;
    }

    /** True when the folder, or any ancestor, is protected (including while protection is being removed). */
    public static boolean isProtected(List<CloudNode> nodes, String folderId) {
        return lockHolder(nodes, folderId) != null;
    }

    /** Payload files whose current key differs from the key their folder now requires. */
    public static List<CloudNode> filesNeedingRekey(List<CloudNode> nodes) {
        Map<String, CloudNode> byId = index(nodes);
        List<CloudNode> out = new ArrayList<>();
        for (CloudNode n : nodes) {
            if (n.type != CloudNode.TYPE_FILE || n.state == CloudNode.STATE_DELETED) continue;
            if (n.encryptionVersion != 1 || n.telegramMessageId == 0) continue;
            CloudNode root = walk(byId, n.parentId, false);
            String desired = root == null ? null : root.id;
            if (!equal(desired, n.keyRef)) out.add(n);
        }
        return out;
    }

    /** Locks that carry {@code lockOff} and no longer protect any payload: their record can be dropped. */
    public static List<CloudNode> finishedUnlocks(List<CloudNode> nodes) {
        Set<String> used = new HashSet<>();
        for (CloudNode n : nodes) {
            if (n.type == CloudNode.TYPE_FILE && n.state != CloudNode.STATE_DELETED && n.keyRef != null) used.add(n.keyRef);
        }
        List<CloudNode> out = new ArrayList<>();
        for (CloudNode n : nodes) {
            if (n.type == CloudNode.TYPE_FOLDER && n.lock != null && n.lockOff && !used.contains(n.id)) out.add(n);
        }
        return out;
    }

    /** All lock roots (folders that own a lock), for settings screens. */
    public static List<CloudNode> lockRoots(List<CloudNode> nodes) {
        List<CloudNode> out = new ArrayList<>();
        for (CloudNode n : nodes) {
            if (n.type == CloudNode.TYPE_FOLDER && n.lock != null && !n.lockOff && n.state != CloudNode.STATE_DELETED) out.add(n);
        }
        return out;
    }

    /** Files (not folders) below {@code folderId}, any depth, excluding deleted tombstones. */
    public static List<CloudNode> filesBelow(List<CloudNode> nodes, String folderId) {
        Map<String, List<CloudNode>> children = new HashMap<>();
        for (CloudNode n : nodes) {
            if (n.state == CloudNode.STATE_DELETED) continue;
            children.computeIfAbsent(n.parentId, k -> new ArrayList<>()).add(n);
        }
        List<CloudNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(children, folderId, seen, out);
        return out;
    }

    private static void collect(Map<String, List<CloudNode>> children, String folderId, Set<String> seen, List<CloudNode> out) {
        if (!seen.add(folderId)) return;
        List<CloudNode> list = children.get(folderId);
        if (list == null) return;
        for (CloudNode n : list) {
            if (n.type == CloudNode.TYPE_FILE) out.add(n);
            else collect(children, n.id, seen, out);
        }
    }

    private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
}
