package org.telegram.messenger.telepremium.theme;

import android.graphics.drawable.GradientDrawable;

public final class TelePremiumThemeWallpaper {
    private TelePremiumThemeWallpaper() {}

    public static GradientDrawable createPreview(TelePremiumThemePreset preset) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{preset.background, preset.surface, preset.outgoingBubble}
        );
        d.setCornerRadius(24f);
        return d;
    }
}
