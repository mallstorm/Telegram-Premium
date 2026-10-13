# Telegram-Premium

TelePremium extends Telegram Android with the Concept 5 product layer: multi-account tooling, Cloud V2, per-account network profiles, global appearance presets and additional privacy/security controls.

## Current product line

The active product architecture is **Cloud V2 + Themes V2**. The previous Saved Messages-backed cloud is legacy-only and is no longer used as the normal storage backend.

## Cloud V2

TelePremium Cloud is a virtual filesystem backed by a dedicated private Telegram service channel created per Telegram account.

- service peer: `TelePremium Cloud Storage`
- service marker: `TELEPREMIUM_CLOUD_STORAGE_V2`
- metadata snapshot prefix: `#TELEPREMIUM_CLOUD_V2`
- payload documents and Cloud metadata are stored in the service peer, not in Saved Messages
- the service dialog is filtered from normal TelePremium dialog/search flows
- folders are virtual filesystem nodes; move/rename/favorite/trash operations mutate Cloud metadata without re-uploading the payload
- SHA-256 deduplication allows multiple Cloud nodes to reference the same Telegram payload
- orphan payloads are removed only when no live Cloud node references them

### Migration from Cloud V1

Saved Messages is accessed only by the legacy migration layer. `LegacyCloudV1Store` and `CloudMigrationV1ToV2` recover V1 snapshots, copy unique payloads to the V2 service peer, persist the old-to-new message mapping, write and verify a V2 snapshot, and only then remove the exact legacy V1 metadata/payload messages.

This keeps user-owned Saved Messages separate from the active TelePremium Cloud backend.

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

Cloud access can be protected independently of the normal Telegram UI.

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

## Build pipeline

GitHub Actions applies the TelePremium patch set to the pinned Telegram upstream revision, validates source invariants, compiles Java/resources and then builds the arm64 release APK artifact.

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
