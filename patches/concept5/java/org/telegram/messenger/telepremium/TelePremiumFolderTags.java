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

    /**
     * Resolve a locally selected colour first and fall back to Telegram's
     * server-provided colour when no TelePremium override exists.
     */
    public static int getColor(int account, int filterId, int fallback) {
        if (filterId <= 0) {
            return fallback;
        }
        return prefs(account).getInt(COLOR_PREFIX + filterId, fallback);
    }

    public static void setColor(int account, int filterId, int color) {
        if (filterId <= 0) {
            return;
        }
        SharedPreferences.Editor editor = prefs(account).edit();
        if (color < 0) {
            editor.remove(COLOR_PREFIX + filterId);
        } else {
            editor.putInt(COLOR_PREFIX + filterId, color);
        }
        editor.apply();
    }
}
