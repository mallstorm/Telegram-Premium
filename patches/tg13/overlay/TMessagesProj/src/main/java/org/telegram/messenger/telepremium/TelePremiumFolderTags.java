package org.telegram.messenger.telepremium;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Local TelePremium override for Telegram's Premium-only folder tags.
 *
 * The server-side Premium state is never spoofed. The switch and per-folder
 * colours live only in this app, so Telegram can keep rejecting Premium-only
 * RPCs while the local UI still renders coloured folders and chat tags.
 */
public final class TelePremiumFolderTags {
    private static final String PREFS_PREFIX = "telepremium_folder_tags_";
    private static final String KEY_ENABLED = "enabled";
    private static final String COLOR_PREFIX = "color_";

    private TelePremiumFolderTags() {
    }

    private static SharedPreferences prefs(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            throw new IllegalStateException("Application context is not ready");
        }
        return context.getSharedPreferences(PREFS_PREFIX + account, Context.MODE_PRIVATE);
    }

    /** Enabled by default in TelePremium; the user can turn it off locally. */
    public static boolean isEnabled(int account) {
        return prefs(account).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(int account, boolean enabled) {
        prefs(account).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static final int AUTO_PALETTE = 7;

    /**
     * Tag color of a folder: the locally chosen one, else the server one, else an automatic palette
     * color, so every chat that sits in a custom folder carries that folder's name label (like the
     * classic chat list). A stored negative value means "no tag" for that folder.
     */
    public static int getColor(int account, int filterId, int fallback) {
        if (filterId <= 0 || filterId == Integer.MAX_VALUE) {
            return fallback;
        }
        SharedPreferences prefs = prefs(account);
        String key = COLOR_PREFIX + filterId;
        if (prefs.contains(key)) {
            return prefs.getInt(key, fallback);
        }
        if (fallback >= 0) {
            return fallback;
        }
        return filterId % AUTO_PALETTE;
    }

    public static void setColor(int account, int filterId, int color) {
        if (filterId <= 0) {
            return;
        }
        prefs(account).edit().putInt(COLOR_PREFIX + filterId, color < 0 ? -1 : color).apply();
    }
}
