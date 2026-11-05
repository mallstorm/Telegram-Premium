package org.telegram.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.media.TelePremiumAudioEffects;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.util.Locale;

/**
 * Sound settings sheet: a real 10-band graphic equalizer (vertical sliders with the response
 * curve), presets, preamp, bass / treble, stereo width, balance, mono, limiter, skip silence
 * and the sleep timer. Everything is processed by TelePremium's own DSP, so it is audible on
 * every device.
 */
final class TelePremiumAudioEffectsDialog {
    private TelePremiumAudioEffectsDialog() {}

    static void show(Activity activity, TelePremiumMediaTheme theme) {
        TelePremiumPlayerKit kit = new TelePremiumPlayerKit(activity);
        TelePremiumPlayerKit.Palette pal = TelePremiumPlayerKit.Palette.surface(theme);
        Dialog dialog = new Dialog(activity);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(22));
        content.setBackground(TelePremiumPlayerKit.round(theme.surfaceElevated, theme.cardStroke, 24, 1));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(kit.icon(R.drawable.tp_ic_equalizer, pal.accent), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = kit.text(t("TP_FX_TITLE", R.string.TP_FX_TITLE), 20, pal.text, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(12);
        header.addView(title, titleLp);
        TextView reset = kit.text(t("TP_FX_RESET", R.string.TP_FX_RESET), 14, pal.accent, false);
        reset.setPadding(dp(10), dp(8), dp(4), dp(8));
        reset.setOnClickListener(v -> { TelePremiumAudioEffects.resetAll(); dialog.dismiss(); show(activity, theme); });
        header.addView(reset);
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));

        // ---- equalizer ----
        EqView eq = new EqView(activity, pal);
        Switch[] eqSwitch = new Switch[1];
        content.addView(switchRow(activity, kit, pal, t("TP_FX_EQ", R.string.TP_FX_EQ), TelePremiumAudioEffects.isEqEnabled(), value -> {
            TelePremiumAudioEffects.setEqEnabled(value);
            eq.invalidate();
        }, eqSwitch));

