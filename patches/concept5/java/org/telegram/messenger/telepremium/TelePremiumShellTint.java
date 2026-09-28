package org.telegram.messenger.telepremium;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

/**
 * Client-only shell tint. It never touches Telegram server state.
 *
 * Position 0..1000 spans:
 * white -> beige -> yellow -> orange -> pink -> purple -> blue -> cyan -> teal -> black.
 */
public final class TelePremiumShellTint {

    private static final String PREFS = "telepremium_shell_tint";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_POSITION = "position";

    private static final int[] STOPS = {
            0xFFFDFDFD,
            0xFFF5EEE2,
            0xFFF2D878,
            0xFFED9B69,
            0xFFD86C9F,
            0xFF9A6BD0,
            0xFF607FDC,
            0xFF55B7D4,
            0xFF3E7775,
            0xFF090A0C
    };

    private TelePremiumShellTint() {
    }

    private static SharedPreferences prefs() {
        Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_ENABLED, false);
    }

    public static int getPosition() {
        SharedPreferences p = prefs();
        if (p == null) {
            return 0;
        }
        if (!p.contains(KEY_POSITION)) {
            return Theme.isCurrentThemeDark() ? 1000 : 0;
        }
        return clamp(p.getInt(KEY_POSITION, 0));
    }

    public static void setPosition(int position) {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        p.edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_POSITION, clamp(position))
                .apply();
    }

    public static void reset() {
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().remove(KEY_ENABLED).remove(KEY_POSITION).apply();
        }
    }

    /**
     * Legacy Theme hook kept intentionally as a no-op. Shell tint must never
     * recolor chat content, message bubbles, dialog content or general cells.
     */
    public static int tint(int key, int color) {
        return color;
    }

    public static int actionBarBackground(int base) {
        if (!isEnabled()) {
            return base;
        }
        return shellColor(base, getTargetColor(getPosition()), 0.92f, -0.02f);
    }

    public static int navigationBarBackground(int base) {
        if (!isEnabled()) {
            return base;
        }
        return shellColor(base, getTargetColor(getPosition()), 0.94f, -0.04f);
    }

    public static int drawerBackground(int base) {
        if (!isEnabled()) {
            return base;
        }
        return shellColor(base, getTargetColor(getPosition()), 0.90f, -0.025f);
    }

    public static int foreground(int fallback) {
        if (!isEnabled()) {
            return fallback;
        }
        return foreground(getTargetColor(getPosition()), false, fallback);
    }

    public static int secondaryForeground(int fallback) {
        if (!isEnabled()) {
            return fallback;
        }
        return foreground(getTargetColor(getPosition()), true, fallback);
    }

    public static int selector(int fallback) {
        if (!isEnabled()) {
            return fallback;
        }
        int target = getTargetColor(getPosition());
        double luminance = (0.2126 * Color.red(target) + 0.7152 * Color.green(target) + 0.0722 * Color.blue(target)) / 255.0;
        return luminance > 0.58 ? 0x1F101820 : 0x24FFFFFF;
    }

    public static int drawerColor(int key, int fallback) {
        if (!isEnabled()) {
            return fallback;
        }
        if (key == Theme.key_chats_menuBackground || key == Theme.key_chats_menuTopBackground || key == Theme.key_chats_menuTopBackgroundCats) {
            return drawerBackground(fallback);
        }
        if (key == Theme.key_chats_menuName || key == Theme.key_chats_menuItemText) {
            return foreground(fallback);
        }
        if (key == Theme.key_chats_menuPhone || key == Theme.key_chats_menuPhoneCats) {
            return secondaryForeground(fallback);
        }
        if (key == Theme.key_chats_menuItemIcon) {
            return secondaryForeground(fallback);
        }
        return fallback;
    }

    public static int getTargetColor(int position) {
        float scaled = clamp(position) / 1000f * (STOPS.length - 1);
        int left = (int) Math.floor(scaled);
        int right = Math.min(STOPS.length - 1, left + 1);
        float f = scaled - left;
        return blend(STOPS[left], STOPS[right], f);
    }

    private static int shellColor(int base, int target, float strength, float valueShift) {
        int mixed = blend(base, target, strength);
        float[] hsv = new float[3];
        Color.colorToHSV(mixed, hsv);
        hsv[2] = clamp01(hsv[2] + valueShift);
        int rgb = Color.HSVToColor(hsv);
        return (Color.alpha(base) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int foreground(int target, boolean secondary, int fallback) {
        double luminance = (0.2126 * Color.red(target) + 0.7152 * Color.green(target) + 0.0722 * Color.blue(target)) / 255.0;
        int alpha = secondary ? 0xB0 : 0xF2;
        int rgb = luminance > 0.58 ? 0x0015181C : 0x00F5F7FA;
        return (alpha << 24) | rgb;
    }

    private static int divider(int target, int fallback) {
        double luminance = (0.2126 * Color.red(target) + 0.7152 * Color.green(target) + 0.0722 * Color.blue(target)) / 255.0;
        return luminance > 0.58 ? 0x24101820 : 0x32FFFFFF;
    }

    private static int accentColor(int base, int target, int position) {
        if (position <= 70) {
            return blend(base, 0xFF507AA3, 0.38f);
        }
        if (position >= 930) {
            return blend(base, 0xFF9AB7D0, 0.52f);
        }
        float[] hsv = new float[3];
        Color.colorToHSV(target, hsv);
        hsv[1] = Math.max(0.55f, hsv[1]);
        hsv[2] = Math.max(0.72f, hsv[2]);
        int vivid = Color.HSVToColor(hsv);
        return blend(base, vivid, 0.72f);
    }

    private static int blend(int a, int b, float f) {
        f = clamp01(f);
        int ar = Color.red(a);
        int ag = Color.green(a);
        int ab = Color.blue(a);
        int aa = Color.alpha(a);
        int br = Color.red(b);
        int bg = Color.green(b);
        int bb = Color.blue(b);
        int ba = Color.alpha(b);
        return Color.argb(
                Math.round(aa + (ba - aa) * f),
                Math.round(ar + (br - ar) * f),
                Math.round(ag + (bg - ag) * f),
                Math.round(ab + (bb - ab) * f)
        );
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
