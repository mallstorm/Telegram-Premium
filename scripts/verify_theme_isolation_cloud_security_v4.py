#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram" / "TMessagesProj" / "src" / "main" / "java"

checks = {
    "org/telegram/ui/ThemeActivity.java": [
        "TelePremiumThemeController.ensureShellTheme(context)",
        "Global palette/theme selection is intentionally owned by TelePremiumAppearanceActivity",
    ],
    "org/telegram/ui/LaunchActivity.java": [
        "TelePremiumCloudAccessGate.openCloud(this, currentAccount)",
    ],
    "org/telegram/ui/DialogsActivity.java": [
        "TelePremiumCloudAccessGate.openCloud(this, currentAccount)",
    ],
    "org/telegram/ui/TelePremiumCloudActivity.java": [
        "TelePremiumCloudSecurityStore.requiresCategoryUnlock",
        "TelePremiumCloudAccessGate.openCategory",
        "TelePremiumCloudAccessGate.canExposeNode",
        "Защищено",
    ],
    "org/telegram/ui/TelePremiumCloudSecurityActivity.java": [
        "Задать графический ключ",
        "Биометрия",
        "Закрывать всё облако",
        "Фото",
        "Видео",
    ],
    "org/telegram/ui/TelePremiumCloudUnlockActivity.java": [
        "BiometricPrompt",
        "TelePremiumPatternView",
        "TelePremiumCloudSecuritySession.unlock",
    ],
    "org/telegram/messenger/telepremium/cloud/TelePremiumCloudSecurityStore.java": [
        "SHA-256",
        "CATEGORY_IMAGES",
        "CATEGORY_VIDEO",
        "remainingLockoutMs",
    ],
}

for rel, needles in checks.items():
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing V4 source: {rel}")
    text = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"{rel}: missing invariant {needle!r}")

activity = (SRC / "org/telegram/ui/ThemeActivity.java").read_text(encoding="utf-8")
start = activity.find("} else if (currentType == THEME_TYPE_BASIC) {")
end = activity.find("        } else {", start + 1)
if start < 0 or end < 0:
    raise SystemExit("ThemeActivity BASIC block not found")
basic = activity[start:end]
for forbidden in ("themeListRow2 = rowCount++", "nightThemeRow = rowCount++"):
    if forbidden in basic:
        raise SystemExit(f"ThemeActivity still exposes global theme mutation in BASIC: {forbidden}")

print("Theme isolation + Cloud security V4 invariants verified")
