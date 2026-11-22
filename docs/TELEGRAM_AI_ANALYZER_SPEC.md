# Telegram AI Analyzer — Specification

**Repository:** `mallstorm/Telegram-Premium`  
**Base:** `feature/classic-telegram-11x` (Telegram 13.1.1 engine, classic 11.x UI available)  
**Implementation branch:** `feature/telegram-ai-analyzer`  
**Status:** approved scope; phased implementation; November 2026.

## 1. Goal

Embed a universal, conversational, source-grounded analysis workspace in TelePremium for authorized Telegram groups/channels across the user's active Telegram accounts. The feature is **not trading-only**. Analyze text, captions, screenshots, images, documents, voice notes and videos; provide short/full summaries, targeted author-level questions and timestamped media summaries.

User examples:
- «Что это за группа? Дай краткую сводку за неделю».
- «Какие материалы публиковали сегодня?»
- «Найди сообщения автора X с графиками EUR/USD, сравни скриншоты и подписи».
- «Сделай конспект утреннего видео; на каких отметках обсуждают GBP/USD?»
- «На чём основан вывод? Открой первоисточник».
- «А теперь сравни его публикации с прошлым месяцем» (context retained).

## 2. UX and modes

Entry: TelePremium Center → **Telegram AI Analyzer**, and classic 11.x Drawer → same destination. Reuse existing theme tokens and media/player components.

Home: account selector (one / all active accounts), search for groups/channels, explicitly selected authorized sources, period (today/7/30/custom), material filter, consent/permissions status. Show the originating account on identical groups; do not silently merge distinct conversations. No automatic ingestion of all groups merely because they are listed.

Main view: Chat-first threaded workspace. Chat bubble follow-ups preserve selected account(s), source(s), author, time range, media type and conversation history. Provider/model selector uses existing saved AI connections; support short/full modes. Evidence cards open original chat/message/video time position. Save sessions by locally scoped account/source identity; offer clear/delete/export. If a model cannot process images/audio/video, show capability/processing fallback, never claim to have visually viewed unseen content.

## 3. Existing code — reuse, do not duplicate

Existing source on base branch:
- `org/telegram/ui/TelePremiumAiActivity.java`: AI connections and model settings.
- `org/telegram/messenger/telepremium/ai/TPAiStore.java`: connection list, default model, encrypted secrets (Android Keystore).
- `.../TPAiClient.java`: OpenAI-compatible and Anthropic one-shot completion.
- `.../TPOmni.kt`: OmniChat provider/account transport, ChatGPT subscription / Codex / others. **Do not imply unverified subscription integrations are official**; preserve current behavior and warn where provider restrictions apply.
- `TelePremiumPlayerActivity.java`, `TelePremiumPlaybackController.java`, `TelePremiumMediaSessionService.java`: player.
- `TelePremiumLocalTranscriber.java`: Android 13+ local voice/audio transcription bridge, not a complete arbitrary-video transcriber.
- `UserConfig.MAX_ACCOUNT_COUNT`, `UserConfig.getInstance(account).isClientActivated()`: activated account enumeration.

Current `TPAiClient.complete(c, system, user)` and `TPOmni.complete` are **one-shot**, not a persistent multi-turn/multimodal conversation. Analyzer must add a backward-compatible conversation/transport abstraction; never break the existing article assistant.

## 4. Architecture

```text
Telegram AI Analyzer (native Telegram 13 overlay / both UIs)
   │
   ├── SourceCatalog: active account slots, selected dialogs/channels, permissions
   ├── ScopedHistoryReader: account + dialog_id + date + sender constraints
   │      └── Telegram existing controller/storage/API (no re-login, no separate userbot)
   ├── EvidenceIndex: local encrypted metadata, source IDs, timestamps, media types
   ├── MediaProcessing: loaded message media → OCR/vision; audio extraction/ASR;
   │                    video scene/frame selection + timecode + transcript
   ├── Retrieval: lexical search first, optional embeddings, source filters
   ├── ConversationEngine: thread context, bounded history, citations, summaries
   └── Existing TPAiStore / TPAiClient / TPOmni + existing player
```

