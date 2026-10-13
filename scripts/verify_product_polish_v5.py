#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def text(rel):
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing: {rel}")
    return path.read_text(encoding="utf-8")


chat = text("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java")
if chat.count("TelePremiumUiScale.chatInsetPx") < 4:
    raise SystemExit("chat width compensation is not installed")

cloud = text("TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCloudActivity.java")
if "Последние файлы" in cloud or "createRecentSheet" in cloud or "updateRecentSheet" in cloud:
    raise SystemExit("legacy Recent files sheet is still present")
if "TelePremiumPlayerActivity.canHandle" not in cloud:
    raise SystemExit("Cloud does not route media through TelePremiumPlayerActivity")

manifest = text("TMessagesProj/src/main/AndroidManifest.xml")
if "org.telegram.ui.TelePremiumPlayerActivity" not in manifest:
    raise SystemExit("TelePremiumPlayerActivity is not registered")
if 'android:mimeType="audio/*"' not in manifest or 'android:mimeType="video/*"' not in manifest:
    raise SystemExit("media VIEW intent filters are missing")

player = text("TMessagesProj/src/main/java/org/telegram/ui/TelePremiumPlayerActivity.java")
for needle in ("AspectSurfaceView", "cycleSpeed", "EXTRA_PATH"):
    if needle not in player:
        raise SystemExit(f"player invariant missing: {needle}")
# V5/V6 owned playback directly; V7 deliberately delegates it to a process-level
# controller so rotation, MediaSession and the Media Center share one player.
if not any(needle in player for needle in ("VideoPlayer", "MediaPlayer", "TelePremiumPlaybackController")):
    raise SystemExit("native player backend is missing")
if "seekRelative" not in player and "controller.seekRelative" not in player:
    raise SystemExit("player seek control is missing")

security = text("TMessagesProj/src/main/java/org/telegram/messenger/telepremium/cloud/TelePremiumCloudSecurityStore.java")
if "hasUnlockMethod" not in security or "hasBiometricCredential" not in security:
    raise SystemExit("biometric/pattern credential model is missing")

pattern = text("TMessagesProj/src/main/java/org/telegram/ui/TelePremiumPatternSetupActivity.java")
if "AndroidUtilities.dp(260), AndroidUtilities.dp(260)" not in pattern:
    raise SystemExit("compact pattern geometry is missing")

print("TelePremium product polish V5 verification passed")
