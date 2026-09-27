#!/usr/bin/env python3
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"
PATCH_ROOT = ROOT / "patches" / "concept5" / "java"


def read(rel):
    p = SRC / rel
    if not p.exists():
        raise SystemExit(f"missing upstream file: {rel}")
    return p, p.read_text(encoding="utf-8")


def write(p, data):
    p.write_text(data, encoding="utf-8")


def replace_once(rel, old, new):
    p, data = read(rel)
    if new in data and old not in data:
        print(f"already patched: {rel}")
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one source match, found {count}")
    write(p, data.replace(old, new, 1))
    print(f"patched: {rel}")


def method_bounds(data, signature):
    start = data.find(signature)
    if start < 0:
        raise SystemExit(f"method signature not found: {signature}")
    brace = data.find("{", start)
    if brace < 0:
        raise SystemExit("opening brace not found")
    depth = 0
    for i in range(brace, len(data)):
        if data[i] == "{":
            depth += 1
        elif data[i] == "}":
            depth -= 1
            if depth == 0:
                return start, brace, i
    raise SystemExit("closing brace not found")


def replace_in_method(rel, signature, old, new, expected=1):
    p, data = read(rel)
    start, brace, end = method_bounds(data, signature)
    method = data[start:end + 1]
    if new in method and old not in method:
        print(f"already patched: {rel} :: {signature}")
        return
    count = method.count(old)
    if count != expected:
        raise SystemExit(
            f"{rel} :: {signature}: expected {expected} matches, found {count}"
        )
    method = method.replace(old, new, expected)
    write(p, data[:start] + method + data[end + 1:])
    print(f"patched: {rel} :: {signature}")


def copy_concept_sources():
    if not PATCH_ROOT.exists():
        raise SystemExit(f"Concept 5 source tree missing: {PATCH_ROOT}")
    destination = SRC / "TMessagesProj" / "src" / "main" / "java"
    for source in PATCH_ROOT.rglob("*.java"):
        rel = source.relative_to(PATCH_ROOT)
        target = destination / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        print(f"copied: {rel}")


if not SRC.exists():
    raise SystemExit(
        "upstream/Telegram is missing. Clone with --recurse-submodules "
        "or run: git submodule update --init --recursive"
    )

copy_concept_sources()

# Login face: keep phone number as the primary screen and expose the two
# alternative methods only from the agreed three-dot menu.
replace_once(
    "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java",
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\nimport org.telegram.messenger.telepremium.TelePremiumPasskeyController;\n",
)

replace_once(
    "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java",
    """            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);

            titleView = new TextView(context);
""",
    """            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);

            if (activityMode == MODE_LOGIN) {
                TextView alternativeLoginMenu = new TextView(context);
                alternativeLoginMenu.setText("⋯");
                alternativeLoginMenu.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
                alternativeLoginMenu.setGravity(Gravity.CENTER);
                alternativeLoginMenu.setContentDescription("Другие способы входа");
                alternativeLoginMenu.setOnClickListener(v -> showAlternativeLoginMenu());
                LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(52), AndroidUtilities.dp(48));
                menuParams.gravity = Gravity.START;
                addView(alternativeLoginMenu, menuParams);
            }

            titleView = new TextView(context);
""",
)

