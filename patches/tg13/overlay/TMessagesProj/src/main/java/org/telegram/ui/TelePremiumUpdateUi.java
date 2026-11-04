package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.TelePremiumUpdateChecker;
import org.telegram.ui.ActionBar.AlertDialog;

/** Dialog flow for TelePremium updates: offer → download with progress → verify → system installer. */
public final class TelePremiumUpdateUi {
    private TelePremiumUpdateUi() {}

    public static void offer(Activity activity, TelePremiumUpdateChecker.Release release) {
        String size = release.size > 0 ? AndroidUtilities.formatFileSize(release.size) : "";
        new AlertDialog.Builder(activity)
                .setTitle("Доступно обновление TelePremium")
                .setMessage("Версия " + release.versionName + " (" + release.versionCode + ")" + (size.isEmpty() ? "" : ", " + size)
                        + ".\nAPK будет проверен по SHA-256 и подписи перед установкой.")
                .setPositiveButton("Скачать", (d, w) -> download(activity, release))
                .setNegativeButton("Позже", null)
                .show();
    }

    public static void download(Activity activity, TelePremiumUpdateChecker.Release release) {
        if (!TelePremiumUpdateChecker.canRequestInstall(activity)) {
            Toast.makeText(activity, "Разрешите установку из этого источника и повторите", Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) {
                // Some ROMs do not expose the per-app screen.
            }
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Toast.makeText(activity, "Загрузка обновления…", Toast.LENGTH_SHORT).show();
        TelePremiumUpdateChecker.download(release, null, (apk, error) -> {
            try { progress.dismiss(); } catch (Exception ignored) { }
            if (activity.isFinishing()) return;
            if (error != null) {
                new AlertDialog.Builder(activity).setTitle("Обновление не установлено").setMessage(error).setPositiveButton("OK", null).show();
                return;
            }
            TelePremiumUpdateChecker.install(activity, apk);
        });
    }
}
