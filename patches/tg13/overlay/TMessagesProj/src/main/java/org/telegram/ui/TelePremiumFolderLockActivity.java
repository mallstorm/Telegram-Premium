package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.messenger.telepremium.cloud.TelePremiumPatternView;
import org.telegram.messenger.telepremium.cloud.VaultFolderLocks;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderBiometric;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultSecretPolicy;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * One screen for both directions: asking for the secret of a closed folder, and choosing a new one
 * (protect / change). The folder's contents never load until this screen reports success.
 */
public class TelePremiumFolderLockActivity extends BaseFragment {
    public static final int MODE_UNLOCK = 0, MODE_PROTECT = 1, MODE_CHANGE = 2;

    private final int account, mode;
    private final String folderId;
    private final Runnable onSuccess;
    private VaultSecretPolicy.Kind kind = VaultSecretPolicy.Kind.PASSWORD;
    private TelePremiumThemePreset theme;
    private Context context;
    private LinearLayout form;
    private TextView status;
    private EditText input, confirm;
    private TelePremiumPatternView pattern;
    private String firstPattern;
    private boolean busy, done;
    private CountDownTimer lockoutTimer;
    private CancellationSignal bioSignal;

    public TelePremiumFolderLockActivity(int account, String folderId, int mode, Runnable onSuccess) {
        this.account = account; this.folderId = folderId; this.mode = mode; this.onSuccess = onSuccess;
    }

    private CloudNode folder() { return TelePremiumCloudManager.getInstance(account).findNodeById(folderId); }

