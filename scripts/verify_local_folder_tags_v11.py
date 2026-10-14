#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def text(rel):
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing patched file: {rel}")
    return path.read_text(encoding="utf-8")


helper = text("TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumFolderTags.java")
for needle in (
    'getBoolean(KEY_ENABLED, true)',
    'COLOR_PREFIX + filterId',
    'putBoolean(KEY_ENABLED, enabled)',
):
    if needle not in helper:
        raise SystemExit(f"local folder tag helper invariant missing: {needle}")

editor = text("TMessagesProj/src/main/java/org/telegram/ui/FilterCreateActivity.java")
for needle in (
    'TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color)',
    'cell.setCloseAsLock(false)',
    'cell.setSelected(newFilterColor, false)',
    'TelePremiumFolderTags.setColor(currentAccount, filter.id, newFilterColor)',
    'if (fragment.getUserConfig().isPremium())',
):
    if needle not in editor:
        raise SystemExit(f"folder editor invariant missing: {needle}")
for forbidden in (
    'cell.setCloseAsLock(!getUserConfig().isPremium())',
    'PremiumFeatureBottomSheet(FilterCreateActivity.this, PremiumPreviewFragment.PREMIUM_FEATURE_FOLDER_TAGS',
    'folderTagsHeader.setPreviewColor(!getUserConfig().isPremium()',
):
    if forbidden in editor:
        raise SystemExit(f"Premium folder colour gate still present: {forbidden}")

settings = text("TMessagesProj/src/main/java/org/telegram/ui/FiltersSetupActivity.java")
for needle in (
    'final boolean enabled = !TelePremiumFolderTags.isEnabled(currentAccount)',
    'TelePremiumFolderTags.setEnabled(currentAccount, enabled)',
    'cell.setTextAndCheck(item.text, TelePremiumFolderTags.isEnabled(currentAccount), divider)',
    'cell.setCheckBoxIcon(0)',
):
    if needle not in settings:
        raise SystemExit(f"folder settings invariant missing: {needle}")
for forbidden in (
    'TLRPC.TL_messages_toggleDialogFilterTags req = new TLRPC.TL_messages_toggleDialogFilterTags()',
    'cell.setCheckBoxIcon(!getUserConfig().isPremium() ? R.drawable.permission_locked : 0)',
    'FolderShowTagsInfoPremium',
):
    if forbidden in settings:
        raise SystemExit(f"server/Premium folder tag gate still present: {forbidden}")

tags = text("TMessagesProj/src/main/java/org/telegram/ui/Components/DialogCellTags.java")
for needle in (
    'if (!TelePremiumFolderTags.isEnabled(currentAccount))',
    'final int localColor = TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color)',
):
    if needle not in tags:
        raise SystemExit(f"dialog tag renderer invariant missing: {needle}")
if 'controller.folderTags && account.getUserConfig().isPremium()' in tags:
    raise SystemExit("dialog tags are still gated by Telegram Premium")

print("TelePremium local folder tags V11 verification passed")
