package org.telegram.messenger.telepremium.cloud.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * In-memory unlocked folder keys. A key lives only here, only for its own timeout, and is zeroed
 * on lock, expiry, screen-off and when the app leaves the foreground (see {@code VaultSessionGuard}).
 * Each folder has an independent timer: opening "Photos" never unlocks "Documents".
 */
public final class VaultFolderSession {
    public interface Clock { long nowMs(); }
    public interface Listener { void onLockStateChanged(); }

    private static final Clock DEFAULT_CLOCK = () -> System.nanoTime() / 1_000_000L;
    private static final VaultFolderSession SHARED = new VaultFolderSession(DEFAULT_CLOCK);

    private static final class Entry {
        byte[] key;
        long expiresAt;
        long ttl;
    }

    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private long maxTtlMs = 5 * 60_000L;

    public VaultFolderSession(Clock clock) { this.clock = clock; }

    public static VaultFolderSession shared() { return SHARED; }

    public synchronized void put(String lockId, byte[] key, long ttlMs) {
        if (lockId == null || key == null || key.length != VaultFolderLock.KEY_BYTES) throw new IllegalArgumentException("key");
        long ttl = Math.max(5_000L, Math.min(ttlMs, maxTtlMs));
        Entry old = entries.remove(lockId);
        if (old != null) Arrays.fill(old.key, (byte) 0);
        Entry e = new Entry();
        e.key = key.clone();
        e.ttl = ttl;
        e.expiresAt = clock.nowMs() + ttl;
        entries.put(lockId, e);
        notifyChanged();
    }

    /** Returns a private copy (the caller zeroes it) or null when locked or expired. */
    public synchronized byte[] get(String lockId) {
        Entry e = live(lockId);
        return e == null ? null : e.key.clone();
    }

    public synchronized boolean isUnlocked(String lockId) { return live(lockId) != null; }

    /** Sliding window: every use of the folder extends its own timer. */
    public synchronized void touch(String lockId) {
        Entry e = live(lockId);
        if (e != null) e.expiresAt = clock.nowMs() + e.ttl;
    }

    public synchronized long remainingMs(String lockId) {
        Entry e = live(lockId);
        return e == null ? 0L : Math.max(0L, e.expiresAt - clock.nowMs());
    }

    public synchronized void lock(String lockId) {
        Entry e = entries.remove(lockId);
        if (e != null) { Arrays.fill(e.key, (byte) 0); notifyChanged(); }
    }

    public synchronized void lockAll() {
        if (entries.isEmpty()) return;
        for (Entry e : entries.values()) Arrays.fill(e.key, (byte) 0);
        entries.clear();
        notifyChanged();
    }

    public synchronized List<String> unlockedIds() {
        sweep();
        return new ArrayList<>(entries.keySet());
    }

    public synchronized void setMaxTtlMs(long ms) { maxTtlMs = Math.max(15_000L, ms); }

    public synchronized void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public synchronized void removeListener(Listener l) { listeners.remove(l); }

    private Entry live(String lockId) {
        Entry e = entries.get(lockId);
        if (e == null) return null;
        if (clock.nowMs() >= e.expiresAt) {
            entries.remove(lockId);
            Arrays.fill(e.key, (byte) 0);
            notifyChanged();
            return null;
        }
        return e;
    }

    private void sweep() {
        long now = clock.nowMs();
        boolean changed = false;
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            if (now >= e.expiresAt) { Arrays.fill(e.key, (byte) 0); it.remove(); changed = true; }
        }
        if (changed) notifyChanged();
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) {
            try { l.onLockStateChanged(); } catch (RuntimeException ignored) { }
        }
    }
}
