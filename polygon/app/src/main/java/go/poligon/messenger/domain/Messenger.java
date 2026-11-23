package go.poligon.messenger.domain;

import java.util.List;

/**
 * Polygon's own messaging contract. Transport-specific classes never leak into the UI.
 */
public interface Messenger {
    final class Conversation {
        public final long id;
        public final String title;
        public final String preview;
        public Conversation(long id, String title, String preview) {
            this.id = id; this.title = title; this.preview = preview;
        }
    }
    final class Entry {
        public final long id;
        public final long chatId;
        public final boolean outgoing;
        public final String text;
        public Entry(long id, long chatId, boolean outgoing, String text) {
            this.id = id; this.chatId = chatId; this.outgoing = outgoing; this.text = text;
        }
    }
    interface Observer {
        void onAuthorization(String step, String message);
        void onConversations(List<Conversation> items);
        void onHistory(long chatId, List<Entry> items);
        void onError(String message);
    }
    void start(Observer observer);
    void authenticatePhone(String phone);
    void authenticateCode(String code);
    void authenticatePassword(String password);
    void loadConversations();
    void loadHistory(long chatId);
    void sendText(long chatId, String text);
    void close();
}
