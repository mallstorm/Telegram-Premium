package org.telegram.messenger.telepremium.analyzer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only bridge to Telegram's existing authorized account sessions.
 * Never requests a second login, never subscribes to all groups or sends any content to AI.
 * Caller must enforce per-source permission before requesting message history.
 */
public final class AnalyzerHistoryReader {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_MESSAGES = 300;
    private static final int MAX_PAGES = 6;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public static final class Source {
        public final int account;
        public final long dialogId;
        public final String title;
        public final String accountName;

        public Source(int account, long dialogId, String title, String accountName) {
            this.account = account;
            this.dialogId = dialogId;
            this.title = title;
            this.accountName = accountName;
        }
    }

    public static final class Entry {
        public final int account;
        public final long dialogId;
        public final int messageId;
        public final int date;
        public final long senderId;
        public final String caption;
        public final String mediaType;
        /** In-memory only, never serialized or sent to the LLM. */
        public final TLRPC.Message sourceMessage;

        Entry(int account, long dialogId, TLRPC.Message message) {
            this.account = account;
            this.dialogId = dialogId;
            this.messageId = message.id;
            this.date = message.date;
            long sender = 0;
            if (message.from_id != null) {
                if (message.from_id.user_id != 0) sender = message.from_id.user_id;
                else if (message.from_id.channel_id != 0) sender = -message.from_id.channel_id;
                else sender = -message.from_id.chat_id;
            }
            this.senderId = sender;
            this.caption = message.message == null ? "" : message.message;
            this.mediaType = classifyMedia(message);
            this.sourceMessage = message;
        }
    }

    private static String classifyMedia(TLRPC.Message message) {
        if (message.media instanceof TLRPC.TL_messageMediaPhoto) return "photo";
        if (message.media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document file = ((TLRPC.TL_messageMediaDocument) message.media).document;
            String mime = file == null || file.mime_type == null ? "" : file.mime_type;
            if (mime.startsWith("video/")) return "video";
            if (mime.startsWith("audio/")) return "audio";
            if (mime.startsWith("image/")) return "image";
            return "document";
        }
        return "text";
    }

    public interface Callback {
        void onResult(List<Entry> entries, String error);
    }

    /** Enumerate all already loaded ordinary/archived group/channel dialogs in activated accounts. */
    public static List<Source> listLoadedGroups(int accountFilter) {
        ArrayList<Source> result = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if ((accountFilter >= 0 && a != accountFilter) || !UserConfig.getInstance(a).isClientActivated()) continue;
            final int account = a;
            TLRPC.User owner = UserConfig.getInstance(a).getCurrentUser();
            String accountName = owner == null ? "Аккаунт " + (a + 1)
                    : org.telegram.messenger.UserObject.getUserName(owner);
            ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(a).getAllDialogs();
            if (dialogs == null) continue;
            for (TLRPC.Dialog dialog : dialogs) {
                // Telegram uses negative dialog IDs for group chats and channels.
                if (dialog == null || dialog.id >= 0) continue;
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialog.id);
                if (chat == null || chat.title == null) continue;
                result.add(new Source(account, dialog.id, chat.title, accountName));
            }
        }
        Collections.sort(result, Comparator.comparing(s -> s.title.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * User-triggered, bounded fetch. Each page comes from the selected account and selected peer.
     * This is intentionally text/caption-only: no attached media is downloaded here.
     */
    public void load(Source source, int days, Callback callback) {
        if (cancelled.get()) return;
        if (source == null || callback == null || source.dialogId >= 0 ||
                !UserConfig.getInstance(source.account).isClientActivated()) {
            post(callback, new ArrayList<>(), "Группа или аккаунт недоступны");
            return;
        }
        int safeDays = Math.max(1, Math.min(days, 365));
        int since = (int) (System.currentTimeMillis() / 1000L - safeDays * 86400L);
        ArrayList<Entry> collected = new ArrayList<>();
        fetchPage(source, since, 0, 0, collected, callback);
    }

    private void fetchPage(Source source, int since, int offset, int pageIndex, ArrayList<Entry> entries, Callback cb) {
        if (cancelled.get()) return;
        TLRPC.InputPeer peer = MessagesController.getInstance(source.account).getInputPeer(source.dialogId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            post(cb, entries, "Не удалось получить доступ к истории группы");
            return;
        }
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = PAGE_SIZE;
        req.offset_id = offset;
        req.offset_date = 0;
        req.add_offset = 0;
        req.max_id = 0;
        req.min_id = 0;
        req.hash = 0;
        ConnectionsManager.getInstance(source.account).sendRequest(req, (response, error) -> {
            if (cancelled.get()) return;
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                post(cb, entries, error == null ? "История недоступна" : "Telegram: " + error.text);
                return;
            }
            TLRPC.messages_Messages page = (TLRPC.messages_Messages) response;
            int oldest = Integer.MAX_VALUE;
            boolean reachedDate = false;
            for (TLRPC.Message m : page.messages) {
                if (m == null || m.id <= 0) continue;
                oldest = Math.min(oldest, m.id);
                if (m.date < since) {
                    reachedDate = true;
                    continue;
                }
                if ((m.message != null && !m.message.trim().isEmpty()) ||
                        m.media instanceof TLRPC.TL_messageMediaPhoto ||
                        m.media instanceof TLRPC.TL_messageMediaDocument) {
                    entries.add(new Entry(source.account, source.dialogId, m));
                    if (entries.size() >= MAX_MESSAGES) break;
                }
            }
            if (reachedDate || page.messages.size() < PAGE_SIZE ||
                    entries.size() >= MAX_MESSAGES || pageIndex >= MAX_PAGES - 1 ||
                    oldest == Integer.MAX_VALUE || oldest == offset) {
                Collections.sort(entries, Comparator.comparingInt(e -> e.date));
                post(cb, entries, null);
                return;
            }
            fetchPage(source, since, oldest, pageIndex + 1, entries, cb);
        });
    }

    private void post(Callback cb, List<Entry> entries, String error) {
        if (cancelled.get()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!cancelled.get()) cb.onResult(entries, error);
        });
    }
}
