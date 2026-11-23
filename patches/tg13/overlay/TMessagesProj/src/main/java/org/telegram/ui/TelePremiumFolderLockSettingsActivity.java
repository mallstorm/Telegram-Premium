package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.messenger.telepremium.cloud.VaultFolderLocks;
import org.telegram.messenger.telepremium.cloud.VaultRekeyJob;
import org.telegram.messenger.telepremium.cloud.VaultSessionGuard;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderBiometric;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultLockTree;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;

import java.util.List;

/** «Защита папок»: auto-lock, screenshots, per-folder secret / biometrics / removal. */
public class TelePremiumFolderLockSettingsActivity extends BaseFragment {
    private final int account;
    private Context context;
    private TelePremiumThemePreset theme;
    private LinearLayout list;
    private final VaultFolderSession.Listener sessionListener = () -> AndroidUtilities.runOnUIThread(this::render);

    public TelePremiumFolderLockSettingsActivity(int account) { this.account = account; }

    @Override public View createView(Context context) {
        this.context = context;
        theme = TelePremiumThemeController.getCurrent(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Защита папок");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(theme.background);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(8), dp(14), dp(32));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        VaultFolderSession.shared().addListener(sessionListener);
        render();
        return fragmentView;
    }

    @Override public void onResume() { super.onResume(); if (list != null) render(); }

    @Override public void onFragmentDestroy() {
        VaultFolderSession.shared().removeListener(sessionListener);
        super.onFragmentDestroy();
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

    private void section(String title) {
        TextView t = text(title, 14, theme.accent, true);
        t.setPadding(dp(4), dp(18), 0, dp(6));
        list.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout row(String title, String subtitle, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.setBackground(round(theme.surfaceElevated, 14));
        r.addView(text(title, 16, theme.textPrimary, true));
        if (subtitle != null) { TextView s = text(subtitle, 13, theme.textSecondary, false); s.setPadding(0, dp(2), 0, 0); r.addView(s); }
        if (click != null) r.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8);
        list.addView(r, p);
        return r;
    }

    private static String timeoutLabel(long ms) { return ms < 120_000L ? "1 минута" : (ms / 60_000L) + " минут"; }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        TelePremiumCloudManager manager = TelePremiumCloudManager.getInstance(account);
        List<CloudNode> locks = manager.getSnapshot() == null ? java.util.Collections.<CloudNode>emptyList()
                : VaultLockTree.lockRoots(manager.getSnapshot().nodes);

        section("Закрытые папки");
        if (locks.isEmpty()) row("Нет защищённых папок", "Откройте меню папки в облаке (⋮) и выберите «Защитить».", null);
        for (CloudNode f : locks) {
            boolean open = VaultFolderSession.shared().isUnlocked(f.id);
            row("🔒  " + f.name, (open ? "Открыта · закроется через " + Math.max(1, VaultFolderSession.shared().remainingMs(f.id) / 1000) + " с"
                    : "Закрыта") + (VaultFolderBiometric.isEnrolled(context, f.id) ? " · отпечаток" : ""), v -> folderMenu(f));
        }
        int waiting = VaultRekeyJob.pending(account);
        if (waiting > 0) row("Идёт перешифровка", "Ожидает файлов: " + waiting + ". Для файлов закрытых папок откройте папку.", v -> VaultRekeyJob.kick(account, null));

        section("Автоблокировка");
        row("Закрывать папки через", timeoutLabel(VaultFolderLocks.autoLockMs()), v -> {
            CharSequence[] items = new CharSequence[VaultFolderLocks.TIMEOUT_CHOICES_MS.length];
            for (int i = 0; i < items.length; i++) items[i] = timeoutLabel(VaultFolderLocks.TIMEOUT_CHOICES_MS[i]);
            new BottomSheet.Builder(context).setTitle("Закрывать папки через").setItems(items, (d, w) -> {
                VaultFolderLocks.setAutoLockMs(VaultFolderLocks.TIMEOUT_CHOICES_MS[w]); render();
            }).create().show();
        });
        row("Экран выключен или приложение свернуто", "Папки закрываются сами (при свёрнутом приложении — через 45 с, кроме воспроизведения)", null);
        row("Запретить снимки экрана", VaultFolderLocks.secureScreen() ? "Включено: внутри закрытых папок скриншоты запрещены" : "Выключено",
                v -> { VaultFolderLocks.setSecureScreen(!VaultFolderLocks.secureScreen()); render(); });
        row("Закрыть все папки сейчас", null, v -> { VaultSessionGuard.lockEverything(); render(); });
    }

    private void folderMenu(CloudNode f) {
        boolean open = VaultFolderSession.shared().isUnlocked(f.id);
        Activity a = getParentActivity();
        boolean bioSupported = a != null && VaultFolderBiometric.supported(a);
        boolean bio = bioSupported && VaultFolderBiometric.isEnrolled(a, f.id);
        CharSequence[] items = {
                open ? "Закрыть сейчас" : "Открыть",
                "Сменить секрет",
                bioSupported ? (bio ? "Выключить отпечаток" : "Включить отпечаток") : "Отпечаток недоступен",
                "Снять защиту"
        };
        new BottomSheet.Builder(context).setTitle(f.name).setItems(items, (d, w) -> {
            if (w == 0) {
                if (open) { VaultFolderLocks.lockNow(f); render(); }
                else presentFragment(new TelePremiumFolderLockActivity(account, f.id, TelePremiumFolderLockActivity.MODE_UNLOCK, this::render));
            } else if (!open && w != 2) {
                presentFragment(new TelePremiumFolderLockActivity(account, f.id, TelePremiumFolderLockActivity.MODE_UNLOCK, () -> folderMenu(f)));
            } else if (w == 1) {
                presentFragment(new TelePremiumFolderLockActivity(account, f.id, TelePremiumFolderLockActivity.MODE_CHANGE, this::render));
            } else if (w == 2 && bioSupported) {
                if (bio) { VaultFolderBiometric.forget(a, f.id); render(); }
                else if (!open) presentFragment(new TelePremiumFolderLockActivity(account, f.id, TelePremiumFolderLockActivity.MODE_UNLOCK, () -> folderMenu(f)));
                else {
                    byte[] fk = VaultFolderSession.shared().get(f.id);
                    if (fk != null) VaultFolderBiometric.enroll(a, f.id, fk, "Отпечаток для папки «" + f.name + "»", "Отмена", e -> { java.util.Arrays.fill(fk, (byte) 0); render(); });
                }
            } else if (w == 3) confirmRemove(f);
        }).create().show();
    }

    private void confirmRemove(CloudNode f) {
        new AlertDialog.Builder(context)
                .setTitle("Снять защиту?")
                .setMessage("Файлы папки будут перешифрованы обычным ключом облака и снова появятся в общих разделах и плеере. Это займёт время и трафик.")
                .setPositiveButton("Снять", (d, w) -> VaultFolderLocks.removeProtection(account, f, context, error -> {
                    if (error != null) AndroidUtilities.runOnUIThread(() -> new AlertDialog.Builder(context).setTitle("Не удалось").setMessage(error).setPositiveButton("OK", null).show());
                    render();
                }))
                .setNegativeButton("Отмена", null).show();
    }
}
