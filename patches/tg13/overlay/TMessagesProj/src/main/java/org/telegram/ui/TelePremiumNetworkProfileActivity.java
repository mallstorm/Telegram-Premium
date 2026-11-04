package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.AccountNetworkController;
import org.telegram.messenger.telepremium.AccountNetworkProfile;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

public class TelePremiumNetworkProfileActivity extends BaseFragment {

    private interface StringReceiver {
        void onValue(String value);
    }

    private final int account;
    private final AccountNetworkController controller = AccountNetworkController.getInstance();
    private AccountNetworkProfile profile;

    private TextCell modeCell;
    private TextCell nameCell;
    private TextCell locationCell;
    private TextCell addressCell;
    private TextCell portCell;
    private TextCell usernameCell;
    private TextCell passwordCell;
    private TextCell secretCell;
    private TextCell ipCell;
    private TextCell testCell;

    public TelePremiumNetworkProfileActivity(int account) {
        this.account = account;
    }

    @Override
    public View createView(Context context) {
        profile = controller.getEditableProfile(account);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_352));
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

        modeCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_353), "");
        modeCell.setOnClickListener(v -> chooseMode());

        nameCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_354), "");
        nameCell.setOnClickListener(v -> editText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_354), profile.displayName, false, value -> {
            profile.displayName = value;
            refresh();
        }));

        locationCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_355), "");
        locationCell.setOnClickListener(v -> editText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_356), profile.locationName, false, value -> {
            profile.locationName = value;
            refresh();
        }));

        addressCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_357), "");
        addressCell.setOnClickListener(v -> editText("Host / IP", profile.address, false, value -> {
            profile.address = value;
            refresh();
        }));

        portCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_358), "");
        portCell.setOnClickListener(v -> editText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_358), String.valueOf(profile.port), false, value -> {
            try {
                profile.port = Math.max(1, Math.min(65535, Integer.parseInt(value.trim())));
            } catch (Throwable ignore) {
            }
            refresh();
        }));

        usernameCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_359), "");
        usernameCell.setOnClickListener(v -> editText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_360), profile.username, false, value -> {
            profile.username = value;
            refresh();
        }));

        passwordCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_361), "");
        passwordCell.setOnClickListener(v -> editText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_362), profile.password, true, value -> {
            profile.password = value;
            refresh();
        }));

        secretCell = addCell(content, context, "MTProto secret", "");
        secretCell.setOnClickListener(v -> editText("MTProto secret", profile.secret, true, value -> {
            profile.secret = value;
            refresh();
        }));

        ipCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_363), "");
        ipCell.setOnClickListener(v -> chooseIpStrategy());

        testCell = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_365));
        testCell.setOnClickListener(v -> testConnection());

        TextCell save = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_366), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_367));
        save.setOnClickListener(v -> {
            controller.saveAndApply(account, profile);
            finishFragment();
        });

        TextCell reset = addCell(content, context, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_368), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_369));
        reset.setOnClickListener(v -> {
            controller.resetToGlobal(account);
            finishFragment();
        });

        refresh();
        fragmentView = scrollView;
        return fragmentView;
    }

    private TextCell addCell(LinearLayout parent, Context context, String text, String value) {
        TextCell cell = new TextCell(context, getResourceProvider());
        cell.setTextAndValue(text, value, true);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        parent.addView(cell, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(58)
        ));
        return cell;
    }

    private void refresh() {
        if (modeCell == null) {
            return;
        }
        modeCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_353), AccountNetworkProfile.modeLabel(profile.mode), true);
        nameCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_354), empty(profile.displayName), true);
        locationCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_355), empty(profile.locationName), true);
        addressCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_357), empty(profile.address), true);
        portCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_358), String.valueOf(profile.port), true);
        usernameCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_359), empty(profile.username), true);
        passwordCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_361), profile.password == null || profile.password.isEmpty() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_370) : "••••••", true);
        secretCell.setTextAndValue("MTProto secret", profile.secret == null || profile.secret.isEmpty() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_370) : "••••••", true);
        ipCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_363), ipLabel(profile.ipStrategy), true);
    }

    private void chooseMode() {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] items = new CharSequence[]{
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_371),
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_372),
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_373),
                "MTProto proxy",
                "SOCKS5 proxy"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_353));
        builder.setItems(items, (dialog, which) -> {
            profile.mode = which;
            refresh();
        });
        showDialog(builder.create());
    }

    private void chooseIpStrategy() {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] items = new CharSequence[]{
                "IPv4",
                "IPv6",
                "IPv4 + IPv6"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_363));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                profile.ipStrategy = ConnectionsManager.USE_IPV4_ONLY;
            } else if (which == 1) {
                profile.ipStrategy = ConnectionsManager.USE_IPV6_ONLY;
            } else {
                profile.ipStrategy = ConnectionsManager.USE_IPV4_IPV6_RANDOM;
            }
            refresh();
        });
        showDialog(builder.create());
    }

    private void editText(String title, String current, boolean secret, StringReceiver receiver) {
        if (getParentActivity() == null) {
            return;
        }
        EditText input = new EditText(getParentActivity());
        input.setSingleLine(true);
        input.setText(current == null ? "" : current);
        input.setSelection(input.length());
        input.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        if (secret) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(title);
        builder.setView(input);
        builder.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_137), (dialog, which) -> receiver.onValue(input.getText().toString()));
        builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_135), null);
        showDialog(builder.create());
    }

    private void testConnection() {
        testCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_374), true);
        controller.checkProfile(account, profile, time -> AndroidUtilities.runOnUIThread(() -> {
            if (time < 0) {
                testCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_375), true);
            } else if (time == 0) {
                testCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_376), true);
            } else {
                testCell.setTextAndValue(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_364), time + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_003), true);
            }
        }));
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_377) : value;
    }

    private static String ipLabel(byte strategy) {
        if (strategy == ConnectionsManager.USE_IPV4_ONLY) {
            return "IPv4";
        }
        if (strategy == ConnectionsManager.USE_IPV6_ONLY) {
            return "IPv6";
        }
        return "IPv4 + IPv6";
    }
}
