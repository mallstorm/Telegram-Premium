package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumUiScale;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * TelePremium hub: a hero card and grouped cards. Every row has its own colored icon tile,
 * a title and a two-line description underneath, so nothing is cut off on narrow screens.
 */
public class TelePremiumCenterActivity extends BaseFragment {

    private Context context;

    @Override
    public View createView(Context context) {
        this.context = context;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("TelePremium");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(createHero(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tools = addSection(content, t("TP_L10N_047", R.string.TP_L10N_047));
        addRow(tools, t("TP_L10N_048", R.string.TP_L10N_048), t("TP_L10N_049", R.string.TP_L10N_049), R.drawable.tp_ic_people, 0xff2f80ed,
                v -> presentFragment(new TelePremiumAccountCenterActivity()));
        addRow(tools, t("TP_L10N_050", R.string.TP_L10N_050), t("TP_L10N_051", R.string.TP_L10N_051), R.drawable.tp_ic_public, 0xff20a464,
                v -> presentFragment(new TelePremiumNetworkCenterActivity()));
        addRow(tools, t("TP_L10N_304", R.string.TP_L10N_304), t("TP_C_MEDIA_SUB", R.string.TP_C_MEDIA_SUB), R.drawable.tp_ic_music, 0xffe5396f,
                v -> { if (getParentActivity() != null) getParentActivity().startActivity(new Intent(getParentActivity(), TelePremiumMediaCenterActivity.class)); });
        addRow(tools, t("TP_L10N_069", R.string.TP_L10N_069), t("TP_C_CLOUD_SUB", R.string.TP_C_CLOUD_SUB), R.drawable.tp_ic_cloud, 0xff2d9cdb,
                v -> TelePremiumCloudAccessGate.openCloud(this, currentAccount));
        addRow(tools, t("TP_L10N_052", R.string.TP_L10N_052), t("TP_L10N_053", R.string.TP_L10N_053), R.drawable.tp_ic_folder, 0xfff2994a,
                v -> presentFragment(new TelePremiumFileCenterActivity()));
        addRow(tools, t("TP_L10N_054", R.string.TP_L10N_054), t("TP_L10N_055", R.string.TP_L10N_055), R.drawable.tp_ic_shield, 0xffe5484d,
                v -> presentFragment(new TelePremiumCloudSecurityActivity()));
        addRow(tools, t("TP_L10N_056", R.string.TP_L10N_056), t("TP_L10N_057", R.string.TP_L10N_057), R.drawable.tp_ic_translate, 0xff8e5cf7,
                v -> presentFragment(new LanguageSelectActivity()));
        addRow(tools, t("TP_L10N_058", R.string.TP_L10N_058), t("TP_L10N_059", R.string.TP_L10N_059), R.drawable.tp_ic_storage, 0xff0fa5b5,
                v -> presentFragment(new CacheControlActivity()));

        LinearLayout look = addSection(content, t("TP_L10N_007", R.string.TP_L10N_007));
        addRow(look, t("TP_L10N_060", R.string.TP_L10N_060), t("TP_L10N_061", R.string.TP_L10N_061), R.drawable.tp_ic_palette, 0xfff5a623,
                v -> presentFragment(new TelePremiumAppearanceActivity()));
        addRow(look, t("TP_L10N_062", R.string.TP_L10N_062), t("TP_L10N_063", R.string.TP_L10N_063), R.drawable.tp_ic_chat, 0xff00a884,
                v -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        addRow(look, t("TP_L10N_064", R.string.TP_L10N_064), t("TP_L10N_065", R.string.TP_L10N_065) + TelePremiumUiScale.getScalePercent(context) + "%",
                R.drawable.tp_ic_text_size, 0xff5b6cf0, v -> presentFragment(new TelePremiumAppearanceActivity()));

        LinearLayout service = addSection(content, t("TP_L10N_066", R.string.TP_L10N_066));
        addRow(service, t("TP_L10N_067", R.string.TP_L10N_067), t("TP_L10N_068", R.string.TP_L10N_068), R.drawable.tp_ic_update, 0xff607d8b,
                v -> presentFragment(new TelePremiumServiceActivity()));

        fragmentView = scrollView;
        return fragmentView;
    }

    private View createHero() {
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{accent, Theme.adaptHSV(accent, -0.05f, -0.18f)});
        bg.setCornerRadius(dp(22));
        hero.setBackground(bg);

        ImageView logo = new ImageView(context);
        try {
            logo.setImageDrawable(context.getPackageManager().getApplicationIcon(context.getPackageName()));
        } catch (Exception e) {
            logo.setImageResource(R.drawable.tp_ic_star);
        }
        logo.setBackground(round(0x33ffffff, 18));
        logo.setPadding(dp(6), dp(6), dp(6), dp(6));
        hero.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        String[] heroLines = t("TP_L10N_046", R.string.TP_L10N_046).split("\n", 2);
        TextView title = text(heroLines[0], 22, Color.WHITE, true);
        texts.addView(title);
        if (heroLines.length > 1) {
            TextView tagline = text(heroLines[1], 15, 0xe6ffffff, false);
            tagline.setPadding(0, dp(3), 0, 0);
            texts.addView(tagline);
        }
        TextView version = text("v" + BuildVars.BUILD_VERSION_STRING + " · arm64-v8a", 12, Color.WHITE, true);
        version.setBackground(round(0x33ffffff, 10));
        version.setPadding(dp(9), dp(3), dp(9), dp(3));
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-2, -2);
        versionLp.topMargin = dp(8);
        texts.addView(version, versionLp);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textsLp.leftMargin = dp(16);
        hero.addView(texts, textsLp);
        return hero;
    }

    private LinearLayout addSection(LinearLayout parent, String title) {
        TextView header = text(title, 14, getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader), true);
        header.setPadding(dp(8), dp(22), dp(8), dp(8));
        parent.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(getThemedColor(Theme.key_windowBackgroundWhite), 18));
        card.setClipToOutline(true);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void addRow(LinearLayout card, String title, String subtitle, int icon, int tileColor, View.OnClickListener listener) {
        if (card.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(getThemedColor(Theme.key_divider));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            lp.leftMargin = dp(68);
            card.addView(divider, lp);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(14), dp(11), dp(10), dp(11));
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(listener);

        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(round(tileColor, 11));
        ImageView glyph = new ImageView(context);
        Drawable d = context.getResources().getDrawable(icon).mutate();
        d.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        glyph.setImageDrawable(d);
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        row.addView(tile, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 16, getThemedColor(Theme.key_windowBackgroundWhiteBlackText), true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = text(subtitle, 13, getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), false);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setLineSpacing(dp(1), 1f);
            sub.setPadding(0, dp(3), 0, 0);
            texts.addView(sub);
        }
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textsLp.setMargins(dp(14), 0, dp(6), 0);
        row.addView(texts, textsLp);

        ImageView chevron = new ImageView(context);
        Drawable c = context.getResources().getDrawable(R.drawable.tp_ic_chevron_right).mutate();
        c.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN);
        chevron.setImageDrawable(c);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(22)));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(AndroidUtilities.bold());
        return v;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private static int dp(float v) { return AndroidUtilities.dp(v); }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
