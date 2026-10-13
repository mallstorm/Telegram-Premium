#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def replace_once(rel, old, new):
    path = SRC / rel
    data = path.read_text(encoding="utf-8")
    if new in data and old not in data:
        print(f"already patched: {rel}")
        return
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one match, got {count}")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {rel}")


def replace_all(rel, old, new, expected):
    path = SRC / rel
    data = path.read_text(encoding="utf-8")
    if new in data and old not in data:
        print(f"already patched: {rel}")
        return
    count = data.count(old)
    if count != expected:
        raise SystemExit(f"{rel}: expected {expected} matches, got {count}")
    path.write_text(data.replace(old, new), encoding="utf-8")
    print(f"patched: {rel} ({count} matches)")


search = "TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsSearchAdapter.java"
replace_once(
    search,
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\n"
    "import org.telegram.messenger.telepremium.cloud.TelePremiumServicePeerRegistry;\n",
)
replace_once(
    search,
    """    private boolean filter(Object obj) {
        if (dialogsType != DialogsActivity.DIALOGS_TYPE_START_ATTACH_BOT) {
            return true;
        }
""",
    """    private boolean filter(Object obj) {
        if (obj instanceof TLRPC.Chat && TelePremiumServicePeerRegistry.isServicePeer(currentAccount, -((TLRPC.Chat) obj).id)) {
            return false;
        }
        if (obj instanceof MessageObject && TelePremiumServicePeerRegistry.isServicePeer(currentAccount, ((MessageObject) obj).getDialogId())) {
            return false;
        }
        if (dialogsType != DialogsActivity.DIALOGS_TYPE_START_ATTACH_BOT) {
            return true;
        }
""",
)
replace_once(
    search,
    """    public void putRecentSearch(final long did, TLObject object) {
        RecentSearchObject recentSearchObject = recentSearchObjectsById.get(did);
""",
    """    public void putRecentSearch(final long did, TLObject object) {
        if (TelePremiumServicePeerRegistry.isServicePeer(currentAccount, did)) {
            return;
        }
        RecentSearchObject recentSearchObject = recentSearchObjectsById.get(did);
""",
)
replace_once(
    search,
    """                while (cursor.next()) {
                    long did = cursor.longValue(0);

                    boolean add = false;
""",
    """                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    if (TelePremiumServicePeerRegistry.isServicePeer(currentAccount, did)) {
                        continue;
                    }

                    boolean add = false;
""",
)
replace_all(
    search,
    """                            TLRPC.Message message = res.messages.get(a);
                            long did = MessageObject.getDialogId(message);
                            int maxId = MessagesController.getInstance(currentAccount).deletedHistory.get(did);
""",
    """                            TLRPC.Message message = res.messages.get(a);
                            long did = MessageObject.getDialogId(message);
                            if (TelePremiumServicePeerRegistry.isServicePeer(currentAccount, did)) {
                                continue;
                            }
                            int maxId = MessagesController.getInstance(currentAccount).deletedHistory.get(did);
""",
    2,
)

print("Cloud V2 search/recent visibility filters applied")
