package org.telegram.messenger.telepremium.cloud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;

import java.io.File;

/**
 * Keeps unlocked folders short-lived: sweeps expired sessions, locks everything when the screen turns
 * off or the app stays in the background, and removes decrypted copies of protected files.
 */
public final class VaultSessionGuard {
    private VaultSessionGuard() {}

    /** Plaintext of files from locked folders is decrypted only here and wiped when any lock closes. */
    public static final String LOCKED_CACHE_DIR = "tpvault_dec_locked";
    private static final long BACKGROUND_GRACE_MS = 45_000L;
    private static final long TICK_MS = 3_000L;

    private static boolean installed;
    private static long pausedSince;
    private static int lastOpen;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** Idempotent; called by every entry point that can unlock a folder. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        final Context app = ApplicationLoader.applicationContext;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { lockEverything(); }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        VaultFolderSession.shared().addListener(() -> {
            int open = VaultFolderSession.shared().unlockedIds().size();
            // unlockedIds() may re-enter listeners during sweep; compare after the call only.
            if (open < lastOpen) wipeDecrypted();
            lastOpen = open;
        });
        HANDLER.postDelayed(new Runnable() {
            @Override public void run() {
                try { tick(); } finally { HANDLER.postDelayed(this, TICK_MS); }
            }
        }, TICK_MS);
    }

    private static void tick() {
        VaultFolderSession session = VaultFolderSession.shared();
        if (session.unlockedIds().isEmpty()) { pausedSince = 0; return; } // also sweeps expired entries
        if (ApplicationLoader.mainInterfacePaused) {
            long now = SystemClock.elapsedRealtime();
            if (pausedSince == 0) pausedSince = now;
            else if (now - pausedSince > BACKGROUND_GRACE_MS && !playingProtectedMedia()) lockEverything();
        } else pausedSince = 0;
    }

    private static boolean playingProtectedMedia() {
        try { return org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.getInstance().isPlayingProtected(); }
        catch (Throwable t) { return false; }
    }

    public static void lockEverything() {
        VaultFolderSession.shared().lockAll();
        wipeDecrypted();
    }

    public static void wipeDecrypted() {
        CloudPhotoThumbnailLoader.clearCache();
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), LOCKED_CACHE_DIR);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
