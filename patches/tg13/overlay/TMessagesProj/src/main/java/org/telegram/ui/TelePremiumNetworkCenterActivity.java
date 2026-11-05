package org.telegram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.AccountNetworkController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

public class TelePremiumNetworkCenterActivity extends BaseFragment {
    private TelePremiumSettingsUi ui;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(t("TP_L10N_050", R.string.TP_L10N_050));
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
        AccountNetworkController controller = AccountNetworkController.getInstance();
        int connected = 0, active = 0;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) continue;
            active++;
            int state = controller.getConnectionState(account);
            if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) connected++;
        }
        ui.hero(t("TP_L10N_050", R.string.TP_L10N_050), t("TP_L10N_051", R.string.TP_L10N_051),
                t("TP_L10N_348", R.string.TP_L10N_348) + connected + " / " + active + t("TP_L10N_349", R.string.TP_L10N_349), R.drawable.tp_ic_public);

        ui.section(t("TP_L10N_350", R.string.TP_L10N_350));
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) continue;
            final int accountId = account;
            TLRPC.User user = config.getCurrentUser();
            String name = user == null ? t("TP_L10N_002", R.string.TP_L10N_002) + (account + 1) : UserObject.getUserName(user);
            int ping = controller.getPing(account);
            int state = controller.getConnectionState(account);
            boolean online = state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
            String subtitle = controller.getSummary(account) + (ping > 0 ? " · " + ping + t("TP_L10N_003", R.string.TP_L10N_003) : "");
            ui.row(name, subtitle, R.drawable.tp_ic_wifi,
                    online ? TelePremiumSettingsUi.GREEN : TelePremiumSettingsUi.ORANGE,
                    online ? "●" : "…", v -> presentFragment(new TelePremiumNetworkProfileActivity(accountId)));
        }
        ui.section(t("TP_L10N_066", R.string.TP_L10N_066));
        ui.row(t("TP_C_PROXY", R.string.TP_C_PROXY), t("TP_C_PROXY_SUB", R.string.TP_C_PROXY_SUB), R.drawable.tp_ic_shield, TelePremiumSettingsUi.INDIGO, null,
                v -> presentFragment(new ProxyListActivity()));
        ui.row(t("TP_C_DATA", R.string.TP_C_DATA), t("TP_C_DATA_SUB", R.string.TP_C_DATA_SUB), R.drawable.tp_ic_storage, TelePremiumSettingsUi.TEAL, null,
                v -> presentFragment(new DataSettingsActivity()));
        ui.note(t("TP_L10N_351", R.string.TP_L10N_351));
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
