package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Quick account switching in the Settings header: swipe the big avatar left / right, or tap the
 * small neighbour avatars with chevrons on both sides of it. Shown only with 2+ accounts.
 */
final class TelePremiumAccountSwitcher {
    private TelePremiumAccountSwitcher() {}

    static ArrayList<Integer> accounts() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) out.add(a);
        return out;
    }

    private static int neighbour(int current, int direction) {
        ArrayList<Integer> list = accounts();
        if (list.size() < 2) return -1;
        int index = Math.max(0, list.indexOf(current));
        return list.get((index + direction + list.size()) % list.size());
    }

    @SuppressLint("ClickableViewAccessibility")
    static void attach(BaseFragment fragment, FrameLayout topView, View avatarContainer) {
        int current = fragment.getCurrentAccount();
        int prev = neighbour(current, -1), next = neighbour(current, 1);
        if (prev < 0 || next < 0) return;
        Context context = topView.getContext();
        topView.addView(sideButton(fragment, context, prev, false), LayoutHelper.createFrame(76, 56, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 28, 150, 0));
        topView.addView(sideButton(fragment, context, next, true), LayoutHelper.createFrame(76, 56, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 150, 28, 0, 0));

        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        float[] start = new float[2];
        boolean[] dragging = {false};
        avatarContainer.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    dragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                    if (!dragging[0] && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging[0] = true;
                        v.setPressed(false);
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        v.setTranslationX(dx * 0.7f);
                        v.setAlpha(1f - Math.min(0.6f, Math.abs(dx) / AndroidUtilities.dp(240)));
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging[0]) return false;
                    dragging[0] = false;
                    float dx = e.getRawX() - start[0];
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dx) > AndroidUtilities.dp(70)) {
                        switchTo(v, dx < 0 ? next : prev, dx < 0);
                    } else {
                        v.animate().translationX(0).alpha(1f).setDuration(180).start();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private static View sideButton(BaseFragment fragment, Context context, int account, boolean right) {
        LinearLayout box = new LinearLayout(context);
        box.setGravity(Gravity.CENTER);
        int iconColor = fragment.getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon);
        ImageView chevron = new ImageView(context);
        Drawable d = context.getResources().getDrawable(R.drawable.tp_ic_chevron_right).mutate();
        d.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        chevron.setImageDrawable(d);
        if (!right) chevron.setRotation(180);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(17));
        AvatarDrawable placeholder = new AvatarDrawable();
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user != null) {
            placeholder.setInfo(account, user);
            avatar.setForUserOrChat(user, placeholder);
        } else {
            avatar.setImageDrawable(placeholder);
        }
        avatar.setAlpha(0.85f);
        if (right) {
            box.addView(avatar, LayoutHelper.createLinear(34, 34));
            box.addView(chevron, LayoutHelper.createLinear(22, 22, 2f, 0f, 0f, 0f));
        } else {
            box.addView(chevron, LayoutHelper.createLinear(22, 22, 0f, 0f, 2f, 0f));
            box.addView(avatar, LayoutHelper.createLinear(34, 34));
        }
        box.setBackground(Theme.createSelectorDrawable(fragment.getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        box.setContentDescription(user == null ? "" : org.telegram.messenger.UserObject.getUserName(user));
        box.setOnClickListener(v -> {
            View parent = (View) v.getParent();
            View avatarContainer = null;
            if (parent instanceof FrameLayout) {
                FrameLayout frame = (FrameLayout) parent;
                if (frame.getChildCount() > 0) avatarContainer = frame.getChildAt(0);
            }
            switchTo(avatarContainer, account, right);
        });
        return box;
    }

    private static void switchTo(View avatar, int account, boolean toLeft) {
        if (avatar != null) avatar.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        Runnable go = () -> {
            if (LaunchActivity.instance != null) LaunchActivity.instance.switchToAccount(account, true);
        };
        if (avatar == null) { go.run(); return; }
        avatar.animate().translationX(AndroidUtilities.dp(toLeft ? -160 : 160)).alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    avatar.setTranslationX(0);
                    avatar.setAlpha(1f);
                    go.run();
                }).start();
    }
}
