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
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;

public class TelePremiumAccountCenterActivity extends BaseFragment {
    private TelePremiumSettingsUi ui;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(t("TP_L10N_000", R.string.TP_L10N_000));
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
        int active = UserConfig.getActivatedAccountsCount();
        ui.hero(t("TP_L10N_048", R.string.TP_L10N_048), t("TP_L10N_049", R.string.TP_L10N_049),
                active + " / " + UserConfig.MAX_ACCOUNT_COUNT + t("TP_L10N_001", R.string.TP_L10N_001), R.drawable.tp_ic_people);

        AccountNetworkController network = AccountNetworkController.getInstance();
        ui.section(t("TP_L10N_000", R.string.TP_L10N_000));
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) continue;
            final int accountId = account;
            TLRPC.User user = config.getCurrentUser();
            String name = user == null ? t("TP_L10N_002", R.string.TP_L10N_002) + (account + 1) : UserObject.getUserName(user);
            String tier = user != null && user.premium ? "Premium" : "Standard";
            int ping = network.getPing(account);
            String subtitle = tier + " · " + network.getSummary(account) + (ping > 0 ? " · " + ping + t("TP_L10N_003", R.string.TP_L10N_003) : "");

            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(TelePremiumSettingsUi.dp(20));
            AvatarDrawable placeholder = new AvatarDrawable();
            if (user != null) {
                placeholder.setInfo(account, user);
                avatar.setForUserOrChat(user, placeholder);
            } else {
                avatar.setImageDrawable(placeholder);
            }
            View row = ui.row(avatar, name, subtitle, account == UserConfig.selectedAccount ? t("TP_C_CURRENT", R.string.TP_C_CURRENT) : null, v -> {
                if (LaunchActivity.instance != null) LaunchActivity.instance.switchToAccount(accountId, true);
                finishFragment();
            });
            row.setOnLongClickListener(v -> {
                presentFragment(new TelePremiumNetworkProfileActivity(accountId));
                return true;
            });
        }
        Integer freeSlot = null;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) { freeSlot = account; break; }
        }
        if (freeSlot != null) {
            final int slot = freeSlot;
            ui.row(t("TP_L10N_004", R.string.TP_L10N_004), t("TP_L10N_005", R.string.TP_L10N_005) + (slot + 1), R.drawable.tp_ic_add, TelePremiumSettingsUi.GREEN, null,
                    v -> presentFragment(new LoginActivity(slot)));
        }
        ui.section(t("TP_L10N_050", R.string.TP_L10N_050));
        ui.row(t("TP_L10N_050", R.string.TP_L10N_050), t("TP_L10N_051", R.string.TP_L10N_051), R.drawable.tp_ic_public, TelePremiumSettingsUi.GREEN, null,
                v -> presentFragment(new TelePremiumNetworkCenterActivity()));
        ui.note(t("TP_L10N_006", R.string.TP_L10N_006));
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }
}
