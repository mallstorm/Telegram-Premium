# TelePremium on Telegram Android 13.1.1

Branch `port/tg-13.1.1`. Upstream `DrKLO/Telegram` is pinned to `3de37c8` (13.1.1, build 7237).

## Structure

The whole TelePremium layer lives in **one SHA-checked overlay**: `patches/tg13/overlay/`
(final contents of every changed or added file under `upstream/Telegram`) plus
`patches/tg13/manifest.json` (pristine base hash and final hash per file).

```bash
git submodule update --init upstream/Telegram
python3 scripts/apply_overlay.py patches/tg13   # idempotent; stops on upstream drift
python3 scripts/verify_tg13.py                  # package, versionCode, arm64-only, 10 accounts, entry points
```

After editing files in the overlay: `python3 scripts/refresh_overlay_manifest.py patches/tg13`.
The old string-replacement scripts (`scripts/apply_*_v*.py`) are kept only as history of the 11.4.2 line.

Updating upstream: move the submodule, run `apply_overlay.py`; every file reported as
"base mismatch" is a file Telegram changed and must be merged by hand, then the manifest refreshed.

## What changed against the 11.4.2 line

- Telegram 13 has no side drawer. TelePremium Center, Cloud, Media and Appearance are entries
  in the new Settings screen (`SettingsActivity`, ids 9001–9004).
- Conflicts re-applied on the 13.x code: QR login token update, sponsored messages switch,
  local emoji status and name/profile colours (local only without Telegram Premium; Premium
  users and collectibles go to the server), folder tag colours, per-account proxy profiles
  (`ProxySettings` API), premium router, privacy screens, chat inset scaling, updater.
- API migrations: media3 player, `TL_account.getPassword`, `onBackPressed(boolean)`,
  `prepareSendingDocument`, `TL_update.*`, `TL_emojiStatus.until`.
- Build: SDK 36, NDK 27.2, CMake 3.22.1 (upstream defaults), arm64-v8a only,
  versionCode 723709 (installs over the 11.4.2 TelePremium build 547099). minSdk is 23 (Android 6).

## Improvements

Security
- Escalating pattern lockout: every 5 wrong patterns → 30 s, 5 min, 15 min, then 1 h.
- PBKDF2 pattern checks run off the UI thread.
- Vault recovery: passphrase check, copy/share of the recovery code, Vault activation is blocked
  until the user confirms the code is saved.
- Optional private signing key via `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` secrets.

Features
- Service screen (TelePremium Center → Service): own update channel, diagnostics report with logs,
  log toggle, trash auto-clean (0/7/30/90 days, default 30).
- Update channel: public GitHub repo (`owner/repo`, latest release with APK +
  `telepremium-update.json`) or a direct HTTPS manifest URL. Before installing, the APK must match
  the manifest SHA-256 and be signed by the same certificate as the running app.
- Cloud: upload several files or a whole folder (queue, deduplicated by SHA-256), early check of
  the Telegram size limit (2000 MB, 4000 MB with Premium).

Code
- All 536 Russian strings of the 21 TelePremium screens are resources (`values` English,
  `values-ru` Russian).
- Pure logic extracted from the monolithic screens: `TelePremiumPlayerMath`,
  `TelePremiumAspectSurfaceView`, `TelePremiumCloudFormat`, `TelePremiumCloudFolderScanner`.
- Bug fix: `CloudIntegrityChecker` counted the real `root` node as a duplicate, so every stored
  snapshot failed integrity and generation recovery was never used.

Tests (plain JVM, run in CI before the Gradle build; `scripts/tests`)
- `VaultCryptoSelfTest` (65), `VaultFileOpsTest` (9), `CloudCoreTest` (17), `PlayerAndFormatTest` (31).

CI
- Gradle and ccache caches; a tag `v*` publishes a GitHub release with the APK, SHA-256 and
  `telepremium-update.json`.
- Status, compiler errors and the APK (45 MB parts) are also pushed to `build-output-tg13`.

## Not done / limits

- Splitting files larger than the Telegram limit into parts is not implemented (needs a
  multi-part Cloud node format); oversize uploads are rejected early with a clear message.
- Folder upload is a manual one-tap sync, not a background watcher.
- Strings inside `messenger/telepremium` (service messages, category names stored in data) are
  still Russian.
