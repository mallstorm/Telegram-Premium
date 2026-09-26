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
    old_proxy_loop,
    new_proxy_loop,
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

# Add the TelePremium entry to the existing Telegram settings profile.
profile = "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"

replace_once(
    profile,
    "    private int notificationRow;\n    private int languageRow;\n",
    "    private int notificationRow;\n"
    "    private int telePremiumRow;\n"
    "    private int languageRow;\n",
)

replace_once(
    profile,
    "        notificationRow = -1;\n        languageRow = -1;\n",
    "        notificationRow = -1;\n"
    "        telePremiumRow = -1;\n"
    "        languageRow = -1;\n",
)

replace_once(
    profile,
    "                settingsSectionRow2 = rowCount++;\n"
    "                chatRow = rowCount++;\n",
    "                settingsSectionRow2 = rowCount++;\n"
    "                telePremiumRow = rowCount++;\n"
    "                chatRow = rowCount++;\n",
)

replace_once(
    profile,
    "            } else if (position == notificationRow) {\n"
    "                presentFragment(new NotificationsSettingsActivity());\n",
    "            } else if (position == telePremiumRow) {\n"
    "                presentFragment(new TelePremiumCenterActivity());\n"
    "            } else if (position == notificationRow) {\n"
    "                presentFragment(new NotificationsSettingsActivity());\n",
)

replace_once(
    profile,
    "                    } else if (position == notificationRow) {\n"
    "                        textCell.setTextAndIcon(LocaleController.getString(R.string.NotificationsAndSounds), R.drawable.msg2_notifications, true);\n",
    "                    } else if (position == telePremiumRow) {\n"
    "                        textCell.setTextAndValueAndIcon(\"TelePremium\", \"Аккаунты, сеть, файлы\", R.drawable.menu_premium_main, true);\n"
    "                    } else if (position == notificationRow) {\n"
    "                        textCell.setTextAndIcon(LocaleController.getString(R.string.NotificationsAndSounds), R.drawable.msg2_notifications, true);\n",
)

replace_once(
    profile,
    "                return position == notificationRow || position == numberRow || position == privacyRow ||\n",
    "                return position == telePremiumRow || position == notificationRow || position == numberRow || position == privacyRow ||\n",
)

replace_once(
    profile,
    "                    position == sendMessageRow || position == notificationRow || position == privacyRow ||\n",
    "                    position == sendMessageRow || position == telePremiumRow || position == notificationRow || position == privacyRow ||\n",
)

print("TelePremium Concept 5 patches applied successfully.")
