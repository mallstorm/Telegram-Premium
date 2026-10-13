#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def method_bounds(text, signature):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"method not found: {signature}")
    line_start = text.rfind("\n", 0, start) + 1
    brace = text.find("{", start)
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
    raise SystemExit(f"unterminated method: {signature}")


cloud = SRC / "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCloudActivity.java"
text = cloud.read_text(encoding="utf-8")
start, end = method_bounds(text, "private void openNode(AccountNode item)")
replacement = '''    private void openNode(AccountNode item) {
        if (item.node.type == CloudNode.TYPE_FILE && !TelePremiumCloudAccessGate.canExposeNode(context, item.node)) {
            String protectedCategory = TelePremiumCloudSecurityStore.categoryForNode(item.node);
            TelePremiumCloudAccessGate.openCategory(this, item.account, protectedCategory, () -> openNode(item));
            return;
        }
        TelePremiumCloudSecuritySession.touch(context);
        if (item.node.type == CloudNode.TYPE_FOLDER) {
            presentFragment(new TelePremiumCloudActivity(item.account, item.node.id, item.node.name));
            return;
        }

        String mime = resolvedMime(item.node);
        if (TelePremiumPlayerActivity.canHandle(item.node.name, mime)) {
            // Cloud audio/video goes straight to the Telegram-backed stream URI. The
            // player itself resolves the service-channel document and starts ExoPlayer
            // as soon as the first playable range is available; no full pre-download.
            setSyncText("Потоковое воспроизведение…", true);
            TelePremiumPlayerActivity.openCloud(
                    getParentActivity(),
                    item.account,
                    item.node.telegramMessageId,
                    item.node.name,
                    mime,
                    item.node.size);
            return;
        }

        attachTransferManager(item.account);
        new CloudDownloadManager(item.account).download(item.node, (state, file, error) -> {
            if (error != null) {
                setSyncText("Не удалось открыть: " + error, false);
                return;
            }
            if (state == CloudDownloadManager.DOWNLOADING) {
                setSyncText("Скачивание файла…", false);
                return;
            }
            setSyncText("✓  Файл загружен", true);
            if (file == null || !file.exists()) {
                setSyncText("Скачанный файл не найден в кэше", false);
            } else if (!AndroidUtilities.openForView(file, item.node.name, mime, getParentActivity(), getResourceProvider(), false)) {
                setSyncText("Нет приложения для открытия этого формата", false);
            }
        });
    }
'''
cloud.write_text(text[:start] + replacement + text[end:], encoding="utf-8")
print("TelePremium Cloud streaming V6 applied")
