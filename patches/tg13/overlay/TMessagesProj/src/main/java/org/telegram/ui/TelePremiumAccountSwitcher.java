package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.TelePremiumUiPrefs;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Quick account switching in the Settings header: a row of account avatars under the name
 * (the current one has an accent ring) — one tap switches, "+" adds an account.
 * Holding the "Profile" tab in the bottom bar opens Telegram's own account menu as well.
 */
final class TelePremiumAccountSwitcher {
    static final int STRIP_HEIGHT_DP = 60;

    private TelePremiumAccountSwitcher() {}

    static ArrayList<Integer> accounts() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) out.add(a);
        return out;
    }

    /** @return extra header height in dp (0 when there is a single account). */
    static int attach(BaseFragment fragment, FrameLayout topView, float topDp) {
        ArrayList<Integer> list = accounts();
        if (list.size() < 2) return 0;
        Context context = topView.getContext();
        LinearLayout strip = new LinearLayout(context);
        strip.setGravity(Gravity.CENTER);
        int current = fragment.getCurrentAccount();
        int accent = fragment.getThemedColor(Theme.key_featuredStickers_addButton);
        for (int account : list) strip.addView(avatar(fragment, context, account, account == current, accent), LayoutHelper.createLinear(46, 46, 3f, 0f, 3f, 0f));
        Integer free = null;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (!UserConfig.getInstance(a).isClientActivated()) { free = a; break; }
        if (free != null) {
            final int slot = free;
            FrameLayout add = new FrameLayout(context);
            add.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(38), fragment.getThemedColor(Theme.key_windowBackgroundWhite)));
            ImageView plus = new ImageView(context);
            Drawable d = context.getResources().getDrawable(R.drawable.tp_ic_add).mutate();
            d.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            plus.setImageDrawable(d);
            add.addView(plus, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
            add.setOnClickListener(v -> fragment.presentFragment(new LoginActivity(slot)));
            FrameLayout holder = new FrameLayout(context);
            holder.addView(add, LayoutHelper.createFrame(38, 38, Gravity.CENTER));
            strip.addView(holder, LayoutHelper.createLinear(46, 46, 3f, 0f, 3f, 0f));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(strip, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL));
        topView.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 16, topDp, 16, 0));
        return STRIP_HEIGHT_DP;
    }

    private static View avatar(BaseFragment fragment, Context context, int account, boolean selected, int accent) {
        FrameLayout box = new FrameLayout(context) {
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (selected) {
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(AndroidUtilities.dp(2));
                    ring.setColor(accent);
                    canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, AndroidUtilities.dp(21.5f), ring);
                }
            }
        };
        BackupImageView image = new BackupImageView(context);
        image.setRoundRadius(AndroidUtilities.dp(18));
        AvatarDrawable placeholder = new AvatarDrawable();
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user != null) {
            placeholder.setInfo(account, user);
            image.setForUserOrChat(user, placeholder);
        } else {
            image.setImageDrawable(placeholder);
        }
        box.addView(image, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
        box.setContentDescription(user == null ? "" : UserObject.getUserName(user));
        box.setOnClickListener(v -> {
            if (selected) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            LaunchActivity activity = LaunchActivity.instance;
            if (activity != null) {
                if (TelePremiumUiPrefs.classicUiEnabled()) {
                    activity.switchToAccount(account, true);
                    activity.presentFragment(new TelePremiumClassicSettingsActivity());
                } else {
                    activity.switchToAccount(account, true, obj -> MainTabsActivity.openedOnSettings());
                }
            }
        });
        box.setOnLongClickListener(v -> {
            fragment.presentFragment(new TelePremiumNetworkProfileActivity(account));
            return true;
        });
        return box;
    }
}
