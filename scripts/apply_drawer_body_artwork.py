#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram" / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram" / "ui" / "ActionBar" / "DrawerLayoutContainer.java"

text = SRC.read_text(encoding="utf-8")

old_import = "import org.telegram.messenger.Utilities;\n"
new_import = old_import + "import org.telegram.messenger.telepremium.theme.TelePremiumThemeArtwork;\n"
if "TelePremiumThemeArtwork" not in text:
    if old_import not in text:
        raise SystemExit("DrawerLayoutContainer: import anchor not found")
    text = text.replace(old_import, new_import, 1)

old = """    public void setDrawerLayout(FrameLayout layout, View drawerListView) {
        drawerLayout = layout;
        this.drawerListView = drawerListView;
        addView(drawerLayout);
"""
new = """    public void setDrawerLayout(FrameLayout layout, View drawerListView) {
        drawerLayout = layout;
        this.drawerListView = drawerListView;
        drawerLayout.setBackground(TelePremiumThemeArtwork.drawerBody(getContext()));
        drawerListView.setBackground(TelePremiumThemeArtwork.drawerBody(getContext()));
        addView(drawerLayout);
"""
if new not in text:
    if old not in text:
        raise SystemExit("DrawerLayoutContainer: setDrawerLayout anchor not found")
    text = text.replace(old, new, 1)

SRC.write_text(text, encoding="utf-8")
print("TelePremium full drawer body artwork applied")
