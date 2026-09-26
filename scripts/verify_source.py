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
    "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java": [
        "telePremiumRow",
        "new TelePremiumCenterActivity()",
    ],
    "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCenterActivity.java": [
        "Мультиаккаунты",
        "Сеть и стабильность",
        "Файлы",
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

if failures:
    print("SOURCE VERIFICATION FAILED")
    for failure in failures:
        print(" -", failure)
    sys.exit(1)

print("SOURCE VERIFICATION OK")
print("Application ID: com.telepremium.messenger")
print("Account slots: 10 Java / 10 native")
print("APK updater: frozen")
print("Telegram translation: native, message control default ON")
print("Concept 5: center UI + per-account network core + file center foundation")
print("Login: custom-package Firebase/Play Integrity SMS path disabled; Telegram fallback enabled")