        LinearLayout chips = new LinearLayout(activity);
        HorizontalScrollView hs = new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        String[] presets = TelePremiumAudioEffects.getPresetNames();
        TextView[] chipViews = new TextView[presets.length];
        Runnable paintChips = () -> {
            int selected = TelePremiumAudioEffects.getPreset();
            for (int i = 0; i < chipViews.length; i++) {
                boolean on = i == selected;
                chipViews[i].setTextColor(on ? pal.accent : pal.text);
                chipViews[i].setBackground(TelePremiumPlayerKit.round(on ? theme.chipSelectedBackground : theme.chipBackground, on ? pal.accent : theme.cardStroke, 16, on ? 2 : 1));
            }
        };
        for (int i = 0; i < presets.length; i++) {
            final int preset = i;
            TextView chip = kit.text(presets[i], 13, pal.text, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                TelePremiumAudioEffects.usePreset(preset);
                if (eqSwitch[0] != null) eqSwitch[0].setChecked(true);
                paintChips.run();
                eq.invalidate();
            });
            chipViews[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        paintChips.run();
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
        hsLp.setMargins(0, dp(4), 0, dp(10));
        content.addView(hs, hsLp);

        eq.listener = () -> {
            if (eqSwitch[0] != null && !eqSwitch[0].isChecked()) eqSwitch[0].setChecked(true);
            paintChips.run();
        };
        eq.setBackground(TelePremiumPlayerKit.round(theme.cardBackground, theme.cardStroke, 18, 1));
        content.addView(eq, new LinearLayout.LayoutParams(-1, dp(230)));

        content.addView(dbSlider(activity, kit, pal, t("TP_FX_PREAMP", R.string.TP_FX_PREAMP), TelePremiumAudioEffects.getPreamp(), TelePremiumAudioEffects::setPreamp));

        addSection(content, kit, pal, t("TP_FX_TONE", R.string.TP_FX_TONE));
        content.addView(dbSlider(activity, kit, pal, t("TP_FX_BASS", R.string.TP_FX_BASS), TelePremiumAudioEffects.getBass(), TelePremiumAudioEffects::setBass));
        content.addView(dbSlider(activity, kit, pal, t("TP_FX_TREBLE", R.string.TP_FX_TREBLE), TelePremiumAudioEffects.getTreble(), TelePremiumAudioEffects::setTreble));

        addSection(content, kit, pal, t("TP_FX_STEREO", R.string.TP_FX_STEREO));
        content.addView(sliderRow(activity, kit, pal, t("TP_FX_WIDTH", R.string.TP_FX_WIDTH), 200, TelePremiumAudioEffects.getWidth(),
                value -> value + "%", TelePremiumAudioEffects::setWidth));
        content.addView(sliderRow(activity, kit, pal, t("TP_FX_BALANCE", R.string.TP_FX_BALANCE), 200, TelePremiumAudioEffects.getBalance() + 100,
                value -> {
                    int b = value - 100;
                    if (b == 0) return "L = R";
                    return b < 0 ? t("TP_FX_LEFT", R.string.TP_FX_LEFT) + " " + (-b) + "%" : t("TP_FX_RIGHT", R.string.TP_FX_RIGHT) + " " + b + "%";
                }, value -> TelePremiumAudioEffects.setBalance(value - 100)));
        content.addView(switchRow(activity, kit, pal, t("TP_FX_MONO", R.string.TP_FX_MONO), TelePremiumAudioEffects.getMono(), TelePremiumAudioEffects::setMono, null));
        content.addView(switchRow(activity, kit, pal, t("TP_FX_LIMITER", R.string.TP_FX_LIMITER), TelePremiumAudioEffects.isLimiter(), TelePremiumAudioEffects::setLimiter, null));

        addSection(content, kit, pal, t("TP_FX_PLAYBACK", R.string.TP_FX_PLAYBACK));
        content.addView(switchRow(activity, kit, pal, t("TP_FX_SKIP_SILENCE", R.string.TP_FX_SKIP_SILENCE), TelePremiumAudioEffects.isSkipSilence(), TelePremiumAudioEffects::setSkipSilence, null));
        content.addView(sleepTimerRow(activity, kit, pal, theme));

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
    interface FloatConsumer { void accept(float value); }
    interface BoolConsumer { void accept(boolean value); }
    interface IntLabel { String label(int value); }

    private static int dp(float v) { return TelePremiumPlayerKit.dp(v); }

    private static void addSection(LinearLayout content, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String title) {
        TextView header = kit.text(title, 14, pal.accent, true);
        header.setPadding(0, dp(18), 0, dp(6));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private static View switchRow(Activity activity, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String label, boolean checked, BoolConsumer consumer, Switch[] out) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(kit.text(label, 15, pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(activity);
        sw.setChecked(checked);
        tintSwitch(sw, pal, checked);
        sw.setOnCheckedChangeListener((b, value) -> {
            tintSwitch(sw, pal, value);
            consumer.accept(value);
        });
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        if (out != null) out[0] = sw;
        return row;
    }

    private static void tintSwitch(Switch sw, TelePremiumPlayerKit.Palette pal, boolean on) {
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(on ? pal.accent : Color.LTGRAY));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(on ? TelePremiumPlayerKit.withAlpha(pal.accent, 0x80) : TelePremiumPlayerKit.withAlpha(pal.muted, 0x60)));
    }

    /** −12 … +12 dB slider in 0.5 dB steps. */
    private static View dbSlider(Activity activity, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String label, float value, FloatConsumer consumer) {
        int max = (int) (TelePremiumAudioEffects.MAX_DB * 4);
        return sliderRow(activity, kit, pal, label, max, Math.round((value + TelePremiumAudioEffects.MAX_DB) * 2),
                v -> TelePremiumAudioEffects.formatDb(v / 2f - TelePremiumAudioEffects.MAX_DB),
                v -> consumer.accept(v / 2f - TelePremiumAudioEffects.MAX_DB));
    }

    private static View sliderRow(Activity activity, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, String label, int max, int value, IntLabel format, IntConsumer consumer) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(kit.text(label, 14, pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
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
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(36)));
        box.setPadding(0, dp(6), 0, dp(2));
        return box;
    }

