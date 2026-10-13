#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def read(rel):
    p = SRC / rel
    if not p.exists():
        raise SystemExit(f"missing upstream file: {rel}")
    return p, p.read_text(encoding="utf-8")


def write(p, text):
    p.write_text(text, encoding="utf-8")


def replace_once(rel, old, new):
    p, data = read(rel)
    if new in data and old not in data:
        print(f"already patched: {rel}")
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one match, got {count}")
    write(p, data.replace(old, new, 1))
    print(f"patched: {rel}")


def replace_between(rel, start_marker, end_marker, replacement):
    p, data = read(rel)
    if replacement in data:
        print(f"already patched: {rel}")
        return
    start = data.find(start_marker)
    end = data.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        raise SystemExit(f"{rel}: marker range not found")
    write(p, data[:start] + replacement + data[end:])
    print(f"patched: {rel}")


def method_bounds(data, signature):
    start = data.find(signature)
    if start < 0:
        raise SystemExit(f"method signature not found: {signature}")
    brace = data.find("{", start)
    if brace < 0:
        raise SystemExit("opening brace not found")
    depth = 0
    for i in range(brace, len(data)):
        if data[i] == "{":
            depth += 1
        elif data[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i
    raise SystemExit("closing brace not found")


def replace_in_method(rel, signature, replacements):
    p, data = read(rel)
    start, end = method_bounds(data, signature)
    method = data[start:end + 1]
    for old, new in replacements:
        if new in method:
            continue
        if old not in method:
            raise SystemExit(f"{rel}::{signature}: missing fragment: {old[:80]}")
        method = method.replace(old, new)
    write(p, data[:start] + method + data[end + 1:])
    print(f"patched: {rel} :: {signature}")


# Drawer: the old sun/moon control becomes the current-theme pictogram and a
# compact 10-theme selector. Long press opens the full Appearance screen.
drawer = "TMessagesProj/src/main/java/org/telegram/ui/Cells/DrawerProfileCell.java"
replace_once(
    drawer,
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeIconRegistry;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;\n"
    "import org.telegram.ui.TelePremiumAppearanceActivity;\n",
)

new_theme_control = """        darkThemeView = new RLottieImageView(context) {
            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setText(TelePremiumThemeIconRegistry.getCurrentThemeContentDescription(getContext()));
            }
        };
        darkThemeView.setFocusable(true);
        darkThemeView.setScaleType(ImageView.ScaleType.CENTER);
        darkThemeView.setBackground(Theme.createCircleSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector), 0, 0));
        TelePremiumThemePreset drawerTheme = TelePremiumThemeController.getCurrent(context);
        darkThemeView.setImageResource(TelePremiumThemeIconRegistry.getCurrentThemeIcon(context));
        darkThemeView.setColorFilter(Theme.getColor(Theme.key_chats_menuName), PorterDuff.Mode.SRC_IN);
        darkThemeView.setContentDescription(TelePremiumThemeIconRegistry.getCurrentThemeContentDescription(context));
        if (Build.VERSION.SDK_INT >= 21) {
            darkThemeView.setBackgroundDrawable(Theme.createSelectorDrawable(darkThemeBackgroundColor = Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(17)));
            Theme.setRippleDrawableForceSoftware((RippleDrawable) darkThemeView.getBackground());
        }
        darkThemeView.setOnClickListener(v -> TelePremiumThemeController.showQuickThemeSelector(context, darkThemeView, () -> {
            darkThemeView.setImageResource(TelePremiumThemeIconRegistry.getCurrentThemeIcon(context));
            darkThemeView.setColorFilter(Theme.getColor(Theme.key_chats_menuName), PorterDuff.Mode.SRC_IN);
            darkThemeView.setContentDescription(TelePremiumThemeIconRegistry.getCurrentThemeContentDescription(context));
        }));
        darkThemeView.setOnLongClickListener(v -> {
            if (drawerLayoutContainer != null) {
                drawerLayoutContainer.presentFragment(new TelePremiumAppearanceActivity());
                return true;
            }
            return false;
        });
        addView(darkThemeView, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 90));

"""
replace_between(
    drawer,
    "        boolean playDrawable;\n",
    "        if (Theme.getEventType() == 0) {",
    new_theme_control,
)

replace_once(
    drawer,
    """        color = Theme.getColor(Theme.key_chats_menuName);
        if (currentMoonColor == null || currentMoonColor != color) {
            currentMoonColor = color;
            sunDrawable.beginApplyLayerColors();
            sunDrawable.setLayerColor("Sunny.**", currentMoonColor);
            sunDrawable.setLayerColor("Path 6.**", currentMoonColor);
            sunDrawable.setLayerColor("Path.**", currentMoonColor);
            sunDrawable.setLayerColor("Path 5.**", currentMoonColor);
            sunDrawable.commitApplyLayerColors();
        }
""",
    """        color = Theme.getColor(Theme.key_chats_menuName);
        if (currentMoonColor == null || currentMoonColor != color) {
            currentMoonColor = color;
            darkThemeView.setColorFilter(currentMoonColor, PorterDuff.Mode.SRC_IN);
        }
""",
)

# Main chat lists, folders and forwarding pickers must not expose the service
# peer. If a stale/search result does reach a click handler, route it to Cloud.
dialogs = "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
replace_once(
    dialogs,
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\n"
    "import org.telegram.messenger.telepremium.cloud.TelePremiumServicePeerRegistry;\n",
)

replace_in_method(
    dialogs,
    "public ArrayList<TLRPC.Dialog> getDialogsArray(int currentAccount, int dialogsType, int folderId, boolean frozen)",
    [
        ("return frozenDialogsList;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, frozenDialogsList);"),
        ("return messagesController.getDialogs(folderId);", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.getDialogs(folderId));"),
        ("return messagesController.dialogsServerOnly;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.dialogsServerOnly);"),
        ("return dialogs;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, dialogs);"),
        ("return messagesController.dialogsForward;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.dialogsForward);"),
        ("return messagesController.dialogsUsersOnly;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.dialogsUsersOnly);"),
        ("return messagesController.dialogsChannelsOnly;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.dialogsChannelsOnly);"),
        ("return messagesController.dialogsGroupsOnly;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.dialogsGroupsOnly);"),
        ("return dialogFilter.dialogsForward;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, dialogFilter.dialogsForward);"),
        ("return dialogFilter.dialogs;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, dialogFilter.dialogs);"),
        ("return messagesController.dialogsForBlock;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, messagesController.dialogsForBlock);"),
        ("return botShareDialogs;", "return TelePremiumServicePeerRegistry.filterDialogs(currentAccount, botShareDialogs);"),
    ],
)

replace_once(
    dialogs,
    """        if (dialogId == 0) {
            return;
        }

        if (onlySelect) {
""",
    """        if (dialogId == 0) {
            return;
        }
        if (TelePremiumServicePeerRegistry.isServicePeer(currentAccount, dialogId)) {
            if (!onlySelect) {
                presentFragment(new TelePremiumCloudActivity(currentAccount));
            }
            return;
        }

        if (onlySelect) {
""",
)

print("Themes V2 / Cloud V2 upstream integration applied")
