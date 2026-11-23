package org.telegram.messenger.telepremium.theme;

import android.content.Context;
import android.content.SharedPreferences;

public final class TelePremiumThemeStore {
    private static final String PREFS = "telepremium_theme_v2";
    private static final String KEY_THEME = "theme_id";
    private static final String KEY_WALLPAPER = "wallpaper_override";
    private static final String KEY_ROUNDNESS = "bubble_roundness";
    private static final String KEY_ACCENT = "accent_override";
    private static final String KEY_CHAT_BG_MODE = "chat_background_mode";
    private static final String KEY_CHAT_ACCENT_MODE = "chat_accent_mode";
    private static final String KEY_SHELL_ACCENT_MODE = "shell_accent_mode";

    public static final int MODE_THEME = 0;
    public static final int MODE_SOFT = 1;
    public static final int MODE_STRONG = 2;
    public static final int CHAT_BG_THEME = 0;
    public static final int CHAT_BG_CLEAN = 1;
    public static final int CHAT_BG_ARTWORK = 2;

    private TelePremiumThemeStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasTheme(Context context) {
        return prefs(context).contains(KEY_THEME);
    }

    public static String getThemeId(Context context, String fallback) {
        return prefs(context).getString(KEY_THEME, fallback);
    }

    public static void setThemeId(Context context, String id) {
        prefs(context).edit().putString(KEY_THEME, id).apply();
    }

    public static String getWallpaperOverride(Context context) {
        return prefs(context).getString(KEY_WALLPAPER, null);
    }

    public static void setWallpaperOverride(Context context, String value) {
        prefs(context).edit().putString(KEY_WALLPAPER, value).apply();
    }

    public static int getBubbleRoundness(Context context) {
        return prefs(context).getInt(KEY_ROUNDNESS, 18);
    }

    public static void setBubbleRoundness(Context context, int value) {
        prefs(context).edit().putInt(KEY_ROUNDNESS, Math.max(8, Math.min(28, value))).apply();
    }

    public static int getAccentOverride(Context context) {
        return prefs(context).getInt(KEY_ACCENT, 0);
    }

    public static void setAccentOverride(Context context, int color) {
        prefs(context).edit().putInt(KEY_ACCENT, color).apply();
    }

    public static int getChatBackgroundMode(Context context) {
        return prefs(context).getInt(KEY_CHAT_BG_MODE, CHAT_BG_ARTWORK);
    }

    public static void setChatBackgroundMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_CHAT_BG_MODE, Math.max(CHAT_BG_THEME, Math.min(CHAT_BG_ARTWORK, mode))).apply();
    }

    public static int getChatAccentMode(Context context) {
        return prefs(context).getInt(KEY_CHAT_ACCENT_MODE, MODE_THEME);
    }

    public static void setChatAccentMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_CHAT_ACCENT_MODE, Math.max(MODE_THEME, Math.min(MODE_STRONG, mode))).apply();
    }

    public static int getShellAccentMode(Context context) {
        return prefs(context).getInt(KEY_SHELL_ACCENT_MODE, MODE_THEME);
    }

    public static void setShellAccentMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_SHELL_ACCENT_MODE, Math.max(MODE_THEME, Math.min(MODE_STRONG, mode))).apply();
    }

    public static void resetChatAppearance(Context context) {
        prefs(context).edit()
                .remove(KEY_WALLPAPER)
                .remove(KEY_ROUNDNESS)
                .remove(KEY_CHAT_BG_MODE)
                .remove(KEY_CHAT_ACCENT_MODE)
                .apply();
    }

    public static void resetAppearance(Context context) {
        String theme = getThemeId(context, TelePremiumThemeRegistry.DEFAULT_THEME);
        prefs(context).edit().clear().putString(KEY_THEME, theme).apply();
    }
}
