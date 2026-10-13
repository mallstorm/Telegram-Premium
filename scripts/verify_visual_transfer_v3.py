#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram" / "TMessagesProj" / "src" / "main" / "java"

checks = {
    "org/telegram/ui/Cells/DrawerProfileCell.java": [
        "TelePremiumThemeArtwork.drawerHeader",
        "TelePremiumThemeIconRegistry.getCurrentThemeIcon",
    ],
    "org/telegram/ui/TelePremiumCloudActivity.java": [
        "TelePremiumThemeArtwork.cloudBackground",
        "createTransferStrip",
        "transferPercent",
        "CloudTransferManager.stateLabel",
        "resumePendingTransfer",
    ],
    "org/telegram/messenger/telepremium/cloud/CloudTransferManager.java": [
        "WAITING_NETWORK",
        "speedBytesPerSec",
        "beginUpload",
        "beginDownload",
        "retrying",
    ],
    "org/telegram/messenger/telepremium/cloud/CloudPayloadRepository.java": [
        "NotificationCenter.fileUploadProgressChanged",
        "NotificationCenter.fileUploadFailed",
        "scheduleRetry",
        "resumePending",
        "sendToCloudPeer",
    ],
    "org/telegram/messenger/telepremium/cloud/CloudDownloadManager.java": [
        "NotificationCenter.fileLoadProgressChanged",
        "scheduleRetry",
        "transferManager.updateProgress",
    ],
    "org/telegram/messenger/telepremium/theme/TelePremiumThemeResources.java": [
        "chats_menuTopBackground",
        "chats_menuTopBackgroundCats",
        "chats_menuPhoneCats",
        "windowBackgroundWhiteBlueButton",
    ],
}

errors = []
for rel, required in checks.items():
    path = SRC / rel
    if not path.exists():
        errors.append(f"missing {rel}")
        continue
    text = path.read_text(encoding="utf-8")
    for token in required:
        if token not in text:
            errors.append(f"{rel}: missing {token}")

if errors:
    print("Visual/transfer V3 verification failed:")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("Visual/transfer V3 verification passed")
