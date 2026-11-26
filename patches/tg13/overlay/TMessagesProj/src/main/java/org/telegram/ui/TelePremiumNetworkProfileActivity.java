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

    private boolean reloadXray;
    @Override public void onResume() { super.onResume(); if(reloadXray){profile=controller.getEditableProfile(account);reloadXray=false;} refresh(); }
    private TextCell vlessCell;
    private TextCell ghostCell;
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
        actionBar.setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_352", org.telegram.messenger.R.string.TP_L10N_352));
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

        vlessCell = addCell(content, context, "VLESS / Xray", "");
        vlessCell.setOnClickListener(v -> pasteVless());
        TextCell xray = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.isRussian() ? "Xray: список профилей и маршруты" : "Xray: profile list and routes", "arm64-v8a");
        xray.setOnClickListener(v -> { reloadXray=true;presentFragment(new TelePremiumXrayProfilesActivity()); });

        ghostCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.isRussian() ? "Невидимка" : "Invisible mode", "");
        ghostCell.setOnClickListener(v -> presentFragment(new TelePremiumGhostActivity(account)));

        modeCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_353", org.telegram.messenger.R.string.TP_L10N_353), "");
        modeCell.setOnClickListener(v -> chooseMode());

        nameCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_354", org.telegram.messenger.R.string.TP_L10N_354), "");
        nameCell.setOnClickListener(v -> editText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_354", org.telegram.messenger.R.string.TP_L10N_354), profile.displayName, false, value -> {
            profile.displayName = value;
            refresh();
        }));

        locationCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_355", org.telegram.messenger.R.string.TP_L10N_355), "");
        locationCell.setOnClickListener(v -> editText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_356", org.telegram.messenger.R.string.TP_L10N_356), profile.locationName, false, value -> {
            profile.locationName = value;
            refresh();
        }));

        addressCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_357", org.telegram.messenger.R.string.TP_L10N_357), "");
        addressCell.setOnClickListener(v -> editText("Host / IP", profile.address, false, value -> {
            profile.address = value;
            refresh();
        }));

        portCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_358", org.telegram.messenger.R.string.TP_L10N_358), "");
        portCell.setOnClickListener(v -> editText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_358", org.telegram.messenger.R.string.TP_L10N_358), String.valueOf(profile.port), false, value -> {
            try {
                profile.port = Math.max(1, Math.min(65535, Integer.parseInt(value.trim())));
            } catch (Throwable ignore) {
            }
            refresh();
        }));

        usernameCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_359", org.telegram.messenger.R.string.TP_L10N_359), "");
        usernameCell.setOnClickListener(v -> editText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_360", org.telegram.messenger.R.string.TP_L10N_360), profile.username, false, value -> {
            profile.username = value;
            refresh();
        }));

        passwordCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_361", org.telegram.messenger.R.string.TP_L10N_361), "");
        passwordCell.setOnClickListener(v -> editText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_362", org.telegram.messenger.R.string.TP_L10N_362), profile.password, true, value -> {
            profile.password = value;
            refresh();
        }));

        secretCell = addCell(content, context, "MTProto secret", "");
        secretCell.setOnClickListener(v -> editText("MTProto secret", profile.secret, true, value -> {
            profile.secret = value;
            refresh();
        }));

        ipCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_363", org.telegram.messenger.R.string.TP_L10N_363), "");
        ipCell.setOnClickListener(v -> chooseIpStrategy());

        testCell = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_364", org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_365", org.telegram.messenger.R.string.TP_L10N_365));
        testCell.setOnClickListener(v -> testConnection());

        TextCell save = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_366", org.telegram.messenger.R.string.TP_L10N_366), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_367", org.telegram.messenger.R.string.TP_L10N_367));
        save.setOnClickListener(v -> {
            controller.saveAndApply(account, profile);
            finishFragment();
        });

        TextCell reset = addCell(content, context, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_368", org.telegram.messenger.R.string.TP_L10N_368), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_369", org.telegram.messenger.R.string.TP_L10N_369));
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
        if (ghostCell != null) {
            boolean ru0 = org.telegram.messenger.telepremium.TPStrings.isRussian();
            ghostCell.setTextAndValue(ru0 ? "Невидимка" : "Invisible mode", org.telegram.messenger.telepremium.TelePremiumGhost.isActive(account) ? (ru0 ? "Включена" : "On") : (ru0 ? "Выключена" : "Off"), true);
        }
        if (vlessCell != null) {
            boolean ru = org.telegram.messenger.telepremium.TPStrings.isRussian();
            String state = profile.mode == AccountNetworkProfile.MODE_XRAY
                    ? (ru ? "Включён · " : "On · ") + empty(profile.displayName)
                    : (ru ? "Вставить ссылку vless:// и включить" : "Paste a vless:// link and turn on");
            vlessCell.setTextAndValue("VLESS / Xray", state, true);
        }
        modeCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_353", org.telegram.messenger.R.string.TP_L10N_353), AccountNetworkProfile.modeLabel(profile.mode), true);
        nameCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_354", org.telegram.messenger.R.string.TP_L10N_354), empty(profile.displayName), true);
        locationCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_355", org.telegram.messenger.R.string.TP_L10N_355), empty(profile.locationName), true);
        addressCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_357", org.telegram.messenger.R.string.TP_L10N_357), empty(profile.address), true);
        portCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_358", org.telegram.messenger.R.string.TP_L10N_358), String.valueOf(profile.port), true);
        usernameCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_359", org.telegram.messenger.R.string.TP_L10N_359), empty(profile.username), true);
        passwordCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_361", org.telegram.messenger.R.string.TP_L10N_361), profile.password == null || profile.password.isEmpty() ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_370", org.telegram.messenger.R.string.TP_L10N_370) : "••••••", true);
        secretCell.setTextAndValue("MTProto secret", profile.secret == null || profile.secret.isEmpty() ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_370", org.telegram.messenger.R.string.TP_L10N_370) : "••••••", true);
        ipCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_363", org.telegram.messenger.R.string.TP_L10N_363), ipLabel(profile.ipStrategy), true);
    }

    private void pasteVless() {
        if (getParentActivity() == null) return;
        final boolean ru = org.telegram.messenger.telepremium.TPStrings.isRussian();
        EditText input = new EditText(getParentActivity());
        input.setMinLines(4);
        input.setMaxLines(10);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setHint("vless://…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(262144)});
        input.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(getParentActivity());
                String low = text == null ? "" : text.toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (low.startsWith("vless://") || low.startsWith("vmess://") || low.startsWith("trojan://") || low.startsWith("ss://") || low.startsWith("hysteria2://") || low.startsWith("{")) {
                    input.setText(text.toString().trim());
                    input.setSelection(input.length());
                }
            }
        } catch (Throwable ignored) {
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(ru ? "VLESS / Xray для этого аккаунта" : "VLESS / Xray for this account");
        builder.setMessage(ru
                ? "Вставьте ссылку vless:// (или vmess://, trojan://, ss://, Xray JSON). Можно сразу много ссылок, по одной в строке: приложение само проверит их на отклик, подключит лучшую и будет менять при сбое."
                : "Paste a vless:// link (or vmess://, trojan://, ss://, Xray JSON). You can paste many links, one per line: the app checks them, connects the best one and switches on failure.");
        builder.setView(input);
        builder.setPositiveButton(ru ? "Включить" : "Turn on", (dialog, which) -> {
            String raw = input.getText().toString().trim();
            input.setText("");
            int before;
            try {
                before = org.telegram.messenger.telepremium.xray.XrayProfileStore.profiles().size();
            } catch (Exception e) {
                toast(ru ? "Не удалось открыть список профилей" : "Could not open the profile list");
                return;
            }
            final int first = before;
            org.telegram.messenger.telepremium.xray.XrayRuntime.importProfiles(raw, error -> {
                if (error != null) {
                    toast(error);
                    return;
                }
                try {
                    java.util.List<org.telegram.messenger.telepremium.xray.XrayProfile> all = org.telegram.messenger.telepremium.xray.XrayProfileStore.profiles();
                    org.telegram.messenger.telepremium.xray.XrayProfile chosen = all.get(Math.min(first, all.size() - 1));
                    boolean many = all.size() - first > 1;
                    profile = controller.getEditableProfile(account);
                    profile.mode = AccountNetworkProfile.MODE_XRAY;
                    profile.xrayProfileId = many ? org.telegram.messenger.telepremium.xray.XrayRuntime.AUTO : chosen.id;
                    profile.displayName = many ? (ru ? "Авто" : "Auto") : chosen.name;
                    controller.saveAndApply(account, profile);
                    refresh();
                    toast(many ? (ru ? "Добавлено ключей: " : "Keys added: ") + (all.size() - first) + (ru ? ". Включён автовыбор лучшего" : ". Auto-select is on") : (ru ? "Xray включён: " : "Xray is on: ") + chosen.name);
                    if (!many) AndroidUtilities.runOnUIThread(this::testConnection, 2500);
                } catch (Exception e) {
                    toast(ru ? "Профиль сохранён, но включить не удалось" : "Profile saved but could not be turned on");
                }
            });
        });
        builder.setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135), null);
        showDialog(builder.create());
    }

    private void toast(String text) {
        if (getParentActivity() != null) android.widget.Toast.makeText(getParentActivity(), text, android.widget.Toast.LENGTH_LONG).show();
    }

    private void chooseMode() {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] items = new CharSequence[]{
                org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_371", org.telegram.messenger.R.string.TP_L10N_371),
                org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_372", org.telegram.messenger.R.string.TP_L10N_372),
                org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_373", org.telegram.messenger.R.string.TP_L10N_373),
                "MTProto proxy",
                "SOCKS5 proxy",
                "Xray"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_353", org.telegram.messenger.R.string.TP_L10N_353));
        builder.setItems(items, (dialog, which) -> {
            if(which==AccountNetworkProfile.MODE_XRAY){
                try {
                    java.util.List<org.telegram.messenger.telepremium.xray.XrayProfile> profiles=org.telegram.messenger.telepremium.xray.XrayProfileStore.profiles();
                    if(profiles.isEmpty()){reloadXray=true;presentFragment(new TelePremiumXrayProfilesActivity());return;}
                    String[] names=new String[profiles.size()+1];names[0]=org.telegram.messenger.telepremium.TPStrings.isRussian()?"Авто: лучший из всех ключей":"Auto: best of all keys";for(int i=1;i<names.length;i++)names[i]=profiles.get(i-1).name;
                    showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle("Xray").setItems(names,(d,w)->{profile.mode=AccountNetworkProfile.MODE_XRAY;if(w==0){profile.xrayProfileId=org.telegram.messenger.telepremium.xray.XrayRuntime.AUTO;profile.displayName=org.telegram.messenger.telepremium.TPStrings.isRussian()?"Авто":"Auto";}else{profile.xrayProfileId=profiles.get(w-1).id;profile.displayName=profiles.get(w-1).name;}refresh();}).create());
                }catch(Exception e){android.widget.Toast.makeText(getParentActivity(),"Не удалось открыть профили",android.widget.Toast.LENGTH_LONG).show();}
            }else {profile.mode = which;refresh();}
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
        builder.setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_363", org.telegram.messenger.R.string.TP_L10N_363));
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
        builder.setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_137", org.telegram.messenger.R.string.TP_L10N_137), (dialog, which) -> receiver.onValue(input.getText().toString()));
        builder.setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135), null);
        showDialog(builder.create());
    }

    private void testConnection() {
        if(profile.mode==AccountNetworkProfile.MODE_XRAY){AccountNetworkProfile saved=controller.getProfile(account);
            if(saved==null||saved.mode!=profile.mode||!saved.xrayProfileId.equals(profile.xrayProfileId)){testCell.setTextAndValue("Xray","Сначала сохраните профиль",true);return;}}

        testCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_364", org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_374", org.telegram.messenger.R.string.TP_L10N_374), true);
        controller.checkProfile(account, profile, time -> AndroidUtilities.runOnUIThread(() -> {
            if (time < 0) {
                testCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_364", org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_375", org.telegram.messenger.R.string.TP_L10N_375), true);
            } else if (time == 0) {
                testCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_364", org.telegram.messenger.R.string.TP_L10N_364), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_376", org.telegram.messenger.R.string.TP_L10N_376), true);
            } else {
                testCell.setTextAndValue(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_364", org.telegram.messenger.R.string.TP_L10N_364), time + org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_003", org.telegram.messenger.R.string.TP_L10N_003), true);
            }
        }));
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_377", org.telegram.messenger.R.string.TP_L10N_377) : value;
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
