package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TelePremiumUiScale;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

public class TelePremiumCenterActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
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
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        scrollView.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView hero = new TextView(context);
        hero.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_046));
        hero.setTextSize(22);
        hero.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hero.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(22), AndroidUtilities.dp(24), AndroidUtilities.dp(22));
        hero.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        addSection(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_047));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_048), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_049), R.drawable.msg2_devices, v -> presentFragment(new TelePremiumAccountCenterActivity()));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_050), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_051), R.drawable.msg2_data, v -> presentFragment(new TelePremiumNetworkCenterActivity()));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_052), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_053), R.drawable.msg2_folder, v -> presentFragment(new TelePremiumFileCenterActivity()));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_054), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_055), R.drawable.msg2_permissions, v -> presentFragment(new TelePremiumCloudSecurityActivity()));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_056), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_057), R.drawable.msg2_language, v -> presentFragment(new LanguageSelectActivity()));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_058), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_059), R.drawable.msg2_data, v -> presentFragment(new CacheControlActivity()));

        addSection(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_007));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_060), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_061), R.drawable.msg2_discussion, v -> presentFragment(new TelePremiumAppearanceActivity()));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_062), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_063), R.drawable.msg2_discussion, v -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_064), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_065) + TelePremiumUiScale.getScalePercent(context) + "%", R.drawable.msg2_discussion, v -> presentFragment(new TelePremiumAppearanceActivity()));

        addSection(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_066));
        addRow(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_067), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_068), R.drawable.msg2_data, v -> presentFragment(new TelePremiumServiceActivity()));

        fragmentView = scrollView;
        return fragmentView;
    }

    private void addSection(LinearLayout parent, Context context, String title) {
        TextView header = new TextView(context);
        header.setText(title);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(20), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        parent.addView(header);
    }

    private void addRow(LinearLayout parent, Context context, String title, String subtitle, int icon, View.OnClickListener listener) {
        TextCell cell = new TextCell(context, getResourceProvider());
        cell.setTextAndValueAndIcon(title, subtitle, icon, true);
        cell.setOnClickListener(listener);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        parent.addView(cell, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(58)));
    }
}
