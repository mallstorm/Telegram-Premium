package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.TelePremiumUpdateChecker;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Updates, diagnostics report / logs and Cloud maintenance in one place. */
public class TelePremiumServiceActivity extends BaseFragment {
    private static final long MAX_LOG_BYTES = 20L * 1024L * 1024L;
    private LinearLayout content;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_066));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        render();
        return fragmentView;
    }

    private void render() {
        Context context = getParentActivity();
        if (context == null || content == null) return;
        content.removeAllViews();

        section(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_496));
        String source = TelePremiumUpdateChecker.getSource();
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_497), TextUtils.isEmpty(source) ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_498) : source, R.drawable.msg2_data, v -> editSource());
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_499), TelePremiumUpdateChecker.isAutoCheck() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_500) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_501), R.drawable.msg2_notifications, v -> {
            TelePremiumUpdateChecker.setAutoCheck(!TelePremiumUpdateChecker.isAutoCheck());
            render();
        });
        long last = TelePremiumUpdateChecker.getLastCheck();
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_502), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_503) + TelePremiumUpdateChecker.installedVersionCode()
                + (last > 0 ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_504) + new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(last)) : ""), R.drawable.msg_retry, v -> checkNow());

        section(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_505));
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_506), (BuildVars.LOGS_ENABLED ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_500) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_501)) + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_507), R.drawable.msg_log, v -> toggleLogs());
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_508), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_509), R.drawable.msg_share, v -> shareReport());
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_166), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_510), R.drawable.msg2_folder, v -> presentFragment(new TelePremiumCloudDiagnosticsActivity(UserConfig.selectedAccount)));
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_511), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_512), R.drawable.msg_delete, v -> {
            Utilities.globalQueue.postRunnable(() -> {
                FileLog.cleanupLogs();
                AndroidUtilities.runOnUIThread(() -> toast(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_513)));
            });
        });

        section(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_069));
        int days = TelePremiumCloudManager.getTrashRetentionDays();
        row(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_514), days == 0 ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_501) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_515) + days + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_516), R.drawable.msg_delete, v -> chooseTrashRetention());
    }

    private void editSource() {
        Context context = getParentActivity();
        if (context == null) return;
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_517));
        input.setText(TelePremiumUpdateChecker.getSource());
        LinearLayout box = new LinearLayout(context);
        box.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(context)
                .setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_497))
                .setMessage(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_518))
                .setView(box)
                .setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_137), (d, w) -> { TelePremiumUpdateChecker.setSource(input.getText().toString()); render(); })
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_135), null)
                .show();
    }

    private void checkNow() {
        if (!TelePremiumUpdateChecker.isConfigured()) { editSource(); return; }
        toast(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_519));
        TelePremiumUpdateChecker.check((newer, error) -> {
            Activity activity = getParentActivity();
            if (activity == null) return;
            render();
            if (error != null) toast(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_520) + error);
            else if (newer == null) toast(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_521));
            else TelePremiumUpdateUi.offer(activity, newer);
        });
    }

    private void toggleLogs() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
        boolean enabled = !prefs.getBoolean("logsEnabled", BuildVars.DEBUG_VERSION);
        prefs.edit().putBoolean("logsEnabled", enabled).commit();
        BuildVars.LOGS_ENABLED = enabled;
        render();
        toast(enabled ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_522) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_523));
    }

    private void chooseTrashRetention() {
        Context context = getParentActivity();
        if (context == null) return;
        int[] choices = TelePremiumCloudManager.TRASH_RETENTION_CHOICES;
        CharSequence[] labels = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) labels[i] = choices[i] == 0 ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_524) : choices[i] + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_525);
        new AlertDialog.Builder(context)
                .setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_514))
                .setItems(labels, (d, which) -> { TelePremiumCloudManager.setTrashRetentionDays(choices[which]); render(); })
                .show();
    }

    static String buildReport() {
        Context ctx = ApplicationLoader.applicationContext;
        StringBuilder sb = new StringBuilder();
        sb.append("TelePremium diagnostics report\n");
        sb.append("time: ").append(new Date()).append('\n');
        sb.append("package: ").append(ctx.getPackageName()).append('\n');
        sb.append("versionCode: ").append(TelePremiumUpdateChecker.installedVersionCode()).append('\n');
        sb.append("telegram: ").append(BuildVars.BUILD_VERSION_STRING).append('\n');
        String cert = TelePremiumUpdateChecker.installedCertSha256();
        sb.append("signer: ").append(cert == null ? "?" : cert.substring(0, 16) + "…").append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("abis: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        int active = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) if (UserConfig.getInstance(a).isClientActivated()) active++;
        sb.append("accounts: ").append(active).append(" / ").append(UserConfig.MAX_ACCOUNT_COUNT).append('\n');
        sb.append("cloud.protection: ").append(TelePremiumCloudSecurityStore.isProtectionEnabled(ctx))
                .append(" whole=").append(TelePremiumCloudSecurityStore.isWholeCloudLocked(ctx))
                .append(" biometric=").append(TelePremiumCloudSecurityStore.isBiometricEnabled(ctx)).append('\n');
        sb.append("vault: enabled=").append(TelePremiumVaultKeyManager.isEnabled(ctx))
                .append(" key=").append(TelePremiumVaultKeyManager.hasMasterKey(ctx))
                .append(" recoveryConfirmed=").append(TelePremiumVaultKeyManager.isRecoveryConfirmed(ctx)).append('\n');
        sb.append("trash.retentionDays: ").append(TelePremiumCloudManager.getTrashRetentionDays()).append('\n');
        sb.append("updates.source: ").append(TelePremiumUpdateChecker.isConfigured() ? "set" : "none").append('\n');
        sb.append("logs: ").append(BuildVars.LOGS_ENABLED).append('\n');
        return sb.toString();
    }

    private void shareReport() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        toast(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_526));
        Utilities.globalQueue.postRunnable(() -> {
            File zip = null;
            String error = null;
            try {
                File dir = AndroidUtilities.getLogsDir();
                zip = new File(dir, "telepremium-report.zip");
                if (zip.exists()) zip.delete();
                File[] logs = dir.listFiles((d, name) -> !name.endsWith(".zip") && !name.endsWith(".hprof"));
                if (logs != null) Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                    out.putNextEntry(new ZipEntry("report.txt"));
                    out.write(buildReport().getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                    long budget = MAX_LOG_BYTES;
                    if (logs != null) {
                        byte[] buf = new byte[64 * 1024];
                        for (File f : logs) {
                            if (!f.isFile() || f.length() > budget) continue;
                            budget -= f.length();
                            out.putNextEntry(new ZipEntry("logs/" + f.getName()));
                            try (FileInputStream in = new FileInputStream(f)) {
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            out.closeEntry();
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            final File result = zip;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                Activity a = getParentActivity();
                if (a == null) return;
                if (err != null || result == null) { toast(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_527) + err); return; }
                Uri uri = FileProvider.getUriForFile(a, ApplicationLoader.getApplicationId() + ".provider", result);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/zip");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, "TelePremium diagnostics");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.startActivity(Intent.createChooser(send, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_508)));
            });
        });
    }

    private void toast(String text) {
        Context context = getParentActivity();
        if (context != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private void section(String title) {
        TextView header = new TextView(getParentActivity());
        header.setText(title);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(20), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        content.addView(header);
    }

    private void row(String title, String subtitle, int icon, View.OnClickListener listener) {
        TextCell cell = new TextCell(getParentActivity(), getResourceProvider());
        cell.setTextAndValueAndIcon(title, subtitle, icon, true);
        cell.setOnClickListener(listener);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(cell, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(58)));
    }
}
