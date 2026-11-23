#!/usr/bin/env python3
"""Guard the optional Telegram-11 classic renderer on the Telegram-13.1.1 source."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
overlay = root / "patches/tg13/overlay"
java = overlay / "TMessagesProj/src/main/java"
def src(rel):
    return (java / rel).read_text(encoding="utf-8")

prefs = src("org/telegram/messenger/telepremium/TelePremiumUiPrefs.java")
chat_object = src("org/telegram/messenger/ChatObject.java")
main = src("org/telegram/ui/MainTabsActivity.java")
dialogs = src("org/telegram/ui/DialogsActivity.java")
settings = src("org/telegram/ui/SettingsActivity.java")
drawer = src("org/telegram/ui/TelePremiumClassicDrawer.java")
classic = src("org/telegram/ui/TelePremiumClassicSettingsActivity.java")
picker = src("org/telegram/ui/TelePremiumInterfaceModeActivity.java")
theme_resources = src("org/telegram/messenger/telepremium/theme/TelePremiumThemeResources.java")
theme_store = src("org/telegram/messenger/telepremium/theme/TelePremiumThemeStore.java")
theme_controller = src("org/telegram/messenger/telepremium/theme/TelePremiumThemeController.java")
shell = src("org/telegram/messenger/telepremium/TelePremiumShell.java")


checks = {
    "classic preference is default": 'getBoolean("classic_ui_11x_enabled", true)' in prefs,
    "classic preference can persist": 'setClassicUiEnabled(boolean enabled)' in prefs,
    "classic drawer is attached conditionally": 'if (TelePremiumUiPrefs.classicUiEnabled())' in main and 'new TelePremiumClassicDrawer(this, context)' in main,
    "classic hides modern tabs": 'tabsViewWrapper.setVisibility(View.GONE)' in main,
    "classic chat list omits the bottom tab inset": 'args.putBoolean("hasMainTabs", !classic)' in main,
    "classic drawer opens from the native dialog toolbar": 'dialogs.setClassicDrawerAction(this::openClassicDrawer)' in main
        and 'bar.setBackButtonDrawable(menu)' in dialogs and 'classicDrawerAction.run()' in dialogs,
    "drawer has a working back route": 'classicDrawer.isOpened()' in main,
    "forum tabs use the modern preference for regular group forums":
        'tabsEnabled && (!isForum(chat) || isMonoForum(chat) || !TelePremiumUiPrefs.classicUiEnabled())' in chat_object,
    "classic opens a standalone topics list and honors view-as-messages":
        re.search(r'boolean useTwoColumnTopics = !TelePremiumUiPrefs\.classicUiEnabled\(\).*?'
                  r'boolean needOpenChatActivity = dialog != null && dialog\.view_forum_as_messages;.*?'
                  r'else if \(!useTwoColumnTopics\) \{\s*'
                  r'if \(needOpenChatActivity\) \{[^}]*ChatActivity\(args\)[^}]*\}\s*'
                  r'else \{[^}]*TopicsFragment\(args\)', dialogs, re.DOTALL) is not None,
    "account switching retains Telegram 13 native path": 'LaunchActivity.instance.switchToAccount(account, true)' in drawer,
    "up to ten accounts by native account count": 'UserConfig.MAX_ACCOUNT_COUNT' in drawer,
    "core settings route to native screens": all(v in classic for v in ('PrivacySettingsActivity()', 'NotificationsSettingsActivity()', 'DataSettingsActivity()', 'FiltersSetupActivity()', 'SessionsActivity(0)', 'LanguageSelectActivity()')),
    "Vault and Media remain accessible": 'TelePremiumCloudAccessGate.openCloud' in drawer and 'TelePremiumMediaCenterActivity.class' in drawer,
    "modern Settings includes UI selector": 'TP_ITEM_MODE' in settings and 'new TelePremiumInterfaceModeActivity()' in settings,
    "mode selection goes through the shell core": 'TelePremiumShell.switchTo(' in picker and 'activity::recreate' in shell
        and 'TelePremiumUiPrefs.setClassicUiEnabled(classic)' in shell,
    "same theme after classic/modern switch": "TelePremiumThemeController.ensureShellTheme(activity)" in shell,
    "shell preference is written synchronously": '.commit()' in prefs and 'classic_ui_11x_enabled' in prefs,
    "drawer's background is native theme": "Theme.key_chats_menuBackground" in drawer and
        "panel.setBackgroundColor(Color.WHITE)" not in drawer and "HEADER_COLOR" not in drawer,
    "classic settings follow native Telegram theme": "Theme.key_windowBackgroundWhite" in classic
        and "private static final int BLUE" not in classic and "private static final int INK" not in classic,
    "classic settings are built from stock themed cells": all(v in classic for v in ("TextCell", "HeaderCell", "ShadowSectionCell", "RecyclerListView")),
    "no undefined old classic palette": not re.search(r"\b(?:GRAY|INK|BLUE)\b", classic),
    "permanent classic hamburger": "FragmentFloatingButton.setAnimatedVisibility(actionBar.getBackButton(), 1f)" in dialogs
        and "setClassicDrawerButton(actionBar)" in dialogs,
    "classic header height matches 11.x reference (148dp + status bar)": "AndroidUtilities.dp(148) + statusBarPadding" in drawer,
    "legacy emoji status uses real Telegram picker": "R.string.SetEmojiStatus" in drawer
        and "chats.showSelectStatusDialog()" in drawer,
    "drawer rows use localized Telegram strings": all(v in drawer for v in ("R.string.NewGroup", "R.string.Contacts", "R.string.Calls", "R.string.SavedMessages", "R.string.InviteFriends", "R.string.TelegramFeatures")),
    "drawer is a recycler list with accounts toggle": "RecyclerListView" in drawer and "accountsShown" in drawer,
    "drawer follows the finger and settles by velocity": all(v in drawer for v in ("VelocityTracker", "beginEdgeDrag", "animateTo(")) and "classicDrawer.beginEdgeDrag(" in main
        and "classicDrawer.handleHostTouch(" in main,
    "hamburger morphs with drawer progress": "setClassicMenuProgress" in dialogs and "setProgressListener" in main,
    "drawer refreshes on theme change": "NotificationCenter.didSetNewTheme" in drawer,
    "system bars are driven by Telegram, not by raw window writes": "needCheckSystemBarColors" in theme_controller
        and "setStatusBarColor" not in theme_controller and "setNavigationBarColor" not in theme_controller,
    "classic standalone search preserves native search engine": "classicHome()" in dialogs
        and "searchItem.setVisibility(classicHome() ? View.VISIBLE : View.GONE)" in dialogs,
    "classic search pill is hidden": "fragmentSearchField.setVisibility(View.GONE)" in dialogs,
    "classic home reserves no search-pill slot (no scroll drift)": "private int searchSlot() {\n        return classicHome() ? 0 : dp(SEARCH_FIELD_HEIGHT);\n    }" in dialogs
        and "value = Math.min(value, -dp(SEARCH_FIELD_HEIGHT))" not in dialogs,
    "classic story strip omitted from reference home": "dialogStoriesCell.setVisibility(View.GONE)" in dialogs
        and "hasStories = false;" in dialogs,

    "collapsed stories do not cover hamburger": "storyStripAlpha" in dialogs
        and "classicDrawerAction != null ? dp(68)" in dialogs,
    "drawer protects navigation bar area": "setSystemInsets(int left, int right, int bottom)" in drawer
        and "classicDrawer.setSystemInsets(" in main,
    "all presets feed one Telegram native theme generator": "buildAttheme" in theme_resources
        and 'c.put("chats_menuBackground", p.drawerBackground)' in theme_resources,
    "theming selection kept separate from UI mode": "TelePremiumThemeStore" in theme_store,

    "same Android package": 'APP_PACKAGE=com.telepremium.messenger' in (overlay / 'gradle.properties').read_text(),
    "new version code supports in-place update": int(re.search(r'APP_VERSION_CODE=(\d+)', (overlay / 'gradle.properties').read_text()).group(1)) * 10 + 9 > 723769,
}
for label, okay in checks.items():
    print(("PASS" if okay else "FAIL") + " " + label)
if not all(checks.values()):
    raise SystemExit("Classic interface wiring validation failed")
print(f"Classic shell checks passed: {len(checks)}")
