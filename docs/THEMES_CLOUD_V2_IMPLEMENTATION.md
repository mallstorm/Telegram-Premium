# Themes V2 + Cloud V2 implementation

## Themes

Theme presets live in `org.telegram.messenger.telepremium.theme` and are translated into Telegram `.attheme` keys rather than painted as isolated custom screens. The ten IDs are:

`dark_graphite`, `silver_gray`, `soft_green`, `frost_morning`, `warm_beige`, `lavender_twilight`, `sea_turquoise`, `pink_sand`, `graphite_ice`, `sunset_peach`.

Every preset references a distinct `tp_theme_*.xml` VectorDrawable. `TelePremiumThemeController` writes/applies the corresponding theme, persists the preset ID, updates status/navigation bars and exposes the two-row Drawer quick selector. `TelePremiumAppearanceActivity` provides the complete theme-card/live-preview UI while retaining TelePremium UI scaling.

## Drawer integration

`apply_themes_cloud_v2.py` patches `DrawerProfileCell`. The control that formerly performed only day/night switching now displays `TelePremiumThemeIconRegistry.getCurrentThemeIcon()`. Tap opens `showQuickThemeSelector`; long press opens the full Appearance fragment. Dark/light system-bar behavior comes from `TelePremiumThemePreset.isDark`.

The V3 visual pass removes the fixed Telegram-blue profile header. `TelePremiumThemeArtwork` generates a lightweight per-theme header drawable (waves, leaves, frost/ice facets, dunes, sunset/twilight or graphite lines), while the body remains driven by `chats_menuBackground`, item/icon/text theme keys. No bitmap wallpaper bundle is required.

## Cloud service peer

`TelePremiumCloudPeerManager` owns one service channel per Telegram account and caches its `channel_id`/`access_hash`. The reserved title is `TelePremium Cloud Storage`; the creation description includes `TELEPREMIUM_CLOUD_STORAGE_V2`. `TelePremiumServicePeerRegistry` exposes the service-dialog identity and filtering helpers.

`TelegramCloudMetadataStore` uses the channel for `#TELEPREMIUM_CLOUD_V2` snapshots. `CloudPayloadRepository` sends documents to the service dialog. `CloudDownloadManager` resolves documents with `channels.getMessages`. `TelePremiumCloudManager` deletes orphan payload with `channels.deleteMessages` and otherwise keeps the existing virtual filesystem, trash, favorite, dedup, conflict and recovery model.

## Cloud visual pass

The Cloud root now uses `TelePremiumThemeArtwork.cloudBackground()` so every theme affects the Cloud atmosphere rather than only text colors. Search, account chips, folder/file cards, recent-files sheet, borders and FAB surfaces use the active `TelePremiumThemePreset` tokens instead of hard-coded blue/gray surfaces.

## Transfer strip and resume

`CloudTransferManager` persists one current transfer per Telegram account and publishes the current file, state, bytes, percentage and measured speed. `TelePremiumCloudActivity` displays this in the top transfer strip directly above search.

Uploads observe Telegram `fileUploadProgressChanged` and `fileUploadFailed`. A network failure becomes `WAITING_NETWORK`; the repository retries after connectivity returns. The persisted content URI allows an interrupted upload to be re-created after process restart if Telegram cannot resume the original upload operation in-place.

Downloads observe `fileLoadProgressChanged`. `FileLoader` keeps its partial file and the manager retries the same document on reconnect, providing real byte-range continuation where Telegram's loader supports it. Persisted download metadata lets Cloud reconstruct and resume a pending download after the screen/process is recreated.

## Migration

Only `LegacyCloudV1Store` and `CloudMigrationV1ToV2` are permitted to access Saved Messages for migration. The migrator recovers V1 generations, forwards unique payload IDs, persists an old→new ID map for restart safety, writes a V2 snapshot, runs integrity checks, and only then removes the exact V1 metadata/payload IDs from Saved Messages.

## UI hiding

`DialogsActivity` returns filtered dialog arrays and intercepts a service-peer open. `DialogsSearchAdapter` filters service channel objects, global message results and recent searches. Since the private service channel has only the account owner and TelePremium writes outgoing storage messages, it cannot create user-facing incoming notification traffic.

## Verification

`verify_themes_cloud_v2.py` validates all ten theme IDs and vector icons, Drawer quick-theme integration, V2 metadata/payload routing, migration states and service-peer filtering. `verify_visual_transfer_v3.py` additionally checks themed Drawer artwork, themed Cloud background, transfer progress state and upload/download reconnect hooks. GitHub Actions runs resource/Java compilation before the native release build.
