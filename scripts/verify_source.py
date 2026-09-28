#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"

checks = {
    "gradle.properties": [
        "APP_PACKAGE=com.telepremium.messenger",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java": [
        "MAX_ACCOUNT_DEFAULT_COUNT = 10",
        "MAX_ACCOUNT_COUNT = 10",
        "return MAX_ACCOUNT_COUNT;",
    ],
    "TMessagesProj/jni/tgnet/Defines.h": [
        "#define MAX_ACCOUNT_COUNT 10",
    ],
    "TMessagesProj/jni/tgnet/ConnectionsManager.cpp": [
        "instance5(5)",
        "instance6(6)",
        "instance7(7)",
        "instance8(8)",
        "instance9(9)",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java": [
        '"/data/data/" + getApplicationId() + "/files"',
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java": [
        'realMimeType != null ? realMimeType : "*/*"',
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/TranslateController.java": [
        'getBoolean("translate_button", true)',
        "return isChatTranslateEnabled();",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/LanguageSelectActivity.java": [
        "cell.setCheckBoxIcon(0);",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java": [
        "public SponsoredMessagesInfo getSponsoredMessages(long dialogId)",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java": [
        "private void addSponsoredMessages(boolean animated)",
    ],
    "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java": [
        "AccountNetworkController",
        "AccountNetworkController.getInstance().applyProfile(currentAccount);",
        "hasExplicitProfile(a)",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/AccountNetworkProfile.java": [
        "MODE_INHERIT",
        "MODE_MTPROTO",
        "MODE_SOCKS5",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/AccountNetworkStore.java": [
        "telepremium_network_account_",
        "bound_user_id",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/AccountNetworkController.java": [
        "applyProfile(int account)",
        "applyInheritedGlobal",
        "native_setProxySettings",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java": [
        "settings.allow_app_hash = false;",
        "settings.allow_firebase = false;",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/Components/TranscribeButton.java": [
        "TelePremiumLocalTranscriber.isSupported()",
        "TelePremiumLocalTranscriber.transcribe",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumLocalTranscriber.java": [
        "RecognizerIntent.EXTRA_AUDIO_SOURCE",
        "MediaCodec.createDecoderByType",
        "SpeechRecognizer.createSpeechRecognizer",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/Cells/AppIconsSelectorCell.java": [
        "TELEPREMIUM_LOCAL_ICONS_UNLOCKED = true",
        "!TELEPREMIUM_LOCAL_ICONS_UNLOCKED && icon.premium",
        "!TELEPREMIUM_LOCAL_ICONS_UNLOCKED && MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java": [
        "new TelePremiumOverviewActivity()",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumOverviewActivity.java": [
        "TelePremium",
        "Без рекламы",
        "Перевод чатов",
        "Расшифровка голосовых",
        "Локальный emoji-статус",
        "Реакция по двойному тапу",
        "new ReactionsDoubleTapManageActivity()",
        "new TelePremiumCenterActivity()",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumPremiumRouter.java": [
        "route(BaseFragment fragment)",
        "fragment instanceof PremiumPreviewFragment",
        "new TelePremiumOverviewActivity()",
        "hideTelegramPremiumUpsell()",
        "localAnimatedEmojiUnlocked()",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumAppearanceActivity.java": [
        "Масштаб интерфейса",
        "TelePremiumUiScale.setScalePercent",
        "activity.recreate()",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumUiScale.java": [
        "DEFAULT_SCALE = 92",
        "updateConfiguration",
        "scaledDensity",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumPeerAppearanceStore.java": [
        "telepremium_peer_appearance",
        "getNameColor",
        "getProfileEmoji",
        "public static void save",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumEmojiStatusStore.java": [
        "telepremium_emoji_status",
        "hasOverride(int account, long userId)",
        "getDocumentId(int account, long userId",
        "getStatus(int account, long userId",
        "public static void save(int account, long userId",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumShellTint.java": [
        "telepremium_shell_tint",
        "getTargetColor",
        "public static int tint",
        "putInt(KEY_POSITION",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/Cells/TelePremiumShellTintCell.java": [
        "Оттенок оболочки",
        "Белый → тёплые оттенки → цветные → чёрный",
        "TelePremiumShellTint.setPosition",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/UserObject.java": [
        "TelePremiumPeerAppearanceStore.getNameColor",
        "TelePremiumPeerAppearanceStore.getNameEmoji",
        "TelePremiumPeerAppearanceStore.getProfileColor",
        "TelePremiumPeerAppearanceStore.getProfileEmoji",
        "TelePremiumEmojiStatusStore.getDocumentId",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java": [
        "TelePremiumUiScale.apply(applicationContext);",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCenterActivity.java": [
        "Мультиаккаунты",
        "Сеть и стабильность",
        "Файлы",
        "Масштаб интерфейса",
        "TelePremiumAppearanceActivity",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumAccountCenterActivity.java": [
        "Центр аккаунтов",
        "switchToAccount",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumNetworkCenterActivity.java": [
        "Маршруты аккаунтов",
        "TelePremiumNetworkProfileActivity",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumNetworkProfileActivity.java": [
        "Сохранить и применить",
        "checkProfile",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumFileCenterActivity.java": [
        "TelePremium File Center",
        "TelePremiumDownloadsActivity",
    ],
}

failures = []
for rel, needles in checks.items():
    p = SRC / rel
    if not p.exists():
        failures.append(f"missing {rel}")
        continue
    data = p.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in data:
            failures.append(f"{rel}: missing {needle}")

launch = SRC / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
shared = SRC / "TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java"

if launch.exists():
    ldata = launch.read_text(encoding="utf-8")
    s = ldata.find("public void checkAppUpdate(boolean force, Browser.Progress progress)")
    snippet = ldata[s:s + 400] if s >= 0 else ""
    if s < 0 or "progress.end()" not in snippet or "return;" not in snippet:
        failures.append("LaunchActivity.checkAppUpdate is not frozen")
else:
    failures.append("LaunchActivity.java missing")

if shared.exists():
    sdata = shared.read_text(encoding="utf-8")
    s = sdata.find("public static boolean isAppUpdateAvailable()")
    if s < 0 or "return false;" not in sdata[s:s + 180]:
        failures.append("SharedConfig.isAppUpdateAvailable is not frozen")
else:
    failures.append("SharedConfig.java missing")

peer_color = SRC / "TMessagesProj/src/main/java/org/telegram/ui/PeerColorActivity.java"
if peer_color.exists():
    pcdata = peer_color.read_text(encoding="utf-8")
    if "button.setText(isChannel ? buttonUnlocked : (!getUserConfig().isPremium() ? buttonLocked : buttonUnlocked), false);" in pcdata:
        failures.append("PeerColorActivity: initial Apply button is still locally Premium-locked")
    if "button.setText(!getUserConfig().isPremium() ? buttonLocked : buttonUnlocked, true);" in pcdata:
        failures.append("PeerColorActivity: Premium state refresh can re-lock Apply button")
    if "new PremiumFeatureBottomSheet(PeerColorActivity.this, PremiumPreviewFragment.PREMIUM_FEATURE_NAME_COLOR, true)" in pcdata:
        failures.append("PeerColorActivity: Apply click still opens the local Premium paywall")
    if "if (applying || !isChannel && !getUserConfig().isPremium())" in pcdata:
        failures.append("PeerColorActivity: apply() still has the local Premium guard")
    apply_start = pcdata.find("private void apply()")
    apply_end = pcdata.find("private void showBulletin()", apply_start)
    apply_snippet = pcdata[apply_start:apply_end] if apply_start >= 0 and apply_end > apply_start else ""
    if "TL_account_updateColor" in apply_snippet or "sendRequest(req" in apply_snippet:
        failures.append("PeerColorActivity: user appearance still sends Telegram account.updateColor")
    if "TelePremiumPeerAppearanceStore.save(" not in apply_snippet:
        failures.append("PeerColorActivity: local TelePremium appearance store is not used")
else:
    failures.append("PeerColorActivity.java missing")

profile_activity = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"
if profile_activity.exists():
    pdata = profile_activity.read_text(encoding="utf-8")
    if "businessRow = rowCount++;" in pdata:
        failures.append("ProfileActivity: Telegram Business row allocation is still present")
    premium_click = pdata.find("} else if (position == premiumRow) {")
    premium_snippet = pdata[premium_click:premium_click + 260] if premium_click >= 0 else ""
    if premium_click < 0 or "new TelePremiumOverviewActivity()" not in premium_snippet:
        failures.append("ProfileActivity: Premium settings row is not wired to TelePremiumOverviewActivity")

    status_start = pdata.find("public void showStatusSelect()")
    status_end = pdata.find("private Drawable getEmojiStatusDrawable", status_start)
    status_snippet = pdata[status_start:status_end] if status_start >= 0 and status_end > status_start else ""
    if "TelePremiumEmojiStatusStore.save(currentAccount" not in status_snippet:
        failures.append("ProfileActivity: local emoji status store is not used")
    if "TL_account_updateEmojiStatus" in status_snippet:
        failures.append("ProfileActivity: user emoji status still sends Telegram account.updateEmojiStatus")
    if "if (request != null)" not in status_snippet:
        failures.append("ProfileActivity: channel-only emoji status request guard is missing")

    drawable_start = pdata.find("private Drawable getEmojiStatusDrawable")
    drawable_end = pdata.find("private float lastEmojiStatusProgress", drawable_start)
    drawable_snippet = pdata[drawable_start:drawable_end] if drawable_start >= 0 and drawable_end > drawable_start else ""
    if "TelePremiumEmojiStatusStore.hasOverride" not in drawable_snippet or "R.drawable.msg_status_set" not in drawable_snippet:
        failures.append("ProfileActivity: local emoji status rendering hook is missing")

    update_start = pdata.find("private void updateProfileData(boolean reload)")
    update_snippet = pdata[update_start:update_start + 45000] if update_start >= 0 else ""
    if "if (user.self)" not in update_snippet:
        failures.append("ProfileActivity: self emoji status selector is still Premium-only")
    if 'textCell.setTextAndIcon("TelePremium"' not in pdata:
        failures.append("ProfileActivity: settings row still advertises Telegram Premium")
else:
    failures.append("ProfileActivity.java missing")

# Verify that the no-ads methods were replaced, not merely that their signatures exist.
messages_controller = SRC / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
if messages_controller.exists():
    mdata = messages_controller.read_text(encoding="utf-8")
    s = mdata.find("public SponsoredMessagesInfo getSponsoredMessages(long dialogId)")
    snippet = mdata[s:s + 180] if s >= 0 else ""
    if s < 0 or "return null;" not in snippet:
        failures.append("SponsoredMessagesInfo method is not locally disabled")

chat_activity = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
if chat_activity.exists():
    cdata = chat_activity.read_text(encoding="utf-8")
    s = cdata.find("private void addSponsoredMessages(boolean animated)")
    snippet = cdata[s:s + 160] if s >= 0 else ""
    if s < 0 or "return;" not in snippet:
        failures.append("ChatActivity sponsored rendering is not disabled")

base_fragment = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BaseFragment.java"
if base_fragment.exists():
    bdata = base_fragment.read_text(encoding="utf-8")
    if bdata.count("TelePremiumPremiumRouter.route(fragment)") < 4:
        failures.append("BaseFragment: Telegram Premium fragment routing is incomplete")
else:
    failures.append("BaseFragment.java missing")

launch_activity = SRC / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
if launch_activity.exists():
    ldata = launch_activity.read_text(encoding="utf-8")
    if ldata.count("TelePremiumPremiumRouter.route(fragment)") < 2:
        failures.append("LaunchActivity: direct Premium fragment routing is incomplete")
else:
    failures.append("LaunchActivity.java missing")

premium_sheet = SRC / "TMessagesProj/src/main/java/org/telegram/ui/Components/Premium/PremiumFeatureBottomSheet.java"
if premium_sheet.exists():
    psdata = premium_sheet.read_text(encoding="utf-8")
    if "TelePremiumPremiumRouter.openOverview(baseFragment)" not in psdata:
        failures.append("PremiumFeatureBottomSheet: official Premium upsell is not redirected")
else:
    failures.append("PremiumFeatureBottomSheet.java missing")

premium_preview_sheet = SRC / "TMessagesProj/src/main/java/org/telegram/ui/Components/Premium/PremiumPreviewBottomSheet.java"
if premium_preview_sheet.exists():
    ppdata = premium_preview_sheet.read_text(encoding="utf-8")
    if "TelePremiumPremiumRouter.openOverview(fragment)" not in ppdata:
        failures.append("PremiumPreviewBottomSheet: official Premium profile sheet is not redirected")
else:
    failures.append("PremiumPreviewBottomSheet.java missing")

action_bar_layout = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java"
if action_bar_layout.exists():
    adata = action_bar_layout.read_text(encoding="utf-8")
    s = adata.find("public boolean presentFragment(NavigationParams params)")
    snippet = adata[s:s + 500] if s >= 0 else ""
    if "TelePremiumPremiumRouter.route(params.fragment)" not in snippet:
        failures.append("ActionBarLayout: global Premium fragment routing is missing")
else:
    failures.append("ActionBarLayout.java missing")

premium_button = SRC / "TMessagesProj/src/main/java/org/telegram/ui/Components/Premium/PremiumButtonView.java"
if premium_button.exists():
    pbdata = premium_button.read_text(encoding="utf-8")
    s = pbdata.find("public void setButton(String text, View.OnClickListener clickListener, boolean animated)")
    snippet = pbdata[s:s + 500] if s >= 0 else ""
    if "setVisibility(View.GONE);" not in snippet:
        failures.append("PremiumButtonView: Telegram Premium purchase button can still be shown")
else:
    failures.append("PremiumButtonView.java missing")

premium_lock = SRC / "TMessagesProj/src/main/java/org/telegram/ui/Components/Premium/PremiumLockIconView.java"
if premium_lock.exists():
    pldata = premium_lock.read_text(encoding="utf-8")
    s = pldata.find("protected void onDraw(Canvas canvas)")
    snippet = pldata[s:s + 260] if s >= 0 else ""
    if "type != TYPE_GIFT_LOCK" not in snippet:
        failures.append("PremiumLockIconView: Premium lock chrome is still drawable")
else:
    failures.append("PremiumLockIconView.java missing")

emoji_tabs = SRC / "TMessagesProj/src/main/java/org/telegram/ui/Components/EmojiTabsStrip.java"
if emoji_tabs.exists():
    etdata = emoji_tabs.read_text(encoding="utf-8")
    if "else if (!isPremium && !free)" in etdata:
        failures.append("EmojiTabsStrip: Premium emoji tab locks remain")
else:
    failures.append("EmojiTabsStrip.java missing")

theme_java = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java"
if theme_java.exists():
    tdata = theme_java.read_text(encoding="utf-8")
    if "TelePremiumShellTint.tint" in tdata:
        failures.append("Theme.java: global shell tint hook must not touch chat/content colors")
else:
    failures.append("Theme.java missing")

base_fragment_shell = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BaseFragment.java"
if base_fragment_shell.exists():
    bfdata = base_fragment_shell.read_text(encoding="utf-8")
    create = bfdata.find("public ActionBar createActionBar(Context context)")
    snippet = bfdata[create:create + 2200] if create >= 0 else ""
    if "TelePremiumShellTint.actionBarBackground" not in snippet:
        failures.append("BaseFragment: top action bar is not shell-tinted")
    if "TelePremiumShellTint.foreground" not in snippet:
        failures.append("BaseFragment: action bar auto-contrast foreground is missing")
else:
    failures.append("BaseFragment.java missing for shell tint verification")

launch_shell = SRC / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
if launch_shell.exists():
    ldata = launch_shell.read_text(encoding="utf-8")
    if ldata.count("TelePremiumShellTint.drawerBackground") < 4:
        failures.append("LaunchActivity: drawer background tint hooks are incomplete")
    if "TelePremiumShellTint.navigationBarBackground" not in ldata:
        failures.append("LaunchActivity: bottom navigation bar shell tint is missing")
else:
    failures.append("LaunchActivity.java missing for shell tint verification")

for rel, needle in [
    ("TMessagesProj/src/main/java/org/telegram/ui/Cells/DrawerActionCell.java", "TelePremiumShellTint.drawerColor"),
    ("TMessagesProj/src/main/java/org/telegram/ui/Cells/DrawerUserCell.java", "TelePremiumShellTint.drawerColor"),
    ("TMessagesProj/src/main/java/org/telegram/ui/Cells/DrawerProfileCell.java", "TelePremiumShellTint.drawerColor"),
]:
    p = SRC / rel
    if not p.exists() or needle not in p.read_text(encoding="utf-8"):
        failures.append(f"{rel}: drawer auto-contrast shell tint hook is missing")

dialogs_shell = SRC / "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
if dialogs_shell.exists():
    ddata = dialogs_shell.read_text(encoding="utf-8")
    if "TelePremiumShellTint.actionBarBackground" not in ddata:
        failures.append("DialogsActivity: chat-list top chrome is not shell-tinted")
else:
    failures.append("DialogsActivity.java missing for shell tint verification")

theme_activity = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ThemeActivity.java"
if theme_activity.exists():
    thdata = theme_activity.read_text(encoding="utf-8")
    if "TelePremiumShellTintCell" not in thdata or "shellTintRow = rowCount++;" not in thdata:
        failures.append("ThemeActivity: shell tint slider is missing")
    if "nightThemeRow = rowCount++;" in thdata:
        failures.append("ThemeActivity: Auto-Night row still occupies the shell tint slot")
else:
    failures.append("ThemeActivity.java missing")

premium_preview = SRC / "TMessagesProj/src/main/java/org/telegram/ui/PremiumPreviewFragment.java"
if premium_preview.exists():
    ppf = premium_preview.read_text(encoding="utf-8")
    buy = ppf.find("public static void buyPremium(BaseFragment fragment, SubscriptionTier tier, String source, boolean forcePremium, BillingFlowParams.SubscriptionUpdateParams updateParams)")
    buy_snippet = ppf[buy:buy + 700] if buy >= 0 else ""
    if "TelePremiumPremiumRouter.hideTelegramPremiumUpsell()" not in buy_snippet or "TelePremiumPremiumRouter.openOverview(fragment)" not in buy_snippet:
        failures.append("PremiumPreviewFragment.buyPremium can still start Telegram Premium billing")
else:
    failures.append("PremiumPreviewFragment.java missing")

emoji_dialog = SRC / "TMessagesProj/src/main/java/org/telegram/ui/SelectAnimatedEmojiDialog.java"
if emoji_dialog.exists():
    edata = emoji_dialog.read_text(encoding="utf-8")
    if "protected boolean allowEmojisForNonPremium()" not in edata or "TelePremiumPremiumRouter.localAnimatedEmojiUnlocked()" not in edata:
        failures.append("SelectAnimatedEmojiDialog: emoji pack tabs can still show Premium locks")
    if "header.setText(pack.set.title, false);" not in edata:
        failures.append("SelectAnimatedEmojiDialog: emoji pack headers can still show Premium locks")
    if "button.set(pack.set.title, false, pack.installed" not in edata:
        failures.append("SelectAnimatedEmojiDialog: emoji pack button can still advertise Premium unlock")
else:
    failures.append("SelectAnimatedEmojiDialog.java missing")

double_tap = SRC / "TMessagesProj/src/main/java/org/telegram/ui/ReactionsDoubleTapManageActivity.java"
if double_tap.exists():
    ddata = double_tap.read_text(encoding="utf-8")
    s = ddata.find("private void updateRows()")
    e = ddata.find("@Override", s)
    snippet = ddata[s:e] if s >= 0 and e > s else ""
    if "premiumReactionRow = rowCount++;" not in snippet or "isPremium()" in snippet:
        failures.append("ReactionsDoubleTapManageActivity: custom local reaction selector is still Premium-gated")
else:
    failures.append("ReactionsDoubleTapManageActivity.java missing")

if failures:
    print("SOURCE VERIFICATION FAILED")
    for failure in failures:
        print(" -", failure)
    sys.exit(1)

print("SOURCE VERIFICATION OK")
print("Application ID: com.telepremium.messenger")
print("Account slots: 10 Java / 10 native")
print("APK updater: frozen")
print("Telegram translation: native transport, full-chat client gate unlocked")
print("Local features: all bundled launcher icons unlocked")
print("Local appearance: user name/profile colors and background emoji stored only in TelePremium")
print("Local emoji status: account/user scoped, no account.updateEmojiStatus RPC")
print("Local double-tap reaction: custom selector enabled without Telegram Premium state")
print("Premium routing: Telegram subscription previews redirect to TelePremium")
print("Animated emoji picker: Premium lock chrome removed for local client use")
print("Premium purchase UI: hidden globally; Stars flows untouched")
print("Shell tint: drawer + top/bottom chrome only; chat list and messages stay on base theme with automatic foreground contrast")
print("Local Voice-to-Text: Android 13+ system recognizer audio injection")
print("TelePremium no-ads: sponsored fetching/rendering disabled")
print("Concept 5: custom Premium overview + Business row removed + center UI")
print("UI scale: app-wide density/scaledDensity, default 92%")
print("Login: custom-package Firebase/Play Integrity SMS path disabled; Telegram fallback enabled")
