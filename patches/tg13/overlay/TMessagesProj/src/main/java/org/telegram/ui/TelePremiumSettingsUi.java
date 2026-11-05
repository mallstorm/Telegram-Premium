package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * One visual language for every TelePremium settings screen: a hero card, grouped rounded
 * cards, rows with a colored icon tile, a title and a two-line description, switches and
 * segmented chips. Colors come from the active Telegram theme (built from the TelePremium
 * preset), so every screen follows theme switches, dark themes included.
 */
final class TelePremiumSettingsUi {
    static final int BLUE = 0xff2f80ed, GREEN = 0xff20a464, PINK = 0xffe5396f, SKY = 0xff2d9cdb, ORANGE = 0xfff2994a,
            RED = 0xffe5484d, PURPLE = 0xff8e5cf7, TEAL = 0xff0fa5b5, AMBER = 0xfff5a623, MINT = 0xff00a884,
            INDIGO = 0xff5b6cf0, SLATE = 0xff607d8b;

    interface IntCallback { void run(int index); }

    private final BaseFragment fragment;
    private final Context context;
    LinearLayout content;
    private LinearLayout card;

    TelePremiumSettingsUi(BaseFragment fragment, Context context) {
        this.fragment = fragment;
        this.context = context;
    }

    static int dp(float v) { return AndroidUtilities.dp(v); }

    int color(int key) { return fragment.getThemedColor(key); }

    int accent() { return color(Theme.key_featuredStickers_addButton); }

    ScrollView createScreen() {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    void clear() {
        if (content != null) content.removeAllViews();
        card = null;
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    TextView text(CharSequence value, float sp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(AndroidUtilities.bold());
        return v;
    }

    Drawable tinted(int res, int color) {
        Drawable d = context.getResources().getDrawable(res).mutate();
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return d;
    }

    /** Colored rounded square with a white glyph. */
    View tile(int icon, int tileColor, int sizeDp) {
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(round(tileColor, sizeDp * 0.28f));
        ImageView glyph = new ImageView(context);
        glyph.setImageDrawable(tinted(icon, Color.WHITE));
        int g = Math.round(sizeDp * 0.55f);
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(g), dp(g), Gravity.CENTER));
        return tile;
    }

