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
        actionBar.setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_054", org.telegram.messenger.R.string.TP_L10N_054));
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
        boolean available=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O;
        boolean hasKey=TelePremiumVaultKeyManager.hasMasterKey(context);
        boolean active=TelePremiumVaultKeyManager.isEnabled(context);
        addSection(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_202", org.telegram.messenger.R.string.TP_L10N_202));
        if(!available){
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_203", org.telegram.messenger.R.string.TP_L10N_203), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_204", org.telegram.messenger.R.string.TP_L10N_204),()->{});
            return;
        }
        if(!hasKey){
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_205", org.telegram.messenger.R.string.TP_L10N_205), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_206", org.telegram.messenger.R.string.TP_L10N_206), this::showVaultSetup);
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_207", org.telegram.messenger.R.string.TP_L10N_207), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_208", org.telegram.messenger.R.string.TP_L10N_208), this::showVaultRecovery);
        } else {
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_209", org.telegram.messenger.R.string.TP_L10N_209), TelePremiumVaultKeyManager.isRecoveryConfirmed(context)
                    ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_210", org.telegram.messenger.R.string.TP_L10N_210)
                    : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_211", org.telegram.messenger.R.string.TP_L10N_211), this::showVaultRecoveryCode);
            addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_212", org.telegram.messenger.R.string.TP_L10N_212), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_213", org.telegram.messenger.R.string.TP_L10N_213), this::showVaultPassphraseCheck);
            if(!active){
                addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_214", org.telegram.messenger.R.string.TP_L10N_214), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_215", org.telegram.messenger.R.string.TP_L10N_215), this::confirmVaultActivation);
            } else {
                addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_216", org.telegram.messenger.R.string.TP_L10N_216), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_217", org.telegram.messenger.R.string.TP_L10N_217),()->toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_218", org.telegram.messenger.R.string.TP_L10N_218)));
                addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_219", org.telegram.messenger.R.string.TP_L10N_219), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_220", org.telegram.messenger.R.string.TP_L10N_220), this::confirmVaultJournalRecovery);
                addAction(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_221", org.telegram.messenger.R.string.TP_L10N_221), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_222", org.telegram.messenger.R.string.TP_L10N_222), this::confirmVaultDeactivation);
            }
        }
        TextView warning=new TextView(context);
        warning.setText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_223", org.telegram.messenger.R.string.TP_L10N_223));
        warning.setTextSize(12);warning.setTextColor(theme.textSecondary);
        warning.setPadding(AndroidUtilities.dp(21),AndroidUtilities.dp(12),AndroidUtilities.dp(21),AndroidUtilities.dp(18));
        content.addView(warning,new LinearLayout.LayoutParams(-1,-2));
    }

    private void showVaultSetup() {
        if(!TelePremiumCloudSecurityStore.isProtectionEnabled(context)||!TelePremiumCloudSecuritySession.isUnlocked(context)){
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_224", org.telegram.messenger.R.string.TP_L10N_224));return;
        }
        LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);
        EditText pass=new EditText(context);pass.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_225", org.telegram.messenger.R.string.TP_L10N_225));
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm=new EditText(context);confirm.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_226", org.telegram.messenger.R.string.TP_L10N_226));
        confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(pass);fields.addView(confirm);
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_227", org.telegram.messenger.R.string.TP_L10N_227))
                .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_228", org.telegram.messenger.R.string.TP_L10N_228))
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
                    .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_232", org.telegram.messenger.R.string.TP_L10N_232))
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
        EditText password=new EditText(context);password.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_238", org.telegram.messenger.R.string.TP_L10N_238));
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
        if(!TelePremiumCloudSecurityStore.isProtectionEnabled(context)||!TelePremiumCloudSecuritySession.isUnlocked(context)){
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_224", org.telegram.messenger.R.string.TP_L10N_224));return;
        }
        LinearLayout fields=new LinearLayout(context);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),0);
        EditText code=new EditText(context);code.setHint("TPVAULT-RECOVERY-1:...");code.setMinLines(2);
        EditText password=new EditText(context);password.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_238", org.telegram.messenger.R.string.TP_L10N_238));
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(code);fields.addView(password);
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_207", org.telegram.messenger.R.string.TP_L10N_207)).setView(fields)
                .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_118", org.telegram.messenger.R.string.TP_L10N_118),(dialog,which)->{
                    String raw=code.getText().toString().trim();String[] parts=raw.split(":",3);
                    if(parts.length!=3||!"TPVAULT-RECOVERY-1".equals(parts[0])){toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_244", org.telegram.messenger.R.string.TP_L10N_244));return;}
                    char[] chars=password.getText().toString().toCharArray();password.setText("");code.setText("");
                    Utilities.globalQueue.postRunnable(()->{
                        try{TelePremiumVaultKeyManager.recoverOnThisDevice(context,chars,parts[1],parts[2]);
                            AndroidUtilities.runOnUIThread(()->{render();toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_245", org.telegram.messenger.R.string.TP_L10N_245));});
                        }catch(Exception e){AndroidUtilities.runOnUIThread(()->toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_246", org.telegram.messenger.R.string.TP_L10N_246)));}
                        finally{Arrays.fill(chars,'\0');}
                    });
                }).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
    }

    private void confirmVaultActivation() {
        if(!TelePremiumVaultKeyManager.isRecoveryConfirmed(context)){
            toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_247", org.telegram.messenger.R.string.TP_L10N_247));
            showVaultRecoveryCode();
            return;
        }
        new AlertDialog.Builder(context)
            .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_248", org.telegram.messenger.R.string.TP_L10N_248))
            .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_249", org.telegram.messenger.R.string.TP_L10N_249))
            .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_250", org.telegram.messenger.R.string.TP_L10N_250),(d,w)->{
                try{TelePremiumVaultKeyManager.activateNewVault(context);render();toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_251", org.telegram.messenger.R.string.TP_L10N_251));}
                catch(Exception e){toastStatus(e.getMessage());}
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

    private void confirmVaultDeactivation() {
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_256", org.telegram.messenger.R.string.TP_L10N_256))
            .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_257", org.telegram.messenger.R.string.TP_L10N_257))
            .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_258", org.telegram.messenger.R.string.TP_L10N_258),(d,w)->{
                try{TelePremiumVaultKeyManager.deactivateVault(context);render();toastStatus(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_259", org.telegram.messenger.R.string.TP_L10N_259));}
                catch(Exception e){toastStatus(e.getMessage());}
            }).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
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