replace_once(
    "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java",
    """        @Override
        public String getHeaderName() {
            return getString("YourPhone", R.string.YourPhone);
        }
""",
    """        private void showAlternativeLoginMenu() {
            new AlertDialog.Builder(getParentActivity())
                    .setItems(new CharSequence[]{"Войти по QR-коду", "Войти с Passkey"}, (dialog, which) -> {
                        if (which == 0) {
                            new TelePremiumQrLoginDialog(getParentActivity(), currentAccount, (auth, error) -> {
                                if (error != null) {
                                    needShowAlert("QR-код", error);
                                } else if (auth != null) {
                                    onAuthSuccess(auth);
                                }
                            }).show();
                        } else {
                            requestTelePremiumPasskey();
                        }
                    })
                    .show();
        }

        private Runnable cancelTelePremiumPasskey;
        private void requestTelePremiumPasskey() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                needShowAlert("Passkey", "Для входа с Passkey требуется Android 9 или новее.");
                return;
            }
            if (cancelTelePremiumPasskey != null) return;
            cancelTelePremiumPasskey = TelePremiumPasskeyController.login(getParentActivity(), currentAccount, (userId, auth, error) -> {
                cancelTelePremiumPasskey = null;
                if ("CANCELLED".equals(error) || "EMPTY".equals(error)) return;
                if (error != null && error.contains("SESSION_PASSWORD_NEEDED")) {
                    TLRPC.TL_account_getPassword req = new TLRPC.TL_account_getPassword();
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, requestError) -> AndroidUtilities.runOnUIThread(() -> {
                        if (requestError == null) {
                            TLRPC.account_Password password = (TLRPC.account_Password) response;
                            if (!TwoStepVerificationActivity.canHandleCurrentPassword(password, true)) {
                                AlertsCreator.showUpdateAppAlert(getParentActivity(), getString("UpdateAppAlert", R.string.UpdateAppAlert), true);
                                return;
                            }
                            Bundle bundle = new Bundle();
                            SerializedData data = new SerializedData(password.getObjectSize());
                            password.serializeToStream(data);
                            bundle.putString("password", Utilities.bytesToHex(data.toByteArray()));
                            setPage(VIEW_PASSWORD, true, bundle, false);
                        } else {
                            needShowAlert(getString(R.string.RestorePasswordNoEmailTitle), requestError.text);
                        }
                    }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
                } else if (error != null) {
                    needShowAlert("Passkey", error);
                } else if (auth instanceof TLRPC.TL_auth_authorization) {
                    onAuthSuccess((TLRPC.TL_auth_authorization) auth);
                }
            });
        }

        @Override
        public String getHeaderName() {
            return getString("YourPhone", R.string.YourPhone);
        }
""",
)

# Isolated authentication backport dependencies: Android Credential Manager for
# native passkeys and ZXing core for the dynamic Telegram QR token.
replace_once(
    "TMessagesProj/build.gradle",
    "dependencies {\n",
    "dependencies {\n    implementation 'androidx.credentials:credentials:1.3.0'\n    implementation 'androidx.credentials:credentials-play-services-auth:1.3.0'\n",
)

replace_once(
    "TMessagesProj/build.gradle",
    "        minSdkVersion 19\n",
    "        minSdkVersion 21\n",
)

replace_once(
    "TMessagesProj_App/build.gradle",
    "        minSdkVersion 19\n",
    "        minSdkVersion 21\n",
)

# Per-account network routing. Stock Telegram still owns the global proxy
# settings, but an explicit TelePremium profile overrides it only for its
# account slot.
connections = "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"

replace_once(
    connections,
    "import org.telegram.messenger.UserConfig;\n",
    "import org.telegram.messenger.UserConfig;\n"
    "import org.telegram.messenger.telepremium.AccountNetworkController;\n",
)

replace_in_method(
    connections,
    "public void init(int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection)",
    "        checkConnection();",
    "        AccountNetworkController.getInstance().applyProfile(currentAccount);\n"
    "        checkConnection();",
)

old_proxy_loop = """        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (enabled && !TextUtils.isEmpty(address)) {
                native_setProxySettings(a, address, port, username, password, secret);
            } else {
                native_setProxySettings(a, "", 1080, "", "", "");
            }
            AccountInstance accountInstance = AccountInstance.getInstance(a);
            if (accountInstance.getUserConfig().isClientActivated()) {
                accountInstance.getMessagesController().checkPromoInfo(true);
            }
        }"""

