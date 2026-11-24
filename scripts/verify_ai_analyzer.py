#!/usr/bin/env python3
from pathlib import Path
r=Path(__file__).resolve().parents[1]
b=r/"patches/tg13/overlay/TMessagesProj/src/main/java"
ui=(b/"org/telegram/ui/TelePremiumAnalyzerActivity.java").read_text()
core=b/"org/telegram/messenger/telepremium/aicenter"
sync=(core/"CenterSync.java").read_text()
store=(core/"CenterStore.java").read_text()
assert 'MAX_SOURCES' not in ui and 'msg:' not in ui
assert 'center_lock' in ui
assert 'AES/GCM/NoPadding' in store and 'AndroidKeyStore' in store
assert 'TL_messages_getHistory' in sync and 'TL_messages_getDialogs' in sync
assert not any(x in sync for x in ['markDialogAsRead(', 'sendMessage(', 'joinChannel('])
assert 'protectedMedia' in sync and 'isEncryptedDialog' in sync
for entry in ['TelePremiumCenterActivity.java','TelePremiumClassicDrawer.java']:
 assert 'new TelePremiumAnalyzerActivity()' in (b/'org/telegram/ui'/entry).read_text()
print('AI Center source/privacy wiring passed')
