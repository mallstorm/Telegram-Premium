#!/usr/bin/env python3
"""Print the TelePremium sources compiled together with scripts/tests/*.java (pure Java, no Android)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "upstream/Telegram/TMessagesProj/src/main/java/org/telegram/messenger/telepremium"
SOURCES = [
    "cloud/vault/TelePremiumVaultCrypto.java",
]
print(" ".join(str(BASE / s) for s in SOURCES))
