#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"
PATCH = ROOT / "patches" / "concept5"


def read(rel):
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing upstream file: {rel}")
    return path, path.read_text(encoding="utf-8")


def write(path, data):
    path.write_text(data, encoding="utf-8")


def replace_once(rel, old, new):
    path, data = read(rel)
    if new in data and old not in data:
        print(f"already patched: {rel}")
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one match, found {count}: {old[:90]!r}")
    write(path, data.replace(old, new, 1))
    print(f"patched: {rel}")


def replace_between(rel, start, end, replacement):
    path, data = read(rel)
    if replacement in data:
        print(f"already patched: {rel}")
        return
    a = data.find(start)
    if a < 0:
        raise SystemExit(f"{rel}: start marker not found")
    b = data.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"{rel}: end marker not found")
    write(path, data[:a] + replacement + data[b:])
    print(f"patched: {rel}")


def ensure_import(rel, anchor, import_line):
    path, data = read(rel)
    if import_line in data:
        return
    if anchor not in data:
        raise SystemExit(f"{rel}: import anchor not found: {anchor.strip()}")
    write(path, data.replace(anchor, anchor + import_line, 1))
    print(f"imported local folder tags: {rel}")


if not SRC.exists():
    raise SystemExit("upstream/Telegram is missing; initialize submodules first")

# Install the small local state helper into the upstream source tree.
helper_src = PATCH / "java/org/telegram/messenger/telepremium/TelePremiumFolderTags.java"
helper_dst = SRC / "TMessagesProj/src/main/java/org/telegram/messenger/telepremium/TelePremiumFolderTags.java"
if not helper_src.exists():
    raise SystemExit(f"missing TelePremium helper: {helper_src}")
helper_dst.parent.mkdir(parents=True, exist_ok=True)
helper_dst.write_text(helper_src.read_text(encoding="utf-8"), encoding="utf-8")
print(f"installed: {helper_dst.relative_to(SRC)}")

FILTER_CREATE = "TMessagesProj/src/main/java/org/telegram/ui/FilterCreateActivity.java"
FILTERS_SETUP = "TMessagesProj/src/main/java/org/telegram/ui/FiltersSetupActivity.java"
DIALOG_TAGS = "TMessagesProj/src/main/java/org/telegram/ui/Components/DialogCellTags.java"

ensure_import(
    FILTER_CREATE,
    "import org.telegram.messenger.R;\n",
    "import org.telegram.messenger.telepremium.TelePremiumFolderTags;\n",
)
ensure_import(
    FILTERS_SETUP,
    "import org.telegram.messenger.R;\n",
    "import org.telegram.messenger.telepremium.TelePremiumFolderTags;\n",
)
ensure_import(
    DIALOG_TAGS,
    "import org.telegram.messenger.MessagesController;\n",
    "import org.telegram.messenger.telepremium.TelePremiumFolderTags;\n",
)

# Folder editor: expose colour controls to every TelePremium account and load
# the local colour override instead of the Premium lock state.
replace_once(
    FILTER_CREATE,
    "        newFilterColor = filter.color;",
    "        newFilterColor = TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color);",
)
replace_once(
    FILTER_CREATE,
    "        if (getMessagesController().folderTags || !getUserConfig().isPremium()) {",
    "        if (TelePremiumFolderTags.isEnabled(currentAccount)) {",
)
replace_once(
    FILTER_CREATE,
    "                    folderTagsHeader.setPreviewColor(!getUserConfig().isPremium() ? -1 : newFilterColor, false);",
    "                    folderTagsHeader.setPreviewColor(newFilterColor, false);",
)
replace_once(
    FILTER_CREATE,
    "                    cell.setCloseAsLock(!getUserConfig().isPremium());",
    "                    cell.setCloseAsLock(false);",
)
replace_once(
    FILTER_CREATE,
    "                    cell.setSelected(!getUserConfig().isPremium() ? -1 : newFilterColor, false);",
    "                    cell.setSelected(newFilterColor, false);",
)
replace_once(
    FILTER_CREATE,
    "                    cell.setOnColorClick(color -> {\n"
    "                        if (!getUserConfig().isPremium()) {\n"
    "                            showDialog(new PremiumFeatureBottomSheet(FilterCreateActivity.this, PremiumPreviewFragment.PREMIUM_FEATURE_FOLDER_TAGS, true));\n"
    "                            return;\n"
    "                        }\n"
    "                        cell.setSelected(newFilterColor = color, true);",
    "                    cell.setOnColorClick(color -> {\n"
    "                        cell.setSelected(newFilterColor = color, true);",
)
replace_once(
    FILTER_CREATE,
    "                            folderTagsHeader.setPreviewColor(!getUserConfig().isPremium() ? -1 : newFilterColor, true);",
    "                            folderTagsHeader.setPreviewColor(newFilterColor, true);",
)
replace_once(
    FILTER_CREATE,
    "    private void save(boolean progress, Runnable after) {\n"
    "        saveFilterToServer(filter, newFilterFlags, newFilterName, newFilterColor,",
    "    private void save(boolean progress, Runnable after) {\n"
    "        TelePremiumFolderTags.setColor(currentAccount, filter.id, newFilterColor);\n"
    "        saveFilterToServer(filter, newFilterFlags, newFilterName, newFilterColor,",
)

