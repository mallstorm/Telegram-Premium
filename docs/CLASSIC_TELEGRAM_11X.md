# TelePremium Classic Telegram 11.x / Modern Telegram 13.1.1

Branch: `feature/classic-telegram-11x`. The branch derives from `port/tg-13.1.1` and preserves the pinned Telegram 13.1.1 upstream and the SHA-checked `patches/tg13/overlay`.

## UI variants

- **Classic (default):** the 11.x blue toolbar, swipe-out left account drawer, account list/unread badges, classic settings rows and entry points to the existing Telegram 13.1.1 screens.
- **Modern:** existing Telegram 13.1.1 five-tab interface and `SettingsActivity`.
- Selection: `telepremium_ui / classic_ui_11x_enabled` in Android SharedPreferences, default `true`. The selector is available in both shells and recreates only the Activity to rebuild visible UI.
- There is no Telegram protocol rollback. Both modes use the same app package, Telegram accounts and storage, Vault V3, media, proxy networks, and messages.

## Source integration

- `TelePremiumClassicDrawer.java` overlays the new `MainTabsActivity`; it does not reinstate the removed `DrawerLayoutContainer` of Telegram 11.
- `TelePremiumClassicSettingsActivity.java` provides the classic settings presentation, routing to the existing Telegram 13 settings activities rather than copying logic.
- `TelePremiumInterfaceModeActivity.java` controls the mode.
- Modern `SettingsActivity.java` retains access to the selector.
- The original five-tab UI code is retained unchanged for modern mode. In classic mode its **visual panel** is hidden, and the classic toolbar/drawer provide navigation.

## Update and signing compatibility

The application ID is unchanged: `com.telepremium.messenger`.

The build retains the release keystore selection already present in `.github/workflows/build-telepremium.yml`; it must not generate or substitute a new key. The existing APK signing certificate SHA-256 recorded in the `build-output-tg13` artifact is `a08d7dc323ddf71ef3201944397e0d3cce7d40847263e11f328b68bbe19229ab`. The workflow rejects an APK whose signing certificate differs.

`APP_VERSION_CODE=72378` yields the arm64 release `versionCode=723789`, newer than `723769`. Package + signing certificate + increasing versionCode are necessary for an in-place update. Data should remain on device; nonetheless back up the Vault recovery code before testing.

## Validation

Run `python3 scripts/verify_classic_shell.py` and `python3 scripts/verify_tg13.py` after applying the overlay, followed by full Java/resources and release APK build. The automated source check is not a substitute for an Android on-device test: verify screen insets, account switch, drawer gestures, cloud security, media, and mode switching.
