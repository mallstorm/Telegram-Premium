#!/usr/bin/env python3
"""Apply a SHA-checked overlay pass onto upstream/Telegram.

usage: python3 scripts/apply_overlay.py patches/tg13

The pass directory holds `overlay/` (final file contents, relative to upstream/Telegram)
and `manifest.json` (path, base_sha256 of the pristine upstream file or null for new
files, sha256 of the final file). A target that matches neither the base nor the final
hash stops the build: upstream drift is never overwritten silently.
"""
import hashlib
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "upstream" / "Telegram"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    if len(sys.argv) not in (2,3):
        print(__doc__)
        return 2
    global TARGET
    if len(sys.argv)==3: TARGET=Path(sys.argv[2]).resolve()
    pass_dir = (ROOT / sys.argv[1]).resolve()
    overlay = pass_dir / "overlay"
    manifest = json.loads((pass_dir / "manifest.json").read_text(encoding="utf-8"))
    applied = skipped = 0
    errors = []
    for entry in manifest["files"]:
        rel = entry["path"]
        source, target = overlay / rel, TARGET / rel
        if sha256(source) != entry["sha256"]:
            errors.append(f"overlay file does not match manifest: {rel} (run scripts/refresh_overlay_manifest.py {sys.argv[1]})")
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
    print(f"{manifest.get('name', sys.argv[1])}: {applied} applied, {skipped} already current, {len(manifest['files'])} total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
