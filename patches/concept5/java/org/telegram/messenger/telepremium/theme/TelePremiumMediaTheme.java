package org.telegram.messenger.telepremium.theme;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;

/**
 * Theme adapter for TelePremium media screens.
 *
 * Media Center, audio player and video chrome consume this palette instead of
 * owning a hard-coded black/purple skin. The palette is derived from the
 * active TelePremium shell theme and therefore follows theme changes without
 * introducing a second, independent media theme selector.
 */
public final class TelePremiumMediaTheme {
    public final TelePremiumThemePreset preset;
    public final boolean dark;

    public final int background;
    public final int surface;
    public final int surfaceElevated;
    public final int accent;
    public final int accentPressed;
    public final int textPrimary;
    public final int textSecondary;
    public final int iconPrimary;
    public final int iconSecondary;
    public final int divider;
    public final int searchBackground;
    public final int inputBackground;

    public final int cardBackground;
    public final int cardStroke;
    public final int chipBackground;
    public final int chipSelectedBackground;
    public final int miniPlayerBackground;
    public final int artworkBackground;
    public final int timelineTrack;
    public final int playerOverlay;
    public final int playerOverlayStrong;
    public final int gestureHint;
    public final int onAccent;

    private TelePremiumMediaTheme(Context context, TelePremiumThemePreset preset) {
        this.preset = preset;
        dark = preset.isDark;

        background = preset.background;
        surface = preset.surface;
        surfaceElevated = preset.surfaceElevated;
        accent = TelePremiumThemeResources.resolveAccent(context, preset, false);
        accentPressed = TelePremiumThemeRegistry.blend(accent, Color.BLACK, dark ? 0.18f : 0.12f);
        textPrimary = preset.textPrimary;
        textSecondary = preset.textSecondary;
        iconPrimary = preset.iconPrimary;
        iconSecondary = preset.iconSecondary;
        divider = preset.divider;
        searchBackground = preset.searchBackground;
        inputBackground = preset.inputBackground;

        cardBackground = preset.surfaceElevated;
        cardStroke = TelePremiumThemeRegistry.blend(preset.divider, accent, dark ? 0.18f : 0.10f);
        chipBackground = TelePremiumThemeRegistry.blend(preset.surface, preset.background, dark ? 0.20f : 0.28f);
        chipSelectedBackground = TelePremiumThemeRegistry.blend(preset.surface, accent, dark ? 0.24f : 0.13f);
        miniPlayerBackground = TelePremiumThemeRegistry.blend(preset.surfaceElevated, accent, dark ? 0.07f : 0.035f);
        artworkBackground = TelePremiumThemeRegistry.blend(preset.background, accent, dark ? 0.11f : 0.055f);
        timelineTrack = TelePremiumThemeRegistry.blend(preset.divider, preset.textSecondary, dark ? 0.18f : 0.10f);

        // Video pixels remain neutral; only transient chrome inherits the shell.
        int overlayBase = TelePremiumThemeRegistry.blend(preset.background, Color.BLACK, dark ? 0.22f : 0.48f);
        playerOverlay = alpha(overlayBase, 0x9a);
        playerOverlayStrong = alpha(overlayBase, 0xc6);
        gestureHint = alpha(accent, 0x78);
        onAccent = contrastOn(accent);
    }

    public static TelePremiumMediaTheme from(Context context) {
        TelePremiumThemeController.ensureShellTheme(context);
        return new TelePremiumMediaTheme(context, TelePremiumThemeController.getCurrent(context));
    }

    public void applySystemBars(Activity activity) {
        if (activity != null) TelePremiumThemeController.applySystemBars(activity, preset);
    }

    public int selectedSurface() {
        return chipSelectedBackground;
    }

    public int withAlpha(int color, int alpha) {
        return alpha(color, alpha);
    }

    private static int alpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int contrastOn(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.66 ? 0xff17212b : 0xffffffff;
    }
}
