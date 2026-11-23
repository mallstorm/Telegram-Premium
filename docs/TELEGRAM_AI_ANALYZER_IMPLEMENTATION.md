# Telegram AI Analyzer — implementation notes

Date: **2026-11-23** · Telegram 13.1.1 overlay, Classic 11.x and Modern shells.

## One compact screen
`TelePremiumAnalyzerActivity` (the only Analyzer screen; entries: TelePremium Center, classic drawer, classic settings).

- **Scope bar**: one or several groups/channels (up to 8, multi-select with search, remembered between launches), period 1/7/30/90 days, AI model line.
- **Chat tab**: quick prompts (Summary, In detail, Trade ideas, Offers, Who posts what), free question composer, answers with clickable `[msg:ID]` references, encrypted history per source set, share/clear in the action bar. Works across all selected sources at once (e.g. "which products do my groups offer?").
- **Stats tab** (local, no AI): message/author/photo/video/link tiles, activity-per-day bars, mentioned instruments (EUR/USD, XAU/USD, indices) and tags, top authors by name. Tapping an author filters chat and media; tapping an instrument/tag asks about it.
- **Media tab**: screenshots (pick up to 6), videos (frames + on-device speech transcription), PDFs (first 5 pages). Files are **downloaded on demand** for the items the user picked; protected (`noforwards`) media is never fetched. Long-press a video to play it in the TelePremium player.
- History is loaded automatically when needed: up to 1500 messages / 20 pages per source, live progress, authors resolved to names.
- **One-time consent** replaces per-session switches; reading happens only for chosen sources and only on request. The Telegram session and the AI credential store are reused.

## Removed as contradictory
Separate text/media consent switches, duplicate short/detailed rows plus detail chips, hidden swipe-to-chat gesture and second chat screen, manual "load history" step, author filter that reset the thread, thread keyed by period/author.

## Not done yet (ideas)
Persistent encrypted evidence index across sessions, daily digest/scheduled report, per-author trend analysis, timecode seek in the player from transcript, scene-change frame sampling, PDF full-document indexing, signal tracking (entry/stop/target vs. later posts), export to PDF/Markdown.

## Verification
`scripts/verify_ai_analyzer.py` checks wiring and privacy fences; CI compiles. On-device testing with 2+ accounts, vision-capable models and Android 13+ on-device ASR is still required.
