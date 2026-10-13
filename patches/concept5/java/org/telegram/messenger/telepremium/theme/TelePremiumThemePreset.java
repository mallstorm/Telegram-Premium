package org.telegram.messenger.telepremium.theme;

public final class TelePremiumThemePreset {
    public final String id;
    public final String displayName;
    public final boolean isDark;
    public final int background;
    public final int surface;
    public final int surfaceElevated;
    public final int actionBar;
    public final int drawerBackground;
    public final int accent;
    public final int accentPressed;
    public final int textPrimary;
    public final int textSecondary;
    public final int iconPrimary;
    public final int iconSecondary;
    public final int divider;
    public final int listSelector;
    public final int incomingBubble;
    public final int outgoingBubble;
    public final int unreadBadge;
    public final int fab;
    public final int inputBackground;
    public final int searchBackground;
    public final int statusBarColor;
    public final int navigationBarColor;
    public final String wallpaperType;
    public final int iconResource;

    public TelePremiumThemePreset(String id, String displayName, boolean isDark,
                                  int background, int surface, int surfaceElevated,
                                  int actionBar, int drawerBackground,
                                  int accent, int accentPressed,
                                  int textPrimary, int textSecondary,
                                  int iconPrimary, int iconSecondary,
                                  int divider, int listSelector,
                                  int incomingBubble, int outgoingBubble,
                                  int unreadBadge, int fab,
                                  int inputBackground, int searchBackground,
                                  int statusBarColor, int navigationBarColor,
                                  String wallpaperType, int iconResource) {
        this.id = id;
        this.displayName = displayName;
        this.isDark = isDark;
        this.background = background;
        this.surface = surface;
        this.surfaceElevated = surfaceElevated;
        this.actionBar = actionBar;
        this.drawerBackground = drawerBackground;
        this.accent = accent;
        this.accentPressed = accentPressed;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.iconPrimary = iconPrimary;
        this.iconSecondary = iconSecondary;
        this.divider = divider;
        this.listSelector = listSelector;
        this.incomingBubble = incomingBubble;
        this.outgoingBubble = outgoingBubble;
        this.unreadBadge = unreadBadge;
        this.fab = fab;
        this.inputBackground = inputBackground;
        this.searchBackground = searchBackground;
        this.statusBarColor = statusBarColor;
        this.navigationBarColor = navigationBarColor;
        this.wallpaperType = wallpaperType;
        this.iconResource = iconResource;
    }
}
