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

    public static int tint(int key, int color) {
        if (!isEnabled()) {
            return color;
        }

        int target = getTargetColor(getPosition());

        if (key == Theme.key_windowBackgroundWhite) {
            return shellColor(color, target, 0.90f, 0.00f);
        }
        if (key == Theme.key_windowBackgroundGray) {
            return shellColor(color, target, 0.88f, -0.045f);
        }
        if (key == Theme.key_actionBarDefault || key == Theme.key_actionBarDefaultArchived) {
            return shellColor(color, target, 0.92f, -0.025f);
        }
        if (key == Theme.key_dialogBackground) {
            return shellColor(color, target, 0.91f, 0.015f);
        }
        if (key == Theme.key_dialogBackgroundGray || key == Theme.key_actionBarDefaultSubmenuBackground) {
            return shellColor(color, target, 0.88f, -0.035f);
        }

        if (key == Theme.key_windowBackgroundWhiteBlueText4
                || key == Theme.key_windowBackgroundWhiteBlueIcon
                || key == Theme.key_switchTrackChecked
                || key == Theme.key_player_progress
                || key == Theme.key_radioBackgroundChecked) {
            return accentColor(color, target, getPosition());
        }

        return color;
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
