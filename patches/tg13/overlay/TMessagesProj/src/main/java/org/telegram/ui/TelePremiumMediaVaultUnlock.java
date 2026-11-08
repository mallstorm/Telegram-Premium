package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.cloud.TelePremiumPatternView;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;

/** Uses the existing Cloud pattern/biometrics and lockout policy in a standalone media Activity. */
final class TelePremiumMediaVaultUnlock {
    interface Callback { void complete(boolean unlocked); }
    private final Activity activity;
    private final Callback callback;
    private final AlertDialog dialog;
    private final TextView status;
    private TelePremiumPatternView pattern;
    private CancellationSignal cancellation;
    private boolean verifying, unlocked, closed;

    TelePremiumMediaVaultUnlock(Activity activity, Callback callback) {
        this.activity = activity; this.callback = callback;
        TelePremiumThemePreset theme = TelePremiumThemeController.getCurrent(activity);
        LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setGravity(Gravity.CENTER_HORIZONTAL); fields.setPadding(dp(16), dp(8), dp(16), dp(12));
        status = new TextView(activity); status.setTextSize(14); status.setGravity(Gravity.CENTER);
        status.setText(TPStrings.get("TP_L10N_284", R.string.TP_L10N_284));
        fields.addView(status, new LinearLayout.LayoutParams(-1, -2));
        if (TelePremiumCloudSecurityStore.hasPattern(activity)) {
            pattern = new TelePremiumPatternView(activity); pattern.setListener(this::verify);
            fields.addView(pattern, new LinearLayout.LayoutParams(dp(250), dp(250)));
        }
        if (TelePremiumCloudSecurityStore.isBiometricEnabled(activity) && Build.VERSION.SDK_INT >= 28) {
            TextView biometric = new TextView(activity); biometric.setTextSize(16); biometric.setGravity(Gravity.CENTER);
            biometric.setTextColor(theme.accent); biometric.setText(TPStrings.get("TP_L10N_287", R.string.TP_L10N_287));
            biometric.setPadding(0, dp(14), 0, dp(14)); biometric.setOnClickListener(v -> biometric()); fields.addView(biometric);
        }
        dialog = new AlertDialog.Builder(activity).setTitle(TPStrings.get("TP_L10N_288", R.string.TP_L10N_288))
                .setView(fields).setNegativeButton(TPStrings.get("TP_L10N_135", R.string.TP_L10N_135), null).create();
        dialog.setOnDismissListener(d -> {
            if (closed) return; closed = true;
            if (cancellation != null) cancellation.cancel();
            callback.complete(unlocked);
        });
    }
    void show() {
        dialog.show();
        if (TelePremiumCloudSecurityStore.isBiometricEnabled(activity) && Build.VERSION.SDK_INT >= 28)
            status.postDelayed(this::biometric, 200);
    }
    void close() { dialog.dismiss(); }
    private int dp(float value) { return AndroidUtilities.dp(value); }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void verify(String value) {
        if (!alive() || verifying) return;
        long delay = TelePremiumCloudSecurityStore.remainingLockoutMs(activity);
        if (delay > 0) { locked(delay); return; }
        verifying = true; status.setText(TPStrings.get("TP_PatternChecking", R.string.TP_PatternChecking));
        TelePremiumCloudSecurityStore.verifyPatternAsync(activity, value, ok -> {
            verifying = false;
            if (!alive()) return;
            if (ok) { success(); return; }
            long remaining = TelePremiumCloudSecurityStore.remainingLockoutMs(activity);
            if (remaining > 0) locked(remaining);
            else status.setText(TPStrings.format("TP_PatternWrongLeft", R.string.TP_PatternWrongLeft,
                    TelePremiumCloudSecurityStore.attemptsBeforeLockout(activity)));
            pattern.showError();
        });
    }
    private void locked(long delay) {
        String duration = TPStrings.format("TP_DurationSeconds", R.string.TP_DurationSeconds, (int) Math.max(1, (delay + 999) / 1000));
        status.setText(TPStrings.format("TP_PatternLockedFor", R.string.TP_PatternLockedFor, duration));
        if (pattern != null) pattern.showError();
    }
    private void biometric() {
        if (!alive() || Build.VERSION.SDK_INT < 28 || !TelePremiumCloudSecurityStore.isBiometricEnabled(activity)) return;
        try {
            if (cancellation != null) cancellation.cancel();
            cancellation = new CancellationSignal();
            new BiometricPrompt.Builder(activity).setTitle(TPStrings.get("TP_L10N_288", R.string.TP_L10N_288))
                    .setNegativeButton(TPStrings.get("TP_L10N_135", R.string.TP_L10N_135), activity.getMainExecutor(), (d, w) -> {})
                    .build().authenticate(cancellation, activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { if (alive()) success(); }
                        @Override public void onAuthenticationError(int code, CharSequence message) { if (alive() && message != null) status.setText(message); }
                    });
        } catch (Exception e) { status.setText(TPStrings.get("TP_L10N_293", R.string.TP_L10N_293)); }
    }
    private void success() {
        if (!alive()) return;
        TelePremiumCloudSecuritySession.unlock(activity); unlocked = true; dialog.dismiss();
    }
}
