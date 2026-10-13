package org.telegram.messenger.telepremium;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

public final class TelePremiumUiScale {

    private static final String PREFS = "telepremium_ui";
    private static final String KEY_SCALE = "ui_scale_percent";
    private static final int DEFAULT_SCALE = 92;

    private TelePremiumUiScale() {
    }

    public static int getScalePercent(Context context) {
        if (context == null) {
            return DEFAULT_SCALE;
        }
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SCALE, DEFAULT_SCALE);
        return Math.max(85, Math.min(100, value));
    }

    public static float getScale(Context context) {
        return getScalePercent(context) / 100.0f;
    }

    public static void setScalePercent(Context context, int percent) {
        if (context == null) {
            return;
        }
        int value = Math.max(85, Math.min(100, percent));
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putInt(KEY_SCALE, value).apply();
    }

    /**
     * Compensates Telegram's fixed chat bubble side insets when the global
     * TelePremium shell is made smaller. Without this compensation a lower
     * density makes channel/group posts look artificially narrow even though
     * more horizontal room is available on the physical display.
     */
    public static int chatInsetPx(Context context, int baseDp) {
        int percent = getScalePercent(context);
        int wideningDp = Math.max(0, DEFAULT_SCALE - percent) * 2;
        wideningDp = Math.min(16, wideningDp);
        int effectiveDp = Math.max(48, baseDp - wideningDp);
        return org.telegram.messenger.AndroidUtilities.dp(effectiveDp);
    }

    @SuppressWarnings("deprecation")
    public static void apply(Context context) {
        if (context == null) {
            return;
        }
        try {
            float scale = getScale(context);
            Resources resources = context.getResources();
            DisplayMetrics system = Resources.getSystem().getDisplayMetrics();
            DisplayMetrics metrics = resources.getDisplayMetrics();

            int targetDpi = Math.max(1, Math.round(system.densityDpi * scale));
            metrics.densityDpi = targetDpi;
            metrics.density = system.density * scale;
            metrics.scaledDensity = system.scaledDensity * scale;

            Configuration configuration = new Configuration(resources.getConfiguration());
            configuration.densityDpi = targetDpi;
            resources.updateConfiguration(configuration, metrics);

            // updateConfiguration may recompute scaledDensity on some Android versions.
            metrics.density = system.density * scale;
            metrics.scaledDensity = system.scaledDensity * scale;
            metrics.densityDpi = targetDpi;
        } catch (Throwable ignore) {
        }
    }
}