new_proxy_loop = """        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (AccountNetworkController.getInstance().hasExplicitProfile(a)) {
                AccountNetworkController.getInstance().applyProfile(a);
            } else if (enabled && !TextUtils.isEmpty(address)) {
                native_setProxySettings(a, address, port, username, password, secret);
            } else {
                native_setProxySettings(a, "", 1080, "", "", "");
            }
            AccountInstance accountInstance = AccountInstance.getInstance(a);
            if (accountInstance.getUserConfig().isClientActivated()) {
                accountInstance.getMessagesController().checkPromoInfo(true);
            }
        }"""

replace_in_method(
    connections,
    "public static void setProxySettings(boolean enabled, String address, int port, String username, String password, String secret)",
    """        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (enabled && !TextUtils.isEmpty(address)) {
                native_setProxySettings(a, address, port, username, password, secret);
            } else {
                native_setProxySettings(a, "", 1080, "", "", "");
            }
            AccountInstance accountInstance = AccountInstance.getInstance(a);
            if (accountInstance.getUserConfig().isClientActivated()) {
                accountInstance.getMessagesController().checkPromoInfo(true);
            }
        }""",
    """        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (AccountNetworkController.getInstance().hasExplicitProfile(a)) {
                AccountNetworkController.getInstance().applyProfile(a);
            } else if (enabled && !TextUtils.isEmpty(address)) {
                native_setProxySettings(a, address, port, username, password, secret);
            } else {
                native_setProxySettings(a, "", 1080, "", "", "");
            }
            AccountInstance accountInstance = AccountInstance.getInstance(a);
            if (accountInstance.getUserConfig().isClientActivated()) {
                accountInstance.getMessagesController().checkPromoInfo(true);
            }
        }""",
)

# TelePremium is signed and packaged independently from the official Telegram
# Play Store app. The official Firebase SMS / Play Integrity path is bound to
# Telegram's production package/signing identity, so a fork must request the
# normal Telegram login-code fallback instead of advertising Firebase/app-hash
# verification that it cannot satisfy.
login = "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java"

replace_once(
    login,
    "            settings.allow_app_hash = settings.allow_firebase = PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices();\n",
    "            settings.allow_app_hash = false;\n"
    "            settings.allow_firebase = false;\n",
)

# Login stability: a restored/autofilled phone number can be present before
# the country-code maps have finished initializing. Revalidate the country code
# immediately before send, reset confirmation whenever the visible number
# changes, and never treat a SIM-autofilled number as implicitly confirmed.
login = "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java"

replace_in_method(
    login,
    "public PhoneView(Context context)",
    "                public void afterTextChanged(Editable editable) {\n"
    "                    if (ignoreOnTextChange) {\n"
    "                        return;\n"
    "                    }",
    "                public void afterTextChanged(Editable editable) {\n"
    "                    if (ignoreOnTextChange) {\n"
    "                        return;\n"
    "                    }\n"
    "                    confirmedNumber = false;",
)

replace_in_method(
    login,
    "public PhoneView(Context context)",
    "                public void afterTextChanged(Editable s) {\n"
    "                    if (ignoreOnPhoneChange) {\n"
    "                        return;\n"
    "                    }",
    "                public void afterTextChanged(Editable s) {\n"
    "                    if (ignoreOnPhoneChange) {\n"
    "                        return;\n"
    "                    }\n"
    "                    confirmedNumber = false;",
)

replace_in_method(
    login,
    "public void fillNumber()",
    "                            confirmedNumber = true;",
    "                            confirmedNumber = false;",
)

replace_in_method(
    login,
    "public void onNextPressed(String code)",
    "            if (countryState == COUNTRY_STATE_EMPTY) {",
    "            String normalizedCountryCode = PhoneFormat.stripExceptNumbers(codeField.getText().toString());\n"
    "            List<CountrySelectActivity.Country> currentCodeCountries = codesMap.get(normalizedCountryCode);\n"
    "            if (currentCodeCountries != null && !currentCodeCountries.isEmpty()) {\n"
    "                countryState = COUNTRY_STATE_NOT_SET_OR_VALID;\n"
    "                if (currentCountry == null || !TextUtils.equals(currentCountry.code, normalizedCountryCode)) {\n"
    "                    currentCountry = currentCodeCountries.get(0);\n"
    "                }\n"
    "            }\n"
    "            if (countryState == COUNTRY_STATE_EMPTY) {",
)

