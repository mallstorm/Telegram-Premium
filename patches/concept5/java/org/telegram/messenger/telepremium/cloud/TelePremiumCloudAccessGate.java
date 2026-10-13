package org.telegram.messenger.telepremium.cloud;

import android.content.Context;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.TelePremiumCloudActivity;
import org.telegram.ui.TelePremiumCloudUnlockActivity;

public final class TelePremiumCloudAccessGate {
    private TelePremiumCloudAccessGate() {}

    public static void openCloud(LaunchActivity host, int account) {
        if (host == null) return;
        Context context = host;
        if (TelePremiumCloudSecurityStore.requiresCloudUnlock(context)) {
            host.presentFragment(new TelePremiumCloudUnlockActivity(account, null,
                    () -> host.presentFragment(new TelePremiumCloudActivity(account))));
        } else {
            TelePremiumCloudSecuritySession.touch(context);
            host.presentFragment(new TelePremiumCloudActivity(account));
        }
    }

    public static void openCloud(BaseFragment host, int account) {
        if (host == null || host.getParentActivity() == null) return;
        Context context = host.getParentActivity();
        if (TelePremiumCloudSecurityStore.requiresCloudUnlock(context)) {
            host.presentFragment(new TelePremiumCloudUnlockActivity(account, null,
                    () -> host.presentFragment(new TelePremiumCloudActivity(account))));
        } else {
            TelePremiumCloudSecuritySession.touch(context);
            host.presentFragment(new TelePremiumCloudActivity(account));
        }
    }

    public static void openCategory(BaseFragment host, int account, String category, Runnable open) {
        if (host == null || host.getParentActivity() == null || open == null) return;
        Context context = host.getParentActivity();
        if (TelePremiumCloudSecurityStore.requiresCategoryUnlock(context, category)) {
            host.presentFragment(new TelePremiumCloudUnlockActivity(account, category, open));
        } else {
            TelePremiumCloudSecuritySession.touch(context);
            open.run();
        }
    }

    public static boolean canExposeNode(Context context, CloudNode node) {
        return context != null && (!TelePremiumCloudSecurityStore.isNodeProtected(context, node)
                || TelePremiumCloudSecuritySession.isUnlocked(context));
    }

    public static boolean canExposeCategory(Context context, String category) {
        return context != null && !TelePremiumCloudSecurityStore.requiresCategoryUnlock(context, category);
    }
}
