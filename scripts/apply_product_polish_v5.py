#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def read(rel):
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing source: {rel}")
    return path, path.read_text(encoding="utf-8")


def replace_once(rel, old, new):
    path, text = read(rel)
    if new in text and old not in text:
        print(f"already patched: {rel}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one match, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {rel}")


def replace_all(rel, old, new, minimum=1):
    path, text = read(rel)
    if new in text and old not in text:
        print(f"already patched: {rel}")
        return
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{rel}: expected at least {minimum} match(es), found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"patched: {rel} ({count} matches)")


def method_bounds(text, signature):
    start = text.find(signature)
    if start < 0:
        return None
    line_start = text.rfind("\n", 0, start) + 1
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"opening brace not found: {signature}")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                if end < len(text) and text[end] == "\n":
                    end += 1
                return line_start, end
    raise SystemExit(f"closing brace not found: {signature}")


def remove_method(rel, signature):
    path, text = read(rel)
    bounds = method_bounds(text, signature)
    if bounds is None:
        print(f"already removed: {rel} :: {signature}")
        return
    start, end = bounds
    path.write_text(text[:start] + text[end:], encoding="utf-8")
    print(f"removed: {rel} :: {signature}")


chat = "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java"
replace_once(chat,"import org.telegram.messenger.Utilities;\n","import org.telegram.messenger.Utilities;\nimport org.telegram.messenger.telepremium.TelePremiumUiScale;\n")
replace_all(chat,"maxWidth = AndroidUtilities.getMinTabletSide() - AndroidUtilities.dp(122);","maxWidth = AndroidUtilities.getMinTabletSide() - TelePremiumUiScale.chatInsetPx(getContext(), 122);")
replace_all(chat,"maxWidth = Math.min(getParentWidth(), AndroidUtilities.displaySize.y) - AndroidUtilities.dp(122);","maxWidth = Math.min(getParentWidth(), AndroidUtilities.displaySize.y) - TelePremiumUiScale.chatInsetPx(getContext(), 122);")
replace_all(chat,"maxWidth = AndroidUtilities.getMinTabletSide() - AndroidUtilities.dp(80);","maxWidth = AndroidUtilities.getMinTabletSide() - TelePremiumUiScale.chatInsetPx(getContext(), 80);")
replace_all(chat,"maxWidth = Math.min(getParentWidth(), AndroidUtilities.displaySize.y) - AndroidUtilities.dp(80);","maxWidth = Math.min(getParentWidth(), AndroidUtilities.displaySize.y) - TelePremiumUiScale.chatInsetPx(getContext(), 80);")

cloud = "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCloudActivity.java"
path, text = read(cloud)
for line in (
    "    private LinearLayout recentSheet;\n","    private LinearLayout recentList;\n","    private TextView recentCount;\n","    private TextView recentArrow;\n","    private boolean recentExpanded;\n","    private float recentTouchY;\n","    private float recentStartTranslation;\n","    private boolean recentDragging;\n","    private static final int RECENT_SHEET_HEIGHT_DP = 420;\n","    private static final int RECENT_SHEET_PEEK_DP = 68;\n",
): text = text.replace(line, "")
text = text.replace("        createRecentSheet(root);\n", "")
text = text.replace("AndroidUtilities.dp(152)", "AndroidUtilities.dp(88)", 1)
text = text.replace("            showRecentSheet(false);\n", "")
text = text.replace("        showRecentSheet(false);\n", "")
text = text.replace("            addCategories();\n            addUserFolders();\n            updateRecentSheet();\n            showRecentSheet(true);\n            return;","            addCategories();\n            addUserFolders();\n            return;")
path.write_text(text, encoding="utf-8")
for signature in ("private void createRecentSheet(FrameLayout root)","private boolean onRecentSheetTouch(MotionEvent event)","private void setRecentExpanded(boolean expanded, boolean animate)","private void showRecentSheet(boolean show)","private void updateRecentSheet()"):
    remove_method(cloud, signature)

path, text = read(cloud)
old_open = '''            if(file==null || !file.exists())setSyncText("Скачанный файл не найден в кэше",false);\n            else if(!AndroidUtilities.openForView(file,item.node.name,resolvedMime(item.node),getParentActivity(),getResourceProvider(),false))\n                setSyncText("Нет приложения для открытия этого формата",false);'''
new_open = '''            if(file==null || !file.exists())setSyncText("Скачанный файл не найден в кэше",false);\n            else {\n                String mime = resolvedMime(item.node);\n                if (TelePremiumPlayerActivity.canHandle(item.node.name, mime)) {\n                    TelePremiumPlayerActivity.open(getParentActivity(), file, item.node.name, mime);\n                } else if(!AndroidUtilities.openForView(file,item.node.name,mime,getParentActivity(),getResourceProvider(),false)) {\n                    setSyncText("Нет приложения для открытия этого формата",false);\n                }\n            }'''
if new_open not in text:
    count = text.count(old_open)
    if count != 1: raise SystemExit(f"{cloud}: Cloud open block expected once, found {count}")
    text = text.replace(old_open, new_open, 1)
path.write_text(text, encoding="utf-8")
print("patched Cloud media opening")

manifest = "TMessagesProj/src/main/AndroidManifest.xml"
player_activity = '''        <activity\n            android:name="org.telegram.ui.TelePremiumPlayerActivity"\n            android:theme="@style/Theme.TMessages"\n            android:configChanges="keyboardHidden|orientation|screenSize|uiMode"\n            android:hardwareAccelerated="true"\n            android:resizeableActivity="true"\n            android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <data android:mimeType="audio/*" />\n                <data android:mimeType="video/*" />\n            </intent-filter>\n            <intent-filter>\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <data android:mimeType="application/ogg" />\n                <data android:mimeType="application/x-matroska" />\n            </intent-filter>\n        </activity>\n'''
path, text = read(manifest)
marker = '        <activity\n            android:name="org.telegram.ui.LaunchActivity"'
if player_activity not in text:
    if marker not in text: raise SystemExit(f"{manifest}: LaunchActivity marker not found")
    text = text.replace(marker, player_activity + marker, 1)
    path.write_text(text, encoding="utf-8")
    print("registered TelePremium player")
else: print("player already registered")

print("TelePremium product polish V5 applied")
