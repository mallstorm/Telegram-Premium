package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TelePremiumUiPrefs;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;

/** Two renderers, one Telegram 13 backend. Classic is the default preference. */
public final class TelePremiumInterfaceModeActivity extends BaseFragment {
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Интерфейс");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        TextView explanation = new TextView(context);
        explanation.setText("Оба интерфейса используют Telegram 13.1.1, те же аккаунты, сообщения, Cloud Vault и настройки. Изменяется только оболочка.");
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        explanation.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        explanation.setPadding(dp(24), dp(20), dp(24), dp(20));
        body.addView(explanation);
        addChoice(body, context, "Классический Telegram 11.x", "Боковое меню, отдельный список тем и классические настройки", true);
        addChoice(body, context, "Современный Telegram 13.1.1", "Нижняя навигация, новые настройки и вкладки тем в поддерживаемых группах", false);
        fragmentView = body;
        return fragmentView;
    }

    private int dp(float v) { return AndroidUtilities.dp(v); }

    private void addChoice(LinearLayout body, Context context, String title, String subtitle, boolean classic) {
        LinearLayout cell = new LinearLayout(context);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(22), dp(16), dp(20), dp(16));
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView desc = new TextView(context);
        desc.setText(subtitle);
        desc.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(heading);
        texts.addView(desc);
        cell.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        RadioButton radio = new RadioButton(context);
        radio.setClickable(false);
        radio.setChecked(TelePremiumUiPrefs.classicUiEnabled() == classic);
        cell.addView(radio);
        cell.setOnClickListener(v -> {
            if (TelePremiumUiPrefs.classicUiEnabled() == classic) return;
            TelePremiumUiPrefs.setClassicUiEnabled(classic);
            Activity activity = getParentActivity();
            if (activity != null) {
                // Reapply the same saved Telegram theme to whichever UI shell is selected.
                TelePremiumThemeController.ensureShellTheme(activity);
                activity.recreate();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(1);
        body.addView(cell, lp);
    }
}