Suggested logical modules (new package `org.telegram.messenger.telepremium.analyzer`): `AnalyzerScope`, `AnalyzerSourceRepository`, `AnalyzerHistoryReader`, `AnalyzerEvidence`, `AnalyzerConversation`, `AnalyzerMediaProcessor`, `AnalyzerPermissionPolicy`. Activity: `org.telegram.ui.TelePremiumAnalyzerActivity`.

### Data contracts / invariants

Every source reference must carry `accountSlot`, `accountUserId`, `dialogId`, `messageId`, `senderId`, `date`, `mediaType`, `caption`, media locator and `allowedForAI`; media URLs/paths and author names must not be sent without explicit authorization. Account switching must never leak account-specific data or credentials. Preserve message IDs and evidence linkage; no invented citations. Deleted or inaccessible messages must be dropped from index or flagged stale. Don't index secret chats, self-destruct media, protected content or forbidden group materials.

For trading analysis: identify instrument, timeframe, entry, stop, take-profit, chart notes, author commentary, and what is **not proven**. Don't turn screenshots of winners into unsupported profitability figures.

### Media pipeline

- Text/captions → locally scoped excerpt summaries.
- Images/screenshots → OCR **plus actual vision model** where supported; retain original message link, no unsupported assertions.
- Voice → reuse local transcriber when applicable; fallback to user-authorized supported speech provider.
- Video → existing player for playback; add safe local audio extraction/transcription, keyframes (scene changes + intervals), optional OCR/vision, synchronized timecodes, short/full summary. Cache by `account:dialog:message:contentHash:processorVersion`. Never claim a video was watched if only audio was transcribed.
- PDFs/documents → bounded extraction where supported; exclude executable/unknown formats.
- Work queue: foreground user-triggered analysis first; resumable explicit jobs later. No stealth background monitoring.

## 5. Privacy, permissions and account safety

**Authorization is not implied by group membership.** Telegram content access and using it for AI/LLM processing are separate permissions. Respect Telegram's rules and groups' restrictions. Only process materials covered by the required authorizations/consent, with per-source and per-attachment opt-in and clear indicators of external transmission. Default: no external AI transmission, no bulk indexing of arbitrary third-party groups. Provide local-only metadata catalog; ask for permission before first import/process. No silent cross-account scan. Local cache protected; allow purge. Respect rate limits and flood waits; no scraping workarounds and no privileged/bot circumvention. Never send credentials or raw auth tokens to LLM.

## 6. Implementation phases and acceptance

### M0 — foundation
- [ ] Isolated branch and this checked-in spec.
- [ ] Analyzer entry in TelePremium Center, and classic Drawer if appropriate.
- [ ] Native Analyzer UI with active-account filtering, selected source, time range, consent status, model default and questions.
- [ ] Unit-level compile/source tests and GitHub Actions APK build.

### M1 — text conversations
- [ ] Picker of authorized groups/channels for selected accounts.
- [ ] Explicit history retrieval with pagination/rate-limit handling and date/sender filters.
- [ ] User-triggered short/full summaries, multi-turn chat with local history and source links.
- [ ] Incremental retrieval rather than entire-history prompt.
- [ ] Metadata/permission isolation tests for 2+ accounts and repeated dialog IDs.

### M2 — images and author investigation
- [ ] Media attachment consent gate; image/caption pairing and visual model capability checks.
- [ ] Author finder and photo-history breakdown.
- [ ] Trading chart specialism as optional prompt profile, not core dependency.
- [ ] Evidence and uncertainty annotations.

### M3 — audio/video
- [ ] Speech + keyframes + timecoded summary integrated with existing player.
- [ ] Video session cancellation, battery/network budgets and resume.
- [ ] Distinguish audio-only vs full audiovisual analysis; media permission tests.

### M4 — full product
- [ ] Index/search and repeat analysis cache, exports, filters and comprehensive UI.
- [ ] Optional explicit monitoring of sources only where rights permit.
- [ ] Stress tests, accessibility, classic/modern UI checks and signed release build.

**Definition of done:** selected authorized source → accurate short/full response with message-level evidence, follow-up context, safe account isolation, video linked to existing player, successful installable APK and regression tests.

## 7. Non-goals

No new Telegram login stack, no replacing media player, no unrestricted group scraping, no auto-trading, no automated posts into the source group, no promise that ChatGPT subscription is equivalent to OpenAI API, no claim that one-shot text completion can already process multimedia.
