#!/usr/bin/env python3
"""Refresh final SHA-256 values after editing files in patches/v11-offline/overlay.

Base hashes are kept: they describe the tree produced by the earlier patch scripts.
New overlay files are added with base_sha256 = null (file must not exist before the pass).
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PASS_DIR = ROOT / "patches" / "v11-offline"
OVERLAY = PASS_DIR / "overlay"

manifest_path = PASS_DIR / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
known = {entry["path"]: entry for entry in manifest["files"]}
files = []
for path in sorted(p for p in OVERLAY.rglob("*") if p.is_file()):
    rel = path.relative_to(OVERLAY).as_posix()
    entry = known.get(rel, {"path": rel, "base_sha256": None})
    entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    files.append(entry)
manifest["files"] = files
manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"manifest refreshed: {len(files)} files")