# A free Telegram account must not send the Premium-only colour bit to the
# server. Other folder edits still sync normally; TelePremium keeps the colour
# locally and renders it from SharedPreferences.
replace_once(
    FILTER_CREATE,
    "        if (newFilterColor < 0) {\n"
    "            req.filter.flags &=~ 134217728;\n"
    "            req.filter.color = 0;\n"
    "        } else {\n"
    "            req.filter.flags |= 134217728;\n"
    "            req.filter.color = newFilterColor;\n"
    "        }",
    "        if (fragment.getUserConfig().isPremium()) {\n"
    "            if (newFilterColor < 0) {\n"
    "                req.filter.flags &=~ 134217728;\n"
    "                req.filter.color = 0;\n"
    "            } else {\n"
    "                req.filter.flags |= 134217728;\n"
    "                req.filter.color = newFilterColor;\n"
    "            }\n"
    "        } else {\n"
    "            req.filter.flags &=~ 134217728;\n"
    "            req.filter.color = 0;\n"
    "        }",
)

# Folder settings: local switch, no Premium upsell, no Premium-only RPC.
replace_once(
    FILTERS_SETUP,
    "                if (MessagesController.getInstance(currentAccount).folderTags && dialogFilters.get(i).color >= 0) {",
    "                if (TelePremiumFolderTags.isEnabled(currentAccount) && TelePremiumFolderTags.getColor(currentAccount, dialogFilters.get(i).id, dialogFilters.get(i).color) >= 0) {",
)
replace_once(
    FILTERS_SETUP,
    "        items.add(ItemInner.asShadow(!getUserConfig().isPremium() ? AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.FolderShowTagsInfoPremium), Theme.key_windowBackgroundWhiteBlueHeader, AndroidUtilities.REPLACING_TAG_TYPE_LINKBOLD, () -> {\n"
    "            presentFragment(new PremiumPreviewFragment(\"settings\"));\n"
    "        }) : LocaleController.getString(R.string.FolderShowTagsInfo)));",
    "        items.add(ItemInner.asShadow(LocaleController.getString(R.string.FolderShowTagsInfo)));",
)
replace_between(
    FILTERS_SETUP,
    "            if (item.viewType == VIEW_TYPE_CHECK) {",
    "            } else if (item.viewType == VIEW_TYPE_FILTER) {",
    "            if (item.viewType == VIEW_TYPE_CHECK) {\n"
    "                final boolean enabled = !TelePremiumFolderTags.isEnabled(currentAccount);\n"
    "                TelePremiumFolderTags.setEnabled(currentAccount, enabled);\n"
    "                getMessagesController().setFolderTags(enabled);\n"
    "                ((TextCheckCell) view).setChecked(enabled);\n"
    "                if (filtersSectionStart >= 0 && filtersSectionEnd >= filtersSectionStart) {\n"
    "                    adapter.notifyItemRangeChanged(filtersSectionStart, filtersSectionEnd - filtersSectionStart);\n"
    "                }\n"
    "                getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);\n",
)
replace_once(
    FILTERS_SETUP,
    "            final int color = getMessagesController().folderTags ? filter.color : -1;",
    "            final int color = TelePremiumFolderTags.isEnabled(currentAccount) ? TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color) : -1;",
)
replace_once(
    FILTERS_SETUP,
    "            if (color >= 0 && filter.color != lastAppliedColor) {",
    "            if (color >= 0 && color != lastAppliedColor) {",
)
replace_once(
    FILTERS_SETUP,
    "                    cell.setTextAndCheck(item.text, getMessagesController().folderTags, divider);",
    "                    cell.setTextAndCheck(item.text, TelePremiumFolderTags.isEnabled(currentAccount), divider);",
)
replace_once(
    FILTERS_SETUP,
    "                    cell.setCheckBoxIcon(!getUserConfig().isPremium() ? R.drawable.permission_locked : 0);",
    "                    cell.setCheckBoxIcon(0);",
)

# Chat list tags: render from the local switch and local colour overrides,
# without requiring Telegram Premium or the server-side folderTags flag. Keep
# the Telegram DialogFilter model untouched so local colours can never leak
# into unrelated server sync paths.
replace_once(
    DIALOG_TAGS,
    "        tag.colorId = filter.color;",
    "        tag.colorId = TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color);",
)
replace_once(
    DIALOG_TAGS,
    "        tag.color = Theme.getColor(Theme.keys_avatar_nameInMessage[filter.color % Theme.keys_avatar_nameInMessage.length]);",
    "        tag.color = Theme.getColor(Theme.keys_avatar_nameInMessage[tag.colorId % Theme.keys_avatar_nameInMessage.length]);",
)
replace_once(
    DIALOG_TAGS,
    "            } else if (filter.color != tag.colorId || filter.name != null && tag.layout != null && filter.name.length() != tag.layout.getText().length()) {",
    "            } else if (TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color) != tag.colorId || filter.name != null && tag.layout != null && filter.name.length() != tag.layout.getText().length()) {",
)
replace_once(
    DIALOG_TAGS,
    "        if (!(controller.folderTags && account.getUserConfig().isPremium())) {",
    "        if (!TelePremiumFolderTags.isEnabled(currentAccount)) {",
)
replace_once(
    DIALOG_TAGS,
    "                MessagesController.DialogFilter filter = allFilters.get(i);\n"
    "                if (filter == null || filter == currentFilter || filter.color < 0)\n"
    "                    continue;\n"
    "                if (filter.includesDialog(account, dialogId)) {",
    "                MessagesController.DialogFilter filter = allFilters.get(i);\n"
    "                if (filter == null || filter == currentFilter) {\n"
    "                    continue;\n"
    "                }\n"
    "                final int localColor = TelePremiumFolderTags.getColor(currentAccount, filter.id, filter.color);\n"
    "                if (localColor < 0) {\n"
    "                    continue;\n"
    "                }\n"
    "                if (filter.includesDialog(account, dialogId)) {",
)

print("TelePremium local folder tags V11 applied")
