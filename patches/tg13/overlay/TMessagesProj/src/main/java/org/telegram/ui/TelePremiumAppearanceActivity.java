package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumUiScale;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeRegistry;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeResources;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeStore;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeWallpaper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Appearance center: live chat preview, theme gallery (light and dark, two columns of
 * mini-previews), inline accent / chat background / bubble / scale choices. Every choice
 * applies immediately; no nested dialogs.
 */
public class TelePremiumAppearanceActivity extends BaseFragment {
    private static final int[] SCALES = {85, 90, 92, 95, 100};
    private static final int[] BUBBLES = {12, 18, 24};

    private TelePremiumSettingsUi ui;
    private Context context;

    @Override
    public View createView(Context context) {
        this.context = context;
        TelePremiumThemeController.ensureShellTheme(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(t("TP_L10N_007", R.string.TP_L10N_007));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ui = new TelePremiumSettingsUi(this, context);
        ScrollView scroll = ui.createScreen();
        fragmentView = scroll;
        render();
        return fragmentView;
    }

    private void render() {
        ui.clear();
        TelePremiumThemePreset current = TelePremiumThemeController.getCurrent(context);
        addPreview(current);

        // ---- theme gallery ----
        List<TelePremiumThemePreset> light = new ArrayList<>(), dark = new ArrayList<>();
        for (TelePremiumThemePreset p : TelePremiumThemeRegistry.all()) (p.isDark ? dark : light).add(p);
        addGallery(t("TP_A_LIGHT", R.string.TP_A_LIGHT), light, current);
        addGallery(t("TP_A_DARK", R.string.TP_A_DARK), dark, current);

        // ---- colors ----
        String[] accentModes = {t("TP_L10N_036", R.string.TP_L10N_036), t("TP_L10N_039", R.string.TP_L10N_039), t("TP_L10N_040", R.string.TP_L10N_040)};
        ui.section(t("TP_L10N_020", R.string.TP_L10N_020));
        ui.chips(t("TP_L10N_009", R.string.TP_L10N_009), t("TP_A_SHELL_SUB", R.string.TP_A_SHELL_SUB), R.drawable.tp_ic_palette, TelePremiumSettingsUi.AMBER,
                accentModes, TelePremiumThemeStore.getShellAccentMode(context), i -> { TelePremiumThemeStore.setShellAccentMode(context, i); reapply(); });
        ui.chips(t("TP_L10N_013", R.string.TP_L10N_013), t("TP_A_CHAT_SUB", R.string.TP_A_CHAT_SUB), R.drawable.tp_ic_chat, TelePremiumSettingsUi.MINT,
                accentModes, TelePremiumThemeStore.getChatAccentMode(context), i -> { TelePremiumThemeStore.setChatAccentMode(context, i); reapply(); });

        // ---- chat ----
        ui.section(t("TP_L10N_011", R.string.TP_L10N_011));
        String[] backgrounds = {t("TP_L10N_036", R.string.TP_L10N_036), t("TP_L10N_037", R.string.TP_L10N_037), t("TP_L10N_038", R.string.TP_L10N_038)};
        ui.chips(t("TP_L10N_012", R.string.TP_L10N_012), null, R.drawable.tp_ic_tune, TelePremiumSettingsUi.SKY,
                backgrounds, TelePremiumThemeStore.getChatBackgroundMode(context), i -> { TelePremiumThemeStore.setChatBackgroundMode(context, i); reapply(); });
        String[] bubbles = {"12 dp", "18 dp", "24 dp"};
        int bubbleIndex = indexOf(BUBBLES, TelePremiumThemeStore.getBubbleRoundness(context));
        ui.chips(t("TP_L10N_014", R.string.TP_L10N_014), null, R.drawable.tp_ic_chat, TelePremiumSettingsUi.PURPLE, bubbles, bubbleIndex, i -> {
            int value = BUBBLES[i];
            TelePremiumThemeStore.setBubbleRoundness(context, value);
            SharedConfig.bubbleRadius = value;
            SharedPreferences.Editor e = MessagesController.getGlobalMainSettings().edit();
            e.putInt("bubbleRadius", value);
            e.apply();
            reapply();
        });
        ui.row(t("TP_L10N_015", R.string.TP_L10N_015), t("TP_L10N_016", R.string.TP_L10N_016), R.drawable.tp_ic_tune, TelePremiumSettingsUi.SLATE, null,
                v -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));

        // ---- scale ----
        ui.section(t("TP_L10N_017", R.string.TP_L10N_017));
        String[] scales = new String[SCALES.length];
        for (int i = 0; i < SCALES.length; i++) scales[i] = SCALES[i] + "%";
        ui.chips(t("TP_L10N_064", R.string.TP_L10N_064), t("TP_A_SCALE_SUB", R.string.TP_A_SCALE_SUB), R.drawable.tp_ic_text_size, TelePremiumSettingsUi.INDIGO,
                scales, indexOf(SCALES, TelePremiumUiScale.getScalePercent(context)), i -> {
                    TelePremiumUiScale.setScalePercent(context, SCALES[i]);
                    TelePremiumUiScale.apply(context);
                    Toast.makeText(context, t("TP_L10N_045", R.string.TP_L10N_045) + SCALES[i] + "%", Toast.LENGTH_SHORT).show();
                    Activity activity = getParentActivity();
                    if (activity != null) activity.recreate();
                });

        ui.button(t("TP_L10N_018", R.string.TP_L10N_018), false, v -> {
            TelePremiumThemeStore.resetAppearance(context);
            reapply();
        });
    }

