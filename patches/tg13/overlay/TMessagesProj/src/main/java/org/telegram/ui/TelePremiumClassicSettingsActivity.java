package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stars.StarsIntroActivity;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;

/** Classic Telegram 11 settings presentation routing into Telegram 13 handlers. */
public final class TelePremiumClassicSettingsActivity extends BaseFragment {
    private LinearLayout body;

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(getThemedColor(Theme.key_actionBarDefault));
        actionBar.setTitle("Настройки");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout screen = new FrameLayout(context);
        screen.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        ScrollView scrolling = new ScrollView(context);
        scrolling.setFillViewport(true);
        screen.addView(scrolling, new FrameLayout.LayoutParams(-1, -1));
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scrolling.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout profile = new LinearLayout(context);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(24), 0, dp(24), 0);
        profile.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        TextView avatar = new TextView(context);
        avatar.setText("◉");
        avatar.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        profile.addView(avatar, new LinearLayout.LayoutParams(dp(55), dp(72)));
        TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        TextView name = new TextView(context);
        name.setText(user == null ? "Telegram" : UserObject.getUserName(user));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        profile.addView(name, new LinearLayout.LayoutParams(-1, dp(72)));
        profile.setOnClickListener(v -> presentFragment(new UserInfoActivity()));
        body.addView(profile);

        section("Учётная запись");
        row("Мой профиль", android.R.drawable.ic_menu_myplaces, null, () -> presentFragment(new UserInfoActivity()));
        row("Настройки чатов", android.R.drawable.ic_menu_edit, null, () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        row("Конфиденциальность", android.R.drawable.ic_lock_lock, null, () -> presentFragment(new PrivacySettingsActivity()));
        row("Уведомления и звуки", android.R.drawable.ic_lock_silent_mode_off, null, () -> presentFragment(new NotificationsSettingsActivity()));
        row("Данные и память", android.R.drawable.ic_menu_save, null, () -> presentFragment(new DataSettingsActivity()));
        row("Энергосбережение", android.R.drawable.ic_lock_idle_charging, null, () -> presentFragment(new LiteModeSettingsActivity()));
        row("Папки с чатами", android.R.drawable.ic_menu_agenda, null, () -> presentFragment(new FiltersSetupActivity()));
        row("Устройства", android.R.drawable.ic_menu_manage, null, () -> presentFragment(new SessionsActivity(0)));
        row("Язык", android.R.drawable.ic_menu_set_as, LocaleController.getCurrentLanguageName(), () -> presentFragment(new LanguageSelectActivity()));

        section("Telegram и TelePremium");
        row("Telegram Premium", android.R.drawable.btn_star_big_on, null, () -> presentFragment(new PremiumPreviewFragment("settings")));
        row("Мои звёзды", android.R.drawable.btn_star_big_on, null, () -> presentFragment(new StarsIntroActivity()));
        row("Облако", android.R.drawable.ic_menu_upload, null, () -> TelePremiumCloudAccessGate.openCloud(this, currentAccount));
        row("Медиатека", android.R.drawable.ic_media_play, null, () -> {
            if (getParentActivity() != null) getParentActivity().startActivity(new Intent(getParentActivity(), TelePremiumMediaCenterActivity.class));
        });
        row("Центр TelePremium", android.R.drawable.ic_menu_manage, null, () -> presentFragment(new TelePremiumCenterActivity()));
        row("Оформление TelePremium", android.R.drawable.ic_menu_edit, null, () -> presentFragment(new TelePremiumAppearanceActivity()));
        row("Интерфейс приложения", android.R.drawable.ic_menu_preferences, "Классический 11.x", () -> presentFragment(new TelePremiumInterfaceModeActivity()));

        section("Помощь");
        row("Задать вопрос", android.R.drawable.ic_dialog_email, null, () -> presentFragment(new SettingsActivity()));
        row("Полные настройки Telegram", android.R.drawable.ic_menu_preferences, null, () -> presentFragment(new SettingsActivity()));
        TextView footer = new TextView(context);
        footer.setText("TelePremium · Telegram 13.1.1 · интерфейс 11.x");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        footer.setGravity(Gravity.CENTER);
        body.addView(footer, new LinearLayout.LayoutParams(-1, dp(54)));
        fragmentView = screen;
        return fragmentView;
    }

    private int dp(float value) { return AndroidUtilities.dp(value); }

    private void section(String title) {
        LinearLayout spacer = new LinearLayout(getContext());
        spacer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        body.addView(spacer, new LinearLayout.LayoutParams(-1, dp(18)));
        TextView heading = new TextView(getContext());
        heading.setText(title);
        heading.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        heading.setPadding(dp(24), 0, dp(8), 0);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        body.addView(heading, new LinearLayout.LayoutParams(-1, dp(37)));
    }

    private void row(String title, int image, String value, Runnable action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(27), 0, dp(24), 0);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(image);
        icon.setColorFilter(getThemedColor(Theme.key_chats_menuItemIcon));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -1, 1);
        textLp.leftMargin = dp(26);
        row.addView(label, textLp);
        if (!TextUtils.isEmpty(value)) {
            TextView side = new TextView(getContext());
            side.setText(value);
            side.setSingleLine(true);
            side.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            side.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            row.addView(side);
        }
        row.setOnClickListener(v -> action.run());
        body.addView(row, new LinearLayout.LayoutParams(-1, dp(55)));
        View divider = new View(getContext());
        divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, dp(1));
        lineLp.leftMargin = dp(83);
        body.addView(divider, lineLp);
    }
}
