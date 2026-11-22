package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;

/**
 * Telegram 11.x visual drawer over the Telegram 13.1.1 MainTabsActivity.
 * No old protocol, database or user-account implementation is reinstated.
 */
public final class TelePremiumClassicDrawer extends FrameLayout {
    static final int HEADER_COLOR = 0xff598fbd;
    private static final int INK = 0xff454545;
    private static final int ICON = 0xff92999d;
    private final MainTabsActivity host;
    private final FrameLayout panel;
    private final LinearLayout rows;
    private final View scrim;
    private final int width;
    private int statusBarPadding;
    private boolean expanded;
    private boolean open;

    public TelePremiumClassicDrawer(MainTabsActivity host, Context context) {
        super(context);
        this.host = host;
        width = Math.min(AndroidUtilities.dp(344), (int) (context.getResources().getDisplayMetrics().widthPixels * .82f));
        setVisibility(GONE);
        setClickable(true);
        scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        panel = new FrameLayout(context);
        panel.setBackgroundColor(Color.WHITE);
        addView(panel, new FrameLayout.LayoutParams(width, -1, Gravity.LEFT));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackgroundColor(Color.WHITE);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        rebuild();
    }

    private static GradientDrawable background(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(AndroidUtilities.dp(radius));
        return d;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void line() {
        View line = new View(getContext());
        line.setBackgroundColor(0xffeeeeee);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(1));
        lp.topMargin = AndroidUtilities.dp(8);
        lp.bottomMargin = AndroidUtilities.dp(8);
        rows.addView(line, lp);
    }

