package org.telegram.messenger.telepremium.theme;

import android.content.Context;

public final class TelePremiumThemeIconRegistry {
    private TelePremiumThemeIconRegistry() {}

    public static int getIcon(String themeId) {
        return TelePremiumThemeRegistry.get(themeId).iconResource;
    }

    public static String getCurrentThemeId(Context context) {
        return TelePremiumThemeStore.getThemeId(context, TelePremiumThemeRegistry.DEFAULT_THEME);
    }

    public static int getCurrentThemeIcon(Context context) {
        return getIcon(getCurrentThemeId(context));
    }

    public static String getCurrentThemeContentDescription(Context context) {
        return "Текущая тема: " + TelePremiumThemeRegistry.get(getCurrentThemeId(context)).displayName;
    }
}