    @Override public View createView(Context context) {
        this.context = context;
        theme = TelePremiumThemeController.getCurrent(context);
        CloudNode f = folder();
        if (f == null) { AndroidUtilities.runOnUIThread(this::finishFragment); return fragmentView = new View(context); }
        if (mode == MODE_UNLOCK) kind = VaultFolderLocks.kindHint(f.id);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(mode == MODE_UNLOCK ? "Папка защищена" : mode == MODE_PROTECT ? "Защитить папку" : "Сменить секрет");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER_HORIZONTAL);
        form.setPadding(dp(24), dp(28), dp(24), dp(28));
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        render();
        if (mode == MODE_UNLOCK && VaultFolderBiometric.isEnrolled(context, f.id)) form.postDelayed(this::biometric, 300);
        return fragmentView;
    }

    private int dp(int v) { return AndroidUtilities.dp(v); }

    private GradientDrawable round(int color, int r) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(r)); return d;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(context); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    private void render() {
        form.removeAllViews();
        CloudNode f = folder();
        if (f == null) return;
        TextView lock = text("🔒", 34, Color.WHITE, false);
        lock.setGravity(Gravity.CENTER);
        lock.setBackground(round(theme.accent, 36));
        form.addView(lock, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = text(f.name, 20, theme.textPrimary, true);
        name.setGravity(Gravity.CENTER); name.setPadding(0, dp(14), 0, dp(4)); name.setSingleLine(true);
        form.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text(mode == MODE_UNLOCK ? "Содержимое скрыто и зашифровано ключом этой папки."
                : mode == MODE_PROTECT ? "Выберите способ защиты. Секрет нигде не хранится — забудете его, и файлы папки не восстановить."
                : "Новый секрет заменит текущий. Файлы перешифровывать не нужно.", 13, theme.textSecondary, false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0, 0, 0, dp(14));
        form.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout chips = new LinearLayout(context);
        chips.setGravity(Gravity.CENTER);
        addChip(chips, "PIN", VaultSecretPolicy.Kind.PIN);
        addChip(chips, "Пароль", VaultSecretPolicy.Kind.PASSWORD);
        addChip(chips, "Графический ключ", VaultSecretPolicy.Kind.PATTERN);
        form.addView(chips, new LinearLayout.LayoutParams(-1, dp(44)));

        status = text("", 13, theme.textSecondary, false);
        status.setGravity(Gravity.CENTER); status.setPadding(0, dp(10), 0, dp(10));

        firstPattern = null;
        if (kind == VaultSecretPolicy.Kind.PATTERN) {
            pattern = new TelePremiumPatternView(context);
            pattern.setListener(this::onPattern);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(260), dp(260));
            pp.topMargin = dp(10);
            form.addView(pattern, pp);
            status.setText(mode == MODE_UNLOCK ? "Нарисуйте графический ключ" : "Нарисуйте новый ключ (минимум 5 точек)");
            form.addView(status, new LinearLayout.LayoutParams(-1, -2));
        } else {
            pattern = null;
            boolean pin = kind == VaultSecretPolicy.Kind.PIN;
            input = field(pin ? "PIN (6–32 цифр)" : "Пароль", pin);
            form.addView(input, fieldParams());
            if (mode != MODE_UNLOCK) { confirm = field("Повторите", pin); form.addView(confirm, fieldParams()); } else confirm = null;
            form.addView(status, new LinearLayout.LayoutParams(-1, -2));
            TextView go = text(mode == MODE_UNLOCK ? "Открыть" : mode == MODE_PROTECT ? "Включить защиту" : "Сменить секрет", 16, Color.WHITE, true);
            go.setGravity(Gravity.CENTER); go.setBackground(round(theme.accent, 14));
            go.setOnClickListener(v -> submitText());
            form.addView(go, new LinearLayout.LayoutParams(-1, dp(52)));
        }
        if (mode == MODE_UNLOCK && VaultFolderBiometric.isEnrolled(context, f.id)) {
            TextView bio = text("Отпечаток / лицо", 15, theme.accent, false);
            bio.setGravity(Gravity.CENTER); bio.setPadding(0, dp(18), 0, 0);
            bio.setOnClickListener(v -> biometric());
            form.addView(bio, new LinearLayout.LayoutParams(-1, dp(56)));
        }
        updateLockout();
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50)); p.topMargin = dp(8); return p;
    }

    private EditText field(String hint, boolean numeric) {
        EditText e = new EditText(context);
        e.setHint(hint); e.setSingleLine(true); e.setTextSize(17);
        e.setTextColor(theme.textPrimary); e.setHintTextColor(theme.textSecondary);
        e.setPadding(dp(16), 0, dp(16), 0);
        e.setBackground(round(theme.searchBackground, 14));
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void addChip(LinearLayout row, String label, VaultSecretPolicy.Kind k) {
        TextView c = text(label, 13, k == kind ? Color.WHITE : theme.textPrimary, k == kind);
        c.setGravity(Gravity.CENTER); c.setPadding(dp(12), 0, dp(12), 0);
        c.setBackground(round(k == kind ? theme.accent : theme.surfaceElevated, 18));
        c.setOnClickListener(v -> { if (!busy && kind != k) { kind = k; render(); } });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36)); p.setMargins(dp(4), 0, dp(4), 0);
        row.addView(c, p);
    }

    private void setStatus(String s, boolean error) {
        if (status == null) return;
        status.setText(s); status.setTextColor(error ? 0xffd0443e : theme.textSecondary);
    }

    private void updateLockout() {
        if (mode != MODE_UNLOCK || folder() == null) return;
        if (lockoutTimer != null) lockoutTimer.cancel();
        long left = VaultFolderLocks.limiter().remainingMs(folderId);
        if (left <= 0) return;
        lockoutTimer = new CountDownTimer(left, 1000) {
            @Override public void onTick(long ms) { setStatus("Слишком много попыток. Подождите " + (ms / 1000 + 1) + " с", true); }
            @Override public void onFinish() { setStatus("Можно пробовать снова", false); }
        }.start();
    }

    private void submitText() {
        if (busy || done) return;
        String raw = input.getText().toString();
        if (mode == MODE_UNLOCK) { run(raw); return; }
        if (!raw.equals(confirm.getText().toString())) { setStatus("Значения не совпадают", true); return; }
        VaultSecretPolicy.Verdict v = VaultSecretPolicy.check(kind, raw);
        if (!VaultSecretPolicy.isAcceptable(v)) { setStatus(verdictText(v), true); return; }
        run(raw);
    }

    private void onPattern(String p) {
        if (busy || done) return;
        if (mode == MODE_UNLOCK) { run(p); return; }
        if (firstPattern == null) {
            VaultSecretPolicy.Verdict v = VaultSecretPolicy.check(kind, p);
            if (!VaultSecretPolicy.isAcceptable(v)) { setStatus(verdictText(v), true); pattern.showError(); return; }
            firstPattern = p; setStatus("Повторите ключ", false); pattern.clearPattern();
        } else if (firstPattern.equals(p)) run(p);
        else { firstPattern = null; setStatus("Ключи не совпали. Начните заново", true); pattern.showError(); }
    }

    private static String verdictText(VaultSecretPolicy.Verdict v) {
        switch (v) {
            case TOO_SHORT: return "Слишком короткий секрет";
            case TOO_LONG: return "Слишком длинный секрет";
            case INVALID_CHARS: return "Недопустимые символы";
            case TRIVIAL: return "Слишком простой — выберите менее очевидный";
            default: return "Секрет не подходит";
        }
    }

    private void run(String raw) {
        CloudNode f = folder();
        if (f == null) return;
        busy = true; setStatus(mode == MODE_UNLOCK ? "Проверяю…" : "Защищаю…", false);
        VaultFolderLocks.Result result = error -> {
            busy = false;
            if (getParentActivity() == null) return;
            if (error != null) {
                setStatus(error, true);
                if (pattern != null) pattern.showError();
                if (mode == MODE_UNLOCK) updateLockout();
                return;
            }
            success(f);
        };
        if (mode == MODE_UNLOCK) VaultFolderLocks.unlock(account, f, kind, raw, result);
        else if (mode == MODE_PROTECT) VaultFolderLocks.protect(account, f, kind, raw, result);
        else VaultFolderLocks.changeSecret(account, f, kind, raw, result);
    }

    private void success(CloudNode f) {
        if (done) return;
        done = true;
        if (bioSignal != null) bioSignal.cancel();
        Activity activity = getParentActivity();
        if (mode == MODE_PROTECT && activity != null && VaultFolderBiometric.supported(activity)) {
            new org.telegram.ui.ActionBar.AlertDialog.Builder(activity)
                    .setTitle("Открывать отпечатком?")
                    .setMessage("Ключ папки будет привязан к отпечатку в защищённом хранилище телефона. Пароль останется запасным способом.")
                    .setPositiveButton("Включить", (d, w) -> enrollBiometric(f))
                    .setNegativeButton("Не сейчас", (d, w) -> finishWithSuccess())
                    .show();
            return;
        }
        finishWithSuccess();
    }

    private void enrollBiometric(CloudNode f) {
        Activity activity = getParentActivity();
        byte[] fk = VaultFolderSession.shared().get(f.id);
        if (activity == null || fk == null) { finishWithSuccess(); return; }
        VaultFolderBiometric.enroll(activity, f.id, fk, "Отпечаток для папки «" + f.name + "»", "Отмена", err -> {
            java.util.Arrays.fill(fk, (byte) 0);
            finishWithSuccess();
        });
    }

    private void finishWithSuccess() {
        finishFragment();
        if (onSuccess != null) AndroidUtilities.runOnUIThread(onSuccess, 80);
    }

    private void biometric() {
        Activity activity = getParentActivity();
        CloudNode f = folder();
        if (activity == null || f == null || done || busy) return;
        if (bioSignal != null) bioSignal.cancel();
        bioSignal = VaultFolderBiometric.unlock(activity, f.id, "Откройте папку «" + f.name + "»", "Пароль", new VaultFolderBiometric.KeyCallback() {
            @Override public void onKey(byte[] key) {
                VaultFolderLocks.unlockWithKey(account, f, key, error -> { if (error == null) success(f); else setStatus(error, true); });
            }
            @Override public void onError(String message) { setStatus(message, true); }
        });
    }

    @Override public void onFragmentDestroy() {
        if (lockoutTimer != null) lockoutTimer.cancel();
        if (bioSignal != null) bioSignal.cancel();
        super.onFragmentDestroy();
    }
}
