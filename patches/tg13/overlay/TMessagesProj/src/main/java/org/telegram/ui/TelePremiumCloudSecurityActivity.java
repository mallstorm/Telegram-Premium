package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.text.InputType;
import android.widget.EditText;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultIndexStore;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import java.util.Arrays;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.concurrent.Executor;

public class TelePremiumCloudSecurityActivity extends BaseFragment {
    private LinearLayout content;
    private Context context;
    private TelePremiumThemePreset theme;
    private CancellationSignal biometricCancellation;

    @Override
    public View createView(Context context) {
        this.context = context;
        theme = TelePremiumThemeController.getCurrent(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Защита облака");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        render();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (content != null && context != null) render();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        boolean hasPattern = TelePremiumCloudSecurityStore.hasPattern(context);
        boolean enabled = TelePremiumCloudSecurityStore.isProtectionEnabled(context);
        boolean whole = TelePremiumCloudSecurityStore.isWholeCloudLocked(context);

        addSection("Доступ");
        addSwitch("Защищать облако", "При открытии защищённых данных требуется ключ или биометрия", enabled, true, (button, checked) -> {
            if (checked && !TelePremiumCloudSecurityStore.hasUnlockMethod(context)) {
                button.setChecked(false);
                chooseUnlockMethod(() -> {
                    TelePremiumCloudSecurityStore.setProtectionEnabled(context, true);
                    render();
                });
                return;
            }
            TelePremiumCloudSecurityStore.setProtectionEnabled(context, checked);
            render();
        });

        addAction(hasPattern ? "Изменить графический ключ" : "Задать графический ключ",
                hasPattern ? "Нарисуйте новый компактный ключ и подтвердите его" : "Минимум 4 из 9 точек",
                () -> presentFragment(new TelePremiumPatternSetupActivity(this::refreshAfterPattern)));
        if (hasPattern) {
            String subtitle = TelePremiumCloudSecurityStore.hasBiometricCredential(context)
                    ? "Биометрия останется доступной"
                    : "Если других способов нет, защита будет выключена";
            addAction("Удалить графический ключ", subtitle, this::confirmRemovePattern);
        }

        boolean biometricSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
        addSwitch("Биометрия", biometricSupported ? "Отпечаток пальца / системная биометрия" : "Требуется Android 9 или новее",
                TelePremiumCloudSecurityStore.hasBiometricCredential(context), biometricSupported,
                (button, checked) -> {
                    if (checked) {
                        button.setChecked(false);
                        promptBiometricEnrollment(() -> {
                            TelePremiumCloudSecurityStore.setBiometricEnabled(context, true);
                            render();
                        });
                    } else {
                        TelePremiumCloudSecurityStore.setBiometricEnabled(context, false);
                        render();
                    }
                });

        addAction("Автоблокировка", autoLockLabel(), this::showAutoLockDialog);
        addAction("Заблокировать сейчас", "Завершить текущую разблокированную сессию", () -> {
            TelePremiumCloudSecuritySession.lockNow();
            toastStatus("Облако заблокировано");
        });

        addSection("Область защиты");
        addSwitch("Закрывать всё облако", "Вход в пункт «Облако» сразу требует подтверждения", whole, true,
                (button, checked) -> applyProtectedToggle(button, checked, () -> {
                    TelePremiumCloudSecurityStore.setWholeCloudLocked(context, checked);
                    render();
                }));

        TextView info = new TextView(context);
        info.setText(whole
                ? "Сейчас защищено всё облако. Отключите этот пункт, чтобы закрывать только выбранные разделы."
                : "Можно оставить корень облака открытым, а закрыть только чувствительные категории. Их файлы не появятся в поиске и «Все файлы» до разблокировки.");
        info.setTextSize(13);
        info.setTextColor(theme.textSecondary);
        info.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(9), AndroidUtilities.dp(21), AndroidUtilities.dp(13));
        content.addView(info, new LinearLayout.LayoutParams(-1, -2));

        addCategory("Фото", TelePremiumCloudSecurityStore.CATEGORY_IMAGES, !whole);
        addCategory("Видео", TelePremiumCloudSecurityStore.CATEGORY_VIDEO, !whole);
        addCategory("Документы", TelePremiumCloudSecurityStore.CATEGORY_DOCUMENTS, !whole);
        addCategory("Аудио", TelePremiumCloudSecurityStore.CATEGORY_AUDIO, !whole);
        addCategory("Архивы", TelePremiumCloudSecurityStore.CATEGORY_ARCHIVES, !whole);
        addCategory("APK", TelePremiumCloudSecurityStore.CATEGORY_APK, !whole);
        addCategory("Избранное Cloud", TelePremiumCloudSecurityStore.CATEGORY_FAVORITES, !whole);
        addCategory("Корзина", TelePremiumCloudSecurityStore.CATEGORY_TRASH, !whole);

        renderVaultControls();

        addSection("Примечание");
        TextView note = new TextView(context);
        note.setText("Графический ключ хранится только локально. Биометрия проверяется системным Android BiometricPrompt. Ни рисунок, ни биометрические данные не отправляются в Telegram Cloud V2.");
        note.setTextSize(13);
        note.setTextColor(theme.textSecondary);
        note.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(18));
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderVaultControls() {
        boolean available=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O;
        boolean hasKey=TelePremiumVaultKeyManager.hasMasterKey(context);
        boolean active=TelePremiumVaultKeyManager.isEnabled(context);
        addSection("Шифрование E2EE · Secure Vault V3");
        if(!available){
            addAction("E2EE недоступно", "Для Vault необходим Android 8.0+",()->{});
            return;
        }
        if(!hasKey){
            addAction("Создать один Master Key", "Один ключ на все Telegram-аккаунты; требуется пароль восстановления", this::showVaultSetup);
            addAction("Восстановить Master Key", "Восстановление после смены телефона по резервному коду и паролю", this::showVaultRecovery);
        } else {
            addAction("Код восстановления Vault", "Сохрани отдельно от Telegram. Не отправляй его в чат или облачный канал.", this::showVaultRecoveryCode);
            if(!active){
                addAction("Включить новое шифрованное облако", "Новые файлы и каталоги будут E2EE на всех подключённых аккаунтах", this::confirmVaultActivation);
            } else {
                addAction("Vault V3 включён · AES-256-GCM", "Вложения .tpv и бинарный каталог; общий Master Key",()->toastStatus("Secure Vault активен"));
                addAction("Восстановить индекс из локального журнала", "После удаления каталога из приватного канала: выбранный аккаунт", this::confirmVaultJournalRecovery);
                addAction("Переключиться к старому облаку V2", "Не удаляет Vault; старые файлы V2 остаются незашифрованными", this::confirmVaultDeactivation);
            }
        }
        TextView warning=new TextView(context);
        warning.setText("Важно: новый Vault V3 отделён от прежнего облака V2. Ранее загруженные файлы не шифруются автоматически и остаются доступными в старом канале до безопасной миграции. Telegram не позволяет запретить владельцу удалять канал. Локальный зашифрованный журнал защищает от части случайных удалений, но для полной защиты необходима независимая внешняя копия.");
        warning.setTextSize(12);warning.setTextColor(theme.textSecondary);
        warning.setPadding(AndroidUtilities.dp(21),AndroidUtilities.dp(12),AndroidUtilities.dp(21),AndroidUtilities.dp(18));
        content.addView(warning,new LinearLayout.LayoutParams(-1,-2));
    }

