#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def text(rel):
    p = SRC / rel
    if not p.exists():
        raise SystemExit(f"missing: {rel}")
    return p.read_text(encoding="utf-8")


base = "TMessagesProj/src/main/java/"
required = [
    "org/telegram/messenger/telepremium/media/TelePremiumMediaItem.java",
    "org/telegram/messenger/telepremium/media/TelePremiumQueueManager.java",
    "org/telegram/messenger/telepremium/media/TelePremiumPlaylistRepository.java",
    "org/telegram/messenger/telepremium/media/TelePremiumMediaStoreRepository.java",
    "org/telegram/messenger/telepremium/media/TelePremiumNetworkPlaylistParser.java",
    "org/telegram/messenger/telepremium/media/TelePremiumCloudMediaRepository.java",
    "org/telegram/messenger/telepremium/media/TelePremiumPlaybackController.java",
    "org/telegram/messenger/telepremium/media/TelePremiumMediaSessionService.java",
    "org/telegram/messenger/telepremium/media/TelePremiumArtworkLoader.java",
    "org/telegram/messenger/telepremium/theme/TelePremiumMediaTheme.java",
    "org/telegram/ui/TelePremiumMediaCenterActivity.java",
    "org/telegram/ui/TelePremiumPlaylistActivity.java",
    "org/telegram/ui/TelePremiumPlayerActivity.java",
]
for rel in required:
    text(base + rel)

item = text(base + required[0])
for needle in ("SOURCE_DEVICE", "SOURCE_CLOUD", "SOURCE_NETWORK", "TYPE_AUDIO", "TYPE_VIDEO", "stableId(", "nameUUIDFromBytes"):
    if needle not in item:
        raise SystemExit(f"unified media item missing {needle}")

queue = text(base + required[1])
for needle in ("insertNext", "move(int from, int to)", "REPEAT_ALL", "setShuffle"):
    if needle not in queue:
        raise SystemExit(f"queue invariant missing {needle}")

local = text(base + required[3])
if "MediaStore.Audio.Media" not in local or "MediaStore.Video.Media" not in local:
    raise SystemExit("MediaStore provider is incomplete")

network = text(base + required[4])
for needle in ("parseM3u", "parsePls", "parseXspf", ".m3u8", ".mpd", "looksLikeHlsManifest", "#EXT-X-STREAM-INF"):
    if needle not in network:
        raise SystemExit(f"network media invariant missing {needle}")

cloud = text(base + required[5])
if "isSupportedMedia" not in cloud:
    raise SystemExit("Cloud media projection does not filter non-media files")

playback = text(base + required[6])
for needle in ("FileStreamLoadOperation.prepareUri", "TelePremiumQueueManager", "STATE_BUFFERING", "setSubtitlesEnabled", "typeFor", "telepremium_media_resume_v1", "setSurfaceView"):
    if needle not in playback:
        raise SystemExit(f"playback invariant missing {needle}")

service = text(base + required[7])
for needle in (
    "MediaSession",
    "AUDIOFOCUS",
    "ACTION_AUDIO_BECOMING_NOISY",
    "startForeground",
    "START_NOT_STICKY",
    ".setOngoing(false)",
    ".setDeleteIntent(close)",
    "shutdownPlayback()",
    "stopForeground(false)",
    "stopForeground(true)",
):
    if needle not in service:
        raise SystemExit(f"MediaSession lifecycle invariant missing {needle}")
if "START_STICKY" in service:
    raise SystemExit("MediaSession service must never be sticky; ghost notifications may return")
if "AUDIOFOCUS_GAIN &&" in service or "controller.play();\n    };" in service:
    raise SystemExit("MediaSession service must not auto-resume playback on audio focus gain")

artwork = text(base + required[8])
for needle in ("loadThumbnail", "MediaMetadataRetriever", "LruCache", "Executors.newFixedThreadPool"):
    if needle not in artwork:
        raise SystemExit(f"artwork loader invariant missing {needle}")

