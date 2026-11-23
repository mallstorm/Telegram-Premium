#!/usr/bin/env python3
"""Smoke-check Telegram AI Analyzer wiring and privacy fences in the 13.1.1 overlay."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
java = root / "patches/tg13/overlay/TMessagesProj/src/main/java"
def file(path):
    return (java / path).read_text(encoding="utf-8")

activity = file("org/telegram/ui/TelePremiumAnalyzerActivity.java")
stats = file("org/telegram/messenger/telepremium/analyzer/AnalyzerStats.java")
history = file("org/telegram/messenger/telepremium/analyzer/AnalyzerHistoryReader.java")
center = file("org/telegram/ui/TelePremiumCenterActivity.java")
drawer = file("org/telegram/ui/TelePremiumClassicDrawer.java")
store = file("org/telegram/messenger/telepremium/ai/TPAiStore.java")
client = file("org/telegram/messenger/telepremium/ai/TPAiClient.java")
omni = file("org/telegram/messenger/telepremium/ai/TPOmni.kt")
transcriber = file("org/telegram/messenger/telepremium/TelePremiumLocalTranscriber.java")
processor = file("org/telegram/messenger/telepremium/analyzer/AnalyzerMediaProcessor.java")
retrieval = file("org/telegram/messenger/telepremium/analyzer/AnalyzerRetrieval.java")

checks = {
    "spec exists": (root / "docs/TELEGRAM_AI_ANALYZER_SPEC.md").is_file(),
    "center entry": "new TelePremiumAnalyzerActivity()" in center,
    "classic drawer entry": "new TelePremiumAnalyzerActivity()" in drawer,
    "selected account source": "UserConfig.getInstance(a).isClientActivated()" in history,
    "scoped peer request": "getInputPeer(source.dialogId)" in history,
    "only selected group's history": "TL_messages_getHistory" in history,
    "bounded history": "MAX_MESSAGES = 1500" in history,
    "cancellation": "cancelled.get()" in history and "r.cancel();" in activity,
    "one-time consent": "withConsent(" in activity and "consent_v1" in activity,
    "no media download": "FileLoader" not in history and "loadFile(" not in history,
    "existing AI connection reused": "TPAiStore.getDefault()" in activity and "TPAiClient.complete" in activity,
    "existing AI internals retained": "getDefault()" in store and "complete(TPAiStore.Connection" in client,
    "source references": "[msg:" in activity,
    "contextual dialog": "private final ArrayList<String[]> thread" in activity,
    "single screen with chat, stats, media tabs": "buildChatPanel(" in activity and "renderStats()" in activity and "renderMedia()" in activity,
    "compact fixed composer": "composer.setMaxLines(4)" in activity,
    "multi-source scope": "MAX_SOURCES" in activity and "pickSources()" in activity,
    "local statistics": "class AnalyzerStats" in stats and "AnalyzerStats.compute(" in activity,
    "clickable message references": "openEvidenceById(id)" in activity,

    "all loaded including archived groups": "getAllDialogs()" in history,
    "author filter": "authorFilter" in activity and "applyAuthorFilter()" in activity,
    "local relevance ranking": "AnalyzerRetrieval.select" in activity and "class AnalyzerRetrieval" in retrieval,
    "media metadata and original handle": "sourceMessage" in history and "classifyMedia" in history,
    "no protected media access": "sourceMessage.noforwards" in processor,
    "media goes through consent and model checks": "mediaReady()" in activity and "withConsent(" in activity,
    "on-demand download of the picked media only": "ensureLocal(" in processor,
    "real image conversion": "BitmapFactory.decodeFile" in processor and "JPEG" in processor,
    "provider vision request": "completeVision" in client and "image_url" in client and "base64" in client,
    "omnichat subscription vision bridge": "completeVision" in omni and "LocalAttachment.encode" in omni,
    "ephemeral attached files": 'temporary.forEach { runCatching { it.delete() } }' in omni,
    "video visual frames": "MediaMetadataRetriever" in processor and "getFrameAtTime" in processor,
    "on device speech only": "isOnDeviceRecognitionAvailable" in transcriber and "createOnDeviceSpeechRecognizer" in transcriber,
    "video speech segments": "VIDEO_SEGMENT_SECONDS" in transcriber and "transcribeSegments" in transcriber,
    "video cancellation": "mediaCancellation.set(true)" in activity and "cancellation.get()" in transcriber,
    "PDF visual extraction": "PdfRenderer" in processor and "pdfPages" in processor,
    "reuse existing player": "TelePremiumPlayerActivity.open" in activity,
    "original evidence navigation": 'new ChatActivity(args)' in activity,
    "export report": "Intent.ACTION_SEND" in activity,
    "encrypted conversations": "TPAiStore.putPrivate" in activity and "TPAiStore.getPrivate" in activity,

}
for label, good in checks.items():
    print(("PASS" if good else "FAIL") + " " + label)
if not all(checks.values()):
    raise SystemExit("AI Analyzer verification failed")
print(f"AI Analyzer source checks passed: {len(checks)}")
