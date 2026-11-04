#!/usr/bin/env python3
"""Verify the V11 offline pass and the arm64-v8a-only build contract."""
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TG = ROOT / "upstream" / "Telegram"
JAVA = TG / "TMessagesProj/src/main/java/org/telegram"


def read(rel: str) -> str:
    return (TG / rel).read_text(encoding="utf-8")


def main() -> int:
    errors = []
    manifest = json.loads((ROOT / "patches/v11-offline/manifest.json").read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        path = TG / entry["path"]
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            errors.append(f"not applied: {entry['path']}")

    # arm64-v8a only: no other ABI may appear in any abiFilters declaration.
    for rel in ("TMessagesProj/build.gradle", "TMessagesProj_App/build.gradle"):
        for line in read(rel).splitlines():
            if "abiFilters" in line and re.search(r"armeabi-v7a|\"x86\"|x86_64", line):
                errors.append(f"non-arm64 ABI in {rel}: {line.strip()}")
    if 'defaultConfig { ndk { abiFilters "arm64-v8a" } }' not in read("TMessagesProj/build.gradle"):
        errors.append("library module is not restricted to arm64-v8a")

    vault = JAVA / "messenger/telepremium/cloud/vault"
    for name in ("TelePremiumVaultCrypto", "TelePremiumVaultKeyManager", "TelePremiumVaultIndexStore",
                 "TelePremiumVaultPendingIndexStore", "TelePremiumVaultBackupStore", "VaultFileOps",
                 "VaultTransportStaging"):
        if not (vault / f"{name}.java").exists():
            errors.append(f"missing Vault class {name}")
    if 'path="tpvault_dec/"' not in read("TMessagesProj/src/main/res/xml/provider_paths.xml"):
        errors.append("Vault decrypted cache path is not exposed through FileProvider")
    if "add_library(cpufeatures" not in read("TMessagesProj/jni/CMakeLists.txt"):
        errors.append("CMake >= 3.31 cpufeatures shim missing")

    security = (JAVA / "messenger/telepremium/cloud/TelePremiumCloudSecurityStore.java").read_text(encoding="utf-8")
    for marker in ("PBKDF2WithHmacSHA256", "hashLegacy", "HASH_V2_PREFIX"):
        if marker not in security:
            errors.append(f"pattern hardening marker missing: {marker}")

    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1
    print(f"V11 offline overlay verified: {len(manifest['files'])} files, arm64-v8a only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
