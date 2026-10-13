package org.telegram.messenger.telepremium.theme;

import android.content.Context;

import org.telegram.ui.ActionBar.Theme;

public final class TelePremiumThemeIconRegistry {
    private TelePremiumThemeIconRegistry() {}

    public static int getIcon(String themeId) {
        return TelePremiumThemeRegistry.get(themeId).iconResource;
    }

    public static String getCurrentThemeId(Context context) {
        String fallback = Theme.isCurrentThemeDark()
                ? TelePremiumThemeRegistry.DARK_GRAPHITE
                : TelePremiumThemeRegistry.SILVER_GRAY;
        return TelePremiumThemeStore.getThemeId(context, fallback);
    }

    public static int getCurrentThemeIcon(Context context) {
        return getIcon(getCurrentThemeId(context));
    }

    public static String getCurrentThemeContentDescription(Context context) {
        return "Текущая тема: " + TelePremiumThemeRegistry.get(getCurrentThemeId(context)).displayName;
    }
}
