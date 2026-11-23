# Polygon Messenger — independent native Android application

**Application ID:** `go.poligon.messenger`  
**UI:** Android native, owned by Polygon. No source classes, assets or activities from Telegram Android are included.  
**Transport:** TDLib adapter (`io.github.tdlib-android:core:0.1.1`, Boost Software License 1.0).

This is the first native vertical slice, not a feature-complete replacement for TelePremium:
phone/code/password login, conversation list, text history and text send. Media, calls, 10-account support and encrypted Vault are not yet ported. Both native shell layouts and their shared theme state are now present, but some destination screens remain explicitly marked as not yet implemented. This separation prevents the old 335-file Telegram Android overlay from remaining the application's architecture.

## Security and signing
Generate **one** signing key and keep it outside Git. GitHub Actions reads:
- `POLYGON_API_ID` and `POLYGON_API_HASH`
- `POLYGON_RELEASE_KEYSTORE_B64` — base64 of PKCS12 keystore
- `POLYGON_RELEASE_STORE_PASSWORD` — key/store password

The release build is never signed by the old TelePremium keystore. Missing secrets permit only the debug build; no fake release artifact is published. A new package means a separate app and a new TDLib database. Direct migration of the old Android private app data is not automatic.

## Build
```bash
cd polygon
gradle :app:assembleDebug
```
The workflow installs the correct Gradle, JDK and Android SDK. Local installations need Android SDK 35 and Gradle 8.11.1.

## Architectural boundaries
```
go.poligon.messenger.MainActivity            # Polygon-native presentation
go.poligon.messenger.domain.Messenger        # transport-neutral app contract
go.poligon.messenger.transport.TdlibMessenger # TDLib implementation
```
Avoid importing `org.telegram.ui.*`, resource packages or activities into this project. Keep independent modules for accounts, secure vault, calls, media and update management.

## Two native Polygon shells

The `polygon/native-client` branch now provides both familiar modes without embedding Telegram Android screens:

- **Classic (11.x-style):** blue top bar, left account drawer, compact chat list and grouped settings; default mode.
- **Modern (13.x-style):** five-item bottom navigation, modern settings rows and chat list.
- **Shared appearance:** 13 palettes, persistent selection and a shell selector.
- **Shared messaging:** the same transport-neutral `Messenger` interface powers the dialogs and message composer in both shells.

Current parity is the shell *structure* and navigation. Exact legacy screen behavior, 10-account management, profile editing, contacts, calls, Vault, media, full chat settings and authentication migration remain outstanding and must not be presented as complete.

Source-level tests: `python3 -m unittest discover -s tests -p 'test_*.py'`.
