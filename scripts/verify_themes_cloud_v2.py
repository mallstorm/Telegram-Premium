#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCH = ROOT / "patches" / "concept5"
UPSTREAM = ROOT / "upstream" / "Telegram"

THEMES = [
    "dark_graphite", "silver_gray", "soft_green", "frost_morning", "warm_beige",
    "lavender_twilight", "sea_turquoise", "pink_sand", "graphite_ice", "sunset_peach",
]
ICONS = [
    "tp_theme_dark_graphite.xml", "tp_theme_silver_gray.xml", "tp_theme_soft_green.xml",
    "tp_theme_frost_morning.xml", "tp_theme_warm_beige.xml", "tp_theme_lavender_twilight.xml",
    "tp_theme_sea_turquoise.xml", "tp_theme_pink_sand.xml", "tp_theme_graphite_ice.xml",
    "tp_theme_sunset_peach.xml",
]


def must(path, *needles):
    if not path.exists():
        raise SystemExit(f"missing: {path.relative_to(ROOT)}")
    text = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"{path.relative_to(ROOT)}: missing invariant {needle!r}")
    return text


registry = must(PATCH / "java/org/telegram/messenger/telepremium/theme/TelePremiumThemeRegistry.java")
for theme in THEMES:
    if theme not in registry:
        raise SystemExit(f"theme registry missing {theme}")
for icon in ICONS:
    if not (PATCH / "res/drawable" / icon).exists():
        raise SystemExit(f"missing vector icon {icon}")

must(PATCH / "java/org/telegram/messenger/telepremium/theme/TelePremiumThemeIconRegistry.java", "getCurrentThemeIcon", "getCurrentThemeContentDescription")
must(PATCH / "java/org/telegram/messenger/telepremium/theme/TelePremiumThemeController.java", "showQuickThemeSelector", "applyTheme", "ensureShellTheme")
must(PATCH / "java/org/telegram/ui/TelePremiumAppearanceActivity.java", "Оформление", "Привет! Как дела?", "Фон чатов", "Акцент сообщений", "Размер интерфейса")

payload = must(PATCH / "java/org/telegram/messenger/telepremium/cloud/CloudPayloadRepository.java", "sendToCloudPeer", "TelePremiumCloudPeerManager")
if "sendToSavedMessages" in payload or "getClientUserId()" in payload:
    raise SystemExit("CloudPayloadRepository still targets Saved Messages")
metadata = must(PATCH / "java/org/telegram/messenger/telepremium/cloud/TelegramCloudMetadataStore.java", "#TELEPREMIUM_CLOUD_V2", "TelePremiumCloudPeerManager")
if "TL_inputPeerSelf" in metadata:
    raise SystemExit("V2 metadata store still targets Saved Messages")

must(PATCH / "java/org/telegram/messenger/telepremium/cloud/TelePremiumCloudPeerManager.java", "TelePremium Cloud Storage", "TELEPREMIUM_CLOUD_STORAGE_V2")
must(PATCH / "java/org/telegram/messenger/telepremium/cloud/CloudMigrationV1ToV2.java", "FAILED_RETRYABLE", "SELF_CLEANUP", "cloud_backend_version")
must(PATCH / "java/org/telegram/messenger/telepremium/cloud/TelePremiumServicePeerRegistry.java", "filterDialogs", "isServicePeer")
must(PATCH / "java/org/telegram/messenger/telepremium/cloud/TelePremiumCloudAccessGate.java", "openCloud", "openCategory", "canExposeNode")

if UPSTREAM.exists():
    drawer = must(UPSTREAM / "TMessagesProj/src/main/java/org/telegram/ui/Cells/DrawerProfileCell.java", "showQuickThemeSelector", "getCurrentThemeIcon")
    click_start = drawer.find("darkThemeView.setOnClickListener")
    click_end = drawer.find("darkThemeView.setOnLongClickListener", click_start)
    if click_start >= 0 and click_end > click_start:
        click = drawer[click_start:click_end]
        if "lastDayTheme" in click or "lastDarkTheme" in click:
            raise SystemExit("Drawer still contains binary day/night click logic")
    must(UPSTREAM / "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java", "TelePremiumServicePeerRegistry.filterDialogs", "TelePremiumCloudAccessGate.openCloud")

print("Themes V2 / Cloud V2 invariants: OK")