# Local-only TelePremium feature: all bundled launcher icons are selectable.
# This does not spoof Telegram Premium status and does not affect server entitlements.
app_icons = "TMessagesProj/src/main/java/org/telegram/ui/Cells/AppIconsSelectorCell.java"

replace_once(
    app_icons,
    "    public final static float ICONS_ROUND_RADIUS = 18;\n",
    "    public final static float ICONS_ROUND_RADIUS = 18;\n"
    "    private static final boolean TELEPREMIUM_LOCAL_ICONS_UNLOCKED = true;\n",
)

replace_in_method(
    app_icons,
    "public AppIconsSelectorCell(Context context, BaseFragment fragment, int currentAccount)",
    "            if (icon.premium && !UserConfig.hasPremiumOnAccounts()) {",
    "            if (!TELEPREMIUM_LOCAL_ICONS_UNLOCKED && icon.premium && !UserConfig.hasPremiumOnAccounts()) {",
)

replace_in_method(
    app_icons,
    "private void updateIconsVisibility()",
    "        if (MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {",
    "        if (!TELEPREMIUM_LOCAL_ICONS_UNLOCKED && MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {",
)

replace_in_method(
    app_icons,
    "private void bind(LauncherIconController.LauncherIcon icon)",
    "            if (icon.premium && !UserConfig.hasPremiumOnAccounts()) {",
    "            if (!TELEPREMIUM_LOCAL_ICONS_UNLOCKED && icon.premium && !UserConfig.hasPremiumOnAccounts()) {",
)

# Local voice-to-text for downloaded voice messages on Android 13+.
# It decodes the Telegram audio to PCM and injects it into the system speech
# recognizer, so it does not depend on Telegram Premium transcription.
transcribe = "TMessagesProj/src/main/java/org/telegram/ui/Components/TranscribeButton.java"

replace_once(
    transcribe,
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\n"
    "import org.telegram.messenger.telepremium.TelePremiumLocalTranscriber;\n",
)

replace_once(
    transcribe,
    "        premium = parent.getMessageObject() != null && UserConfig.getInstance(parent.getMessageObject().currentAccount).isPremium();",
    "        premium = parent.getMessageObject() != null && (UserConfig.getInstance(parent.getMessageObject().currentAccount).isPremium() || TelePremiumLocalTranscriber.isSupported());",
)

replace_in_method(
    transcribe,
    "private static void transcribePressed(MessageObject messageObject, boolean open, ChatMessageCell.ChatMessageCellDelegate delegate)",
    "        int messageId = messageObject.messageOwner.id;\n"
    "        if (open) {",
    "        int messageId = messageObject.messageOwner.id;\n"
    "        if (open && TelePremiumLocalTranscriber.isSupported() && (TextUtils.isEmpty(messageObject.messageOwner.voiceTranscription) || !messageObject.messageOwner.voiceTranscriptionFinal)) {\n"
    "            if (transcribeOperationsByDialogPosition == null) {\n"
    "                transcribeOperationsByDialogPosition = new HashMap<>();\n"
    "            }\n"
    "            transcribeOperationsByDialogPosition.put((Integer) reqInfoHash(messageObject), messageObject);\n"
    "            TelePremiumLocalTranscriber.transcribe(messageObject, new TelePremiumLocalTranscriber.Callback() {\n"
    "                @Override\n"
    "                public void onSuccess(String text) {\n"
    "                    String finalText = text == null ? \"\" : text.trim();\n"
    "                    messageObject.messageOwner.voiceTranscription = finalText;\n"
    "                    messageObject.messageOwner.voiceTranscriptionOpen = true;\n"
    "                    messageObject.messageOwner.voiceTranscriptionFinal = true;\n"
    "                    TranscribeButton.openVideoTranscription(messageObject);\n"
    "                    TranscribeButton.finishTranscription(messageObject, 0, finalText);\n"
    "                }\n"
    "                @Override\n"
    "                public void onError(String reason) {\n"
    "                    if (transcribeOperationsByDialogPosition != null) {\n"
    "                        transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));\n"
    "                    }\n"
    "                    messageObject.messageOwner.voiceTranscriptionOpen = false;\n"
    "                    messageObject.messageOwner.voiceTranscriptionFinal = false;\n"
    "                    MessagesStorage.getInstance(account).updateMessageVoiceTranscriptionOpen(dialogId, messageId, messageObject.messageOwner);\n"
    "                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject);\n"
    "                    android.widget.Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext, reason, android.widget.Toast.LENGTH_LONG).show();\n"
    "                }\n"
    "            });\n"
    "            return;\n"
    "        }\n"
    "        if (open) {",
)

