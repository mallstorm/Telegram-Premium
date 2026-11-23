package org.telegram.messenger.telepremium;

import android.app.Activity;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;

/**
 * The two interchangeable application shells over the single Telegram 13.1.1 engine.
 * CLASSIC = 11.x look (side menu, no bottom tabs); MODERN = native 13.x (bottom tabs).
 * Both read the same Theme.key_* map, so a TelePremium theme preset is shared by both.
 */
public final class TelePremiumShell {
    private TelePremiumShell() {}

    public static boolean isClassic() {
        return TelePremiumUiPrefs.classicUiEnabled();
    }

    public static String shortName() {
        return isClassic()
                ? TPStrings.get("TP_ShellClassicShort", R.string.TP_ShellClassicShort)
                : TPStrings.get("TP_ShellModernShort", R.string.TP_ShellModernShort);
    }

    /**
     * Persists the choice synchronously, makes sure the saved preset is the active Telegram theme
     * and rebuilds the activity so the new shell is created from scratch (no half-rebuilt state).
     */
    public static boolean switchTo(Activity activity, boolean classic) {
        if (activity == null || isClassic() == classic) return false;
        TelePremiumUiPrefs.setClassicUiEnabled(classic);
        try {
            TelePremiumThemeController.ensureShellTheme(activity);
        } catch (Throwable ignore) {
        }
        activity.getWindow().getDecorView().post(activity::recreate);
        return true;
    }
}
