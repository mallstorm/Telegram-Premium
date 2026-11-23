package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.os.SystemClock;

public final class TelePremiumCloudSecuritySession {
    private static volatile long unlockedUntilElapsed;

    private TelePremiumCloudSecuritySession() {}

    public static boolean isUnlocked(Context context) {
        if (!TelePremiumCloudSecurityStore.isProtectionEnabled(context)) return true;
        return SystemClock.elapsedRealtime() < unlockedUntilElapsed;
    }

    public static void unlock(Context context) {
        long duration = Math.max(15, TelePremiumCloudSecurityStore.getAutoLockSeconds(context)) * 1000L;
        unlockedUntilElapsed = SystemClock.elapsedRealtime() + duration;
    }

    public static void touch(Context context) {
        if (isUnlocked(context)) unlock(context);
    }

    public static void lockNow() {
        unlockedUntilElapsed = 0L;
        // Locking the cloud closes every protected folder as well.
        org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession.shared().lockAll();
    }
}
