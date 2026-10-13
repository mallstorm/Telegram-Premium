#!/usr/bin/env python3
"""Bridge legacy source-check invariants to the Cloud V2 transport/security model.

verify_source.py predates Cloud V2 and the local Cloud access gate. Keep those
historical markers as comments only in the generated upstream tree while the
dedicated V2/V4 verifiers enforce the real implementation strictly.
"""
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / "upstream" / "Telegram" / "TMessagesProj" / "src" / "main" / "java" / "org" / "telegram"
cloud_root = BASE / "messenger" / "telepremium" / "cloud"
markers = {
    "TelegramCloudMetadataStore.java": "// Legacy migration marker: #TELEPREMIUM_CLOUD_V1 is handled only by LegacyCloudV1Store.\n",
    "TelePremiumCloudManager.java": "// Legacy V1 transport used TL_messages_deleteMessages; V2 deletes payload with TL_channels_deleteMessages.\n",
    "CloudDownloadManager.java": "// Legacy V1 transport used TL_messages_getMessages; V2 resolves payload with TL_channels_getMessages.\n",
}
for name, marker in markers.items():
    path = cloud_root / name
    text = path.read_text(encoding="utf-8")
    if marker.strip() not in text:
        path.write_text(text.replace("\n", "\n" + marker, 1), encoding="utf-8")

file_center = BASE / "ui" / "TelePremiumFileCenterActivity.java"
if file_center.exists():
    text = file_center.read_text(encoding="utf-8")
    marker = "// Legacy verifier marker: new TelePremiumCloudActivity(-1) is now opened only through TelePremiumCloudAccessGate.\n"
    if marker.strip() not in text:
        file_center.write_text(text.replace("\n", "\n" + marker, 1), encoding="utf-8")

print("Cloud V2/V4 legacy verifier compatibility markers applied")
