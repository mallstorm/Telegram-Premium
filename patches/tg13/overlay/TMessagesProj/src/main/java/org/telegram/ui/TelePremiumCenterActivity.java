package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumUiScale;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * TelePremium hub: a hero card and grouped cards. Every row has its own colored icon tile,
 * a title and a two-line description underneath, so nothing is cut off on narrow screens.
 */
public class TelePremiumCenterActivity extends BaseFragment {
    private TelePremiumSettingsUi ui;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("TelePremium");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ui = new TelePremiumSettingsUi(this, context);
        fragmentView = ui.createScreen();
        render(context);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ui != null && getParentActivity() != null) render(getParentActivity());
    }

    private void render(Context context) {
        ui.clear();
        String[] heroLines = t("TP_L10N_046", R.string.TP_L10N_046).split("\n", 2);
        ui.hero(heroLines[0], heroLines.length > 1 ? heroLines[1] : null, "v" + BuildVars.BUILD_VERSION_STRING + " · arm64-v8a", 0);

        ui.section(t("TP_L10N_047", R.string.TP_L10N_047));
        ui.row(t("TP_L10N_048", R.string.TP_L10N_048), t("TP_L10N_049", R.string.TP_L10N_049), R.drawable.tp_ic_people, TelePremiumSettingsUi.BLUE, null,
                v -> presentFragment(new TelePremiumAccountCenterActivity()));
        ui.row(t("TP_L10N_050", R.string.TP_L10N_050), t("TP_L10N_051", R.string.TP_L10N_051), R.drawable.tp_ic_public, TelePremiumSettingsUi.GREEN, null,
                v -> presentFragment(new TelePremiumNetworkCenterActivity()));
        ui.row(t("TP_L10N_304", R.string.TP_L10N_304), t("TP_C_MEDIA_SUB", R.string.TP_C_MEDIA_SUB), R.drawable.tp_ic_music, TelePremiumSettingsUi.PINK, null,
                v -> { if (getParentActivity() != null) getParentActivity().startActivity(new Intent(getParentActivity(), TelePremiumMediaCenterActivity.class)); });
        ui.row(t("TP_L10N_069", R.string.TP_L10N_069), t("TP_C_CLOUD_SUB", R.string.TP_C_CLOUD_SUB), R.drawable.tp_ic_cloud, TelePremiumSettingsUi.SKY, null,
                v -> TelePremiumCloudAccessGate.openCloud(this, currentAccount));
        ui.row(t("TP_L10N_052", R.string.TP_L10N_052), t("TP_L10N_053", R.string.TP_L10N_053), R.drawable.tp_ic_folder, TelePremiumSettingsUi.ORANGE, null,
                v -> presentFragment(new TelePremiumFileCenterActivity()));
        ui.row(t("TP_L10N_054", R.string.TP_L10N_054), t("TP_L10N_055", R.string.TP_L10N_055), R.drawable.tp_ic_shield, TelePremiumSettingsUi.RED, null,
                v -> presentFragment(new TelePremiumCloudSecurityActivity()));
        ui.row(TPStrings.isRussian() ? "ИИ-подключения" : "AI connections",
                TPStrings.isRussian() ? "Свои модели по API-ключу или через аккаунт (OAuth PKCE) для редактора статей"
                        : "Your own models by API key or account sign-in (OAuth PKCE) for the article editor",
                R.drawable.tp_ic_ai, TelePremiumSettingsUi.INDIGO, null, v -> presentFragment(new TelePremiumAiActivity()));
        ui.row(t("TP_L10N_056", R.string.TP_L10N_056), t("TP_L10N_057", R.string.TP_L10N_057), R.drawable.tp_ic_translate, TelePremiumSettingsUi.PURPLE, null,
                v -> presentFragment(new LanguageSelectActivity()));
        ui.row(t("TP_L10N_058", R.string.TP_L10N_058), t("TP_L10N_059", R.string.TP_L10N_059), R.drawable.tp_ic_storage, TelePremiumSettingsUi.TEAL, null,
                v -> presentFragment(new CacheControlActivity()));

        ui.section(t("TP_L10N_007", R.string.TP_L10N_007));
        ui.row(t("TP_L10N_060", R.string.TP_L10N_060), t("TP_L10N_061", R.string.TP_L10N_061), R.drawable.tp_ic_palette, TelePremiumSettingsUi.AMBER, null,
                v -> presentFragment(new TelePremiumAppearanceActivity()));
        ui.row(t("TP_L10N_062", R.string.TP_L10N_062), t("TP_L10N_063", R.string.TP_L10N_063), R.drawable.tp_ic_chat, TelePremiumSettingsUi.MINT, null,
                v -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        ui.row(t("TP_L10N_064", R.string.TP_L10N_064), t("TP_L10N_065", R.string.TP_L10N_065) + TelePremiumUiScale.getScalePercent(context) + "%",
                R.drawable.tp_ic_text_size, TelePremiumSettingsUi.INDIGO, null, v -> presentFragment(new TelePremiumAppearanceActivity()));

        ui.switchRow(t("TP_C_SHOW_TG_PREMIUM", R.string.TP_C_SHOW_TG_PREMIUM), t("TP_C_SHOW_TG_PREMIUM_SUB", R.string.TP_C_SHOW_TG_PREMIUM_SUB),
                R.drawable.tp_ic_star, TelePremiumSettingsUi.PURPLE, org.telegram.messenger.telepremium.TelePremiumUiPrefs.showTelegramPremiumBlock(), true,
                (b, v) -> org.telegram.messenger.telepremium.TelePremiumUiPrefs.setShowTelegramPremiumBlock(v));

        ui.section(t("TP_L10N_304", R.string.TP_L10N_304));
        ui.switchRow(t("TP_C_PLAYER_CARD", R.string.TP_C_PLAYER_CARD), t("TP_C_PLAYER_CARD_SUB", R.string.TP_C_PLAYER_CARD_SUB),
                R.drawable.tp_ic_play, TelePremiumSettingsUi.PINK, org.telegram.messenger.telepremium.TelePremiumUiPrefs.customPlayerNotification(), true,
                (b, v) -> org.telegram.messenger.telepremium.TelePremiumUiPrefs.setCustomPlayerNotification(v));

        ui.section(t("TP_L10N_066", R.string.TP_L10N_066));
        ui.row(t("TP_L10N_067", R.string.TP_L10N_067), t("TP_L10N_068", R.string.TP_L10N_068), R.drawable.tp_ic_update, TelePremiumSettingsUi.SLATE, null,
                v -> presentFragment(new TelePremiumServiceActivity()));
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