    /** Gradient hero card in the theme accent. {@code icon} 0 = application icon. */
    View hero(String title, String subtitle, String badge, int icon) {
        int accent = accent();
        LinearLayout hero = new LinearLayout(context);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{accent, Theme.adaptHSV(accent, -0.05f, -0.18f)});
        bg.setCornerRadius(dp(22));
        hero.setBackground(bg);
        if (icon == 0) {
            ImageView logo = new ImageView(context);
            try {
                logo.setImageDrawable(context.getPackageManager().getApplicationIcon(context.getPackageName()));
            } catch (Exception e) {
                logo.setImageResource(R.drawable.tp_ic_star);
            }
            logo.setBackground(round(0x33ffffff, 18));
            logo.setPadding(dp(6), dp(6), dp(6), dp(6));
            hero.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        } else {
            hero.addView(tile(icon, 0x33ffffff, 60), new LinearLayout.LayoutParams(dp(60), dp(60)));
        }
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 21, Color.WHITE, true));
        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = text(subtitle, 14, 0xe6ffffff, false);
            s.setPadding(0, dp(3), 0, 0);
            s.setMaxLines(3);
            texts.addView(s);
        }
        if (!TextUtils.isEmpty(badge)) {
            TextView b = text(badge, 12, Color.WHITE, true);
            b.setBackground(round(0x33ffffff, 10));
            b.setPadding(dp(9), dp(3), dp(9), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(8);
            texts.addView(b, lp);
        }
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textsLp.leftMargin = dp(16);
        hero.addView(texts, textsLp);
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        return hero;
    }

    /** Section header (optional) and a new rounded card that following rows go into. */
    LinearLayout section(String title) {
        if (!TextUtils.isEmpty(title)) {
            TextView header = text(title, 14, color(Theme.key_windowBackgroundWhiteBlueHeader), true);
            header.setPadding(dp(8), dp(22), dp(8), dp(8));
            content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        } else {
            View gap = new View(context);
            content.addView(gap, new LinearLayout.LayoutParams(-1, dp(14)));
        }
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(color(Theme.key_windowBackgroundWhite), 18));
        card.setClipToOutline(true);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** The card rows go into; a new one starts when something else (e.g. a note) was added after it. */
    private LinearLayout card() {
        if (card == null || content.getChildCount() == 0 || content.getChildAt(content.getChildCount() - 1) != card) section(null);
        return card;
    }

    private void divider() {
        if (card().getChildCount() == 0) return;
        View divider = new View(context);
        divider.setBackgroundColor(color(Theme.key_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(68);
        card.addView(divider, lp);
    }

    private LinearLayout baseRow(View leading, String title, String subtitle) {
        divider();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));
        if (leading != null) row.addView(leading, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 16, color(Theme.key_windowBackgroundWhiteBlackText), true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = text(subtitle, 13, color(Theme.key_windowBackgroundWhiteGrayText2), false);
            sub.setMaxLines(3);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(3), 0, 0);
            texts.addView(sub);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(leading == null ? dp(4) : dp(14), 0, dp(6), 0);
        row.addView(texts, lp);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** Navigation / action row; {@code value} (optional) is shown in accent at the end. */
    LinearLayout row(String title, String subtitle, int icon, int tileColor, String value, View.OnClickListener listener) {
        return row(icon == 0 ? null : tile(icon, tileColor, 40), title, subtitle, value, listener);
    }

    LinearLayout row(View leading, String title, String subtitle, String value, View.OnClickListener listener) {
        LinearLayout row = baseRow(leading, title, subtitle);
        if (!TextUtils.isEmpty(value)) {
            TextView v = text(value, 14, accent(), true);
            v.setSingleLine(true);
            v.setPadding(0, 0, dp(4), 0);
            row.addView(v);
        }
        if (listener != null) {
            ImageView chevron = new ImageView(context);
            chevron.setImageDrawable(tinted(R.drawable.tp_ic_chevron_right, color(Theme.key_windowBackgroundWhiteGrayIcon)));
            row.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(22)));
            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                listener.onClick(v);
            });
        } else {
            row.setBackground(null);
        }
        return row;
    }

    Switch switchRow(String title, String subtitle, int icon, int tileColor, boolean checked, boolean enabled, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = baseRow(icon == 0 ? null : tile(icon, tileColor, 40), title, subtitle);
        Switch sw = new Switch(context);
        sw.setChecked(checked);
        sw.setEnabled(enabled);
        sw.setClickable(false);
        sw.setFocusable(false);
        tintSwitch(sw, checked, enabled);
        sw.setOnCheckedChangeListener((button, value) -> {
            tintSwitch(sw, value, enabled);
            listener.onCheckedChanged(button, value);
        });
        row.addView(sw);
        row.setAlpha(enabled ? 1f : 0.45f);
        row.setOnClickListener(v -> {
            if (!enabled) return;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sw.setChecked(!sw.isChecked());
        });
        return sw;
    }

    private void tintSwitch(Switch sw, boolean checked, boolean enabled) {
        if (Build.VERSION.SDK_INT < 21) return;
        int accent = accent();
        int off = color(Theme.key_windowBackgroundWhiteGrayIcon);
        sw.setThumbTintList(ColorStateList.valueOf(checked && enabled ? accent : off));
        sw.setTrackTintList(ColorStateList.valueOf(checked ? (accent & 0x00ffffff) | 0x80000000 : (off & 0x00ffffff) | 0x50000000));
    }

    /** Segmented choice inside the current card: label above, chips below. */
    void chips(String title, String subtitle, int icon, int tileColor, String[] labels, int selected, IntCallback onSelect) {
        LinearLayout row = baseRow(icon == 0 ? null : tile(icon, tileColor, 40), title, subtitle);
        row.setBackground(null);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        scroll.addView(chips);
        int accent = accent();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView chip = text(labels[i], 14, on ? Color.WHITE : color(Theme.key_windowBackgroundWhiteBlackText), on);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable bg = round(on ? accent : color(Theme.key_windowBackgroundGray), 16);
            chip.setBackground(bg);
            chip.setOnClickListener(v -> {
                if (index == selected) return;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onSelect.run(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        LinearLayout holder = new LinearLayout(context);
        holder.setPadding(dp(68), 0, dp(12), dp(12));
        holder.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        card.addView(holder, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Explanatory text under a card. */
    TextView note(String text) {
        TextView v = text(text, 13, color(Theme.key_windowBackgroundWhiteGrayText), false);
        v.setPadding(dp(10), dp(8), dp(10), dp(4));
        v.setLineSpacing(dp(2), 1f);
        content.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    /** Full-width outlined button at the end of a screen. */
    TextView button(String label, boolean destructive, View.OnClickListener listener) {
        TextView v = text(label, 15, destructive ? color(Theme.key_text_RedRegular) : accent(), true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = round(color(Theme.key_windowBackgroundWhite), 16);
        v.setBackground(bg);
        v.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(18);
        content.addView(v, lp);
        return v;
    }
}