    private void showVaultSetup() {
        if(!TelePremiumCloudSecurityStore.isProtectionEnabled(context)||!TelePremiumCloudSecuritySession.isUnlocked(context)){
            toastStatus("Сначала включи защиту облака и разблокируй её");return;
        }
        LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);
        EditText pass=new EditText(context);pass.setHint("Пароль восстановления от 12 символов");
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm=new EditText(context);confirm.setHint("Повтори пароль восстановления");
        confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(pass);fields.addView(confirm);
        new AlertDialog.Builder(context).setTitle("Новый общий Master Key")
                .setMessage("Пароль нужен для восстановления на другом устройстве. Если его потерять, локального графического ключа будет недостаточно.")
                .setView(fields).setPositiveButton("Создать",(dialog,which)->{
                    String p1=pass.getText().toString();String p2=confirm.getText().toString();
                    if(!p1.equals(p2)){toastStatus("Пароли не совпадают");return;}
                    char[] chars=p1.toCharArray();
                    pass.setText("");confirm.setText("");
                    Utilities.globalQueue.postRunnable(()->{
                        try {TelePremiumVaultKeyManager.initialize(context,chars);
                            AndroidUtilities.runOnUIThread(()->{render();showVaultRecoveryCode();});
                        }catch(Exception e){AndroidUtilities.runOnUIThread(()->toastStatus("Ошибка ключа: "+e.getMessage()));}
                        finally {Arrays.fill(chars,'\0');}
                    });
                }).setNegativeButton("Отмена",null).show();
    }

    private void showVaultRecoveryCode() {
        try {
            String code=TelePremiumVaultKeyManager.exportRecoveryEnvelope(context);
            TextView view=new TextView(context);view.setText(code);view.setTextIsSelectable(true);
            view.setPadding(AndroidUtilities.dp(20),AndroidUtilities.dp(12),AndroidUtilities.dp(20),AndroidUtilities.dp(12));
            view.setTextSize(13);
            new AlertDialog.Builder(context).setTitle("Восстановление Vault")
                    .setMessage("Сохрани этот код отдельно и приватно. Для восстановления дополнительно потребуется пароль. Никогда не публикуй код вместе с паролем.")
                    .setView(view).setPositiveButton("Закрыть",null).show();
        }catch(Exception e){toastStatus(e.getMessage());}
    }

    private void showVaultRecovery() {
        if(!TelePremiumCloudSecurityStore.isProtectionEnabled(context)||!TelePremiumCloudSecuritySession.isUnlocked(context)){
            toastStatus("Сначала включи защиту облака и разблокируй её");return;
        }
        LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);
        EditText code=new EditText(context);code.setHint("TPVAULT-RECOVERY-1:...");code.setMinLines(2);
        EditText password=new EditText(context);password.setHint("Пароль восстановления");
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(code);fields.addView(password);
        new AlertDialog.Builder(context).setTitle("Восстановить Master Key").setView(fields)
                .setPositiveButton("Восстановить",(dialog,which)->{
                    String raw=code.getText().toString().trim();String[] parts=raw.split(":",3);
                    if(parts.length!=3||!"TPVAULT-RECOVERY-1".equals(parts[0])){toastStatus("Неверный формат кода");return;}
                    char[] chars=password.getText().toString().toCharArray();password.setText("");code.setText("");
                    Utilities.globalQueue.postRunnable(()->{
                        try{TelePremiumVaultKeyManager.recoverOnThisDevice(context,chars,parts[1],parts[2]);
                            AndroidUtilities.runOnUIThread(()->{render();toastStatus("Master Key восстановлен");});
                        }catch(Exception e){AndroidUtilities.runOnUIThread(()->toastStatus("Не удалось восстановить ключ"));}
                        finally{Arrays.fill(chars,'\0');}
                    });
                }).setNegativeButton("Отмена",null).show();
    }

    private void confirmVaultActivation() {
        new AlertDialog.Builder(context)
            .setTitle("Включить новый E2EE Vault?")
            .setMessage("Будет создан отдельный приватный Vault V3 для каждого подключённого аккаунта. Новые файлы и каталоги станут зашифрованными. Старые файлы и сообщения V2 останутся в предыдущих каналах в открытом виде. Это НЕ миграция старых данных. Сохрани код восстановления до включения!")
            .setPositiveButton("Включить V3",(d,w)->{
                try{TelePremiumVaultKeyManager.activateNewVault(context);render();toastStatus("Vault включён. Заново открой раздел «Облако».");}
                catch(Exception e){toastStatus(e.getMessage());}
            }).setNegativeButton("Отмена",null).show();
    }

    private void confirmVaultJournalRecovery() {
        int account=UserConfig.selectedAccount;
        new AlertDialog.Builder(context).setTitle("Восстановление каталога Vault")
            .setMessage("Восстановить последний зашифрованный каталог для текущего Telegram-аккаунта из локального журнала? Файлы, удалённые из Telegram, дополнительно потребуют собственных резервных копий.")
            .setPositiveButton("Восстановить",(d,w)->new TelePremiumVaultIndexStore(account)
                    .restoreLatestLocalIndex(error->toastStatus(error==null?"Каталог Vault восстановлен":"Ошибка восстановления: "+error)))
            .setNegativeButton("Отмена",null).show();
    }

    private void confirmVaultDeactivation() {
        new AlertDialog.Builder(context).setTitle("Вернуться к V2?")
            .setMessage("Старое облако V2 может содержать незашифрованные файлы. Данные V3 останутся зашифрованными в отдельном канале и снова появятся при включении V3.")
            .setPositiveButton("Вернуться",(d,w)->{
                try{TelePremiumVaultKeyManager.deactivateVault(context);render();toastStatus("Режим V2. Открой облако заново.");}
                catch(Exception e){toastStatus(e.getMessage());}
            }).setNegativeButton("Отмена",null).show();
    }

    private void applyProtectedToggle(CompoundButton button, boolean checked, Runnable apply) {
        if (checked && !TelePremiumCloudSecurityStore.hasUnlockMethod(context)) {
            button.setChecked(false);
            chooseUnlockMethod(() -> {
                TelePremiumCloudSecurityStore.setProtectionEnabled(context, true);
                apply.run();
            });
            return;
        }
        if (checked && !TelePremiumCloudSecurityStore.isProtectionEnabled(context)) {
            TelePremiumCloudSecurityStore.setProtectionEnabled(context, true);
        }
        apply.run();
    }

    private void chooseUnlockMethod(Runnable afterReady) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            new AlertDialog.Builder(context)
                    .setTitle("Способ защиты")
                    .setItems(new CharSequence[]{"Графический ключ", "Отпечаток / биометрия"}, (dialog, which) -> {
                        if (which == 0) {
                            presentFragment(new TelePremiumPatternSetupActivity(() -> {
                                if (afterReady != null) afterReady.run();
                            }));
                        } else {
                            promptBiometricEnrollment(() -> {
                                TelePremiumCloudSecurityStore.setBiometricEnabled(context, true);
                                if (afterReady != null) afterReady.run();
                            });
                        }
                    })
                    .show();
        } else {
            presentFragment(new TelePremiumPatternSetupActivity(() -> {
                if (afterReady != null) afterReady.run();
            }));
        }
    }

    private void promptBiometricEnrollment(Runnable onSuccess) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || getParentActivity() == null) {
            toastStatus("Биометрия недоступна на этом устройстве");
            render();
            return;
        }
        try {
            if (biometricCancellation != null) biometricCancellation.cancel();
            biometricCancellation = new CancellationSignal();
            Context activity = getParentActivity();
            Executor executor = activity.getMainExecutor();
            BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                    .setTitle("Включить биометрию")
                    .setSubtitle("Подтвердите отпечаток или другой системный метод")
                    .setNegativeButton("Отмена", executor, (DialogInterface dialog, int which) -> render())
                    .build();
            prompt.authenticate(biometricCancellation, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    if (onSuccess != null) onSuccess.run();
                }

                @Override public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    toastStatus("Биометрия не распознана");
                }

                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        toastStatus(errString == null ? "Биометрия недоступна" : errString.toString());
                    }
                    render();
                }
            });
        } catch (Throwable e) {
            toastStatus("Биометрия недоступна. Проверьте отпечаток в настройках Android.");
            render();
        }
    }

    private void addCategory(String title, String category, boolean rowEnabled) {
        addSwitch(title, "Запрашивать подтверждение перед просмотром", TelePremiumCloudSecurityStore.isCategoryLocked(context, category), rowEnabled,
                (button, checked) -> applyProtectedToggle(button, checked, () -> {
                    TelePremiumCloudSecurityStore.setCategoryLocked(context, category, checked);
                    render();
                }));
    }

    private void addSection(String title) {
        TextView header = new TextView(context);
        header.setText(title);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setTextColor(theme.accent);
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(18), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSwitch(String title, String subtitle, boolean checked, boolean enabled, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        row.setBackgroundColor(theme.surface);
        row.setAlpha(enabled ? 1f : 0.42f);
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setFocusable(enabled);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(theme.textPrimary);
        TextView s = new TextView(context);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(theme.textSecondary);
        texts.addView(t);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch sw = new Switch(context);
        sw.setChecked(checked);
        sw.setEnabled(enabled);
        sw.setClickable(false);
        sw.setFocusable(false);
        tintSwitch(sw, checked, enabled);
        sw.setOnCheckedChangeListener((button, value) -> {
            tintSwitch(sw, value, enabled);
            listener.onCheckedChanged(button, value);
        });
        row.addView(sw, new LinearLayout.LayoutParams(AndroidUtilities.dp(58), AndroidUtilities.dp(48)));
        row.setOnClickListener(v -> {
            if (!enabled) return;
            sw.setChecked(!sw.isChecked());
            performRowHaptic(row);
        });
        content.addView(row, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(72)));
        addDivider();
    }

    private void tintSwitch(Switch sw, boolean checked, boolean enabled) {
        if (Build.VERSION.SDK_INT < 21) return;
        int thumb = enabled ? (checked ? theme.accent : theme.iconSecondary) : theme.divider;
        int track = checked ? blend(theme.accent, theme.surface, 0.58f) : blend(theme.iconSecondary, theme.surface, 0.68f);
        sw.setThumbTintList(ColorStateList.valueOf(thumb));
        sw.setTrackTintList(ColorStateList.valueOf(enabled ? track : theme.divider));
    }

    private int blend(int from, int to, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int a = (int) (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * amount);
        int r = (int) (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * amount);
        int g = (int) (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * amount);
        int b = (int) (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * amount);
        return android.graphics.Color.argb(a, r, g, b);
    }

    private void performRowHaptic(View row) {
        row.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void addAction(String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), 0);
        row.setBackgroundColor(theme.surface);
        row.setClickable(true);
        row.setFocusable(true);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(theme.textPrimary);
        TextView s = new TextView(context);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(theme.textSecondary);
        row.addView(t);
        row.addView(s);
        row.setOnClickListener(v -> {
            performRowHaptic(row);
            action.run();
        });
        content.addView(row, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(68)));
        addDivider();
    }

    private void addDivider() {
        View divider = new View(context);
        divider.setBackgroundColor(theme.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, AndroidUtilities.dp(1)));
        lp.leftMargin = AndroidUtilities.dp(21);
        content.addView(divider, lp);
    }

    private String autoLockLabel() {
        int seconds = TelePremiumCloudSecurityStore.getAutoLockSeconds(context);
        if (seconds < 60) return seconds + " секунд после разблокировки";
        if (seconds == 60) return "1 минута после разблокировки";
        return (seconds / 60) + " минут после разблокировки";
    }

    private void showAutoLockDialog() {
        CharSequence[] names = {"30 секунд", "1 минута", "5 минут"};
        int[] values = {30, 60, 300};
        new AlertDialog.Builder(context)
                .setTitle("Автоблокировка")
                .setItems(names, (dialog, which) -> {
                    TelePremiumCloudSecurityStore.setAutoLockSeconds(context, values[which]);
                    render();
                })
                .show();
    }

    private void confirmRemovePattern() {
        String message = TelePremiumCloudSecurityStore.hasBiometricCredential(context)
                ? "Графический ключ будет удалён. Биометрическая разблокировка останется активной."
                : "Графический ключ будет удалён, и защита Cloud выключится до выбора нового способа.";
        new AlertDialog.Builder(context)
                .setTitle("Удалить графический ключ?")
                .setMessage(message)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    TelePremiumCloudSecurityStore.clearPattern(context);
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void refreshAfterPattern() {
        if (content != null) render();
    }

    private void toastStatus(String text) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onFragmentDestroy() {
        if (biometricCancellation != null) biometricCancellation.cancel();
        super.onFragmentDestroy();
    }
}
