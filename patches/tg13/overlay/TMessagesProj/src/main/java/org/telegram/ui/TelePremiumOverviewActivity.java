package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

public class TelePremiumOverviewActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("TelePremium");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(28), AndroidUtilities.dp(20), AndroidUtilities.dp(30));
        GradientDrawable heroBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(77, 139, 255), Color.rgb(133, 88, 255), Color.rgb(219, 83, 195)}
        );
        heroBackground.setCornerRadius(AndroidUtilities.dp(24));
        hero.setBackground(heroBackground);

        ImageView star = new ImageView(context);
        star.setImageResource(R.drawable.menu_premium_main);
        star.setColorFilter(Color.WHITE);
        hero.addView(star, new LinearLayout.LayoutParams(AndroidUtilities.dp(92), AndroidUtilities.dp(92)));

        TextView title = new TextView(context);
        title.setText("TelePremium");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = AndroidUtilities.dp(12);
        hero.addView(title, titleParams);

        TextView subtitle = new TextView(context);
        subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_378));
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xEEFFFFFF);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = AndroidUtilities.dp(8);
        hero.addView(subtitle, subtitleParams);

        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        heroParams.setMargins(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(14));
        content.addView(hero, heroParams);

        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_379), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_380), R.drawable.menu_premium_main,
                v -> presentFragment(new PeerColorActivity(0)));
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_381), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_382), R.drawable.msg_smile_status,
                v -> openSelfProfile());
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_383), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_384), R.drawable.msg_reactions,
                v -> presentFragment(new ReactionsDoubleTapManageActivity()));
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_385), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_386), R.drawable.menu_premium_main, null);
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_387), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_388), R.drawable.msg2_folder,
                v -> presentFragment(new TelePremiumFileCenterActivity()));
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_389), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_390), R.drawable.msg2_devices,
                v -> presentFragment(new TelePremiumAccountCenterActivity()));
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_391), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_392), R.drawable.msg2_data, null);
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_393), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_394), R.drawable.msg2_language,
                v -> presentFragment(new LanguageSelectActivity()));
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_395), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_396), R.drawable.msg2_discussion, null);
        addFeature(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_397), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_398), R.drawable.menu_premium_main,
                v -> presentFragment(new TelePremiumCenterActivity()));

        fragmentView = scroll;
        return fragmentView;
    }

    private void openSelfProfile() {
        Bundle args = new Bundle();
        args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
        presentFragment(new ProfileActivity(args));
    }

    private void addFeature(LinearLayout parent, Context context, String titleText, String valueText, int iconRes, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        background.setCornerRadius(AndroidUtilities.dp(18));
        card.setBackground(background);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlueIcon));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        iconBg.setCornerRadius(AndroidUtilities.dp(14));
        icon.setBackground(iconBg);
        icon.setPadding(AndroidUtilities.dp(11), AndroidUtilities.dp(11), AndroidUtilities.dp(11), AndroidUtilities.dp(11));
        card.addView(icon, new LinearLayout.LayoutParams(AndroidUtilities.dp(52), AndroidUtilities.dp(52)));

        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textBoxParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textBoxParams.leftMargin = AndroidUtilities.dp(14);
        card.addView(textBox, textBoxParams);

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textBox.addView(title);

        TextView value = new TextView(context);
        value.setText(valueText);
        value.setTextSize(14);
        value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        value.setLineSpacing(AndroidUtilities.dp(1), 1.0f);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        valueParams.topMargin = AndroidUtilities.dp(3);
        textBox.addView(value, valueParams);

        if (listener != null) {
            TextView arrow = new TextView(context);
            arrow.setText("›");
            arrow.setTextSize(28);
            arrow.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            card.addView(arrow, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(24),
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            card.setOnClickListener(listener);
            card.setClickable(true);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = AndroidUtilities.dp(10);
        parent.addView(card, params);
    }
}
