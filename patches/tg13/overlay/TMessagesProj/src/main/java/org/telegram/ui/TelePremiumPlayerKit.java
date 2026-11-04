package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

/**
 * Shared building blocks for the TelePremium player and media library: vector icon buttons,
 * outlined pills, the round play button and a palette derived from the active TelePremium
 * theme (so the player follows theme switches instead of a fixed violet skin).
 */
final class TelePremiumPlayerKit {

    /** Colors for one surface of the player. */
    static final class Palette {
        final int background, panel, stroke, accent, text, muted, onAccent, track, buffered, scrim;

        private Palette(int background, int panel, int stroke, int accent, int text, int muted, int onAccent, int track, int buffered, int scrim) {
            this.background = background;
            this.panel = panel;
            this.stroke = stroke;
            this.accent = accent;
            this.text = text;
            this.muted = muted;
            this.onAccent = onAccent;
            this.track = track;
            this.buffered = buffered;
            this.scrim = scrim;
        }

        /** Portrait player and media library: the theme's own surfaces. */
        static Palette surface(TelePremiumMediaTheme t) {
            return new Palette(t.background, t.cardBackground, t.cardStroke, t.accent, t.textPrimary, t.textSecondary,
                    t.onAccent, t.timelineTrack, t.withAlpha(t.accent, 0x66), t.withAlpha(t.background, 0xcc));
        }

        /** Full-screen video: neutral black backdrop, accent from the theme. */
        static Palette video(TelePremiumMediaTheme t) {
            int accent = t.accent;
            return new Palette(Color.BLACK, 0x8c101014, withAlpha(accent, 0xb0), accent, 0xfff7f7fa, 0xccffffff,
                    t.onAccent, 0x55ffffff, withAlpha(accent, 0x70), 0xb0000000);
        }
    }

    private final Context context;

    TelePremiumPlayerKit(Context context) {
        this.context = context;
    }

    static int dp(float v) { return AndroidUtilities.dp(v); }

    static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    static GradientDrawable round(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    static GradientDrawable oval(int fill, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 0x33)), content, null);
    }

    Drawable tinted(int res, int color) {
        Drawable d = context.getResources().getDrawable(res).mutate();
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return d;
    }

    ImageView icon(int res, int color) {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setImageDrawable(tinted(res, color));
        return v;
    }

    /** Square touch target with a centered icon of {@code iconDp}. */
    ImageView iconButton(int res, int color, int iconDp, String description, View.OnClickListener listener) {
        ImageView v = icon(res, color);
        int boxPad = dp(Math.max(0, (44 - iconDp) / 2f));
        v.setPadding(boxPad, boxPad, boxPad, boxPad);
        v.setBackground(ripple(oval(Color.TRANSPARENT, Color.TRANSPARENT, 0), color));
        v.setContentDescription(description);
        v.setOnClickListener(listener);
        return v;
    }

    void setIcon(ImageView view, int res, int color) {
        if (view != null) view.setImageDrawable(tinted(res, color));
    }

    TextView text(CharSequence value, float sp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    TextView singleLine(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    /** Outlined pill with an optional leading icon; wraps its content (never clipped). */
    LinearLayout pill(int iconRes, CharSequence label, Palette p, boolean accentText) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(7), dp(16), dp(7));
        pill.setBackground(round(p.panel, p.stroke, 20, 1));
        if (iconRes != 0) {
            ImageView i = icon(iconRes, accentText ? p.accent : p.muted);
            pill.addView(i, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        TextView t = singleLine(text(label, 13, accentText ? p.accent : p.muted, false));
        t.setTag("label");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = iconRes != 0 ? dp(8) : 0;
        pill.addView(t, lp);
        return pill;
    }

    static void setPillText(LinearLayout pill, CharSequence label) {
        if (pill == null) return;
        View v = pill.findViewWithTag("label");
        if (v instanceof TextView) ((TextView) v).setText(label);
    }

    /** Round play/pause button: theme-tinted fill, accent ring, accent glyph. */
    ImageView playButton(Palette p, int sizeDp, View.OnClickListener listener) {
        ImageView v = icon(org.telegram.messenger.R.drawable.tp_ic_play, p.text);
        int pad = dp(sizeDp * 0.27f);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(ripple(oval(withAlpha(p.accent, 0x22), p.accent, 2.5f), p.accent));
        v.setOnClickListener(listener);
        return v;
    }

    /** Outlined rounded square used for the landscape tool buttons. */
    ImageView toolButton(int res, Palette p, String description, View.OnClickListener listener) {
        ImageView v = icon(res, p.text);
        v.setPadding(dp(13), dp(11), dp(13), dp(11));
        v.setBackground(ripple(round(p.panel, p.stroke, 16, 1), p.accent));
        v.setContentDescription(description);
        v.setOnClickListener(listener);
        return v;
    }

    void tintSeekBar(SeekBar bar, Palette p) {
        bar.setProgressTintList(ColorStateList.valueOf(p.accent));
        bar.setThumbTintList(ColorStateList.valueOf(p.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(p.track));
        bar.setSecondaryProgressTintList(ColorStateList.valueOf(p.buffered));
        bar.setSplitTrack(false);
    }

    View divider(int color, boolean vertical) {
        View v = new View(context);
        v.setBackgroundColor(color);
        v.setLayoutParams(vertical ? new LinearLayout.LayoutParams(dp(1), dp(36)) : new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    /** Wraps a view so it is centered inside a weighted slot. */
    FrameLayout slot(View child, int widthDp, int heightDp) {
        FrameLayout f = new FrameLayout(context);
        f.addView(child, new FrameLayout.LayoutParams(dp(widthDp), dp(heightDp), Gravity.CENTER));
        return f;
    }
}