    private void row(String title, int iconRes, Runnable onClick) {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(AndroidUtilities.dp(55));
        line.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(22), 0);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(ICON);
        line.addView(icon, new LinearLayout.LayoutParams(AndroidUtilities.dp(24), AndroidUtilities.dp(24)));
        TextView label = text(title, 16, INK, true);
        LinearLayout.LayoutParams txt = new LinearLayout.LayoutParams(0, -2, 1);
        txt.leftMargin = AndroidUtilities.dp(27);
        line.addView(label, txt);
        line.setOnClickListener(v -> { close(); onClick.run(); });
        rows.addView(line, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(55)));
    }

    private View avatar(int account, int dp) {
        FrameLayout container = new FrameLayout(getContext());
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        BackupImageView image = new BackupImageView(getContext());
        AvatarDrawable fallback = new AvatarDrawable();
        if (user != null) {
            fallback.setInfo(user);
            image.getImageReceiver().setCurrentAccount(account);
            image.setForUserOrChat(user, fallback);
        }
        image.setRoundRadius(AndroidUtilities.dp(dp / 2));
        container.addView(image, new FrameLayout.LayoutParams(AndroidUtilities.dp(dp), AndroidUtilities.dp(dp), Gravity.CENTER));
        return container;
    }

    private void accounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            final int account = a;
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            LinearLayout line = new LinearLayout(getContext());
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(AndroidUtilities.dp(17), 0, AndroidUtilities.dp(18), 0);
            line.addView(avatar(a, 38), new LinearLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(44)));
            TextView label = text(user == null ? "Telegram" : UserObject.getUserName(user), 16, INK, true);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1);
            nameLp.leftMargin = AndroidUtilities.dp(17);
            line.addView(label, nameLp);
            int unread = MessagesStorage.getInstance(a).getMainUnreadCount();
            if (unread > 0) {
                TextView count = text(String.valueOf(unread), 13, Color.WHITE, true);
                count.setGravity(Gravity.CENTER);
                count.setMinWidth(AndroidUtilities.dp(26));
                count.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
                count.setBackground(background(0xff4acb67, 30));
                line.addView(count, new LinearLayout.LayoutParams(-2, AndroidUtilities.dp(27)));
            }
            if (UserConfig.selectedAccount == a) {
                TextView selected = text(" ✓", 14, 0xff40bd63, true);
                line.addView(selected);
            }
            line.setOnClickListener(v -> {
                close();
                if (UserConfig.selectedAccount != account && LaunchActivity.instance != null) {
                    LaunchActivity.instance.switchToAccount(account, true);
                }
            });
            rows.addView(line, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(57)));
        }
        row("Добавить аккаунт", android.R.drawable.ic_input_add, () -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    host.presentFragment(new LoginActivity(a));
                    break;
                }
            }
        });
    }

    void setStatusBarPadding(int padding) {
        if (statusBarPadding != padding) {
            statusBarPadding = padding;
            if (open) {
                rebuild();
            }
        }
    }

    public void rebuild() {
        rows.removeAllViews();
        FrameLayout head = new FrameLayout(getContext());
        head.setBackgroundColor(HEADER_COLOR);
        head.setPadding(0, statusBarPadding, 0, 0);
        rows.addView(head, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(205) + statusBarPadding));
        int active = UserConfig.selectedAccount;
        TLRPC.User user = UserConfig.getInstance(active).getCurrentUser();
        FrameLayout.LayoutParams photo = new FrameLayout.LayoutParams(AndroidUtilities.dp(69), AndroidUtilities.dp(69), Gravity.TOP | Gravity.LEFT);
        photo.leftMargin = AndroidUtilities.dp(20);
        photo.topMargin = AndroidUtilities.dp(28);
        head.addView(avatar(active, 68), photo);
        String name = user == null ? "Telegram" : UserObject.getUserName(user);
        TextView username = text(name, 18, Color.WHITE, true);
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(31), Gravity.TOP);
        nameLp.leftMargin = AndroidUtilities.dp(20);
        nameLp.rightMargin = AndroidUtilities.dp(22);
        nameLp.topMargin = AndroidUtilities.dp(119);
        head.addView(username, nameLp);
        String phone = user == null || TextUtils.isEmpty(user.phone) ? "" : "+" + user.phone;
        TextView number = text(phone, 15, 0xffd9eaf5, false);
        FrameLayout.LayoutParams phoneLp = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(32), Gravity.TOP);
        phoneLp.leftMargin = AndroidUtilities.dp(20);
        phoneLp.topMargin = AndroidUtilities.dp(153);
        phoneLp.rightMargin = AndroidUtilities.dp(48);
        head.addView(number, phoneLp);
        TextView theme = text("☾", 24, Color.WHITE, false);
        theme.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams moon = new FrameLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44), Gravity.RIGHT | Gravity.TOP);
        moon.topMargin = AndroidUtilities.dp(28);
        moon.rightMargin = AndroidUtilities.dp(20);
        head.addView(theme, moon);
        theme.setOnClickListener(v -> host.presentFragment(new TelePremiumAppearanceActivity()));
        TextView arrow = text(expanded ? "⌃" : "⌄", 26, Color.WHITE, false);
        arrow.setGravity(Gravity.CENTER);
        head.addView(arrow, new FrameLayout.LayoutParams(AndroidUtilities.dp(48), AndroidUtilities.dp(48), Gravity.RIGHT | Gravity.BOTTOM));
        arrow.setOnClickListener(v -> { expanded = !expanded; rebuild(); });
        if (expanded) {
            accounts();
            line();
        }
        row("Мой профиль", android.R.drawable.ic_menu_myplaces, () -> host.presentFragment(new UserInfoActivity()));
        row("Создать группу", android.R.drawable.ic_menu_add, () -> host.presentFragment(new GroupCreateActivity(new Bundle())));
        row("Контакты", android.R.drawable.ic_menu_agenda, () -> {
            Bundle args = new Bundle();
            args.putBoolean("needPhonebook", true);
            host.presentFragment(new ContactsActivity(args));
        });
        row("Звонки", android.R.drawable.ic_menu_call, () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            host.presentFragment(new CallLogActivity(args));
        });
        row("Избранное", android.R.drawable.btn_star_big_off, () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
            host.presentFragment(new ChatActivity(args));
        });
        row("Настройки", android.R.drawable.ic_menu_preferences, () -> host.presentFragment(new TelePremiumClassicSettingsActivity()));
        line();
        row("Облако", android.R.drawable.ic_menu_upload, () ->
            TelePremiumCloudAccessGate.openCloud(host, UserConfig.selectedAccount));
        row("Медиатека", android.R.drawable.ic_media_play, () -> {
            if (host.getParentActivity() != null) {
                host.getParentActivity().startActivity(new Intent(host.getParentActivity(), TelePremiumMediaCenterActivity.class));
            }
        });
        row("Центр TelePremium", android.R.drawable.ic_menu_manage, () -> host.presentFragment(new TelePremiumCenterActivity()));
        row("Внешний вид", android.R.drawable.ic_menu_edit, () -> host.presentFragment(new TelePremiumAppearanceActivity()));
        line();
        row("Пригласить друзей", android.R.drawable.ic_menu_share, () -> {
            if (host.getParentActivity() != null) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, "Telegram: https://telegram.org/");
                host.getParentActivity().startActivity(Intent.createChooser(send, "Пригласить друзей"));
            }
        });
        row("Выбрать интерфейс", android.R.drawable.ic_menu_set_as, () -> host.presentFragment(new TelePremiumInterfaceModeActivity()));
        row("Telegram AI Analyzer", android.R.drawable.ic_menu_search, () -> host.presentFragment(new TelePremiumAnalyzerActivity()));
    }

    public boolean isOpened() { return open; }
    public void open() {
        if (open) return;
        rebuild();
        open = true;
        setVisibility(VISIBLE);
        setAlpha(1f);
        scrim.setAlpha(0f);
        panel.setTranslationX(-width);
        scrim.animate().alpha(1f).setDuration(185).start();
        panel.animate().translationX(0).setDuration(185).start();
    }
    public void close() {
        if (!open) return;
        open = false;
        scrim.animate().alpha(0f).setDuration(170).start();
        panel.animate().translationX(-width).setDuration(170).withEndAction(() -> {
            if (!open) setVisibility(GONE);
        }).start();
    }
}
