package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.cloud.TelePremiumPatternView;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

public class TelePremiumPatternSetupActivity extends BaseFragment {
    private final Runnable onSaved;
    private String firstPattern;
    private TextView title;
    private TextView subtitle;
    private TelePremiumPatternView patternView;

    public TelePremiumPatternSetupActivity() {
        this(null);
    }

    public TelePremiumPatternSetupActivity(Runnable onSaved) {
        this.onSaved = onSaved;
    }

    @Override
    public View createView(Context context) {
        TelePremiumThemePreset theme = TelePremiumThemeController.getCurrent(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_261));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(22), AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        root.setBackgroundColor(theme.background);

        title = new TextView(context);
        title.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_399));
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(theme.textPrimary);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(42)));

        subtitle = new TextView(context);
        subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_400));
        subtitle.setTextSize(14);
        subtitle.setTextColor(theme.textSecondary);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(50));
        slp.setMargins(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        root.addView(subtitle, slp);

        patternView = new TelePremiumPatternView(context);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(theme.surfaceElevated);
        panel.setCornerRadius(AndroidUtilities.dp(22));
        panel.setStroke(Math.max(1, AndroidUtilities.dp(1)), theme.divider);
        patternView.setBackground(panel);
        patternView.setListener(this::onPattern);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(AndroidUtilities.dp(260), AndroidUtilities.dp(260));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.setMargins(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(18));
        root.addView(patternView, pp);

        TextView hint = new TextView(context);
        hint.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_401));
        hint.setTextSize(12);
        hint.setTextColor(theme.textSecondary);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        fragmentView = root;
        return fragmentView;
    }

    private void onPattern(String pattern) {
        if (pattern == null || pattern.length() < 4) {
            subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_402));
            patternView.showError();
            return;
        }
        if (firstPattern == null) {
            firstPattern = pattern;
            title.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_403));
            subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_404));
            patternView.clearPattern();
            return;
        }
        if (!firstPattern.equals(pattern)) {
            firstPattern = null;
            title.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_405));
            subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_406));
            patternView.showError();
            return;
        }
        Context context = getParentActivity();
        if (context == null) return;
        subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_PatternChecking));
        TelePremiumCloudSecurityStore.setPatternAsync(context, pattern, ok -> {
            Context current = getParentActivity();
            if (current == null) return;
            if (!ok) {
                firstPattern = null;
                title.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_407));
                subtitle.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_408));
                patternView.showError();
                return;
            }
            TelePremiumCloudSecurityStore.setProtectionEnabled(current, true);
            if (onSaved != null) onSaved.run();
            finishFragment();
        });
    }
}
