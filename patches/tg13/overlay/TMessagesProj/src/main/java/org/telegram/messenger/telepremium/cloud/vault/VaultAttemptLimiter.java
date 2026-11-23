package org.telegram.messenger.telepremium.cloud.vault;

/**
 * Escalating delay between wrong folder secrets: free tries, then 30 s doubling up to 15 min.
 * State lives in an injected store so the policy runs on a plain JDK. This slows the UI only;
 * the offline cost of guessing is set by PBKDF2 and the binding to the master key.
 */
public final class VaultAttemptLimiter {
    public interface Store {
        long getLong(String key, long def);
        void putLong(String key, long value);
    }
    public interface Clock { long nowMs(); }

    public static final int FREE_TRIES = 5;
    public static final long BASE_DELAY_MS = 30_000L;
    public static final long MAX_DELAY_MS = 15 * 60_000L;

    private final Store store;
    private final Clock clock;

    public VaultAttemptLimiter(Store store, Clock clock) { this.store = store; this.clock = clock; }

    private static String failKey(String lockId) { return "fails_" + lockId; }
    private static String untilKey(String lockId) { return "until_" + lockId; }

    public long remainingMs(String lockId) {
        long until = store.getLong(untilKey(lockId), 0L);
        long left = until - clock.nowMs();
        // A clock set far back must not extend the lock beyond the maximum.
        return left <= 0 ? 0 : Math.min(left, MAX_DELAY_MS);
    }

    public int triesLeftBeforeDelay(String lockId) {
        return Math.max(0, FREE_TRIES - (int) store.getLong(failKey(lockId), 0L));
    }

    public void recordFailure(String lockId) {
        long fails = store.getLong(failKey(lockId), 0L) + 1;
        store.putLong(failKey(lockId), fails);
        if (fails >= FREE_TRIES) {
            int shift = (int) Math.min(fails - FREE_TRIES, 5);
            long delay = Math.min(MAX_DELAY_MS, BASE_DELAY_MS << shift);
            store.putLong(untilKey(lockId), clock.nowMs() + delay);
        }
    }

    public void recordSuccess(String lockId) {
        store.putLong(failKey(lockId), 0L);
        store.putLong(untilKey(lockId), 0L);
    }
}
