package org.telegram.messenger.telepremium.cloud.vault;

import org.telegram.messenger.telepremium.cloud.CloudNode;

import java.util.List;

/**
 * Chooses the AES key for a payload: the installation master key or the key of the lock folder
 * that protects it. Pure logic (the master key source is injected) so it runs on a plain JDK.
 * Callers own the returned bytes and must zero them after use.
 */
public final class VaultKeyResolver {
    private VaultKeyResolver() {}

    public interface MasterSource { byte[] get() throws Exception; }

    /** The needed lock is closed. UI reacts by showing the unlock screen for {@link #lockId}. */
    public static final class LockedException extends Exception {
        public final String lockId;
        public LockedException(String lockId) { super("Folder is locked"); this.lockId = lockId; }
    }

    public static final class Key {
        public final byte[] bytes;
        /** Lock-folder id to store in CloudNode.keyRef, or null for the master key. */
        public final String keyRef;
        Key(byte[] bytes, String keyRef) { this.bytes = bytes; this.keyRef = keyRef; }
        public void wipe() { java.util.Arrays.fill(bytes, (byte) 0); }
        public String keyRefAndWipe() { wipe(); return keyRef; }
    }

    /** Key that decrypts an existing payload whose node carries {@code keyRef}. */
    public static Key forPayload(String keyRef, MasterSource master, VaultFolderSession session) throws Exception {
        if (keyRef == null) return new Key(master.get(), null);
        byte[] folderKey = session.get(keyRef);
        if (folderKey == null) throw new LockedException(keyRef);
        return new Key(folderKey, keyRef);
    }

    public static Key forNode(CloudNode node, MasterSource master, VaultFolderSession session) throws Exception {
        return forPayload(node == null ? null : node.keyRef, master, session);
    }

    /** Key for NEW content placed in {@code parentId}: the nearest active lock above it, else master. */
    public static Key forUpload(List<CloudNode> nodes, String parentId, MasterSource master,
                                VaultFolderSession session) throws Exception {
        return forPayload(VaultLockTree.desiredKeyRef(nodes, parentId), master, session);
    }
}
