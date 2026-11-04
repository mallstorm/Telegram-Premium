#!/usr/bin/env python3
"""Print the sources compiled together with scripts/tests/*.java (pure Java, no Android).

Includes the test-only org.json subset from scripts/tests/stubs (the JDK has no org.json).
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "upstream/Telegram/TMessagesProj/src/main/java/org/telegram/messenger/telepremium"
SOURCES = [
    "cloud/vault/TelePremiumVaultCrypto.java",
    "cloud/vault/VaultFileOps.java",
    "cloud/CloudFileHasher.java",
    "cloud/CloudNode.java",
    "cloud/CloudSnapshot.java",
    "cloud/CloudConflictResolver.java",
    "cloud/CloudIntegrityChecker.java",
    "cloud/CloudRecoveryEngine.java",
    "cloud/TelePremiumCloudFormat.java",
    "media/TelePremiumPlayerMath.java",
]
stubs = sorted(str(p) for p in (ROOT / "scripts/tests/stubs").rglob("*.java"))
print(" ".join([str(BASE / s) for s in SOURCES] + stubs))
