#!/usr/bin/env python3
"""Verify the TelePremium 13.x tree after scripts/apply_overlay.py patches/tg13."""
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TG = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT / "upstream" / "Telegram"
JAVA = TG / "TMessagesProj/src/main/java/org/telegram"
errors = []


def read(rel: str) -> str:
    return (TG / rel).read_text(encoding="utf-8")


def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


manifest = json.loads((ROOT / "patches/tg13/manifest.json").read_text(encoding="utf-8"))
for entry in manifest["files"]:
    path = TG / entry["path"]
    need(path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"], f"not applied: {entry['path']}")

props = read("gradle.properties")
need("APP_PACKAGE=com.telepremium.messenger" in props, "application id is not com.telepremium.messenger")
code = int(re.search(r"APP_VERSION_CODE=(\d+)", props).group(1))
need(code * 10 + 9 > 547099, "versionCode must be higher than the 11.4.2 TelePremium build (547099)")

for rel in ("TMessagesProj/build.gradle", "TMessagesProj_App/build.gradle"):
    for line in read(rel).splitlines():
        if "abiFilters" in line:
            need(not re.search(r"armeabi-v7a|\"x86\"|x86_64", line), f"non-arm64 ABI in {rel}: {line.strip()}")
need('defaultConfig { ndk { abiFilters "arm64-v8a" } }' in read("TMessagesProj/build.gradle"), "native library module is not arm64-only")

need("MAX_ACCOUNT_COUNT = 10;" in read("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"), "Java account limit is not 10")
need("#define MAX_ACCOUNT_COUNT 10" in read("TMessagesProj/jni/tgnet/Defines.h"), "native account limit is not 10")

settings = read("TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java")
for marker in ("TP_ITEM_CENTER", "TP_ITEM_CLOUD", "TP_ITEM_MEDIA", "TP_ITEM_APPEARANCE", "onTelePremiumClick(item)"):
    need(marker in settings, f"Settings entry point missing: {marker}")
need("DISABLE_TELEGRAM_UPDATER" in read("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"), "Telegram APK updater is not disabled")
need("TelePremiumServicePeerRegistry.filterDialogs" in read("TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"), "Cloud service peer is not filtered from dialogs")
need('path="tpvault_dec/"' in read("TMessagesProj/src/main/res/xml/provider_paths.xml"), "Vault FileProvider path missing")

vault = JAVA / "messenger/telepremium/cloud/vault"
for name in ("TelePremiumVaultCrypto", "TelePremiumVaultKeyManager", "TelePremiumVaultIndexStore", "VaultFileOps"):
    need((vault / f"{name}.java").exists(), f"missing Vault class {name}")
need("PBKDF2WithHmacSHA256" in (JAVA / "messenger/telepremium/cloud/TelePremiumCloudSecurityStore.java").read_text(encoding="utf-8"), "pattern PBKDF2 missing")

# TelePremium strings: English in res/values, Russian in assets (Telegram 13 strips locale resources).
import xml.etree.ElementTree as ET
en_keys = set()
for f in (TG / "TMessagesProj/src/main/res/values").glob("strings_telepremium*.xml"):
    en_keys |= {s.get("name") for s in ET.parse(f).getroot().findall("string")}
ru_path = TG / "TMessagesProj/src/main/assets/telepremium/strings_ru.json"
ru_keys = set(json.loads(ru_path.read_text(encoding="utf-8"))) if ru_path.exists() else set()
need(en_keys and en_keys == ru_keys, f"TelePremium en/ru string keys differ: {sorted(en_keys ^ ru_keys)[:5]}")
used = set()
for f in JAVA.rglob("*.java"):
    src = f.read_text(encoding="utf-8")
    used |= set(re.findall(r'TPStrings\.(?:get|format)\("(TP_\w+)", org\.telegram\.messenger\.R\.string\.(TP_\w+)', src))
need(all(a == b for a, b in used), "TPStrings key does not match its resource id")
need({a for a, _ in used} <= en_keys, f"TPStrings keys without resources: {sorted({a for a, _ in used} - en_keys)[:5]}")

if errors:
    for error in errors:
        print(f"::error::{error}")
    sys.exit(1)
print(f"TelePremium 13.x tree verified: {len(manifest['files'])} overlay files, arm64-v8a only, 10 accounts")