replace_in_method(
    transcribe,
    "public static boolean showTranscribeLock(MessageObject messageObject)",
    "        if (isFreeTranscribeInChat(messageObject)) {",
    "        if (TelePremiumLocalTranscriber.isSupported()) {\n"
    "            return false;\n"
    "        }\n"
    "        if (isFreeTranscribeInChat(messageObject)) {",
)

# Integrate TelePremium into the stock Telegram Premium entry, remove the
# Telegram Business row, and apply the app-wide UI scale.
profile = "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"

replace_in_method(
    profile,
    "private void updateRowsIds()",
    "                if (!getMessagesController().premiumFeaturesBlocked()) {\n"
    "                    businessRow = rowCount++;\n"
    "                }\n",
    "",
)

replace_in_method(
    profile,
    "public View createView(Context context)",
    '            } else if (position == premiumRow) {\n'
    '                presentFragment(new PremiumPreviewFragment("settings"));\n',
    '            } else if (position == premiumRow) {\n'
    '                presentFragment(new TelePremiumOverviewActivity());\n',
)

application_loader = "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java"

replace_once(
    application_loader,
    "import org.telegram.messenger.voip.VideoCapturerDevice;\n",
    "import org.telegram.messenger.voip.VideoCapturerDevice;\n"
    "import org.telegram.messenger.telepremium.TelePremiumUiScale;\n",
)

replace_in_method(
    application_loader,
    "public void onCreate()",
    "        super.onCreate();",
    "        super.onCreate();\n"
    "        TelePremiumUiScale.apply(applicationContext);",
)

replace_in_method(
    application_loader,
    "public void onConfigurationChanged(Configuration newConfig)",
    "        super.onConfigurationChanged(newConfig);",
    "        super.onConfigurationChanged(newConfig);\n"
    "        TelePremiumUiScale.apply(applicationContext);",
)

launch_activity = "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"

replace_once(
    launch_activity,
    "import org.telegram.messenger.ApplicationLoader;\n",
    "import org.telegram.messenger.ApplicationLoader;\n"
    "import org.telegram.messenger.telepremium.TelePremiumUiScale;\n",
)

replace_in_method(
    launch_activity,
    "protected void onCreate(Bundle savedInstanceState)",
    "        ApplicationLoader.postInitApplication();\n"
    "        AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());",
    "        ApplicationLoader.postInitApplication();\n"
    "        TelePremiumUiScale.apply(this);\n"
    "        AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());",
)

replace_in_method(
    launch_activity,
    "public void onConfigurationChanged(Configuration newConfig)",
    "        AndroidUtilities.checkDisplaySize(this, newConfig);",
    "        TelePremiumUiScale.apply(this);\n"
    "        AndroidUtilities.checkDisplaySize(this, newConfig);",
)


