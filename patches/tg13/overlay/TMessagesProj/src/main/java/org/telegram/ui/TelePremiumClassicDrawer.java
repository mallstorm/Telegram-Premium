package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.messenger.telepremium.theme.TelePremiumDrawerArt;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Telegram 11.x side menu, reimplemented over the Telegram 13.1.1 engine (no 11.x protocol code):
 * profile header with accounts toggle, account rows, 11.x row metrics, edge-drag state machine with
 * a velocity-based settle, scrim, live theme refresh. It is the only classic-shell menu surface.
 */
public final class TelePremiumClassicDrawer extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    public interface ProgressListener {
        void onProgress(float progress);
    }

    private static final int TYPE_PROFILE = 0;
    private static final int TYPE_EMPTY = 1;
    private static final int TYPE_DIVIDER = 2;
    private static final int TYPE_ACTION = 3;
    private static final int TYPE_USER = 4;
    private static final int TYPE_ADD = 5;

    private static final class Item {
        final CharSequence text;
        final int icon;
        final Runnable action;

        Item(CharSequence text, int icon, Runnable action) {
            this.text = text;
            this.icon = icon;
            this.action = action;
        }
    }

    private final MainTabsActivity host;
    private final View scrim;
    private final FrameLayout panel;
    private final RecyclerListView listView;
    private final Adapter adapter;
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Integer> accountNumbers = new ArrayList<>();
    private final int touchSlop;

    private ProfileCell profileCell;
    private ProgressListener progressListener;
    private boolean accountsShown;
    private int statusBarPadding;
    private int insetLeft;
    private int panelWidth;

    // drag state machine
    private float progress;
    private boolean dragging;
    private float dragStartRawX;
    private float dragBaseProgress;
    private float downX;
    private float downY;
    private boolean downOnScrim;
    private VelocityTracker velocityTracker;
    private ValueAnimator settle;
    private int observedAccount = -1;

    public TelePremiumClassicDrawer(MainTabsActivity host, Context context) {
        super(context);
        this.host = host;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        panelWidth = computePanelWidth(AndroidUtilities.displaySize.x, 0);
        setVisibility(GONE);
        setClickable(true);

        scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new FrameLayout(context);
        panel.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
        addView(panel, new FrameLayout.LayoutParams(panelWidth, -1, Gravity.LEFT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(animator);
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setClipToPadding(false);
        listView.setBackground(TelePremiumDrawerArt.body(TelePremiumThemeController.getCurrent(context)));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((RecyclerListView.OnItemClickListener) (view, position) -> onItemClick(position));
        panel.addView(listView, new FrameLayout.LayoutParams(-1, -1));

        accountsShown = UserConfig.getActivatedAccountsCount() > 1
                && MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true);
        resetItems();
        applyProgress();
    }

    private static int computePanelWidth(int total, int inset) {
        return Math.min(AndroidUtilities.dp(320), total - AndroidUtilities.dp(56)) + inset;
    }

    // ---------------------------------------------------------------- insets / theme

    /** The overlay fills the system navigation area; only the list is inset. */
    void setSystemInsets(int left, int right, int bottom) {
        if (insetLeft != left) {
            insetLeft = left;
            panel.setPadding(left, 0, 0, 0);
            requestLayout();
        }
        int safeBottom = Math.max(0, bottom);
        if (listView.getPaddingBottom() != safeBottom) {
            listView.setPadding(0, 0, 0, safeBottom);
        }
    }

    void setStatusBarPadding(int padding) {
        if (statusBarPadding != padding) {
            statusBarPadding = padding;
            if (profileCell != null) {
                profileCell.requestLayout();
            }
        }
    }

    public void setProgressListener(ProgressListener listener) {
        progressListener = listener;
    }

    private Drawable headerArt;

    /** Scene behind the profile header; rebuilt with the theme, shared by all bind() calls. */
    private Drawable headerArt() {
        if (headerArt == null) {
            headerArt = TelePremiumDrawerArt.header(TelePremiumThemeController.getCurrent(getContext()));
        }
        return headerArt;
    }

    private void updateTheme() {
        headerArt = null;
        TelePremiumThemePreset preset = TelePremiumThemeController.getCurrent(getContext());
        panel.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
        listView.setBackground(TelePremiumDrawerArt.body(preset));
        adapter.notifyDataSetChanged();
    }

    /** Kept for callers that rebuilt the old drawer after a theme change. */
    public void rebuild() {
        resetItems();
        updateTheme();
    }

    // ---------------------------------------------------------------- items

    private void resetItems() {
        accountNumbers.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            return Long.compare(l1, l2);
        });
        if (accountNumbers.size() < 2) {
            accountsShown = false;
        }

        items.clear();
        if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        UserConfig me = UserConfig.getInstance(UserConfig.selectedAccount);
        items.add(new Item(LocaleController.getString(R.string.MyProfile), R.drawable.left_status_profile,
                () -> host.presentFragment(new UserInfoActivity())));
        if (me.isPremium()) {
            boolean hasStatus = UserObject.getEmojiStatusDocumentId(me.getCurrentUser()) != null;
            items.add(new Item(
                    LocaleController.getString(hasStatus ? R.string.ChangeEmojiStatus : R.string.SetEmojiStatus),
                    hasStatus ? R.drawable.msg_edit : R.drawable.msg_premium_liststar,
                    () -> {
                        DialogsActivity chats = host.getDialogsActivity();
                        if (chats != null) chats.showSelectStatusDialog();
                    }));
        }
        items.add(null);
        items.add(new Item(LocaleController.getString(R.string.NewGroup), R.drawable.msg_groups,
                () -> host.presentFragment(new GroupCreateActivity(new Bundle()))));
        items.add(new Item(LocaleController.getString(R.string.Contacts), R.drawable.msg_contacts, () -> {
            Bundle args = new Bundle();
            args.putBoolean("needPhonebook", true);
            host.presentFragment(new ContactsActivity(args));
        }));
        items.add(new Item(LocaleController.getString(R.string.Calls), R.drawable.msg_calls, () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            host.presentFragment(new CallLogActivity(args));
        }));
        items.add(new Item(LocaleController.getString(R.string.SavedMessages), R.drawable.msg_saved, () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
            host.presentFragment(new ChatActivity(args));
        }));
        items.add(new Item(LocaleController.getString(R.string.Settings), R.drawable.msg_settings_old,
                () -> host.presentFragment(new TelePremiumClassicSettingsActivity())));
        items.add(null);
        items.add(new Item(TPStrings.get("TP_SettingsCloud", R.string.TP_SettingsCloud), R.drawable.tp_ic_cloud,
                () -> TelePremiumCloudAccessGate.openCloud(host, UserConfig.selectedAccount)));
        items.add(new Item(TPStrings.get("TP_SettingsMedia", R.string.TP_SettingsMedia), R.drawable.tp_ic_play, () -> {
            if (host.getParentActivity() != null) {
                host.getParentActivity().startActivity(new Intent(host.getParentActivity(), TelePremiumMediaCenterActivity.class));
            }
        }));
        items.add(new Item(TPStrings.get("TP_SettingsCenter", R.string.TP_SettingsCenter), R.drawable.tp_ic_tune,
                () -> host.presentFragment(new TelePremiumCenterActivity())));
        items.add(new Item("Telegram AI Analyzer", R.drawable.tp_ic_ai,
                () -> host.presentFragment(new TelePremiumAnalyzerActivity())));
        items.add(null);
        items.add(new Item(LocaleController.getString(R.string.InviteFriends), R.drawable.msg_addcontact, () -> {
            if (host.getParentActivity() != null) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, inviteText());
                host.getParentActivity().startActivity(
                        Intent.createChooser(send, LocaleController.getString(R.string.InviteFriends)));
            }
        }));
        items.add(new Item(LocaleController.getString(R.string.TelegramFeatures), R.drawable.msg_help,
                () -> Browser.openUrl(getContext(), LocaleController.getString(R.string.TelegramFeaturesUrl))));
    }

    private static String inviteText() {
        return "Telegram: https://telegram.org/";
    }

    private int accountRows() {
        int count = accountNumbers.size();
        if (accountNumbers.size() < UserConfig.MAX_ACCOUNT_COUNT) {
            count++;
        }
        return count;
    }

    private int itemsOffset() {
        return 2 + (accountsShown ? accountRows() : 0);
    }

    private long lastToggle;

    private void toggleAccounts() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastToggle < 350 || accountNumbers.size() < 2) {
            return;
        }
        lastToggle = now;
        accountsShown = !accountsShown;
        MessagesController.getGlobalMainSettings().edit().putBoolean("accountsShown", accountsShown).commit();
        // a plain rebind: no range animations that could leave stale rows behind
        adapter.notifyDataSetChanged();
        if (profileCell != null) {
            profileCell.updateArrow(true);
        }
    }

    private void onItemClick(int position) {
        if (position == 0) {
            toggleAccounts();
            return;
        }
        if (position == 1) {
            return;
        }
        int p = position - 2;
        if (accountsShown) {
            int rows = accountRows();
            if (p < rows) {
                if (p < accountNumbers.size()) {
                    final int account = accountNumbers.get(p);
                    closeNow();
                    if (UserConfig.selectedAccount != account && LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(account, true);
                    }
                } else {
                    closeNow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (!UserConfig.getInstance(a).isClientActivated()) {
                            host.presentFragment(new LoginActivity(a));
                            break;
                        }
                    }
                }
                return;
            }
            p -= rows;
        }
        if (p < 0 || p >= items.size()) {
            return;
        }
        Item item = items.get(p);
        if (item != null && item.action != null) {
            item.action.run();
            closeNow();
        }
    }

    // ---------------------------------------------------------------- state machine

    public boolean isOpened() {
        return progress > 0f;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void open() {
        if (settle == null && progress >= 1f) return;
        prepareToShow();
        animateTo(1f, 0f);
    }

    public void close() {
        if (progress <= 0f && !dragging) return;
        animateTo(0f, 0f);
    }

    private void closeNow() {
        cancelSettle();
        dragging = false;
        setProgress(0f);
    }

    private void prepareToShow() {
        if (progress > 0f) return;
        resetItems();
        adapter.notifyDataSetChanged();
        listView.scrollToPosition(0);
        registerObservers();
    }

    /** Called by the host when an edge swipe was recognised. */
    void beginEdgeDrag(float rawX) {
        cancelSettle();
        prepareToShow();
        dragging = true;
        dragStartRawX = rawX;
        dragBaseProgress = progress;
        resetTracker();
        setProgress(progress);
    }

    /** Forwarded by the host while it owns the gesture. */
    boolean handleHostTouch(MotionEvent ev) {
        return handleDragEvent(ev);
    }

    private void resetTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
        }
        velocityTracker = VelocityTracker.obtain();
    }

    private boolean handleDragEvent(MotionEvent ev) {
        if (!dragging) {
            return false;
        }
        if (velocityTracker == null) {
            resetTracker();
        }
        velocityTracker.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - dragStartRawX;
                setProgress(Math.max(0f, Math.min(1f, dragBaseProgress + dx / Math.max(1, panelWidth))));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                velocityTracker.computeCurrentVelocity(1000);
                float vx = velocityTracker.getXVelocity();
                velocityTracker.recycle();
                velocityTracker = null;
                dragging = false;
                float target;
                if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL && Math.abs(vx) < AndroidUtilities.dp(400)) {
                    target = progress > 0.5f ? 1f : 0f;
                } else if (Math.abs(vx) > AndroidUtilities.dp(600)) {
                    target = vx > 0 ? 1f : 0f;
                } else {
                    target = progress > 0.5f ? 1f : 0f;
                }
                animateTo(target, vx);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (progress <= 0f) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && settle == null) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (dx < -touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        dragStartRawX = ev.getRawX();
                        dragBaseProgress = progress;
                        resetTracker();
                        velocityTracker.addMovement(ev);
                        return true;
                    }
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (dragging) {
            return handleDragEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downOnScrim = ev.getX() > panelWidth;
                break;
            case MotionEvent.ACTION_MOVE:
                if (settle == null && Math.abs(ev.getX() - downX) > touchSlop
                        && Math.abs(ev.getX() - downX) > Math.abs(ev.getY() - downY) * 1.5f) {
                    dragging = true;
                    dragStartRawX = ev.getRawX();
                    dragBaseProgress = progress;
                    resetTracker();
                    velocityTracker.addMovement(ev);
                    downOnScrim = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (downOnScrim && Math.abs(ev.getX() - downX) < touchSlop && Math.abs(ev.getY() - downY) < touchSlop) {
                    close();
                }
                downOnScrim = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                downOnScrim = false;
                break;
        }
        return true;
    }

    private void cancelSettle() {
        if (settle != null) {
            settle.removeAllListeners();
            settle.cancel();
            settle = null;
        }
    }

    private void animateTo(final float target, float velocityX) {
        cancelSettle();
        if (progress == target) {
            setProgress(target);
            return;
        }
        float distance = Math.abs(target - progress);
        long duration = (long) (Math.max(120f, 250f * distance));
        if (Math.abs(velocityX) > AndroidUtilities.dp(600)) {
            float px = distance * panelWidth;
            duration = (long) Math.max(110f, Math.min(duration, px / Math.abs(velocityX) * 1000f * 1.2f));
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.4f));
        animator.addUpdateListener(a -> setProgress((Float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (settle == animator) {
                    settle = null;
                    setProgress(target);
                }
            }
        });
        settle = animator;
        animator.start();
    }

    private void setProgress(float value) {
        progress = value;
        applyProgress();
        if (progressListener != null) {
            progressListener.onProgress(value);
        }
    }

    private void applyProgress() {
        panel.setTranslationX(-panelWidth * (1f - progress));
        scrim.setAlpha(progress);
        if (progress > 0f || dragging) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
        } else if (getVisibility() != GONE) {
            setVisibility(GONE);
            unregisterObservers();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int target = computePanelWidth(width, insetLeft);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (lp.width != target) {
            lp.width = target;
            panelWidth = target;
            applyProgress();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    // ---------------------------------------------------------------- notifications

    private void registerObservers() {
        unregisterObservers();
        observedAccount = UserConfig.selectedAccount;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.dialogsUnreadCounterChanged);
        NotificationCenter center = NotificationCenter.getInstance(observedAccount);
        center.addObserver(this, NotificationCenter.updateInterfaces);
        center.addObserver(this, NotificationCenter.mainUserInfoChanged);
        center.addObserver(this, NotificationCenter.userEmojiStatusUpdated);
        center.addObserver(this, NotificationCenter.currentUserPremiumStatusChanged);
    }

    private void unregisterObservers() {
        if (observedAccount < 0) return;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.dialogsUnreadCounterChanged);
        NotificationCenter center = NotificationCenter.getInstance(observedAccount);
        center.removeObserver(this, NotificationCenter.updateInterfaces);
        center.removeObserver(this, NotificationCenter.mainUserInfoChanged);
        center.removeObserver(this, NotificationCenter.userEmojiStatusUpdated);
        center.removeObserver(this, NotificationCenter.currentUserPremiumStatusChanged);
        observedAccount = -1;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelSettle();
        unregisterObservers();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateTheme();
        } else if (id == NotificationCenter.emojiLoaded) {
            if (profileCell != null) profileCell.invalidateName();
        } else {
            resetItems();
            adapter.notifyDataSetChanged();
        }
    }

    // ---------------------------------------------------------------- adapter

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return items.size() + 2 + (accountsShown ? accountRows() : 0);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_ACTION || type == TYPE_USER || type == TYPE_ADD || type == TYPE_PROFILE;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_PROFILE;
            if (position == 1) return TYPE_EMPTY;
            int p = position - 2;
            if (accountsShown) {
                int rows = accountRows();
                if (p < rows) {
                    return p < accountNumbers.size() ? TYPE_USER : TYPE_ADD;
                }
                p -= rows;
            }
            return items.get(p) == null ? TYPE_DIVIDER : TYPE_ACTION;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case TYPE_PROFILE:
                    view = profileCell = new ProfileCell(context);
                    break;
                case TYPE_EMPTY:
                    view = new View(context);
                    view.setLayoutParams(new RecyclerView.LayoutParams(-1, AndroidUtilities.dp(8)));
                    break;
                case TYPE_DIVIDER:
                    view = new DividerView(context);
                    break;
                case TYPE_USER:
                    view = new UserRow(context);
                    break;
                case TYPE_ADD:
                case TYPE_ACTION:
                default:
                    view = new ActionRow(context);
                    break;
            }
            if (viewType != TYPE_EMPTY) {
                view.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_PROFILE:
                    ((ProfileCell) holder.itemView).bind();
                    break;
                case TYPE_USER: {
                    int p = position - 2;
                    ((UserRow) holder.itemView).bind(accountNumbers.get(p));
                    break;
                }
                case TYPE_ADD:
                    ((ActionRow) holder.itemView).bind(LocaleController.getString(R.string.AddAccount), R.drawable.msg_add);
                    break;
                case TYPE_ACTION: {
                    int p = position - 2;
                    if (accountsShown) p -= accountRows();
                    Item item = items.get(p);
                    ((ActionRow) holder.itemView).bind(item.text, item.icon);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ---------------------------------------------------------------- cells

    private static Drawable tinted(Context context, int res, int color) {
        Drawable d = context.getResources().getDrawable(res).mutate();
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        return d;
    }

    /** 11.x DrawerActionCell metrics: 48dp row, icon at 19dp, text at 72dp, 15sp medium. */
    private static final class ActionRow extends FrameLayout {
        private final ImageView icon;
        private final TextView label;

        ActionRow(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(AndroidUtilities.dp(24), AndroidUtilities.dp(24), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            ilp.leftMargin = AndroidUtilities.dp(19);
            addView(icon, ilp);
            label = new TextView(context);
            label.setTextSize(15);
            label.setTypeface(AndroidUtilities.bold());
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -1);
            tlp.leftMargin = AndroidUtilities.dp(72);
            tlp.rightMargin = AndroidUtilities.dp(16);
            addView(label, tlp);
        }

        void bind(CharSequence text, int iconRes) {
            label.setText(text);
            label.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
            icon.setImageDrawable(tinted(getContext(), iconRes, Theme.getColor(Theme.key_chats_menuItemIcon)));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
        }
    }

    private static final class DividerView extends View {
        private final Paint paint = new Paint();

        DividerView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(16));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(Theme.getColor(Theme.key_divider));
            float y = AndroidUtilities.dp(8);
            canvas.drawRect(0, y, getMeasuredWidth(), y + Math.max(1, AndroidUtilities.dp(0.5f)), paint);
        }
    }

    /** 11.x DrawerUserCell: 48dp row, 34dp avatar at 14dp, name at 72dp, unread badge at the end. */
    private static final class UserRow extends FrameLayout {
        private final BackupImageView avatar;
        private final TextView label;
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeText = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        private int unread;

        UserRow(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackground(Theme.getSelectorDrawable(false));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(17));
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(AndroidUtilities.dp(34), AndroidUtilities.dp(34), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            alp.leftMargin = AndroidUtilities.dp(14);
            addView(avatar, alp);
            label = new TextView(context);
            label.setTextSize(15);
            label.setTypeface(AndroidUtilities.bold());
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -1);
            tlp.leftMargin = AndroidUtilities.dp(72);
            tlp.rightMargin = AndroidUtilities.dp(16);
            addView(label, tlp);
            badgeText.setTextSize(AndroidUtilities.dp(13));
            badgeText.setTypeface(AndroidUtilities.bold());
        }

        void bind(int account) {
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            AvatarDrawable drawable = new AvatarDrawable();
            if (user != null) {
                drawable.setInfo(user);
                avatar.getImageReceiver().setCurrentAccount(account);
                avatar.setForUserOrChat(user, drawable);
                CharSequence name = UserObject.getUserName(user);
                try {
                    name = Emoji.replaceEmoji(name, label.getPaint().getFontMetricsInt(), false);
                } catch (Exception ignore) {
                }
                label.setText(name);
            } else {
                label.setText("Telegram");
            }
            label.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
            unread = account == UserConfig.selectedAccount ? 0 : MessagesStorage.getInstance(account).getMainUnreadCount();
            FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) label.getLayoutParams();
            tlp.rightMargin = AndroidUtilities.dp(unread > 0 ? 60 : 16);
            label.setLayoutParams(tlp);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (unread > 0) {
                String text = unread > 99 ? "99+" : String.valueOf(unread);
                badgeText.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
                badgePaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
                float textWidth = badgeText.measureText(text);
                float w = Math.max(AndroidUtilities.dp(23), textWidth + AndroidUtilities.dp(14));
                float h = AndroidUtilities.dp(23);
                float right = getMeasuredWidth() - AndroidUtilities.dp(16);
                float top = (getMeasuredHeight() - h) / 2f;
                canvas.drawRoundRect(right - w, top, right, top + h, h / 2f, h / 2f, badgePaint);
                canvas.drawText(text, right - w / 2f - textWidth / 2f, top + AndroidUtilities.dp(16), badgeText);
            }
        }
    }

    /** 11.x DrawerProfileCell: 148dp (+ status bar) header, 64dp avatar, name/phone at the bottom, accounts arrow. */
    private final class ProfileCell extends FrameLayout {
        private final BackupImageView avatar;
        private final SimpleTextView name;
        private final SimpleTextView phone;
        private final ImageView arrow;
        private final ImageView themeButton;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status;
        private Drawable premiumStar;
        private boolean attached;

        ProfileCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(true);
            setOnClickListener(v -> toggleAccounts());

            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(32));
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(AndroidUtilities.dp(64), AndroidUtilities.dp(64), Gravity.LEFT | Gravity.BOTTOM);
            alp.leftMargin = AndroidUtilities.dp(16);
            alp.bottomMargin = AndroidUtilities.dp(67);
            addView(avatar, alp);

            name = new SimpleTextView(context);
            name.setTextSize(15);
            name.setTypeface(AndroidUtilities.bold());
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setEllipsizeByGradient(true);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(20), Gravity.LEFT | Gravity.BOTTOM);
            nlp.leftMargin = AndroidUtilities.dp(16);
            nlp.rightMargin = AndroidUtilities.dp(84);
            nlp.bottomMargin = AndroidUtilities.dp(28);
            addView(name, nlp);

            phone = new SimpleTextView(context);
            phone.setTextSize(13);
            phone.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(20), Gravity.LEFT | Gravity.BOTTOM);
            plp.leftMargin = AndroidUtilities.dp(16);
            plp.rightMargin = AndroidUtilities.dp(76);
            plp.bottomMargin = AndroidUtilities.dp(9);
            addView(phone, plp);

            arrow = new ImageView(context);
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            // generous hit area; the arrow owns its gesture so drawer drag / list click can't swallow the tap
            arrow.setClickable(true);
            arrow.setOnTouchListener(new View.OnTouchListener() {
                private float sx;
                private float sy;
                private boolean down;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            down = true;
                            sx = e.getX();
                            sy = e.getY();
                            v.setPressed(true);
                            if (v.getParent() != null) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            v.setPressed(false);
                            if (down && Math.abs(e.getX() - sx) < touchSlop * 3 && Math.abs(e.getY() - sy) < touchSlop * 3) {
                                toggleAccounts();
                            }
                            down = false;
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            down = false;
                            return true;
                    }
                    return true;
                }
            });
            addView(arrow, new FrameLayout.LayoutParams(AndroidUtilities.dp(84), AndroidUtilities.dp(72), Gravity.RIGHT | Gravity.BOTTOM));

            themeButton = new ImageView(context);
            themeButton.setScaleType(ImageView.ScaleType.CENTER);
            themeButton.setBackground(Theme.createSelectorDrawable(0x22ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(AndroidUtilities.dp(48), AndroidUtilities.dp(48), Gravity.RIGHT | Gravity.BOTTOM);
            tlp.rightMargin = AndroidUtilities.dp(6);
            tlp.bottomMargin = AndroidUtilities.dp(90);
            addView(themeButton, tlp);
            themeButton.setOnClickListener(v -> TelePremiumThemeController.showQuickThemeSelector(getContext(), themeButton, TelePremiumClassicDrawer.this::updateTheme));
            themeButton.setOnLongClickListener(v -> {
                closeNow();
                host.presentFragment(new TelePremiumAppearanceActivity());
                return true;
            });

            status = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(name, AndroidUtilities.dp(20), AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS);
            name.setDrawablePadding(AndroidUtilities.dp(4));
            name.setRightDrawable(status);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(148) + statusBarPadding, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            status.attach();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            attached = false;
            status.detach();
        }

        void invalidateName() {
            name.invalidate();
        }

        void updateArrow(boolean animated) {
            float rotation = accountsShown ? 180f : 0f;
            if (animated) {
                arrow.animate().rotation(rotation).setDuration(220).start();
            } else {
                arrow.animate().cancel();
                arrow.setRotation(rotation);
            }
            arrow.setVisibility(accountNumbers.size() > 1 ? VISIBLE : GONE);
            arrow.setContentDescription(LocaleController.getString(accountsShown ? R.string.AccDescrHideAccounts : R.string.AccDescrShowAccounts));
        }

        private int backgroundKey() {
            return Theme.hasThemeKey(Theme.key_chats_menuTopBackground) && Theme.getColor(Theme.key_chats_menuTopBackground) != 0
                    ? Theme.key_chats_menuTopBackground : Theme.key_chats_menuTopBackgroundCats;
        }

        void bind() {
            setBackground(headerArt());
            int nameColor = Theme.getColor(Theme.key_chats_menuName);
            name.setTextColor(nameColor);
            phone.setTextColor(Theme.getColor(Theme.key_chats_menuPhoneCats));
            arrow.setImageDrawable(tinted(getContext(), R.drawable.msg_expand, nameColor));
            themeButton.setImageDrawable(tinted(getContext(), R.drawable.msg_theme, nameColor));
            updateArrow(false);

            int account = UserConfig.selectedAccount;
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            if (user == null) {
                return;
            }
            CharSequence text = UserObject.getUserName(user);
            try {
                text = Emoji.replaceEmoji(text, name.getPaint().getFontMetricsInt(), false);
            } catch (Exception ignore) {
            }
            name.setText(text);
            Long statusId = UserObject.getEmojiStatusDocumentId(user);
            int statusColor = Theme.getColor(Theme.isCurrentThemeDark() ? Theme.key_chats_verifiedBackground : Theme.key_chats_menuPhoneCats);
            if (statusId != null) {
                status.set(statusId, false);
            } else if (user.premium) {
                if (premiumStar == null) {
                    premiumStar = getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
                }
                premiumStar.setColorFilter(new PorterDuffColorFilter(statusColor, PorterDuff.Mode.SRC_IN));
                status.set(premiumStar, false);
            } else {
                status.set((Drawable) null, false);
            }
            status.setColor(statusColor);
            phone.setText(TextUtils.isEmpty(user.phone) ? "" : PhoneFormat.getInstance().format("+" + user.phone));
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(user);
            avatar.getImageReceiver().setCurrentAccount(account);
            avatar.setForUserOrChat(user, avatarDrawable);
        }
    }
}
