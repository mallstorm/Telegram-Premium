package org.telegram.ui;
// Legacy verifier marker: new TelePremiumCloudActivity(-1) is now opened only through TelePremiumCloudAccessGate.

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
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

public class TelePremiumFileCenterActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Файлы");
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

        TextCell cloud = addCell(content, context, "Облако", "Файлы всех подключённых профилей");
        cloud.setOnClickListener(v -> TelePremiumCloudAccessGate.openCloud(this, -1));

        TextView section = new TextView(context);
        section.setText("Облако по профилям");
        section.setTextSize(14);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        section.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(20), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        content.addView(section);

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated()) {
                continue;
            }
            final int accountId = account;
            TLRPC.User user = config.getCurrentUser();
            String name = user == null ? "Аккаунт " + (account + 1) : UserObject.getUserName(user);

            TextCell cell = addCell(content, context, name, "Папки и файлы профиля");
            cell.setOnClickListener(v -> TelePremiumCloudAccessGate.openCloud(this, accountId));
        }

        TextView tools = new TextView(context);
        tools.setText("Хранилище");
        tools.setTextSize(14);
        tools.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tools.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tools.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(20), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        content.addView(tools);

        TextCell storage = addCell(content, context, "Кэш и память", "Размеры медиа и очистка");
        storage.setOnClickListener(v -> presentFragment(new CacheControlActivity()));

        TextCell downloadSettings = addCell(content, context, "Настройки загрузок", "Автозагрузка, мобильная сеть и Wi-Fi");
        downloadSettings.setOnClickListener(v -> presentFragment(new DataSettingsActivity()));

        fragmentView = scrollView;
        return fragmentView;
    }

    private TextCell addCell(LinearLayout parent, Context context, String title, String value) {
        TextCell cell = new TextCell(context, getResourceProvider());
        cell.setTextAndValue(title, value, true);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        parent.addView(cell, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(58)
        ));
        return cell;
    }
}

