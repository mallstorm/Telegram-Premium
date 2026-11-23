package org.telegram.messenger.telepremium.cloud.vault;

import org.telegram.messenger.telepremium.cloud.CloudNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for "may this entry be shown / played / counted right now".
 *
 * A node is hidden while any folder above it holds a lock that is not unlocked in the session,
 * whatever the file type (images, programs, archives, video, audio, ...). The locked folder itself
 * stays visible (with a lock icon) unless it lies inside another locked folder.
 * A payload that is still keyed to a lock (keyRef) is hidden as well until that lock is open:
 * it could not be decrypted anyway, and this closes the window between "move out" and re-key.
 *
 * Every consumer (catalog lists, categories, search, favorites, trash, counters, media library,
 * player queue, gallery) must go through this class; none may inspect locks on its own.
 */
public final class VaultVisibility {
    private VaultVisibility() {}

    /** Snapshot of the lock state, built once per query. */
    public static final class View {
        private final Map<String, CloudNode> byId = new HashMap<>();
        private final VaultFolderSession session;
        private final Map<String, Boolean> hiddenContents = new HashMap<>();
        private final boolean anyLock;

        public View(List<CloudNode> nodes, VaultFolderSession session) {
            this.session = session;
            boolean any = false;
            if (nodes != null) for (CloudNode n : nodes) {
                if (n == null || n.id == null) continue;
                byId.put(n.id, n);
                if (n.lock != null && n.state != CloudNode.STATE_DELETED) any = true;
                if (n.keyRef != null) any = true;
            }
            anyLock = any;
        }

        /** True when the folder, or an ancestor, holds a lock that is currently closed. */
        public boolean contentsHidden(String folderId) {
            if (!anyLock || folderId == null) return false;
            Boolean memo = hiddenContents.get(folderId);
            if (memo != null) return memo;
            boolean hidden = false;
            Set<String> seen = new HashSet<>();
            List<String> path = new ArrayList<>();
            String cur = folderId;
            while (cur != null && seen.add(cur)) {
                Boolean known = hiddenContents.get(cur);
                if (known != null) { hidden = known; break; }
                path.add(cur);
                CloudNode f = byId.get(cur);
                if (f == null) break;
                if (f.type == CloudNode.TYPE_FOLDER && f.lock != null && f.state != CloudNode.STATE_DELETED
                        && !session.isUnlocked(f.id)) { hidden = true; break; }
                cur = f.parentId;
            }
            hiddenContents.put(folderId, hidden);
            return hidden;
        }

        /** May this entry appear in lists, counts, search, the media library or a player queue? */
        public boolean visible(CloudNode n) {
            if (n == null) return false;
            if (!anyLock) return true;
            if (contentsHidden(n.parentId)) return false;
            if (n.type == CloudNode.TYPE_FILE && n.keyRef != null && !session.isUnlocked(n.keyRef)) return false;
            return true;
        }

        /**
         * Data that belongs to a lock domain, whether or not the lock is open right now:
         * under a lock-holding folder (including one being unlocked for good) or still keyed to one.
         * The media player and its library treat such data as non-existent, with no unlock prompt.
         */
        public boolean isProtected(CloudNode n) {
            if (n == null) return false;
            if (n.type == CloudNode.TYPE_FILE && n.keyRef != null) return true;
            if (!anyLock) return false;
            Set<String> seen = new HashSet<>();
            String cur = n.parentId;
            while (cur != null && seen.add(cur)) {
                CloudNode f = byId.get(cur);
                if (f == null) return false;
                if (f.type == CloudNode.TYPE_FOLDER && f.lock != null && f.state != CloudNode.STATE_DELETED) return true;
                cur = f.parentId;
            }
            return false;
        }

        /** A folder that owns a lock which is closed now (render with a lock; open only via unlock). */
        public boolean isClosedLock(CloudNode n) {
            return n != null && n.type == CloudNode.TYPE_FOLDER && n.lock != null
                    && n.state != CloudNode.STATE_DELETED && !session.isUnlocked(n.id);
        }

        public <T extends CloudNode> List<T> filter(List<T> in) {
            if (!anyLock) return in;
            List<T> out = new ArrayList<>(in.size());
            for (T n : in) if (visible(n)) out.add(n);
            return out;
        }
    }

    public static View view(List<CloudNode> nodes) { return new View(nodes, VaultFolderSession.shared()); }
}
