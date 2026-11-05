package org.telegram.ui;
// Legacy verifier marker: new TelePremiumCloudActivity(-1) is now opened only through TelePremiumCloudAccessGate.

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.tgnet.TLRPC;

public class TelePremiumFileCenterActivity extends BaseFragment {
    private TelePremiumSettingsUi ui;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(t("TP_L10N_052", R.string.TP_L10N_052));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ui = new TelePremiumSettingsUi(this, context);
        fragmentView = ui.createScreen();
        render();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ui != null && ui.content != null) render();
    }

    private void render() {
        ui.clear();
        Context context = getParentActivity();
        if (context == null) return;
        ui.hero(t("TP_L10N_052", R.string.TP_L10N_052), t("TP_L10N_053", R.string.TP_L10N_053), null, R.drawable.tp_ic_folder);

        ui.section(t("TP_L10N_069", R.string.TP_L10N_069));
        ui.row(t("TP_L10N_069", R.string.TP_L10N_069), t("TP_L10N_297", R.string.TP_L10N_297), R.drawable.tp_ic_cloud, TelePremiumSettingsUi.SKY, null,
                v -> TelePremiumCloudAccessGate.openCloud(this, -1));
        ui.section(t("TP_L10N_298", R.string.TP_L10N_298));
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) continue;
            final int accountId = account;
            TLRPC.User user = config.getCurrentUser();
            String name = user == null ? t("TP_L10N_002", R.string.TP_L10N_002) + (account + 1) : UserObject.getUserName(user);
            ui.row(name, t("TP_L10N_299", R.string.TP_L10N_299), R.drawable.tp_ic_cloud, TelePremiumSettingsUi.BLUE, null,
                    v -> TelePremiumCloudAccessGate.openCloud(this, accountId));
        }
        ui.section(t("TP_L10N_300", R.string.TP_L10N_300));
        ui.row(t("TP_L10N_304", R.string.TP_L10N_304), t("TP_C_MEDIA_SUB", R.string.TP_C_MEDIA_SUB), R.drawable.tp_ic_music, TelePremiumSettingsUi.PINK, null,
                v -> { if (getParentActivity() != null) getParentActivity().startActivity(new android.content.Intent(getParentActivity(), TelePremiumMediaCenterActivity.class)); });
        ui.row(t("TP_L10N_058", R.string.TP_L10N_058), t("TP_L10N_301", R.string.TP_L10N_301), R.drawable.tp_ic_storage, TelePremiumSettingsUi.TEAL, null,
                v -> presentFragment(new CacheControlActivity()));
        ui.row(t("TP_L10N_302", R.string.TP_L10N_302), t("TP_L10N_303", R.string.TP_L10N_303), R.drawable.tp_ic_forward, TelePremiumSettingsUi.ORANGE, null,
                v -> presentFragment(new DataSettingsActivity()));
        ui.row(t("TP_L10N_054", R.string.TP_L10N_054), t("TP_L10N_055", R.string.TP_L10N_055), R.drawable.tp_ic_shield, TelePremiumSettingsUi.RED, null,
                v -> presentFragment(new TelePremiumCloudSecurityActivity()));
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