media_theme = text(base + required[9])
for needle in (
    "TelePremiumThemeResources.resolveAccent",
    "TelePremiumThemeController.ensureShellTheme",
    "cardBackground",
    "miniPlayerBackground",
    "playerOverlay",
):
    if needle not in media_theme:
        raise SystemExit(f"media theme adapter invariant missing {needle}")

center = text(base + required[10])
for needle in (
    "TelePremiumMediaStoreRepository",
    "TelePremiumCloudMediaRepository",
    "TelePremiumNetworkPlaylistParser",
    "saveQueueAsPlaylist",
    "RecyclerView.LayoutParams",
    "ViewGroup.LayoutParams.MATCH_PARENT",
    "TelePremiumArtworkLoader.load",
    "showItemMenu",
    "READ_MEDIA_AUDIO",
    "READ_MEDIA_VIDEO",
    "getAdapterPosition()",
    "TelePremiumMediaTheme.from(this)",
):
    if needle not in center:
        raise SystemExit(f"Media Center V10 invariant missing {needle}")
if "getBindingAdapterPosition()" in center:
    raise SystemExit("Media Center uses RecyclerView API unavailable in pinned Telegram")
for hardcoded in ("0xff050508", "0xff111119", "0xff8a4fff"):
    if hardcoded in center:
        raise SystemExit(f"Media Center still owns hard-coded legacy shell color {hardcoded}")

playlist_ui = text(base + required[11])
for needle in ("TelePremiumMediaTheme.from(this)", "mediaTheme.cardBackground", "mediaTheme.applySystemBars"):
    if needle not in playlist_ui:
        raise SystemExit(f"Playlist shell theme invariant missing {needle}")

player = text(base + required[12])
for needle in (
    "PictureInPictureParams",
    "onDoubleTap",
    "screenBrightness",
    "showQueue",
    "AspectSurfaceView",
    "showPlayerSettings",
    "settingsRow",
    "SCALE_CROP",
    "SCALE_STRETCH",
    "SCALE_ORIGINAL",
    "seekStepSeconds",
    "controlsLocked",
    "gestureBrightness",
    "gestureVolume",
    "SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION",
    "LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES",
    "onWindowFocusChanged(boolean hasFocus)",
    "TelePremiumMediaTheme.from(this)",
    'speedButton = transparentButton("1×", 14)',
    'hwButton = transparentButton("HW", 13)',
):
    if needle not in player:
        raise SystemExit(f"player V10 invariant missing {needle}")
for forbidden in (
    "centerChrome",
    'roundButton("↶\\n10 сек.",',
    'roundButton("↷\\n10 сек.",',
    'roundButton("▶", 36)',
    "surface.setBackgroundColor",
    "setDecorFitsSystemWindows(false)",
):
    if forbidden in player:
        raise SystemExit(f"player V10 regression survived: {forbidden}")
if player.count('transparentButton("1×", 14)') != 1:
    raise SystemExit("player speed control must exist exactly once in the lower transport bar")
for hardcoded in ("0xff050508", "0xff111119", "0xff8a4fff"):
    if hardcoded in player:
        raise SystemExit(f"Player still owns hard-coded legacy shell color {hardcoded}")

manifest = text("TMessagesProj/src/main/AndroidManifest.xml")
for needle in (
    "TelePremiumMediaCenterActivity",
    "TelePremiumPlaylistActivity",
    "TelePremiumMediaSessionService",
    'android:foregroundServiceType="mediaPlayback"',
    'android:supportsPictureInPicture="true"',
):
    if needle not in manifest:
        raise SystemExit(f"manifest invariant missing {needle}")

drawer = text("TMessagesProj/src/main/java/org/telegram/ui/Adapters/DrawerLayoutAdapter.java")
launch = text("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java")
if 'new Item(19, "Медиа"' not in drawer or "TelePremiumMediaCenterActivity.class" not in launch:
    raise SystemExit("Media Center drawer integration missing")

print("TelePremium Media Center / Player V10 verification passed")
