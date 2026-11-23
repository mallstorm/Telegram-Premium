package org.telegram.messenger.telepremium.theme;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class TelePremiumThemeController {
    private TelePremiumThemeController() {}

    public static TelePremiumThemePreset getCurrent(Context context) {
        return TelePremiumThemeRegistry.get(TelePremiumThemeIconRegistry.getCurrentThemeId(context));
    }

    public static void applySavedTheme(Context context, boolean recreate) {
        if (!TelePremiumThemeStore.hasTheme(context)) return;
        applyTheme(context, TelePremiumThemeStore.getThemeId(context, TelePremiumThemeRegistry.SILVER_GRAY), recreate);
    }

    public static void ensureShellTheme(Context context) {
        if (context == null || !TelePremiumThemeStore.hasTheme(context)) return;
        String id = TelePremiumThemeStore.getThemeId(context, TelePremiumThemeRegistry.SILVER_GRAY);
        String expected = "TelePremium_" + id;
        Theme.ThemeInfo active = Theme.getActiveTheme();
        String key = active == null ? null : active.getKey();
        if (key == null || (!expected.equals(key) && !key.contains(id))) {
            applyTheme(context, id, false);
        } else {
            applySystemBars(context, TelePremiumThemeRegistry.get(id));
        }
    }

    public static boolean reapplyCurrent(Context context, boolean recreate) {
        return applyTheme(context, TelePremiumThemeStore.getThemeId(context, TelePremiumThemeRegistry.SILVER_GRAY), recreate);
    }

    public static boolean applyTheme(Context context, String themeId, boolean recreate) {
        TelePremiumThemePreset preset = TelePremiumThemeRegistry.get(themeId);
        try {
            File dir = new File(ApplicationLoader.getFilesDirFixed(), "telepremium/themes");
            if (!dir.exists() && !dir.mkdirs()) return false;
            File file = new File(dir, preset.id + ".attheme");
            byte[] data = TelePremiumThemeResources.buildAttheme(context, preset).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(data);
            }
            // A TelePremium theme is an explicit choice: stop Telegram's automatic day/night switching.
            // With "follow system" on (the Android 10+ default) Telegram replaces any dark *day* theme
            // by its default light one, which is why the dark preset never stuck.
            disableAutoNight();
            Theme.ThemeInfo applied = Theme.applyThemeFile(file, "TelePremium_" + preset.id, null, false);
            if (applied == null) return false;
            if (preset.isDark) {
                try { Theme.setCurrentNightTheme(applied); } catch (Throwable ignore) {}
            }
            TelePremiumThemeStore.setThemeId(context, preset.id);
            applySystemBars(context, preset);
            if (recreate && context instanceof Activity) {
                Activity activity = (Activity) context;
                activity.getWindow().getDecorView().post(activity::recreate);
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void disableAutoNight() {
        try {
            if (Theme.selectedAutoNightType != Theme.AUTO_NIGHT_TYPE_NONE) {
                Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
                Theme.cancelAutoNightThemeCallbacks();
                Theme.saveAutoNightThemeConfig();
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * System bars belong to Telegram: it derives status/navigation icon colors from the visible
     * fragment and the active theme (edge-to-edge in 13.x). Writing window colors/flags here fought
     * that logic and left mismatched bars on light/dark presets, so only ask Telegram to re-check.
     */
    public static void applySystemBars(Context context, TelePremiumThemePreset preset) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.needCheckSystemBarColors, true));
    }

    public static void showQuickThemeSelector(Context context, View anchor, Runnable onApplied) {
        final TelePremiumThemePreset current = getCurrent(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        root.setBackgroundColor(current.surfaceElevated);

        TextView title = new TextView(context);
        title.setText("Оформление · " + current.displayName);
        title.setTextSize(15);
        title.setTextColor(current.textPrimary);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(AndroidUtilities.dp(6), 0, 0, AndroidUtilities.dp(8));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PopupWindow popup = new PopupWindow(root, AndroidUtilities.dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(current.surfaceElevated));
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(AndroidUtilities.dp(10));

        LinearLayout row = null;
        int index = 0;
        for (TelePremiumThemePreset preset : TelePremiumThemeRegistry.all()) {
            if (index % 5 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(76)));
            }
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription("Применить тему " + preset.displayName);

            ImageView icon = new ImageView(context);
            icon.setImageResource(preset.iconResource);
            icon.setColorFilter(preset.accent, PorterDuff.Mode.SRC_IN);
            item.addView(icon, new LinearLayout.LayoutParams(AndroidUtilities.dp(28), AndroidUtilities.dp(28)));

            TextView label = new TextView(context);
            label.setText(shortName(preset.displayName));
            label.setTextSize(9);
            label.setTextColor(current.textPrimary);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            item.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(28)));

            item.setOnClickListener(v -> {
                popup.dismiss();
                if (applyTheme(context, preset.id, true) && onApplied != null) onApplied.run();
            });
            row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            index++;
        }

        TextView all = new TextView(context);
        all.setText("Все темы ›");
        all.setTextColor(current.accent);
        all.setTextSize(14);
        all.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        all.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(4), AndroidUtilities.dp(6), 0);
        root.addView(all, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(38)));
        all.setOnClickListener(v -> {
            popup.dismiss();
            if (context instanceof Activity) {
                try {
                    Class<?> cls = Class.forName("org.telegram.ui.TelePremiumAppearanceActivity");
                    Object fragment = cls.newInstance();
                    java.lang.reflect.Method m = context.getClass().getMethod("presentFragment", Class.forName("org.telegram.ui.ActionBar.BaseFragment"));
                    m.invoke(context, fragment);
                } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
            }
        });

        int x = -AndroidUtilities.dp(300);
        popup.showAsDropDown(anchor, x, -anchor.getHeight());
    }

    private static String shortName(String name) {
        String[] parts = name.split(" ");
        return parts.length > 1 ? parts[0] : name;
    }
}
