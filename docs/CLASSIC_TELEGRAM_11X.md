# TelePremium Classic Telegram 11.x / Modern Telegram 13.1.1

Branch: `feature/classic-telegram-11x`. The branch derives from `port/tg-13.1.1` and preserves the pinned Telegram 13.1.1 upstream and the SHA-checked `patches/tg13/overlay`.

## UI variants

- **Classic (default):** the blue chat list toolbar, swipe-out left account drawer, account list/unread badges and classic settings. Regular group forums open a separate, full-width topics list. Opening a topic from that list shows its conversation with a back route to the list. Groups set to **View as Messages** open the message view.
- **Modern:** the Telegram 13.1.1 five-item navigation, `SettingsActivity`, and the usual forum layouts, including tabs in groups that support them.
- Selection: `telepremium_ui / classic_ui_11x_enabled` in Android SharedPreferences, default `true`. The selector is available in both shells and recreates only the Activity to rebuild visible UI.
- Both modes use the Telegram 13.1.1 protocol, app package, Telegram accounts and storage, Vault V3, media, and proxy networks. Switching modes preserves the group's **View as Messages** setting.

## Source integration

- `MainTabsActivity.java` hosts `TelePremiumClassicDrawer.java`. The drawer button sits in the native dialog action bar with the existing search controls, and the classic dialog screen uses normal navigation bar insets without space for the hidden tabs.
- `DialogsActivity.java` routes regular group forums to the separate topic list in Classic mode. The group tab check in `ChatObject.java` also applies inside conversations reached through links and search. Both modes use the 13.1.1 topic list, message composer, unread state and drafts.
- `TelePremiumClassicSettingsActivity.java` provides the classic settings presentation, routing to the existing Telegram 13 settings activities rather than copying logic.
- `TelePremiumInterfaceModeActivity.java` controls the mode.
- Modern `SettingsActivity.java` retains access to the selector.

## Update and signing compatibility

The application ID is unchanged: `com.telepremium.messenger`.

The build retains the release keystore selection already present in `.github/workflows/build-telepremium.yml`; it must not generate or substitute a new key. The existing APK signing certificate SHA-256 recorded in the `build-output-tg13` artifact is `a08d7dc323ddf71ef3201944397e0d3cce7d40847263e11f328b68bbe19229ab`. The workflow rejects an APK whose signing certificate differs.

`APP_VERSION_CODE=72378` yields the arm64 release `versionCode=723789`, newer than `723769`. Package + signing certificate + increasing versionCode are necessary for an in-place update. Data should remain on device; nonetheless back up the Vault recovery code before testing.

## Validation

Run `python3 scripts/verify_classic_shell.py` and `python3 scripts/verify_tg13.py` after applying the overlay, followed by Java/resources compilation and a release APK build. On Android, open the same forum in both modes, open a topic and go back, check **View as Messages**, and open a topic from search or a link. Check search, multi-select, the drawer gesture, system bar insets, account switching and persistence after reopening the app.
