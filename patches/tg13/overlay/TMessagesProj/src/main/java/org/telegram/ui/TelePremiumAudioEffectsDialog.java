package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.media.TelePremiumAudioEffects;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.util.Locale;

/** Bottom sheet with the equalizer, bass, surround, loudness, stereo balance and mono. */
final class TelePremiumAudioEffectsDialog {
    private TelePremiumAudioEffectsDialog() {}

    static void show(Activity activity, TelePremiumMediaTheme theme) {
        TelePremiumPlayerKit kit = new TelePremiumPlayerKit(activity);
        TelePremiumPlayerKit.Palette pal = TelePremiumPlayerKit.Palette.surface(theme);
        Dialog dialog = new Dialog(activity);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int d16 = TelePremiumPlayerKit.dp(16);
        content.setPadding(TelePremiumPlayerKit.dp(18), d16, TelePremiumPlayerKit.dp(18), TelePremiumPlayerKit.dp(22));
        content.setBackground(TelePremiumPlayerKit.round(theme.surfaceElevated, theme.cardStroke, 24, 1));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(kit.icon(R.drawable.tp_ic_equalizer, pal.accent), new LinearLayout.LayoutParams(TelePremiumPlayerKit.dp(26), TelePremiumPlayerKit.dp(26)));
        TextView title = kit.text(t("TP_FX_TITLE", R.string.TP_FX_TITLE), 20, pal.text, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = TelePremiumPlayerKit.dp(12);
        header.addView(title, titleLp);
        TextView reset = kit.text(t("TP_FX_RESET", R.string.TP_FX_RESET), 14, pal.accent, false);
        reset.setPadding(TelePremiumPlayerKit.dp(10), TelePremiumPlayerKit.dp(8), TelePremiumPlayerKit.dp(4), TelePremiumPlayerKit.dp(8));
        reset.setOnClickListener(v -> { TelePremiumAudioEffects.resetAll(); dialog.dismiss(); show(activity, theme); });
        header.addView(reset);
        content.addView(header, new LinearLayout.LayoutParams(-1, TelePremiumPlayerKit.dp(52)));

        // Equalizer
        boolean attached = TelePremiumAudioEffects.isAttached();
        content.addView(switchRow(activity, kit, pal, t("TP_FX_EQ", R.string.TP_FX_EQ), TelePremiumAudioEffects.isEqEnabled(),
                TelePremiumAudioEffects::setEqEnabled));
        if (!attached) {
            TextView hint = kit.text(t("TP_FX_UNAVAILABLE", R.string.TP_FX_UNAVAILABLE), 13, pal.muted, false);
            hint.setPadding(0, TelePremiumPlayerKit.dp(6), 0, TelePremiumPlayerKit.dp(10));
            content.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        } else {
            String[] presets = TelePremiumAudioEffects.getPresetNames();
            if (presets.length > 0) {
                LinearLayout chips = new LinearLayout(activity);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(activity);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(chips);
                int selected = TelePremiumAudioEffects.getPreset();
                for (int i = -1; i < presets.length; i++) {
                    final int preset = i;
                    boolean on = preset == selected;
                    TextView chip = kit.text(preset < 0 ? t("TP_FX_CUSTOM", R.string.TP_FX_CUSTOM) : presets[i], 13, on ? pal.accent : pal.text, on);
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(TelePremiumPlayerKit.dp(14), 0, TelePremiumPlayerKit.dp(14), 0);
                    chip.setBackground(TelePremiumPlayerKit.round(on ? theme.chipSelectedBackground : theme.chipBackground, on ? pal.accent : theme.cardStroke, 16, 1));
                    chip.setOnClickListener(v -> {
                        if (preset >= 0) TelePremiumAudioEffects.usePreset(preset);
                        dialog.dismiss();
                        show(activity, theme);
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, TelePremiumPlayerKit.dp(34));
                    lp.rightMargin = TelePremiumPlayerKit.dp(8);
                    chips.addView(chip, lp);
                }
                LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
                hsLp.setMargins(0, TelePremiumPlayerKit.dp(4), 0, TelePremiumPlayerKit.dp(8));
                content.addView(hs, hsLp);
            }
            int[] range = TelePremiumAudioEffects.getBandRange();
            int bands = TelePremiumAudioEffects.getBandCount();
            for (int b = 0; b < bands; b++) {
                final int band = b;
                int hz = TelePremiumAudioEffects.getBandFrequency(b);
                String label = hz >= 1000 ? String.format(Locale.ROOT, "%.1f kHz", hz / 1000f).replace(".0 ", " ") : hz + " Hz";
                content.addView(sliderRow(activity, kit, pal, label, range[1] - range[0], TelePremiumAudioEffects.getBandLevel(b) - range[0],
                        value -> String.format(Locale.ROOT, "%+.1f dB", (value + range[0]) / 100f),
                        value -> TelePremiumAudioEffects.setBandLevel(band, value + range[0])));
            }
        }

        addSection(content, kit, pal, t("TP_FX_EFFECTS", R.string.TP_FX_EFFECTS));
        content.addView(sliderRow(activity, kit, pal, t("TP_FX_BASS", R.string.TP_FX_BASS), 1000, TelePremiumAudioEffects.getBass(),
                value -> value / 10 + "%", TelePremiumAudioEffects::setBass));
        content.addView(sliderRow(activity, kit, pal, t("TP_FX_VIRT", R.string.TP_FX_VIRT), 1000, TelePremiumAudioEffects.getVirtualizer(),
                value -> value / 10 + "%", TelePremiumAudioEffects::setVirtualizer));
        content.addView(sliderRow(activity, kit, pal, t("TP_FX_LOUD", R.string.TP_FX_LOUD), 1500, TelePremiumAudioEffects.getLoudness(),
                value -> String.format(Locale.ROOT, "+%.1f dB", value / 100f), TelePremiumAudioEffects::setLoudness));

        addSection(content, kit, pal, t("TP_FX_STEREO", R.string.TP_FX_STEREO));
        content.addView(sliderRow(activity, kit, pal, t("TP_FX_BALANCE", R.string.TP_FX_BALANCE), 200, TelePremiumAudioEffects.getBalance() + 100,
                value -> {
                    int b = value - 100;
                    if (b == 0) return "L = R";
                    return b < 0 ? t("TP_FX_LEFT", R.string.TP_FX_LEFT) + " " + (-b) + "%" : t("TP_FX_RIGHT", R.string.TP_FX_RIGHT) + " " + b + "%";
                }, value -> TelePremiumAudioEffects.setBalance(value - 100)));
        content.addView(switchRow(activity, kit, pal, t("TP_FX_MONO", R.string.TP_FX_MONO), TelePremiumAudioEffects.getMono(), TelePremiumAudioEffects::setMono));

        dialog.setContentView(scroll);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(-1, -2);
                window.setGravity(Gravity.BOTTOM);
            }
        });
        dialog.show();
    }

    interface IntConsumer { void accept(int value); }
    interface BoolConsumer { void accept(boolean value); }
    interface IntLabel { String label(int value); }

    private static void addSection(LinearLayout content, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String title) {
        TextView header = kit.text(title, 14, pal.accent, true);
        header.setPadding(0, TelePremiumPlayerKit.dp(18), 0, TelePremiumPlayerKit.dp(6));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private static View switchRow(Activity activity, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String label, boolean checked, BoolConsumer consumer) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text = kit.text(label, 15, pal.text, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(activity);
        sw.setChecked(checked);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(checked ? pal.accent : Color.LTGRAY));
        sw.setOnCheckedChangeListener((b, value) -> {
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(value ? pal.accent : Color.LTGRAY));
            consumer.accept(value);
        });
        row.addView(sw);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, TelePremiumPlayerKit.dp(48)));
        return row;
    }

    private static View sliderRow(Activity activity, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String label, int max, int value, IntLabel format, IntConsumer consumer) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(kit.text(label, 13, pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView valueView = kit.text(format.label(value), 13, pal.muted, false);
        top.addView(valueView);
        box.addView(top, new LinearLayout.LayoutParams(-1, -2));
        SeekBar bar = new SeekBar(activity);
        bar.setMax(Math.max(1, max));
        bar.setProgress(Math.max(0, Math.min(max, value)));
        kit.tintSeekBar(bar, pal);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueView.setText(format.label(progress));
                if (fromUser) consumer.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(bar, new LinearLayout.LayoutParams(-1, TelePremiumPlayerKit.dp(36)));
        box.setPadding(0, TelePremiumPlayerKit.dp(4), 0, TelePremiumPlayerKit.dp(2));
        return box;
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
