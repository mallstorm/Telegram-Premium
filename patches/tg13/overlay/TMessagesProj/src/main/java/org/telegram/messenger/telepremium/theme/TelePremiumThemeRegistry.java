package org.telegram.messenger.telepremium.theme;

import android.graphics.Color;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TelePremiumThemeRegistry {
    /** The theme every install starts with (and falls back to). */
    public static final String DEFAULT_THEME = "frost_morning";
    public static final String DARK_GRAPHITE = "dark_graphite";
    public static final String SILVER_GRAY = "silver_gray";
    public static final String SOFT_GREEN = "soft_green";
    public static final String FROST_MORNING = "frost_morning";
    public static final String WARM_BEIGE = "warm_beige";
    public static final String LAVENDER_TWILIGHT = "lavender_twilight";
    public static final String SEA_TURQUOISE = "sea_turquoise";
    public static final String PINK_SAND = "pink_sand";
    public static final String GRAPHITE_ICE = "graphite_ice";
    public static final String SUNSET_PEACH = "sunset_peach";
    public static final String MIDNIGHT_AMOLED = "midnight_amoled";
    public static final String NIGHT_OCEAN = "night_ocean";
    public static final String DARK_VIOLET = "dark_violet";

    private static final LinkedHashMap<String, TelePremiumThemePreset> THEMES = new LinkedHashMap<>();

    static {
        add(theme(DARK_GRAPHITE, "Тёмный графит", true, "#17212B", "#202B36", "#63AEE8", "#F2F6F9", R.drawable.tp_theme_dark_graphite, "graphite"));
        add(theme(SILVER_GRAY, "Серо-серебряная", false, "#F5F7FA", "#FFFFFF", "#526C82", "#1E2B36", R.drawable.tp_theme_silver_gray, "silver"));
        add(theme(SOFT_GREEN, "Нежная зелень", false, "#F2F8EC", "#FEFFF9", "#338B3D", "#203321", R.drawable.tp_theme_soft_green, "leaves"));
        add(theme(FROST_MORNING, "Морозное утро", false, "#F1F9FF", "#FCFEFF", "#2A84C3", "#173247", R.drawable.tp_theme_frost_morning, "frost"));
        add(theme(WARM_BEIGE, "Тёплый беж", false, "#FBF2E6", "#FFF9F1", "#8F5A35", "#3E2D23", R.drawable.tp_theme_warm_beige, "paper"));
        add(theme(LAVENDER_TWILIGHT, "Лавандовый сумрак", false, "#F4F0FF", "#FCFAFF", "#7751C8", "#322A4B", R.drawable.tp_theme_lavender_twilight, "twilight"));
        add(theme(SEA_TURQUOISE, "Морская бирюза", false, "#EFFCFC", "#FBFFFF", "#0F9DA5", "#163A3D", R.drawable.tp_theme_sea_turquoise, "waves"));
        add(theme(PINK_SAND, "Розовый песок", false, "#FFF4F4", "#FFFAFA", "#D45F7C", "#432B33", R.drawable.tp_theme_pink_sand, "dunes"));
        add(theme(GRAPHITE_ICE, "Графитовый лёд", false, "#EEF4F7", "#FBFDFE", "#2E8497", "#1E2D35", R.drawable.tp_theme_graphite_ice, "ice"));
        add(theme(MIDNIGHT_AMOLED, "Полночь AMOLED", true, "#000000", "#0E0F12", "#4FB3F6", "#ECEFF3", R.drawable.tp_theme_dark_graphite, "graphite"));
        add(theme(NIGHT_OCEAN, "Ночной океан", true, "#0D1A22", "#14242E", "#2EC4B6", "#E4F1F4", R.drawable.tp_theme_sea_turquoise, "waves"));
        add(theme(DARK_VIOLET, "Тёмная фиалка", true, "#14111D", "#1D1929", "#A084FF", "#EEEAF8", R.drawable.tp_theme_lavender_twilight, "twilight"));
        add(theme(SUNSET_PEACH, "Закатный персик", false, "#FFF2E7", "#FFFAF4", "#EF7048", "#422A20", R.drawable.tp_theme_sunset_peach, "sunset"));
    }

    private TelePremiumThemeRegistry() {}

    private static void add(TelePremiumThemePreset preset) {
        THEMES.put(preset.id, preset);
    }

    private static TelePremiumThemePreset theme(String id, String name, boolean dark,
                                                 String background, String surface, String accent,
                                                 String text, int icon, String wallpaper) {
        int bg = Color.parseColor(background);
        int sf = Color.parseColor(surface);
        int ac = Color.parseColor(accent);
        int tx = Color.parseColor(text);
        int elevated = blend(sf, dark ? Color.WHITE : Color.BLACK, dark ? 0.07f : 0.018f);
        // Keep secondary text well above the low-contrast gray-on-white values of stock presets.
        int secondary = blend(tx, bg, dark ? 0.32f : 0.28f);
        int iconSecondary = blend(ac, tx, dark ? 0.18f : 0.22f);
        int divider = blend(sf, tx, dark ? 0.16f : 0.12f);
        int selector = blend(sf, ac, dark ? 0.20f : 0.10f);
        int incoming = dark ? blend(sf, Color.WHITE, 0.04f) : sf;
        int outgoing = blend(sf, ac, dark ? 0.29f : 0.14f);
        int input = dark ? blend(sf, Color.BLACK, 0.10f) : blend(sf, bg, 0.24f);
        int status = dark ? blend(bg, Color.BLACK, 0.12f) : bg;
        int nav = dark ? blend(bg, Color.BLACK, 0.20f) : blend(bg, Color.BLACK, 0.025f);
        return new TelePremiumThemePreset(
                id, name, dark,
                bg, sf, elevated,
                bg, sf,
                ac, blend(ac, Color.BLACK, dark ? 0.18f : 0.12f),
                tx, secondary,
                tx, iconSecondary,
                divider, selector,
                incoming, outgoing,
                ac, ac,
                input, blend(bg, sf, 0.50f),
                status, nav,
                wallpaper, icon
        );
    }

    public static int blend(int from, int to, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.argb(a, r, g, b);
    }

    public static TelePremiumThemePreset get(String id) {
        TelePremiumThemePreset preset = THEMES.get(id);
        return preset != null ? preset : THEMES.get(DEFAULT_THEME);
    }

    public static List<TelePremiumThemePreset> all() {
        return Collections.unmodifiableList(new ArrayList<>(THEMES.values()));
    }

    public static Map<String, TelePremiumThemePreset> map() {
        return Collections.unmodifiableMap(THEMES);
    }
}