# Experimental user color/profile color unlock. Remove only the local Premium
# gate; do not spoof account Premium state. Telegram still decides server-side.
peer_color = "TMessagesProj/src/main/java/org/telegram/ui/PeerColorActivity.java"

replace_once(
    peer_color,
    "            button.setText(isChannel ? buttonUnlocked : (!getUserConfig().isPremium() ? buttonLocked : buttonUnlocked), false);\n",
    "            button.setText(buttonUnlocked, false);\n",
)

replace_once(
    peer_color,
    "                button.setText(!getUserConfig().isPremium() ? buttonLocked : buttonUnlocked, true);\n",
    "                button.setText(buttonUnlocked, true);\n",
)

replace_once(
    peer_color,
    """        if (isChannel) {
            finishFragment();
        } else {
            if (!getUserConfig().isPremium()) {
                showDialog(new PremiumFeatureBottomSheet(PeerColorActivity.this, PremiumPreviewFragment.PREMIUM_FEATURE_NAME_COLOR, true));
                return;
            }
        }

        apply();""",
    """        if (isChannel) {
            finishFragment();
        }

        apply();""",
)

replace_once(
    peer_color,
    "        if (applying || !isChannel && !getUserConfig().isPremium()) {\n            return;\n        }\n",
    "        if (applying) {\n            return;\n        }\n",
)

# Experimental voice-message privacy unlock. Remove only the local Premium
# gates for Contacts/Nobody and exception rows; normal privacy TL request remains.
privacy_control = "TMessagesProj/src/main/java/org/telegram/ui/PrivacyControlActivity.java"

replace_once(
    privacy_control,
    """            } else if (rulesType == PRIVACY_RULES_TYPE_VOICE_MESSAGES && (position == myContactsRow || position == nobodyRow) && !getUserConfig().isPremium()) {
                BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.star_premium_2,
                        LocaleController.getString(R.string.OptionPremiumRequiredTitle),
                        AndroidUtilities.replaceTags(LocaleController.getString(R.string.OptionPremiumRequiredMessage)),
                        LocaleController.getString(R.string.OptionPremiumRequiredButton),
                        () -> {
                            presentFragment(new PremiumPreviewFragment("settings"));
                        }
                ).show();
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
                AndroidUtilities.shakeViewSpring(view, shakeDp = -shakeDp);
""",
    "",
)

replace_once(
    privacy_control,
    "        if (rulesType != PRIVACY_RULES_TYPE_MESSAGES && (rulesType != PRIVACY_RULES_TYPE_VOICE_MESSAGES || getUserConfig().isPremium())) {\n",
    "        if (rulesType != PRIVACY_RULES_TYPE_MESSAGES) {\n",
)

replace_once(
    privacy_control,
    """                        if (getUserConfig().isPremium()) {
                            backgroundResId = R.drawable.greydivider;
                        } else {
                            backgroundResId = R.drawable.greydivider_bottom;
                        }
""",
    "                        backgroundResId = R.drawable.greydivider;\n",
)

p, src = read(privacy_control)
old_voice_lock = "rulesType == PRIVACY_RULES_TYPE_VOICE_MESSAGES && !getUserConfig().isPremium() || rulesType == PRIVACY_RULES_TYPE_MESSAGES && !getMessagesController().newNoncontactPeersRequirePremiumWithoutOwnpremium && !getUserConfig().isPremium()"
new_voice_lock = "rulesType == PRIVACY_RULES_TYPE_MESSAGES && !getMessagesController().newNoncontactPeersRequirePremiumWithoutOwnpremium && !getUserConfig().isPremium()"
count = src.count(old_voice_lock)
if count != 2:
    raise SystemExit(f"{privacy_control}: expected exactly two voice lock matches, found {count}")
p.write_text(src.replace(old_voice_lock, new_voice_lock), encoding="utf-8")
print(f"patched: {privacy_control} :: removed {count} voice privacy lock icons")

print("TelePremium Concept 5 patches applied successfully.")