    private void reapply() {
        TelePremiumThemeController.reapplyCurrent(context, true);
    }

    // ---- live preview ----

    private void addPreview(TelePremiumThemePreset current) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(TelePremiumThemeWallpaper.createPreview(current));
        box.setClipToOutline(true);
        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), TelePremiumSettingsUi.dp(22));
            }
        });

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // mini action bar
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(TelePremiumSettingsUi.dp(14), 0, TelePremiumSettingsUi.dp(14), 0);
        bar.setBackgroundColor(TelePremiumThemeRegistry.blend(current.surface, current.accent, current.isDark ? 0.10f : 0.05f));
        ImageView icon = new ImageView(context);
        icon.setImageResource(current.iconResource);
        icon.setColorFilter(current.accent, PorterDuff.Mode.SRC_IN);
        bar.addView(icon, new LinearLayout.LayoutParams(TelePremiumSettingsUi.dp(26), TelePremiumSettingsUi.dp(26)));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(ui.text(current.displayName, 16, current.textPrimary, true));
        names.addView(ui.text(current.isDark ? t("TP_A_DARK_ONE", R.string.TP_A_DARK_ONE) : t("TP_A_LIGHT_ONE", R.string.TP_A_LIGHT_ONE), 12, current.textSecondary, false));
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, -2, 1f);
        namesLp.leftMargin = TelePremiumSettingsUi.dp(12);
        bar.addView(names, namesLp);
        column.addView(bar, new LinearLayout.LayoutParams(-1, TelePremiumSettingsUi.dp(58)));

        LinearLayout chat = new LinearLayout(context);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(TelePremiumSettingsUi.dp(14), TelePremiumSettingsUi.dp(10), TelePremiumSettingsUi.dp(14), TelePremiumSettingsUi.dp(14));
        int chatAccent = TelePremiumThemeResources.resolveAccent(context, current, true);
        int outBg = TelePremiumThemeResources.resolveOutgoingBubble(context, current, chatAccent);
        int inBg = TelePremiumThemeStore.getChatBackgroundMode(context) == TelePremiumThemeStore.CHAT_BG_CLEAN ? current.surface : current.incomingBubble;
        chat.addView(bubble(t("TP_L10N_021", R.string.TP_L10N_021), current.textPrimary, inBg), bubbleParams(Gravity.LEFT));
        chat.addView(bubble(t("TP_L10N_022", R.string.TP_L10N_022), current.textPrimary, outBg), bubbleParams(Gravity.RIGHT));
        column.addView(chat, new LinearLayout.LayoutParams(-1, -2));
        box.addView(column, new FrameLayout.LayoutParams(-1, -2));
        ui.content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView bubble(String text, int textColor, int background) {
        TextView v = ui.text(text, 14, textColor, false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(TelePremiumSettingsUi.dp(12), 0, TelePremiumSettingsUi.dp(12), 0);
        v.setBackground(TelePremiumSettingsUi.round(background, TelePremiumThemeStore.getBubbleRoundness(context)));
        return v;
    }

    private LinearLayout.LayoutParams bubbleParams(int gravity) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, TelePremiumSettingsUi.dp(40));
        lp.gravity = gravity;
        lp.topMargin = TelePremiumSettingsUi.dp(8);
        return lp;
    }

    // ---- gallery ----

    private void addGallery(String title, List<TelePremiumThemePreset> presets, TelePremiumThemePreset current) {
        if (presets.isEmpty()) return;
        TextView header = ui.text(title, 14, ui.color(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueHeader), true);
        header.setPadding(TelePremiumSettingsUi.dp(8), TelePremiumSettingsUi.dp(22), TelePremiumSettingsUi.dp(8), TelePremiumSettingsUi.dp(8));
        ui.content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = null;
        for (int i = 0; i < presets.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = TelePremiumSettingsUi.dp(10);
                ui.content.addView(row, lp);
            }
            TelePremiumThemePreset preset = presets.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % 2 == 1) lp.leftMargin = TelePremiumSettingsUi.dp(10);
            row.addView(themeTile(preset, preset.id.equals(current.id)), lp);
            if (i == presets.size() - 1 && i % 2 == 0) row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private View themeTile(TelePremiumThemePreset preset, boolean selected) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = TelePremiumSettingsUi.round(preset.background, 18);
        bg.setStroke(TelePremiumSettingsUi.dp(selected ? 2.5f : 1), selected ? preset.accent : preset.divider);
        tile.setBackground(bg);
        tile.setPadding(TelePremiumSettingsUi.dp(10), TelePremiumSettingsUi.dp(10), TelePremiumSettingsUi.dp(10), TelePremiumSettingsUi.dp(10));

        // mini chat
        LinearLayout mini = new LinearLayout(context);
        mini.setOrientation(LinearLayout.VERTICAL);
        View top = new View(context);
        top.setBackground(TelePremiumSettingsUi.round(preset.accent, 4));
        mini.addView(top, new LinearLayout.LayoutParams(TelePremiumSettingsUi.dp(44), TelePremiumSettingsUi.dp(8)));
        View in = new View(context);
        in.setBackground(TelePremiumSettingsUi.round(preset.incomingBubble, 8));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(TelePremiumSettingsUi.dp(72), TelePremiumSettingsUi.dp(16));
        inLp.topMargin = TelePremiumSettingsUi.dp(10);
        mini.addView(in, inLp);
        View out = new View(context);
        out.setBackground(TelePremiumSettingsUi.round(preset.outgoingBubble, 8));
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(TelePremiumSettingsUi.dp(60), TelePremiumSettingsUi.dp(16));
        outLp.topMargin = TelePremiumSettingsUi.dp(6);
        outLp.gravity = Gravity.RIGHT;
        mini.addView(out, outLp);
        tile.addView(mini, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.text(preset.displayName, 13, preset.textPrimary, true);
        name.setMaxLines(2);
        bottom.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (selected) {
            FrameLayout check = new FrameLayout(context);
            check.setBackground(TelePremiumSettingsUi.round(preset.accent, 11));
            TextView mark = ui.text("✓", 12, Color.WHITE, true);
            mark.setGravity(Gravity.CENTER);
            check.addView(mark, new FrameLayout.LayoutParams(-1, -1));
            bottom.addView(check, new LinearLayout.LayoutParams(TelePremiumSettingsUi.dp(22), TelePremiumSettingsUi.dp(22)));
        }
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(-1, -2);
        bottomLp.topMargin = TelePremiumSettingsUi.dp(10);
        tile.addView(bottom, bottomLp);

        tile.setOnClickListener(v -> {
            if (selected) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (!TelePremiumThemeController.applyTheme(context, preset.id, true)) {
                Toast.makeText(context, t("TP_L10N_025", R.string.TP_L10N_025), Toast.LENGTH_SHORT).show();
            }
        });
        return tile;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
