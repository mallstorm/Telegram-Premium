#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def read(rel):
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing: {rel}")
    return path.read_text(encoding="utf-8")


cloud = read("TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCloudActivity.java")
for needle in (
    "TelePremiumPlayerActivity.openCloud(",
    "Потоковое воспроизведение",
    "item.node.telegramMessageId",
):
    if needle not in cloud:
        raise SystemExit(f"Cloud streaming route missing: {needle}")
if "TelePremiumPlayerActivity.open(getParentActivity(), file" in cloud:
    raise SystemExit("Cloud media still requires full file before player launch")

player = read("TMessagesProj/src/main/java/org/telegram/ui/TelePremiumPlayerActivity.java")
if "android.media.MediaPlayer" in player:
    raise SystemExit("legacy platform MediaPlayer backend is still imported")
if "EXTRA_CLOUD_MESSAGE_ID" not in player:
    raise SystemExit("streaming player cloud launch contract is missing")

controller_path = "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/media/TelePremiumPlaybackController.java"
controller_file = SRC / controller_path
streaming = read(controller_path) if controller_file.exists() else player
for needle in (
    "FileStreamLoadOperation.prepareUri",
    "FileStreamLoadOperation.setPriorityForDocument",
    "new VideoPlayer(true, false)",
    "TL_channels_getMessages",
    "getBufferedPosition",
    "seekTo",
    "startFullDownloadFallback",
):
    if needle not in streaming:
        raise SystemExit(f"streaming backend invariant missing: {needle}")
if "getBufferedPercentage" not in streaming and "getBufferedPercentage" not in player:
    raise SystemExit("streaming buffer reporting is missing")

print("TelePremium Cloud streaming V6 verification passed")
