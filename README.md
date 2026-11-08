# Telegram-Premium

TelePremium extends Telegram Android with multi-account tooling, encrypted Cloud V3, per-account network profiles, themes and privacy controls.

## Encrypted cloud by default

The Telegram 13.1.1 product line uses **Vault V3 for every cloud account**, with no V2 switch and no encryption setup screen. A random 256-bit master key is created automatically and wrapped by AndroidKeyStore. Files, multipart files and folder catalogs are encrypted locally with authenticated AES-256-GCM before Telegram receives them.

Each Telegram account has its own private `TelePremium Vault` service channel (`TP_VAULT_V3`). Catalog documents use `#TPV3I`; ordinary encrypted payloads use `#TPV3F`. No filenames or folder JSON are sent as plaintext metadata. Existing V3 keys and password-protected recovery codes remain compatible.

Screen protection (pattern/biometrics) is optional and separate from encryption. Save a private recovery code from Cloud protection settings to open the same encrypted cloud after losing this device or on another device. New standalone secret codes need no password; adding password protection is optional and keeps the same master key. Previously exported standalone codes remain valid.

V1/V2 channels are neither accessed nor migrated by the active cloud flow. Their existing data is not modified. See [Vault V3 behavior and validation](docs/VAULT_V3_DEFAULT.md).

## Cloud transfers

Uploads and downloads expose persistent transfer state per Telegram account:

- file name, progress, transferred bytes and current speed
- upload/download progress from Telegram runtime notifications
- waiting-for-network state on connectivity loss
- automatic retry after reconnect
- persisted transfer metadata for process/screen recreation
- resumable partial downloads through Telegram `FileLoader` where supported
- upload restart from the persisted content URI when an in-place resume is unavailable

## Cloud security

The cloud is always encrypted. Optional screen access protection is independent of the normal Telegram UI.

- pattern lock with salted hashing
- optional biometric unlock
- auto-lock timeout
- whole-Cloud lock mode
- per-category protection for photos, video, documents, audio, archives, APK, favorites and trash
- temporary lockout after repeated failed pattern attempts
- session unlock state isolated from the visual theme system

## Themes V2

TelePremium includes ten global presets integrated through Telegram theme keys rather than isolated screen-only colors:

- `dark_graphite`
- `silver_gray`
- `soft_green`
- `frost_morning`
- `warm_beige`
- `lavender_twilight`
- `sea_turquoise`
- `pink_sand`
- `graphite_ice`
- `sunset_peach`

The Drawer day/night control is replaced by the current-theme pictogram and a compact quick selector. Long press opens the full Appearance screen. Drawer profile headers and Cloud use lightweight runtime artwork so the selected theme affects both color tokens and the surrounding visual atmosphere without large bitmap bundles.

## Telegram 13.1.1 port

Branch `port/tg-13.1.1`: TelePremium on Telegram Android 13.1.1 as one SHA-checked overlay (`patches/tg13`). See `docs/TG13_PORT.md`.

## Secure Vault and Player (V11 offline pass)

Secure Vault V3.5 Quiet Preview, Player V3.6 and the SDK 36 build environment are applied by `scripts/apply_offline_v11_overlay.py` from `patches/v11-offline`. See `docs/V11_OFFLINE_PASS.md`.

## Build pipeline

GitHub Actions applies the TelePremium patch set to the pinned Telegram upstream revision, validates source invariants, runs the Vault crypto self-test, compiles Java/resources and then builds the release APK for arm64-v8a only.

Key verification scripts include:

- `scripts/verify_themes_cloud_v2.py`
- `scripts/verify_visual_transfer_v3.py`
- `scripts/verify_theme_isolation_cloud_security_v4.py`
- `scripts/cloud_v2_verify_compat.py`

## Architecture notes

Detailed implementation notes are available in:

- `docs/THEMES_CLOUD_V2_IMPLEMENTATION.md`
- `docs/CONCEPT5_IMPLEMENTATION.md`
- `docs/PASS_HISTORY.md`

The `patches/concept5` tree contains the TelePremium product layer that is applied to the pinned upstream Telegram Android source during CI builds.
