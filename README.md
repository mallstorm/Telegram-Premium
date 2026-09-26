# TelePremium

TelePremium is a custom Android Telegram client maintained from a reproducible source baseline.

## Source layout

- `upstream/Telegram` — pinned official Telegram Android source.
- `scripts/apply_telepremium_patches.py` — applies TelePremium source changes.
- `scripts/verify_source.py` — verifies package ID, frozen updater, translation defaults and 10-account infrastructure.
- `docs/UPSTREAM.md` — explains the source baseline and the 11.3.4 binary lineage.
- `docs/PASS_HISTORY.md` — records Pass 1–4 changes.

## Bootstrap

```bash
git clone --recurse-submodules git@github.com:mallstorm/Telegram-Premium.git
cd Telegram-Premium
python3 scripts/apply_telepremium_patches.py
python3 scripts/verify_source.py
```

The upstream submodule is intentionally pinned. Do not update it casually: TelePremium patches are validated against that exact source revision.

## Current source target

- Application ID: `com.telepremium.messenger`
- Internal Java namespace remains `org.telegram.*`
- In-app APK updater: disabled
- Message translation button: enabled by default
- Whole-chat translation: Telegram's native `TranslateController`, Premium-gated by Telegram
- Account slots: 10 in Java and native tgnet
- Upstream source baseline: Telegram Android 11.4.2 (5469), commit `fb2e545101f41303f1e2712de2e7611a9335f1c3`

## Important version note

The working control APK produced in earlier passes is TelePremium 11.3.4. Telegram's public Android source repository did not publish a separate 11.3.4 source commit/tag; its public history jumps from 11.2.3 to 11.4.2. For that reason this repository uses the nearest later official source baseline (11.4.2) rather than pretending an exact 11.3.4 source tree exists.

## License

Telegram Android is GPLv2-or-later. TelePremium modifications are maintained under the same terms.
