import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/go/poligon/messenger"


class ShellArchitectureTest(unittest.TestCase):
    def test_both_shells_are_implemented(self):
        ui = (JAVA / "ui/ShellUi.java").read_text(encoding="utf-8")
        self.assertIn("showDrawer()", ui)
        self.assertIn("bottomNavigation(", ui)
        self.assertIn("interfacePicker(", ui)
        self.assertIn("preferences.classic(classic)", ui)
        self.assertIn("actions.navigate(destination)", ui)

    def test_all_13_existing_theme_palettes_are_available(self):
        state = (JAVA / "ui/ShellPreferences.java").read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r'p.add\(new Palette\(', state)), 13)
        self.assertIn('getSharedPreferences("polygon.shell.v1"', state)
        self.assertIn('getBoolean("classic",true)', state)

    def test_no_telegram_android_ui_runtime_dependencies(self):
        for java in JAVA.rglob("*.java"):
            body = java.read_text(encoding="utf-8")
            self.assertNotRegex(body, r"(?m)^\s*import\s+org\.telegram\.")
            self.assertNotIn("TelePremium", body)
            self.assertNotIn("com.telepremium.messenger", body)

    def test_one_transport_neutral_interface_for_both_shells(self):
        activity = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
        self.assertIn("implements Messenger.Observer, ShellUi.Actions", activity)
        self.assertIn("messenger.loadConversations()", activity)
        self.assertIn("messenger.loadHistory(c.id)", activity)
        self.assertIn("messenger.sendText(currentChat,message)", activity)


if __name__ == "__main__":
    unittest.main()
