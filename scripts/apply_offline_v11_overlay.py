#!/usr/bin/env python3
"""Apply the V11 offline-environment pass (Secure Vault V3.1-V3.5, Player V3.6, SDK 36 build).

The overlay stores final file contents. Each target must match either the expected
base SHA-256 (output of the earlier patch scripts) or the final SHA-256 (already
applied), so a drifting upstream or script change fails loudly instead of being
silently overwritten.
"""
import hashlib
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PASS_DIR = ROOT / "patches" / "v11-offline"
OVERLAY = PASS_DIR / "overlay"
TARGET = ROOT / "upstream" / "Telegram"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    manifest = json.loads((PASS_DIR / "manifest.json").read_text(encoding="utf-8"))
    applied = skipped = 0
    errors = []
    for entry in manifest["files"]:
        rel = entry["path"]
        source = OVERLAY / rel
        target = TARGET / rel
        if sha256(source) != entry["sha256"]:
            errors.append(f"overlay file does not match manifest: {rel} (run scripts/refresh_offline_v11_manifest.py)")
            continue
        if target.exists():
            current = sha256(target)
            if current == entry["sha256"]:
                skipped += 1
                continue
            if entry["base_sha256"] is None:
                errors.append(f"unexpected existing file: {rel}")
                continue
            if current != entry["base_sha256"]:
                errors.append(f"base mismatch: {rel} has {current}, expected {entry['base_sha256']}")
                continue
        elif entry["base_sha256"] is not None:
            errors.append(f"missing base file: {rel}")
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        applied += 1
    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1
    print(f"V11 offline overlay: {applied} applied, {skipped} already current, {len(manifest['files'])} total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
