#!/usr/bin/env python3
"""Guard the optional Telegram-11 classic renderer on the Telegram-13.1.1 source."""
from pathlib import Path
import json
import re

root = Path(__file__).resolve().parents[1]
overlay = root / "patches/tg13/overlay"
java = overlay / "TMessagesProj/src/main/java"
def src(rel):
    return (java / rel).read_text(encoding="utf-8")

prefs = src("org/telegram/messenger/telepremium/TelePremiumUiPrefs.java")
main = src("org/telegram/ui/MainTabsActivity.java")
settings = src("org/telegram/ui/SettingsActivity.java")
drawer = src("org/telegram/ui/TelePremiumClassicDrawer.java")
classic = src("org/telegram/ui/TelePremiumClassicSettingsActivity.java")
picker = src("org/telegram/ui/TelePremiumInterfaceModeActivity.java")

checks = {
    "classic preference is default": 'getBoolean("classic_ui_11x_enabled", true)' in prefs,
    "classic preference can persist": 'setClassicUiEnabled(boolean enabled)' in prefs,
    "classic drawer is attached conditionally": 'if (TelePremiumUiPrefs.classicUiEnabled())' in main and 'new TelePremiumClassicDrawer(this, context)' in main,
    "classic hides modern tabs": 'tabsViewWrapper.setVisibility(View.GONE)' in main,
    "drawer has a working back route": 'classicDrawer.isOpened()' in main,
    "account switching retains Telegram 13 native path": 'LaunchActivity.instance.switchToAccount(account, true)' in drawer,
    "up to ten accounts by native account count": 'UserConfig.MAX_ACCOUNT_COUNT' in drawer,
    "core settings route to native screens": all(v in classic for v in ('PrivacySettingsActivity()', 'NotificationsSettingsActivity()', 'DataSettingsActivity()', 'FiltersSetupActivity()', 'SessionsActivity(0)', 'LanguageSelectActivity()')),
    "Vault and Media remain accessible": 'TelePremiumCloudAccessGate.openCloud' in drawer and 'TelePremiumMediaCenterActivity.class' in drawer,
    "modern Settings includes UI selector": 'TP_ITEM_MODE' in settings and 'new TelePremiumInterfaceModeActivity()' in settings,
    "mode selection recreates Activity": 'activity.recreate()' in picker,
    "same Android package": 'APP_PACKAGE=com.telepremium.messenger' in (overlay / 'gradle.properties').read_text(),
    "new version code supports in-place update": int(re.search(r'APP_VERSION_CODE=(\d+)', (overlay / 'gradle.properties').read_text()).group(1)) * 10 + 9 > 723769,
}
for label, okay in checks.items():
    print(("PASS" if okay else "FAIL") + " " + label)
if not all(checks.values()):
    raise SystemExit("Classic interface wiring validation failed")
print(f"Classic shell checks passed: {len(checks)}")
