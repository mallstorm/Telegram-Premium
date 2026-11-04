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
        actionBar.setTitle("Защита облака");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(28), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        root.setBackgroundColor(theme.background);

        TextView title = new TextView(context);
        title.setText(category == null ? "Облако защищено" : "Защищено: " + TelePremiumCloudSecurityStore.categoryLabel(category));
        title.setTextSize(22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(theme.textPrimary);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(46)));

        status = new TextView(context);
        status.setText(hasPattern ? "Проведите графический ключ" : biometric ? "Подтвердите отпечаток или биометрию" : "Способ разблокировки не настроен");
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
            biometricButton.setText("Использовать биометрию");
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

    private void verifyPattern(String pattern) {
        Context context = getParentActivity();
        if (context == null || completed || patternView == null) return;
        long lockout = TelePremiumCloudSecurityStore.remainingLockoutMs(context);
        if (lockout > 0) {
            status.setText("Слишком много попыток. Повторите через " + Math.max(1, (lockout + 999) / 1000) + " сек.");
            patternView.showError();
            return;
        }
        if (TelePremiumCloudSecurityStore.verifyPattern(context, pattern)) {
            completeUnlock();
        } else {
            long after = TelePremiumCloudSecurityStore.remainingLockoutMs(context);
            status.setText(after > 0 ? "Ввод временно заблокирован на 30 секунд" : "Неверный графический ключ");
            patternView.showError();
        }
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
                    .setTitle("Открыть TelePremium Cloud")
                    .setSubtitle(category == null ? "Подтвердите личность" : "Раздел: " + TelePremiumCloudSecurityStore.categoryLabel(category));
            if (TelePremiumCloudSecurityStore.hasPattern(context)) {
                builder.setNegativeButton("Графический ключ", executor, (DialogInterface dialog, int which) -> status.setText("Проведите графический ключ"));
            } else {
                builder.setNegativeButton("Отмена", executor, (DialogInterface dialog, int which) -> status.setText("Разблокировка отменена"));
            }
            BiometricPrompt prompt = builder.build();
            prompt.authenticate(biometricCancellation, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    completeUnlock();
                }
                @Override public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    status.setText("Биометрия не распознана. Попробуйте снова.");
                }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        status.setText(errString == null ? "Биометрия недоступна" : errString);
                    }
                }
            });
        } catch (Throwable e) {
            status.setText(TelePremiumCloudSecurityStore.hasPattern(context)
                    ? "Биометрия недоступна. Используйте графический ключ."
                    : "Биометрия недоступна на этом устройстве.");
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
                status.setText("Не удалось открыть облако. Повторите попытку.");
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
