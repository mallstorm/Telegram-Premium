package org.telegram.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumSessionBackup;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Export every signed-in account to an encrypted file and restore from it. */
public final class TelePremiumSessionBackupActivity extends BaseFragment {
    private static final int REQ_EXPORT = 7301, REQ_IMPORT = 7302;
    private LinearLayout content;
    private final boolean[] selected = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private boolean includeXray = true;
    private byte[] pendingFile;
    private String t(String en, String ru) { return TPStrings.isRussian() ? ru : en; }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(t("Sessions backup", "Сессии: резервная копия"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        for (int a = 0; a < selected.length; a++) selected[a] = true;
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(32));
        scroll.addView(content);
        fragmentView = scroll;
        render();
        return scroll;
    }

    private void render() {
        Context context = getParentActivity();
        if (context == null || content == null) return;
        content.removeAllViews();
        label(t("Save the signed-in accounts to one password-protected file (put it on Google Drive). If the phone breaks or the app is removed, restore them here without logging in again.",
                "Сохраните авторизованные аккаунты в один файл под паролем (например, на Google Диск). Если телефон сломается или приложение удалится, восстановите их здесь без повторного входа."));
        label(t("Accounts to save", "Аккаунты для сохранения"));
        for (TelePremiumSessionBackup.Account acc : TelePremiumSessionBackup.activeAccounts()) {
            final int slot = acc.src;
            TextCheckCell cell = new TextCheckCell(context);
            cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            String title = acc.name + (acc.phone.isEmpty() ? "" : " · +" + acc.phone.replace("+", ""));
            cell.setTextAndCheck(title, selected[slot], true);
            cell.setOnClickListener(v -> { selected[slot] = !selected[slot]; cell.setChecked(selected[slot]); });
            content.addView(cell, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(56)));
        }
        TextCheckCell xray = new TextCheckCell(context);
        xray.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        xray.setTextAndCheck(t("Include VLESS / Xray keys", "Включить ключи VLESS / Xray"), includeXray, false);
        xray.setOnClickListener(v -> { includeXray = !includeXray; xray.setChecked(includeXray); });
        content.addView(xray, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(56)));

        action(t("Save sessions to file…", "Сохранить сессии в файл…"), t("Encrypted, you choose the place (Drive, Files)", "Шифруется паролем, место выбираете сами (Диск, Файлы)"), this::startExport);
        action(t("Restore from file…", "Восстановить из файла…"), t("Signs the saved accounts back in and restarts the app", "Вернёт сохранённые аккаунты и перезапустит приложение"), this::startImport);

        label(t("Keep the file private: with the password it gives full access to the accounts. Do not run the same session on two devices at once: Telegram can end it. Local chat cache and secret chats are not included; cloud chats reload from the server.",
                "Храните файл в тайне: вместе с паролем он даёт полный доступ к аккаунтам. Не запускайте одну сессию на двух устройствах одновременно: Telegram может её завершить. Локальный кэш чатов и секретные чаты не сохраняются; облачные чаты загрузятся с сервера."));
    }

    private TextCell action(String title, String value, Runnable click) {
        TextCell c = new TextCell(getParentActivity(), getResourceProvider());
        c.setTextAndValue(title, value, true);
        c.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        c.setOnClickListener(v -> click.run());
        content.addView(c, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(60)));
        return c;
    }

    private void label(String text) {
        TextView v = new TextView(getParentActivity());
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        v.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        content.addView(v);
    }

    private void toast(String text) {
        if (getParentActivity() != null) Toast.makeText(getParentActivity(), text, Toast.LENGTH_LONG).show();
    }

    private interface PasswordResult { void run(char[] password); }

    private void askPassword(boolean confirm, PasswordResult result) {
        Context context = getParentActivity();
        if (context == null) return;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        EditText first = new EditText(context), second = new EditText(context);
        first.setHint(t("Password", "Пароль"));
        second.setHint(t("Repeat password", "Повторите пароль"));
        for (EditText e : new EditText[]{first, second}) {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        box.addView(first);
        if (confirm) box.addView(second);
        AlertDialog.Builder b = new AlertDialog.Builder(context, getResourceProvider());
        b.setTitle(confirm ? t("Set a file password", "Задайте пароль файла") : t("File password", "Пароль файла"));
        if (confirm) b.setMessage(t("At least 8 characters. Without it the file cannot be opened and cannot be recovered.", "Не менее 8 символов. Без него файл не открыть и не восстановить."));
        b.setView(box);
        b.setPositiveButton(t("Continue", "Продолжить"), (d, w) -> {
            String p1 = first.getText().toString(), p2 = second.getText().toString();
            first.setText("");
            second.setText("");
            if (confirm && p1.length() < 8) { toast(t("Password is too short", "Пароль слишком короткий")); return; }
            if (confirm && !p1.equals(p2)) { toast(t("Passwords do not match", "Пароли не совпадают")); return; }
            if (p1.isEmpty()) return;
            result.run(p1.toCharArray());
        });
        b.setNegativeButton(t("Cancel", "Отмена"), null);
        showDialog(b.create());
    }

    private void startExport() {
        final List<Integer> slots = new ArrayList<>();
        for (TelePremiumSessionBackup.Account a : TelePremiumSessionBackup.activeAccounts()) if (selected[a.src]) slots.add(a.src);
        if (slots.isEmpty()) { toast(t("Select at least one account", "Выберите хотя бы один аккаунт")); return; }
        askPassword(true, password -> {
            toast(t("Preparing the file…", "Готовлю файл…"));
            final boolean xray = includeXray;
            new Thread(() -> {
                byte[] file = null;
                String error = null;
                try {
                    file = TelePremiumSessionBackup.export(slots, xray, password);
                } catch (IllegalStateException e) {
                    error = "NO_SESSION_FILE".equals(e.getMessage()) ? t("Session file of an account was not found", "Не найден файл сессии одного из аккаунтов") : t("Nothing to save", "Нечего сохранять");
                } catch (Throwable e) {
                    error = t("Could not prepare the file", "Не удалось подготовить файл");
                }
                final byte[] result = file;
                final String message = error;
                AndroidUtilities.runOnUIThread(() -> {
                    if (message != null) { toast(message); return; }
                    pendingFile = result;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, "telepremium-sessions-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".tpsession");
                    startActivityForResult(intent, REQ_EXPORT);
                });
            }, "tp-session-export").start();
        });
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (getParentActivity() == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_EXPORT) pendingFile = null;
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            final byte[] file = pendingFile;
            pendingFile = null;
            if (file == null) return;
            new Thread(() -> {
                boolean ok = false;
                try (OutputStream out = getParentActivity().getContentResolver().openOutputStream(uri, "wt")) {
                    if (out != null) { out.write(file); out.flush(); ok = true; }
                } catch (Throwable ignored) {
                }
                final boolean saved = ok;
                AndroidUtilities.runOnUIThread(() -> toast(saved ? t("Sessions saved. Keep the file and the password.", "Сессии сохранены. Сохраните файл и пароль.") : t("Could not write the file", "Не удалось записать файл")));
            }, "tp-session-write").start();
        } else if (requestCode == REQ_IMPORT) {
            new Thread(() -> {
                byte[] bytes = null;
                try (InputStream in = getParentActivity().getContentResolver().openInputStream(uri)) {
                    if (in != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            if (out.size() > 48 * 1024 * 1024) throw new IllegalArgumentException("big");
                        }
                        bytes = out.toByteArray();
                    }
                } catch (Throwable ignored) {
                }
                final byte[] file = bytes;
                AndroidUtilities.runOnUIThread(() -> {
                    if (file == null) { toast(t("Could not read the file", "Не удалось прочитать файл")); return; }
                    askPassword(false, password -> openFile(file, password));
                });
            }, "tp-session-read").start();
        }
    }

    private void openFile(byte[] file, char[] password) {
        toast(t("Checking the file…", "Проверяю файл…"));
        new Thread(() -> {
            TelePremiumSessionBackup.Opened opened = null;
            String error = null;
            try {
                opened = TelePremiumSessionBackup.open(file, password);
            } catch (SecurityException e) {
                error = t("Wrong password or damaged file", "Неверный пароль или файл повреждён");
            } catch (Throwable e) {
                error = t("This is not a TelePremium sessions file", "Это не файл сессий TelePremium");
            }
            final TelePremiumSessionBackup.Opened result = opened;
            final String message = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (message != null) { toast(message); return; }
                confirmRestore(result);
            });
        }, "tp-session-open").start();
    }

    private void confirmRestore(TelePremiumSessionBackup.Opened opened) {
        if (getParentActivity() == null) return;
        TelePremiumSessionBackup.assignSlots(opened.accounts);
        StringBuilder sb = new StringBuilder();
        int restorable = 0;
        for (TelePremiumSessionBackup.Account a : opened.accounts) {
            sb.append("• ").append(a.name.isEmpty() ? String.valueOf(a.userId) : a.name);
            if (!a.phone.isEmpty()) sb.append(" · +").append(a.phone.replace("+", ""));
            if (a.targetSlot < 0) sb.append(" — ").append(t("no free account slot", "нет свободного места"));
            else { restorable++; sb.append(a.replaces ? " — " + t("replaces the current session", "заменит текущую сессию") : " — " + t("will be added", "будет добавлен")); }
            sb.append('\n');
        }
        if (opened.hasXray) sb.append('\n').append(t("VLESS / Xray keys will be added to the list.", "Ключи VLESS / Xray будут добавлены в список."));
        if (restorable == 0) { toast(t("No free account slots", "Нет свободных мест для аккаунтов")); return; }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        b.setTitle(t("Restore sessions?", "Восстановить сессии?"));
        b.setMessage(sb.toString() + "\n" + t("The app will restart. Make sure the old device no longer uses these sessions.", "Приложение перезапустится. Убедитесь, что старое устройство больше не использует эти сессии."));
        b.setPositiveButton(t("Restore and restart", "Восстановить и перезапустить"), (d, w) -> {
            try {
                TelePremiumSessionBackup.stage(opened);
            } catch (Throwable e) {
                toast(t("Could not prepare the restore", "Не удалось подготовить восстановление"));
                return;
            }
            restartApp();
        });
        b.setNegativeButton(t("Cancel", "Отмена"), null);
        showDialog(b.create());
    }

    private void restartApp() {
        Context app = getParentActivity().getApplicationContext();
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pi = PendingIntent.getActivity(app, 7303, launch, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 700, pi);
            }
        } catch (Throwable ignored) {
        }
        AndroidUtilities.runOnUIThread(() -> { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(0); }, 300);
    }
}
