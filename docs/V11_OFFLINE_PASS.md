# V11 offline pass — Secure Vault V3.5, Player V3.6, hardening

Changes made in the autonomous build environment after the V11 repository state are
brought back into the repository as `patches/v11-offline`.

## How it is applied

`patches/v11-offline/overlay/` holds final contents of 39 files under `upstream/Telegram`.
`scripts/apply_offline_v11_overlay.py` runs after `verify_local_folder_tags_v11.py` and
checks every target against `manifest.json`:

- target already has the final SHA-256 → skipped (idempotent);
- target has the expected base SHA-256 (output of the earlier passes) → replaced;
- anything else → the build fails, so upstream or script drift is never overwritten silently.

After editing overlay files run `python3 scripts/refresh_offline_v11_manifest.py`.
`scripts/verify_offline_v11_overlay.py` checks the result and the arm64-v8a-only contract.

## Content

- Secure Vault V3.1 → V3.5 Quiet Preview: `telepremium/cloud/vault/*` (AES-256-GCM chunked
  envelope, HKDF subkeys, AndroidKeyStore-wrapped master key, encrypted index, pending index,
  backup store, transport staging), Cloud manager / transfer / payload integration,
  `tpvault_dec/` FileProvider path for authenticated temporary decrypted files.
- Player V3.6 final UI: `TelePremiumPlayerActivity`, playback controller, media item and repository.
- Build: compileSdk / build-tools 36, CMake 3.31.6 with a `cpufeatures` shim replacing the removed
  `AndroidNdkModules`, NDK r21e, arm64-v8a only, `APP_VERSION_CODE=54709`.
- `PremiumPreviewFragment`: explicit `Browser.Progress` cast for the SDK 36 overload.

## Improvements made during the transfer

- Cloud pattern lock: PBKDF2 (HMAC-SHA256 on API 26+, HMAC-SHA1 below, 60 000 iterations) instead
  of a single SHA-256 pass. Old hashes are verified once and upgraded transparently on the first
  successful unlock; no user action and no reset of the pattern.
- Silent `catch {}` blocks in Cloud migration, metadata snapshots, playlists, artwork, theme and
  player code now report through `FileLog.e` (visible with logs enabled). Best-effort one-line
  cleanup calls (`release()`, `stopForeground()`, etc.) are left untouched.
- `scripts/tests/VaultCryptoSelfTest.java`: 65 checks — round trips around chunk boundaries for
  every purpose, wrong key/purpose, header/chunk/tag bit flips, truncation, trailing data, chunk
  reordering. Runs in CI before the Gradle build.

## CI

`build-telepremium.yml` installs SDK 36, NDK r21e and CMake 3.31.6 explicitly, applies all passes,
runs the Vault self-test, builds `assembleAfatRelease`, fails if the APK contains any ABI other
than `arm64-v8a`, and publishes status, error excerpts and the APK (split into 45 MB parts with
SHA-256) to the `build-output` branch in addition to the Actions artifact.

Signing: without secrets the upstream `release.keystore` is used, which keeps update compatibility
with earlier CI builds. Setting `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` switches to a private key (requires one reinstall).
