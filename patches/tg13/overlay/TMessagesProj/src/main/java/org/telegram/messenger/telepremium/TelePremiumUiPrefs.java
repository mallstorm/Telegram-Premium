package org.telegram.messenger.telepremium;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Small UI switches of TelePremium that other screens read. */
public final class TelePremiumUiPrefs {
    private TelePremiumUiPrefs() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_ui", Context.MODE_PRIVATE);
    }

    /** Classic Telegram 11.x is the default shell; Telegram 13.1.1 remains the engine. */
    public static boolean classicUiEnabled() {
        return prefs().getBoolean("classic_ui_11x_enabled", true);
    }

    public static void setClassicUiEnabled(boolean enabled) {
        prefs().edit().putBoolean("classic_ui_11x_enabled", enabled).commit();
    }

    /** Telegram Premium / Stars / Business / Gift rows in Settings (off: they duplicated TelePremium). */
    public static boolean showTelegramPremiumBlock() { return prefs().getBoolean("show_tg_premium", false); }

    public static void setShowTelegramPremiumBlock(boolean value) { prefs().edit().putBoolean("show_tg_premium", value).apply(); }

    /** TelePremium player card in the notification shade instead of the system media style. */
    public static boolean customPlayerNotification() { return prefs().getBoolean("custom_player_notification", true); }

    public static void setCustomPlayerNotification(boolean value) { prefs().edit().putBoolean("custom_player_notification", value).apply(); }
}
