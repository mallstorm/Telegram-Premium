package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.cloud.TelePremiumPatternView;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.Executor;

public class TelePremiumCloudUnlockActivity extends BaseFragment {
    private final int account;
    private final String category;
    private final Runnable onUnlocked;
    private TextView status;
    private TelePremiumPatternView patternView;
    private CancellationSignal biometricCancellation;
    private boolean completed;

    public TelePremiumCloudUnlockActivity(int account, String category, Runnable onUnlocked) {
        this.account = account;
        this.category = category;
        this.onUnlocked = onUnlocked;
    }

    @Override
    public View createView(Context context) {
        TelePremiumThemePreset theme = TelePremiumThemeController.getCurrent(context);
        boolean hasPattern = TelePremiumCloudSecurityStore.hasPattern(context);
        boolean biometric = TelePremiumCloudSecurityStore.isBiometricEnabled(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_054));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(28), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        root.setBackgroundColor(theme.background);

        TextView title = new TextView(context);
        title.setText(category == null ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_282) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_283) + TelePremiumCloudSecurityStore.categoryLabel(category));
        title.setTextSize(22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(theme.textPrimary);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(46)));

        status = new TextView(context);
        status.setText(hasPattern ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_284) : biometric ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_285) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_286));
        status.setTextSize(14);
        status.setTextColor(theme.textSecondary);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(42)));

        if (hasPattern) {
            patternView = new TelePremiumPatternView(context);
            patternView.setListener(this::verifyPattern);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(AndroidUtilities.dp(260), AndroidUtilities.dp(260));
            pp.gravity = Gravity.CENTER_HORIZONTAL;
            pp.setMargins(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(14));
            root.addView(patternView, pp);
        }

        if (biometric) {
            TextView biometricButton = new TextView(context);
            biometricButton.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_287));
            biometricButton.setTextSize(15);
            biometricButton.setTextColor(theme.accent);
            biometricButton.setGravity(Gravity.CENTER);
            biometricButton.setBackgroundColor(theme.surfaceElevated);
            biometricButton.setOnClickListener(v -> requestBiometric());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(AndroidUtilities.dp(260), AndroidUtilities.dp(50));
            bp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(biometricButton, bp);
            root.postDelayed(this::requestBiometric, 250);
        }

        fragmentView = root;
        return fragmentView;
    }

    private boolean verifying;

    private static String formatLockout(long ms) {
        long seconds = Math.max(1, (ms + 999) / 1000);
        if (seconds < 120) {
            return org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.TP_DurationSeconds, (int) seconds);
        }
        return org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.TP_DurationMinutes, (int) ((seconds + 59) / 60));
    }

    private void verifyPattern(String pattern) {
        Context context = getParentActivity();
        if (context == null || completed || patternView == null || verifying) return;
        long lockout = TelePremiumCloudSecurityStore.remainingLockoutMs(context);
        if (lockout > 0) {
            status.setText(org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.TP_PatternTooManyAttempts, formatLockout(lockout)));
            patternView.showError();
            return;
        }
        verifying = true;
        status.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_PatternChecking));
        TelePremiumCloudSecurityStore.verifyPatternAsync(context, pattern, ok -> {
            verifying = false;
            if (completed || patternView == null || getParentActivity() == null) return;
            if (ok) {
                completeUnlock();
                return;
            }
            long after = TelePremiumCloudSecurityStore.remainingLockoutMs(getParentActivity());
            status.setText(after > 0
                    ? org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.TP_PatternLockedFor, formatLockout(after))
                    : org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.TP_PatternWrongLeft, TelePremiumCloudSecurityStore.attemptsBeforeLockout(getParentActivity())));
            patternView.showError();
        });
    }

    private void requestBiometric() {
        if (completed || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || getParentActivity() == null) return;
        Context context = getParentActivity();
        if (!TelePremiumCloudSecurityStore.isBiometricEnabled(context)) return;
        try {
            if (biometricCancellation != null) biometricCancellation.cancel();
            biometricCancellation = new CancellationSignal();
            Executor executor = context.getMainExecutor();
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(context)
                    .setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_288))
                    .setSubtitle(category == null ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_289) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_290) + TelePremiumCloudSecurityStore.categoryLabel(category));
            if (TelePremiumCloudSecurityStore.hasPattern(context)) {
                builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_261), executor, (DialogInterface dialog, int which) -> status.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_284)));
            } else {
                builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_135), executor, (DialogInterface dialog, int which) -> status.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_291)));
            }
            BiometricPrompt prompt = builder.build();
            prompt.authenticate(biometricCancellation, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    completeUnlock();
                }
                @Override public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    status.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_292));
                }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        status.setText(errString == null ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_267) : errString);
                    }
                }
            });
        } catch (Throwable e) {
            status.setText(TelePremiumCloudSecurityStore.hasPattern(context)
                    ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_293)
                    : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_294));
        }
    }

    private void completeUnlock() {
        if (completed || getParentActivity() == null) return;
        completed = true;
        TelePremiumCloudSecuritySession.unlock(getParentActivity());
        if (biometricCancellation != null) biometricCancellation.cancel();
        if (onUnlocked == null && category == null) {
            // Open Cloud from the *active* unlock fragment and atomically replace it.
            // The previous host fragment may already have been destroyed.
            if (!presentFragment(new TelePremiumCloudActivity(account), true)) {
                completed = false;
                status.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_295));
            }
        } else {
            finishFragment();
            if (onUnlocked != null) AndroidUtilities.runOnUIThread(onUnlocked, 80);
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (biometricCancellation != null) biometricCancellation.cancel();
        super.onFragmentDestroy();
    }
}
