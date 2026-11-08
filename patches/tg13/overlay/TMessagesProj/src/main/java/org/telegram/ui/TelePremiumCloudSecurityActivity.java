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
    private TelePremiumSettingsUi ui;
    private CancellationSignal biometricCancellation;

    @Override
    public View createView(Context context) {
        this.context = context;
        theme = TelePremiumThemeController.getCurrent(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_054", org.telegram.messenger.R.string.TP_L10N_054));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        ui = new TelePremiumSettingsUi(this, context);
        ScrollView scroll = ui.createScreen();
        content = ui.content;
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
        ui.clear();
        boolean hasPattern = TelePremiumCloudSecurityStore.hasPattern(context);
        boolean enabled = TelePremiumCloudSecurityStore.isProtectionEnabled(context);
        boolean whole = TelePremiumCloudSecurityStore.isWholeCloudLocked(context);
        ui.hero(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_054", org.telegram.messenger.R.string.TP_L10N_054),
                org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_055", org.telegram.messenger.R.string.TP_L10N_055),
                enabled ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_450", org.telegram.messenger.R.string.TP_L10N_450) : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_451", org.telegram.messenger.R.string.TP_L10N_451),
                R.drawable.tp_ic_shield);

        addSection(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_177", org.telegram.messenger.R.string.TP_L10N_177));
        addSwitch(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_178", org.telegram.messenger.R.string.TP_L10N_178), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_179", org.telegram.messenger.R.string.TP_L10N_179), enabled, true, (button, checked) -> {
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

        addAction(hasPattern ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_180", org.telegram.messenger.R.string.TP_L10N_180) : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_181", org.telegram.messenger.R.string.TP_L10N_181),
                hasPattern ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_182", org.telegram.messenger.R.string.TP_L10N_182) : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_183", org.telegram.messenger.R.string.TP_L10N_183),
                () -> presentFragment(new TelePremiumPatternSetupActivity(this::refreshAfterPattern)));
        if (hasPattern) {
            String subtitle = TelePremiumCloudSecurityStore.hasBiometricCredential(context)
                    ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_184", org.telegram.messenger.R.string.TP_L10N_184)
                    : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_185", org.telegram.messenger.R.string.TP_L10N_185);
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_186", org.telegram.messenger.R.string.TP_L10N_186), subtitle, this::confirmRemovePattern);
        }

        boolean biometricSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
        addSwitch(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_187", org.telegram.messenger.R.string.TP_L10N_187), biometricSupported ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_188", org.telegram.messenger.R.string.TP_L10N_188) : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_189", org.telegram.messenger.R.string.TP_L10N_189),
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

        addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_190", org.telegram.messenger.R.string.TP_L10N_190), autoLockLabel(), this::showAutoLockDialog);
        addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_191", org.telegram.messenger.R.string.TP_L10N_191), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_192", org.telegram.messenger.R.string.TP_L10N_192), () -> {
            TelePremiumCloudSecuritySession.lockNow();
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_193", org.telegram.messenger.R.string.TP_L10N_193));
        });

        addSection(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_194", org.telegram.messenger.R.string.TP_L10N_194));
        addSwitch(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_195", org.telegram.messenger.R.string.TP_L10N_195), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_196", org.telegram.messenger.R.string.TP_L10N_196), whole, true,
                (button, checked) -> applyProtectedToggle(button, checked, () -> {
                    TelePremiumCloudSecurityStore.setWholeCloudLocked(context, checked);
                    render();
                }));

        TextView info = new TextView(context);
        info.setText(whole
                ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_197", org.telegram.messenger.R.string.TP_L10N_197)
                : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_198", org.telegram.messenger.R.string.TP_L10N_198));
        info.setTextSize(13);
        info.setTextColor(theme.textSecondary);
        info.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(9), AndroidUtilities.dp(21), AndroidUtilities.dp(13));
        content.addView(info, new LinearLayout.LayoutParams(-1, -2));

        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_097", org.telegram.messenger.R.string.TP_L10N_097), TelePremiumCloudSecurityStore.CATEGORY_IMAGES, !whole);
        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_098", org.telegram.messenger.R.string.TP_L10N_098), TelePremiumCloudSecurityStore.CATEGORY_VIDEO, !whole);
        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_099", org.telegram.messenger.R.string.TP_L10N_099), TelePremiumCloudSecurityStore.CATEGORY_DOCUMENTS, !whole);
        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_100", org.telegram.messenger.R.string.TP_L10N_100), TelePremiumCloudSecurityStore.CATEGORY_AUDIO, !whole);
        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_101", org.telegram.messenger.R.string.TP_L10N_101), TelePremiumCloudSecurityStore.CATEGORY_ARCHIVES, !whole);
        addCategory("APK", TelePremiumCloudSecurityStore.CATEGORY_APK, !whole);
        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_199", org.telegram.messenger.R.string.TP_L10N_199), TelePremiumCloudSecurityStore.CATEGORY_FAVORITES, !whole);
        addCategory(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_103", org.telegram.messenger.R.string.TP_L10N_103), TelePremiumCloudSecurityStore.CATEGORY_TRASH, !whole);

        renderVaultControls();

        addSection(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_200", org.telegram.messenger.R.string.TP_L10N_200));
        TextView note = new TextView(context);
        note.setText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_201", org.telegram.messenger.R.string.TP_L10N_201));
        note.setTextSize(13);
        note.setTextColor(theme.textSecondary);
        note.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(18));
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderVaultControls() {
        addSection("Secure Vault V3");
        addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_DEFAULT", org.telegram.messenger.R.string.TP_VAULT_DEFAULT), org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_DEFAULT_SUB", org.telegram.messenger.R.string.TP_VAULT_DEFAULT_SUB), () -> {});
        addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_209", org.telegram.messenger.R.string.TP_L10N_209), org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_RECOVERY_SUB", org.telegram.messenger.R.string.TP_VAULT_RECOVERY_SUB), this::showVaultRecoveryCode);
        addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_207", org.telegram.messenger.R.string.TP_L10N_207), org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_IMPORT_SUB", org.telegram.messenger.R.string.TP_VAULT_IMPORT_SUB), this::showVaultRecovery);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_PASSWORD", org.telegram.messenger.R.string.TP_VAULT_PASSWORD), org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_PASSWORD_SUB", org.telegram.messenger.R.string.TP_VAULT_PASSWORD_SUB), this::showVaultSetup);
            if (TelePremiumVaultKeyManager.hasPasswordRecovery(context))
                addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_212", org.telegram.messenger.R.string.TP_L10N_212), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_213", org.telegram.messenger.R.string.TP_L10N_213), this::showVaultPassphraseCheck);
        }
        addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_219", org.telegram.messenger.R.string.TP_L10N_219), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_220", org.telegram.messenger.R.string.TP_L10N_220), this::confirmVaultJournalRecovery);
    }

    private void showVaultSetup() {
        if(TelePremiumCloudSecurityStore.isProtectionEnabled(context)&&!TelePremiumCloudSecuritySession.isUnlocked(context)){
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_224", org.telegram.messenger.R.string.TP_L10N_224));return;
        }
        LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);
        EditText pass=new EditText(context);pass.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_225", org.telegram.messenger.R.string.TP_L10N_225));
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm=new EditText(context);confirm.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_226", org.telegram.messenger.R.string.TP_L10N_226));
        confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(pass);fields.addView(confirm);
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_PASSWORD", org.telegram.messenger.R.string.TP_VAULT_PASSWORD))
                .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_PASSWORD_SUB", org.telegram.messenger.R.string.TP_VAULT_PASSWORD_SUB))
                .setView(fields).setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_158", org.telegram.messenger.R.string.TP_L10N_158),(dialog,which)->{
                    String p1=pass.getText().toString();String p2=confirm.getText().toString();
                    if(!p1.equals(p2)){toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_229", org.telegram.messenger.R.string.TP_L10N_229));return;}
                    char[] chars=p1.toCharArray();
                    pass.setText("");confirm.setText("");
                    Utilities.globalQueue.postRunnable(()->{
                        try {TelePremiumVaultKeyManager.initialize(context,chars);
                            AndroidUtilities.runOnUIThread(()->{render();showVaultRecoveryCode();});
                        }catch(Exception e){AndroidUtilities.runOnUIThread(()->toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_230", org.telegram.messenger.R.string.TP_L10N_230)+e.getMessage()));}
                        finally {Arrays.fill(chars,'\0');}
                    });
                }).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
    }

    private void showVaultRecoveryCode() {
        try {
            String code=TelePremiumVaultKeyManager.exportRecoveryEnvelope(context);
            TextView view=new TextView(context);view.setText(code);view.setTextIsSelectable(true);
            view.setPadding(AndroidUtilities.dp(20),AndroidUtilities.dp(12),AndroidUtilities.dp(20),AndroidUtilities.dp(12));
            view.setTextSize(13);
            new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_231", org.telegram.messenger.R.string.TP_L10N_231))
                    .setMessage(TelePremiumVaultKeyManager.hasPasswordRecovery(context) ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_232", org.telegram.messenger.R.string.TP_L10N_232) : org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_RECOVERY_SECRET", org.telegram.messenger.R.string.TP_VAULT_RECOVERY_SECRET))
                    .setView(view)
                    .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_233", org.telegram.messenger.R.string.TP_L10N_233),(d,w)->{TelePremiumVaultKeyManager.setRecoveryConfirmed(context,true);render();})
                    .setNeutralButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_234", org.telegram.messenger.R.string.TP_L10N_234),(d,w)->shareRecoveryCode(code))
                    .setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_235", org.telegram.messenger.R.string.TP_L10N_235),null).show();
        }catch(Exception e){toastStatus(e.getMessage());}
    }

    private void shareRecoveryCode(String code) {
        try {
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){
                android.content.ClipData clip=android.content.ClipData.newPlainText("TelePremium Vault recovery",code);
                if(Build.VERSION.SDK_INT>=33){
                    android.os.PersistableBundle extras=new android.os.PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE",true);
                    clip.getDescription().setExtras(extras);
                }
                clipboard.setPrimaryClip(clip);
            }
            android.content.Intent send=new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_SUBJECT,"TelePremium Vault recovery code");
            send.putExtra(android.content.Intent.EXTRA_TEXT,code);
            context.startActivity(android.content.Intent.createChooser(send,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_236", org.telegram.messenger.R.string.TP_L10N_236)));
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_237", org.telegram.messenger.R.string.TP_L10N_237));
        }catch(Exception e){toastStatus(e.getMessage());}
    }

    private void showVaultPassphraseCheck() {
        EditText password=new EditText(context);password.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_PASSWORD_HINT", org.telegram.messenger.R.string.TP_VAULT_PASSWORD_HINT));
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box=new LinearLayout(context);box.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);box.addView(password,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_239", org.telegram.messenger.R.string.TP_L10N_239)).setView(box)
                .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_240", org.telegram.messenger.R.string.TP_L10N_240),(d,w)->{
                    char[] chars=password.getText().toString().toCharArray();password.setText("");
                    Utilities.globalQueue.postRunnable(()->{
                        try{boolean ok=TelePremiumVaultKeyManager.verifyRecoveryPassphrase(context,chars);
                            AndroidUtilities.runOnUIThread(()->toastStatus(ok?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_241", org.telegram.messenger.R.string.TP_L10N_241):org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_242", org.telegram.messenger.R.string.TP_L10N_242)));
                        }catch(Exception e){AndroidUtilities.runOnUIThread(()->toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_243", org.telegram.messenger.R.string.TP_L10N_243)+e.getMessage()));}
                        finally{Arrays.fill(chars,'\0');}
                    });
                }).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
    }

    private void showVaultRecovery() {
        if(TelePremiumCloudSecurityStore.isProtectionEnabled(context)&&!TelePremiumCloudSecuritySession.isUnlocked(context)){
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_224", org.telegram.messenger.R.string.TP_L10N_224));return;
        }
        LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);
        EditText code=new EditText(context);code.setHint("TPVAULT-KEY-1:… / TPVAULT-RECOVERY-1:…");code.setMinLines(2);
        EditText password=new EditText(context);password.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_VAULT_PASSWORD_HINT", org.telegram.messenger.R.string.TP_VAULT_PASSWORD_HINT));
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(code);fields.addView(password);
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_207", org.telegram.messenger.R.string.TP_L10N_207)).setView(fields)
                .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_118", org.telegram.messenger.R.string.TP_L10N_118),(dialog,which)->{
                    String raw=code.getText().toString().trim();
                    char[] chars=password.getText().toString().toCharArray();password.setText("");code.setText("");
                    Utilities.globalQueue.postRunnable(()->{
                        try{TelePremiumVaultKeyManager.recoverFromCode(context,raw,chars);
                            AndroidUtilities.runOnUIThread(()->{render();toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_245", org.telegram.messenger.R.string.TP_L10N_245));});
                        }catch(Exception e){AndroidUtilities.runOnUIThread(()->toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_246", org.telegram.messenger.R.string.TP_L10N_246) + ": " + e.getMessage()));}
                        finally{Arrays.fill(chars,'\0');}
                    });
                }).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
    }

    private void confirmVaultJournalRecovery() {
        int account=UserConfig.selectedAccount;
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_252", org.telegram.messenger.R.string.TP_L10N_252))
            .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_253", org.telegram.messenger.R.string.TP_L10N_253))
            .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_118", org.telegram.messenger.R.string.TP_L10N_118),(d,w)->new TelePremiumVaultIndexStore(account)
                    .restoreLatestLocalIndex(error->toastStatus(error==null?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_254", org.telegram.messenger.R.string.TP_L10N_254):org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_255", org.telegram.messenger.R.string.TP_L10N_255)+error)))
            .setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
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
                    .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_260", org.telegram.messenger.R.string.TP_L10N_260))
                    .setItems(new CharSequence[]{org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_261", org.telegram.messenger.R.string.TP_L10N_261), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_262", org.telegram.messenger.R.string.TP_L10N_262)}, (dialog, which) -> {
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
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_263", org.telegram.messenger.R.string.TP_L10N_263));
            render();
            return;
        }
        try {
            if (biometricCancellation != null) biometricCancellation.cancel();
            biometricCancellation = new CancellationSignal();
            Context activity = getParentActivity();
            Executor executor = activity.getMainExecutor();
            BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                    .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_264", org.telegram.messenger.R.string.TP_L10N_264))
                    .setSubtitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_265", org.telegram.messenger.R.string.TP_L10N_265))
                    .setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135), executor, (DialogInterface dialog, int which) -> render())
                    .build();
            prompt.authenticate(biometricCancellation, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    if (onSuccess != null) onSuccess.run();
                }

                @Override public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_266", org.telegram.messenger.R.string.TP_L10N_266));
                }

                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        toastStatus(errString == null ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_267", org.telegram.messenger.R.string.TP_L10N_267) : errString.toString());
                    }
                    render();
                }
            });
        } catch (Throwable e) {
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_268", org.telegram.messenger.R.string.TP_L10N_268));
            render();
        }
    }

    private void addCategory(String title, String category, boolean rowEnabled) {
        addSwitch(title, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_269", org.telegram.messenger.R.string.TP_L10N_269), TelePremiumCloudSecurityStore.isCategoryLocked(context, category), rowEnabled,
                (button, checked) -> applyProtectedToggle(button, checked, () -> {
                    TelePremiumCloudSecurityStore.setCategoryLocked(context, category, checked);
                    render();
                }));
    }

    private void addSection(String title) {
        ui.section(title);
    }

    private void addSwitch(String title, String subtitle, boolean checked, boolean enabled, CompoundButton.OnCheckedChangeListener listener) {
        ui.switchRow(title, subtitle, 0, 0, checked, enabled, listener);
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
        ui.row(title, subtitle, 0, 0, null, v -> action.run());
    }

    private String autoLockLabel() {
        int seconds = TelePremiumCloudSecurityStore.getAutoLockSeconds(context);
        if (seconds < 60) return seconds + org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_270", org.telegram.messenger.R.string.TP_L10N_270);
        if (seconds == 60) return org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_271", org.telegram.messenger.R.string.TP_L10N_271);
        return (seconds / 60) + org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_272", org.telegram.messenger.R.string.TP_L10N_272);
    }

    private void showAutoLockDialog() {
        CharSequence[] names = {org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_273", org.telegram.messenger.R.string.TP_L10N_273), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_274", org.telegram.messenger.R.string.TP_L10N_274), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_275", org.telegram.messenger.R.string.TP_L10N_275)};
        int[] values = {30, 60, 300};
        new AlertDialog.Builder(context)
                .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_190", org.telegram.messenger.R.string.TP_L10N_190))
                .setItems(names, (dialog, which) -> {
                    TelePremiumCloudSecurityStore.setAutoLockSeconds(context, values[which]);
                    render();
                })
                .show();
    }

    private void confirmRemovePattern() {
        String message = TelePremiumCloudSecurityStore.hasBiometricCredential(context)
                ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_276", org.telegram.messenger.R.string.TP_L10N_276)
                : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_277", org.telegram.messenger.R.string.TP_L10N_277);
        new AlertDialog.Builder(context)
                .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_278", org.telegram.messenger.R.string.TP_L10N_278))
                .setMessage(message)
                .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_133", org.telegram.messenger.R.string.TP_L10N_133), (dialog, which) -> {
                    TelePremiumCloudSecurityStore.clearPattern(context);
                    render();
                })
                .setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135), null)
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
