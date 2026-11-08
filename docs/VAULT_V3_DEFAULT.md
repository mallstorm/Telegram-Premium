Vault V3 is the only cloud backend on the Telegram 13.1.1 product line. Opening Cloud creates a random 256-bit installation master key if none exists; AndroidKeyStore wraps it before the first catalog or file is encrypted. Encryption never depends on enabling screen protection, choosing a password, or confirming a recovery-code dialog. Existing V3 master keys are reused, including installations whose old `enabled` flag was false. No V1/V2 migration or plaintext channel is entered by the normal bootstrap path.

Files and multipart files are encrypted before the document sender receives a path. The sender accepts only a staged ciphertext path, a random transport filename, an opaque caption and `application/octet-stream`. Folder names, original filenames, hashes, MIME types and references are inside an authenticated encrypted catalog. The transport format, HKDF purposes and AES-256-GCM parameters remain compatible with previous V3 ciphertext.

Screen protection remains optional. When enabled, an unlocked session is required to access the master key. First-time encrypted cloud use works on Android 6+ without enabling pattern or biometric authentication.

Recovery codes are deliberately not uploaded to Telegram. A new installation can export a standalone `TPVAULT-KEY-1` secret, with a checksum, by an explicit action in Cloud protection settings. Possession of this secret grants access to the encrypted cloud: save it privately outside Telegram. An optional password converts the exported representation into the existing `TPVAULT-RECOVERY-1` password-wrapped format without rotating the master key. Existing password-backed codes continue to work. Previously exported standalone codes are still valid. Without a saved recovery code or a working original device, losing the master key makes recovery impossible.

Restoring a matching key can repair a lost AndroidKeyStore wrapper. Restoring a different key may replace an unused, automatically provisioned key on a fresh device, but never replaces a key already used to encrypt data. Old installations with password-backed recovery are conservatively treated as used. Key fingerprints identify matching recovery material without persisting plaintext keys.

Catalog synchronization now has the following behavior:

- Discover an existing V3 channel in Telegram dialogs, including pinned dialogs and subsequent pages, before creating one. A failed discovery request cannot create a replacement channel. Remember peers by Telegram user ID rather than a reusable account slot.
- Page through history to find catalog documents beyond the latest 100 messages. An incomplete history scan or missing confirmed catalog remains an error, not a fresh empty cloud.
- Verify AES-GCM, snapshot structure, owner identity and cloud identity. An unreadable newer index keeps older verified data available with a warning. An unreadable historical index behind a valid latest catalog cannot disable that catalog.
- Evict and download a damaged transport cache once before reporting an index error. Keep verified remote catalogs in an encrypted local journal as well as locally created catalogs.
- Preserve a durable journal before sending. After a failed first save or a locally newer unacknowledged save, merge with verified remote state and publish automatically. A server generation below a confirmed checkpoint remains read-only until explicit recovery; this guard is not bypassed by retries.
- Associate completion, timeout, observers and staging-file cleanup with the individual upload. A completed upload's timer cannot fail another operation or invoke its callback twice.
- Scan verified local journal candidates during manual recovery. A damaged newest file cannot hide intact older copies. Write journals atomically and retain newer generations when pruning.
- Refresh the cloud after reconnection and on screen resume, preserving the selected category and search during automatic refresh.

The settings screen has no V3 activation flow and no V2 return action. V1/V2 data is left untouched in its original channels. Existing plaintext files are not claimed to have been migrated or encrypted.

Validation commands, after applying `patches/tg13` to the pinned upstream revision:

```sh
python3 scripts/verify_tg13.py
java scripts/CheckJavaSyntax.java patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram
python3 scripts/test_vault_keys.py
python3 scripts/test_vault_runtime.py
python3 scripts/test_vault_peers.py
javac -d /tmp/tp-tests $(python3 scripts/tests/sources.py) scripts/tests/*.java
```

The new host tests compile the production key manager, index store, cloud manager and peer manager against deterministic Android/Telegram test doubles. Real AES-GCM, PBKDF2, catalog JSON and conflict resolution are exercised; the test keystore is in memory. Android hardware-backed storage, UI interaction and a real Telegram round trip still require a device smoke test. The APK workflow performs the Android compilation and creates a separate preview output branch for pull requests, leaving normal release output separate.
