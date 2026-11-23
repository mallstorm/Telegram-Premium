package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumShell;
import org.telegram.messenger.telepremium.TelePremiumUiPrefs;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Telegram 11.x style settings list (profile card, grouped rows) routing into the Telegram 13
 * handlers. Built from stock cells, so it follows whatever theme preset is active.
 */
public final class TelePremiumClassicSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int VIEW_PROFILE = 0;
    private static final int VIEW_HEADER = 1;
    private static final int VIEW_ROW = 2;
    private static final int VIEW_SHADOW = 3;

    private static final class Row {
        final int type;
        final CharSequence title;
        final int icon;
        final CharSequence value;
        final Runnable action;

        Row(int type, CharSequence title, int icon, CharSequence value, Runnable action) {
            this.type = type;
            this.title = title;
            this.icon = icon;
            this.value = value;
            this.action = action;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.Settings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout screen = new FrameLayout(context);
        screen.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        adapter = new ListAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((RecyclerListView.OnItemClickListener) (view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            if (row.action != null) row.action.run();
        });
        screen.addView(listView, new FrameLayout.LayoutParams(-1, -1));
        fragmentView = screen;
        rebuildRows();
        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            rebuildRows();
        }
    }

    private void rebuildRows() {
        rows.clear();
        rows.add(new Row(VIEW_PROFILE, null, 0, null, () -> presentFragment(new UserInfoActivity())));
        rows.add(new Row(VIEW_SHADOW, null, 0, null, null));

        rows.add(new Row(VIEW_HEADER, LocaleController.getString(R.string.Settings), 0, null, null));
        rows.add(row(R.string.ChatSettings, R.drawable.msg_theme, null, () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC))));
        rows.add(row(R.string.NotificationsAndSounds, R.drawable.msg_notifications, null, () -> presentFragment(new NotificationsSettingsActivity())));
        rows.add(row(R.string.PrivacySettings, R.drawable.menu_privacy, null, () -> presentFragment(new PrivacySettingsActivity())));
        rows.add(row(R.string.DataSettings, R.drawable.msg_filled_datausage, null, () -> presentFragment(new DataSettingsActivity())));
        rows.add(row(R.string.PowerUsage, R.drawable.msg_settings_old, null, () -> presentFragment(new LiteModeSettingsActivity())));
        rows.add(row(R.string.Filters, R.drawable.msg_folders, null, () -> presentFragment(new FiltersSetupActivity())));
        rows.add(row(R.string.Devices, R.drawable.menu_devices, null, () -> presentFragment(new SessionsActivity(0))));
        rows.add(row(R.string.Language, R.drawable.msg_language, LocaleController.getCurrentLanguageName(), () -> presentFragment(new LanguageSelectActivity())));
        rows.add(new Row(VIEW_SHADOW, null, 0, null, null));

        rows.add(new Row(VIEW_HEADER, TPStrings.get("TP_SettingsSection", R.string.TP_SettingsSection), 0, null, null));
        rows.add(new Row(VIEW_ROW, TPStrings.get("TP_SettingsCloud", R.string.TP_SettingsCloud), R.drawable.tp_ic_cloud, null,
                () -> TelePremiumCloudAccessGate.openCloud(this, currentAccount)));
        rows.add(new Row(VIEW_ROW, TPStrings.get("TP_SettingsMedia", R.string.TP_SettingsMedia), R.drawable.tp_ic_play, null, () -> {
            if (getParentActivity() != null) {
                getParentActivity().startActivity(new Intent(getParentActivity(), TelePremiumMediaCenterActivity.class));
            }
        }));
        rows.add(new Row(VIEW_ROW, TPStrings.get("TP_SettingsCenter", R.string.TP_SettingsCenter), R.drawable.tp_ic_tune, null,
                () -> presentFragment(new TelePremiumCenterActivity())));
        rows.add(new Row(VIEW_ROW, TPStrings.get("TP_SettingsAppearance", R.string.TP_SettingsAppearance), R.drawable.tp_ic_palette, null,
                () -> presentFragment(new TelePremiumAppearanceActivity())));
        rows.add(new Row(VIEW_ROW, "Telegram AI Analyzer", R.drawable.tp_ic_ai, null,
                () -> presentFragment(new TelePremiumAnalyzerActivity())));
        rows.add(new Row(VIEW_ROW, TPStrings.get("TP_ShellRow", R.string.TP_ShellRow), R.drawable.tp_ic_device, TelePremiumShell.shortName(),
                () -> presentFragment(new TelePremiumInterfaceModeActivity())));
        rows.add(new Row(VIEW_SHADOW, null, 0, null, null));

        rows.add(new Row(VIEW_HEADER, LocaleController.getString(R.string.SettingsHelp), 0, null, null));
        if (TelePremiumUiPrefs.showTelegramPremiumBlock()) {
            rows.add(row(R.string.TelegramPremium, R.drawable.msg_premium_liststar, null, () -> presentFragment(new PremiumPreviewFragment("settings"))));
        }
        rows.add(row(R.string.TelegramFaq, R.drawable.msg_help, null,
                () -> Browser.openUrl(getParentActivity(), LocaleController.getString(R.string.TelegramFaqUrl))));
        rows.add(new Row(VIEW_ROW, TPStrings.get("TP_FullSettings", R.string.TP_FullSettings), R.drawable.msg_settings, null,
                () -> presentFragment(new SettingsActivity())));
        rows.add(new Row(VIEW_SHADOW, null, 0, null, null));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private Row row(int titleRes, int icon, CharSequence value, Runnable action) {
        return new Row(VIEW_ROW, LocaleController.getString(titleRes), icon, value, action);
    }

    private final class ListAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_ROW || type == VIEW_PROFILE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case VIEW_PROFILE:
                    view = new ProfileView(context);
                    break;
                case VIEW_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                case VIEW_ROW:
                default:
                    view = new TextCell(context);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (holder.getItemViewType()) {
                case VIEW_PROFILE:
                    ((ProfileView) holder.itemView).bind();
                    break;
                case VIEW_HEADER:
                    holder.itemView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case VIEW_ROW: {
                    holder.itemView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    boolean divider = position + 1 < rows.size() && rows.get(position + 1).type == VIEW_ROW;
                    TextCell cell = (TextCell) holder.itemView;
                    if (row.value != null) {
                        cell.setTextAndValueAndIcon(row.title, row.value, row.icon, divider);
                    } else {
                        cell.setTextAndIcon(row.title, row.icon, divider);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    private final class ProfileView extends FrameLayout {
        private final BackupImageView avatar;
        private final SimpleTextView name;
        private final SimpleTextView phone;

        ProfileView(Context context) {
            super(context);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(32));
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(AndroidUtilities.dp(64), AndroidUtilities.dp(64), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            alp.leftMargin = AndroidUtilities.dp(17);
            addView(avatar, alp);
            name = new SimpleTextView(context);
            name.setTextSize(19);
            name.setTypeface(AndroidUtilities.bold());
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(26), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            nlp.leftMargin = AndroidUtilities.dp(97);
            nlp.rightMargin = AndroidUtilities.dp(16);
            nlp.bottomMargin = AndroidUtilities.dp(22);
            addView(name, nlp);
            phone = new SimpleTextView(context);
            phone.setTextSize(14);
            phone.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(22), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            plp.leftMargin = AndroidUtilities.dp(97);
            plp.rightMargin = AndroidUtilities.dp(16);
            plp.topMargin = AndroidUtilities.dp(26);
            addView(phone, plp);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(96), MeasureSpec.EXACTLY));
        }

        void bind() {
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            phone.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (user == null) return;
            name.setText(UserObject.getUserName(user));
            phone.setText(android.text.TextUtils.isEmpty(user.phone) ? "" : PhoneFormat.getInstance().format("+" + user.phone));
            AvatarDrawable drawable = new AvatarDrawable();
            drawable.setInfo(user);
            avatar.getImageReceiver().setCurrentAccount(currentAccount);
            avatar.setForUserOrChat(user, drawable);
        }
    }
}
