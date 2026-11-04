package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.AccountNetworkController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

public class TelePremiumAccountCenterActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_000));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        int active = UserConfig.getActivatedAccountsCount();
        TextView summary = new TextView(context);
        summary.setText(active + " / " + UserConfig.MAX_ACCOUNT_COUNT + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_001));
        summary.setTextSize(20);
        summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summary.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        summary.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(18), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        content.addView(summary);

        AccountNetworkController network = AccountNetworkController.getInstance();

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) {
                continue;
            }

            final int accountId = account;
            TLRPC.User user = config.getCurrentUser();
            String name = user == null ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_002) + (account + 1) : UserObject.getUserName(user);
            String tier = user != null && user.premium ? "Premium" : "Standard";
            String route = network.getSummary(account);
            int ping = network.getPing(account);
            String value = tier + " • " + route + (ping > 0 ? " • " + ping + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_003) : "");

            TextCell cell = new TextCell(context, getResourceProvider());
            cell.setTextAndValue(name, value, true);
            cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            cell.setOnClickListener(v -> {
                if (LaunchActivity.instance != null) {
                    LaunchActivity.instance.switchToAccount(accountId, true);
                }
                finishFragment();
            });
            cell.setOnLongClickListener(v -> {
                presentFragment(new TelePremiumNetworkProfileActivity(accountId));
                return true;
            });
            content.addView(cell, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    AndroidUtilities.dp(58)
            ));
        }

        Integer freeSlot = null;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                freeSlot = account;
                break;
            }
        }

        if (freeSlot != null) {
            final int slot = freeSlot;
            TextCell add = new TextCell(context, getResourceProvider());
            add.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_004), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_005) + (slot + 1), false);
            add.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            add.setOnClickListener(v -> presentFragment(new LoginActivity(slot)));
            content.addView(add, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    AndroidUtilities.dp(58)
            ));
        }

        TextView hint = new TextView(context);
        hint.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_006));
        hint.setTextSize(13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        content.addView(hint);

        fragmentView = scrollView;
        return fragmentView;
    }
}
