#!/usr/bin/env python3
"""Refresh final SHA-256 values after editing files in <pass>/overlay.

usage: python3 scripts/refresh_overlay_manifest.py patches/tg13

Base hashes are kept. A newly added overlay file gets base_sha256 = null unless the file
exists in upstream/Telegram (checked-out pristine submodule), in which case its current
hash there is recorded as the base.
"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
pass_dir = ROOT / sys.argv[1]
overlay = pass_dir / "overlay"
manifest_path = pass_dir / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
known = {entry["path"]: entry for entry in manifest["files"]}
files = []
for path in sorted(p for p in overlay.rglob("*") if p.is_file()):
    rel = path.relative_to(overlay).as_posix()
    entry = known.get(rel)
    if entry is None:
        upstream = ROOT / "upstream" / "Telegram" / rel
        entry = {"path": rel, "base_sha256": hashlib.sha256(upstream.read_bytes()).hexdigest() if upstream.exists() else None}
    entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    files.append(entry)
manifest["files"] = files
manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"manifest refreshed: {len(files)} files")
