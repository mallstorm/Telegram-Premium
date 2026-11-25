#!/usr/bin/env python3
"""Print the sources compiled together with scripts/tests/*.java (pure Java, no Android).

Includes the test-only org.json subset from scripts/tests/stubs (the JDK has no org.json).
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = (Path(sys.argv[1]) if len(sys.argv)>1 else ROOT / "upstream/Telegram") / "TMessagesProj/src/main/java/org/telegram/messenger/telepremium"
SOURCES = [
    "aicenter/CenterData.java",
    "aicenter/CenterDocuments.java",
    "aicenter/CenterInputFiles.java",
    "aicenter/CenterExport.java",
    "aicenter/CenterStats.java",
    "aicenter/CenterLinks.java",
    "aicenter/CenterChartReport.java",
    "aicenter/CenterAuthorProfile.java",
    "aicenter/CenterMediaBundle.java",
    "aicenter/CenterMediaResults.java",
    "aicenter/CenterTopics.java",
    "aicenter/CenterTranscript.java",
    "aicenter/CenterTranslation.java",
    "aicenter/CenterEnvelope.java",
    "aicenter/CenterReports.java",
    "cloud/vault/TelePremiumVaultCrypto.java",
    "cloud/vault/VaultFileOps.java",
    "cloud/vault/VaultFolderLock.java",
    "cloud/vault/VaultSecretPolicy.java",
    "cloud/vault/VaultFolderSession.java",
    "cloud/vault/VaultLockTree.java",
    "cloud/vault/VaultVisibility.java",
    "cloud/vault/VaultKeyResolver.java",
    "cloud/vault/VaultAttemptLimiter.java",
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
