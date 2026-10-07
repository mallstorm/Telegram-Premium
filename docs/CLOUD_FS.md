# TelePremium Cloud FS

## Status

Cloud FS Pass 1 introduces an account-scoped persistent filesystem metadata layer backed by Telegram Saved Messages. It is integrated into TelePremium File Center and copied into the upstream Telegram tree by the existing Concept 5 patch pipeline.

## Invariants

- Telegram Cloud is the persistence source for committed Cloud FS metadata.
- Local application storage must never be the only copy of the hierarchy.
- Every Telegram account owns an independent Cloud FS manager.
- Folders are virtual nodes, not Telegram groups/channels.
- Every node has a stable UUID.
- Rename/move operations change metadata and never require re-uploading file bytes.
- Telegram server Premium state is unrelated to TelePremium Cloud.
- Technical Telegram IDs are not exposed in normal UI.

## Current implementation

Packages:
- `org.telegram.messenger.telepremium.cloud.CloudNode`
- `CloudSnapshot`
- `TelegramCloudMetadataStore`
- `TelePremiumCloudManager`
- `org.telegram.ui.TelePremiumCloudActivity`

Metadata records are stored in Saved Messages with the marker:

`#TELEPREMIUM_CLOUD_V1`

Bootstrap reads recent Saved Messages, validates schema and selects the highest generation. If no Cloud FS exists, a root filesystem is initialized automatically.

Current UI supports:
- automatic bootstrap;
- nested folders;
- persistent rename;
- favorite flag;
- trash state;
- account isolation;
- automatic recovery of hierarchy after clean install/login.

## Recovery model

The client never requires a local backup to rediscover the filesystem. After authentication it reads cloud metadata, selects the newest valid generation and rebuilds the in-memory projection.

Malformed metadata messages are ignored, so an older valid generation remains recoverable.

Future passes must retain multiple valid generations and may compact old generations only after a verified newer snapshot exists.

## File payload phase

File payload upload/download must use Telegram's existing FileLoader/SendMessagesHelper infrastructure. Do not introduce a second HTTP uploader.

A CloudNode file record must bind the logical node to the Telegram message/document identity. The logical display name remains independent from the original Telegram document filename.

Required follow-up implementation:
1. Android file picker and upload queue.
2. Telegram document binding.
3. Download/offline state.
4. SHA-256 deduplication.
5. restore/trash UI.
6. move/copy picker.
7. incremental journal and snapshot compaction.
8. integrity checker and deep recovery.
9. categories/search.
10. large-scale tests (10k/50k nodes).

## Acceptance tests for Pass 1

1. Create nested folders.
2. Rename a folder.
3. Kill/restart app: hierarchy remains.
4. Clear local app data, sign in again: hierarchy is recovered from Saved Messages.
5. Install on another device and sign in: hierarchy is recovered.
6. Verify Account 0 and Account 1 do not share managers or cloud metadata state.
7. Inject a malformed older metadata message: bootstrap still selects the newest valid generation.

## Security

No auth keys, session tokens, passcodes or credentials may be written to Cloud FS metadata.


## Pass 2 — payload foundation and filesystem operations

Implemented:
- file-node metadata for SHA-256, original name and offline state;
- streaming SHA-256 calculation;
- Android URI import staging without loading the whole file into memory;
- deduplication lookup by SHA-256;
- logical copy reusing an existing Telegram payload binding;
- move support through parentId mutation;
- global favorites projection;
- trash projection and restore primitive;
- transfer-state model;
- source verifier coverage for Cloud FS core.

The physical Telegram document-send binding is intentionally kept behind `CloudPayloadRepository`. It must use the upstream Telegram send/file infrastructure; no parallel HTTP transport is allowed.
