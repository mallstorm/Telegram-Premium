# Telegram AI Analyzer — implementation review

Date: **2026-11-22**  
Branch: `feature/telegram-ai-analyzer` (based on `feature/classic-telegram-11x`)  
Application: Telegram 13.1.1 / classic 11.x shell available.

## Implemented source

### 1. Telegram source selection and conversation
- Main TelePremium Center and classic drawer open `TelePremiumAnalyzerActivity`.
- Account/group catalog enumerates already loaded dialogs (including archives) of activated accounts.
- User selects exactly one source, days 1/7/30 and optionally one author; the selected account slot is retained.
- Selected Telegram history is fetched via MTProto `messages.getHistory`, bounded by 6 pages / 300 entries per load.
- Local lexical relevance ranking of text/captions keeps prompt content bounded. Short/full overview and user follow-up chat use default saved AI connection.
- Encrypted scoped conversation history via existing `TPAiStore`/Android Keystore.
- Original message IDs are retained. Source rows attempt native Telegram navigation, subject to the selected Telegram account. Text reports can be shared by explicit Android share action.

### 2. Image/screenshots
- Explicit per-source consent **and separate media consent**. No automatic media download.
- Images are retrieved only from files already downloaded by the Telegram client. Protected (`noforwards`) media is rejected.
- Up to 4 messages per request; multiple batches. Each image is downscaled and JPEG-encoded to a bounded 850 KB. Caption and author ID are paired with the specific `msg:ID`.
- `TPAiClient.completeVision` supports OpenAI-compatible Chat Completions and Anthropic Messages image payloads.
- `TPOmni.completeVision` reuses existing OmniChat attachment handling, including already configured account/subscription transport when the model actually accepts image inputs. Temporary attachment JPEG files are deleted after the request. The request may still fail if a given model or subscription transport does not support vision.
- Text and images are treated as untrusted source data, not instructions. Claimed trade outcomes require evidence.

### 3. Video/audio
- Existing `TelePremiumPlayerActivity` reused to open downloaded video.
- `AnalyzerMediaProcessor.video` extracts at most 5 timestamped visual samples using `MediaMetadataRetriever` (samples, not exhaustive frame-by-frame analysis).
- `TelePremiumLocalTranscriber.transcribeMediaFile` decodes an audio track via Android `MediaExtractor/MediaCodec`, splits PCM into 35-second segments, and uses **on-device** Android speech recognition (Android 13+ with an installed on-device recognizer). Transcript stamps mark segment starts, not precise word offsets.
- Video file cap 400 MB, duration 40 min; a maximum of 60 ASR segments (~35 min) is processed and longer transcripts are explicitly labelled partial. Result combines speech transcription, keyframe context, published caption and source `msg:ID`.
- If local ASR unavailable, video summary remains visually based when model supports it; it must not claim audio was heard.
- Processing is canceled/invalidated on source changes and fragment destruction; cancellation is cooperative.

### 4. Documents
- PDF media classification and on-device `PdfRenderer` cover the **first 5 pages** (up to 30 MB). Each rendered page is individually labeled for visual analysis and citation.
- Other documents are not yet parsed.

### 5. Safety and privacy
- The app enumerates existing chats locally; selecting a group does not automatically send messages to AI.
- An explicit first consent is needed for sending selected text/captions to the model; a second consent for selected media and transcript.
- Authorization and the group’s restrictions must be respected; group membership alone does not grant rights to use other participants' content for external AI training/processing.
- No background crawling, unsolicited Telegram posting or automatic trading.
- No copying Telegram login credentials; the existing signed-in Telegram sessions and existing AI credential store are reused.
- Local scopes are keyed by Telegram user ID, dialog ID, author filter and days.

## Verification status

The source and build workflows were updated to check Analyzer invariants and parse the new Java activity. A static balanced-delimiter/string scan of changed Java/Kotlin sources found no unmatched structures. **This does not prove compilation.**

GitHub Actions on this repository currently reports early job failures *with no executable steps or accessible job logs*, including before the Analyzer branch existed (parent branch runs from 2026-11-20). No installable APK was verified. Inspect repository Actions/runner/billing settings and restart source-check/build workflows; then address any compile errors and perform on-device functional testing.

## Remaining product work (not claimed complete)

1. Reliable full-history pagination beyond the present bounded sample, source-permission audit and large-group performance.
2. Interactive viewer with direct playback seeking to a transcript timestamp (the current UI opens the existing player, but does not seek to a timecode).
3. Image and video semantic search across an encrypted, user-approved, incremental evidence index; persistent batch cache and invalidation on edits/deletes.
4. Better video scene-change sampling and quality controls; progress, cancellation controls, and device thermal/memory benchmarks for long videos.
5. Encrypted export/reimport of sessions, PDF full-document indexing, author-level trend analysis.
6. Full build, runtime tests on 2+ Telegram accounts, Android 13+ on-device ASR, Vision model combinations, classic/modern shells, and signed release update compatibility.

The implementation is **an integrated working-source slice**, not a tested release.