    private static View sleepTimerRow(Activity activity, TelePremiumPlayerKit kit, TelePremiumPlayerKit.Palette pal, TelePremiumMediaTheme theme) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
        long remaining = controller.getSleepTimerRemaining();
        String state = remaining == TelePremiumPlaybackController.SLEEP_END_OF_TRACK ? t("TP_FX_SLEEP_END", R.string.TP_FX_SLEEP_END)
                : remaining > 0 ? TPStrings.format("TP_FX_SLEEP_LEFT", R.string.TP_FX_SLEEP_LEFT, (int) Math.ceil(remaining / 60000.0)) : t("TP_L10N_451", R.string.TP_L10N_451);
        LinearLayout top = new LinearLayout(activity);
        top.addView(kit.text(t("TP_FX_SLEEP", R.string.TP_FX_SLEEP), 15, pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView stateView = kit.text(state, 13, pal.muted, false);
        top.addView(stateView);
        box.addView(top);
        LinearLayout chips = new LinearLayout(activity);
        HorizontalScrollView hs = new HorizontalScrollView(activity);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        long[] values = {0, 15, 30, 45, 60, 90, TelePremiumPlaybackController.SLEEP_END_OF_TRACK};
        for (long value : values) {
            String label = value == 0 ? t("TP_L10N_451", R.string.TP_L10N_451)
                    : value == TelePremiumPlaybackController.SLEEP_END_OF_TRACK ? t("TP_FX_SLEEP_END", R.string.TP_FX_SLEEP_END)
                    : TPStrings.format("TP_FX_SLEEP_MIN", R.string.TP_FX_SLEEP_MIN, (int) value);
            TextView chip = kit.text(label, 13, pal.text, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(TelePremiumPlayerKit.round(theme.chipBackground, theme.cardStroke, 16, 1));
            chip.setOnClickListener(v -> {
                controller.setSleepTimer(value);
                stateView.setText(label);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-1, -2);
        hsLp.topMargin = dp(8);
        box.addView(hs, hsLp);
        return box;
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }

    /** Ten vertical band sliders with the resulting response curve; drag any column. */
    static final class EqView extends View {
        private final TelePremiumPlayerKit.Palette pal;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int dragging = -1;
        Runnable listener;

        EqView(Context context, TelePremiumPlayerKit.Palette pal) {
            super(context);
            this.pal = pal;
            track.setColor(pal.track);
            track.setStrokeWidth(dp(4));
            track.setStrokeCap(Paint.Cap.ROUND);
            fill.setColor(pal.accent);
            fill.setStrokeWidth(dp(4));
            fill.setStrokeCap(Paint.Cap.ROUND);
            thumb.setColor(pal.accent);
            curve.setColor(pal.accent);
            curve.setStyle(Paint.Style.STROKE);
            curve.setStrokeWidth(dp(2));
            area.setColor(TelePremiumPlayerKit.withAlpha(pal.accent, 0x22));
            label.setColor(pal.muted);
            label.setTextSize(dp(10));
            label.setTextAlign(Paint.Align.CENTER);
            grid.setColor(TelePremiumPlayerKit.withAlpha(pal.muted, 0x40));
            grid.setStrokeWidth(Math.max(1, dp(1)));
        }

        private float top() { return dp(26); }
        private float bottom() { return getHeight() - dp(28); }
        private float columnX(int i) {
            float w = getWidth() - dp(16);
            return dp(8) + w * (i + 0.5f) / TelePremiumAudioEffects.BANDS;
        }
        private float yFor(float db) {
            float t = (TelePremiumAudioEffects.MAX_DB - db) / (2 * TelePremiumAudioEffects.MAX_DB);
            return top() + (bottom() - top()) * t;
        }

        @Override protected void onDraw(Canvas c) {
            boolean on = TelePremiumAudioEffects.isEqEnabled();
            float[] bands = TelePremiumAudioEffects.getBands();
            float zero = yFor(0);
            c.drawLine(dp(8), zero, getWidth() - dp(8), zero, grid);
            label.setTextAlign(Paint.Align.LEFT);
            c.drawText("+" + (int) TelePremiumAudioEffects.MAX_DB, dp(8), top() - dp(10), label);
            label.setTextAlign(Paint.Align.CENTER);
            // response curve through the band points
            path.reset();
            for (int i = 0; i < bands.length; i++) {
                float x = columnX(i), y = yFor(bands[i]);
                if (i == 0) path.moveTo(x, y);
                else {
                    float px = columnX(i - 1), py = yFor(bands[i - 1]);
                    float mx = (px + x) / 2f;
                    path.cubicTo(mx, py, mx, y, x, y);
                }
            }
            if (on) {
                Path fillPath = new Path(path);
                fillPath.lineTo(columnX(bands.length - 1), zero);
                fillPath.lineTo(columnX(0), zero);
                fillPath.close();
                c.drawPath(fillPath, area);
            }
            for (int i = 0; i < bands.length; i++) {
                float x = columnX(i), y = yFor(bands[i]);
                c.drawLine(x, top(), x, bottom(), track);
                if (on) c.drawLine(x, zero, x, y, fill);
                thumb.setColor(on ? pal.accent : pal.muted);
                c.drawCircle(x, y, dp(i == dragging ? 10 : 8), thumb);
                int hz = TelePremiumAudioEffects.FREQUENCIES[i];
                c.drawText(hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz), x, getHeight() - dp(9), label);
                if (i == dragging) c.drawText(String.format(Locale.ROOT, "%+.1f", bands[i]), x, top() - dp(10), label);
            }
            curve.setColor(on ? pal.accent : pal.muted);
            c.drawPath(path, curve);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    float best = Float.MAX_VALUE;
                    for (int i = 0; i < TelePremiumAudioEffects.BANDS; i++) {
                        float d = Math.abs(columnX(i) - e.getX());
                        if (d < best) { best = d; dragging = i; }
                    }
                    getParent().requestDisallowInterceptTouchEvent(true);
                    apply(e.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging >= 0) apply(e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void apply(float y) {
            float t = (y - top()) / Math.max(1f, bottom() - top());
            t = Math.max(0f, Math.min(1f, t));
            float db = TelePremiumAudioEffects.MAX_DB - t * 2 * TelePremiumAudioEffects.MAX_DB;
            db = Math.round(db * 2) / 2f;
            if (Math.abs(db) < 0.5f) db = 0;
            TelePremiumAudioEffects.setBand(dragging, db);
            if (listener != null) listener.run();
            invalidate();
        }

        private int dp(float v) { return TelePremiumPlayerKit.dp(v); }
    }
}
