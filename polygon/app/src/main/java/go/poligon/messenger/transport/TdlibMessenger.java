package go.poligon.messenger.transport;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import go.poligon.messenger.BuildConfig;
import go.poligon.messenger.domain.Messenger;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Telegram-compatible transport adapter. No UI or product branding is inherited.
 * TDLib is the only upstream runtime component in this new Android project.
 */
public final class TdlibMessenger implements Messenger {
    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Client client;
    private Observer observer;
    private volatile boolean authorized;

    public TdlibMessenger(Context context) { this.context = context.getApplicationContext(); }
    private void post(Runnable task) { ui.post(task); }
    private void fail(String msg) { post(() -> { if (observer != null) observer.onError(msg); }); }
    private void state(String step, String text) {
        post(() -> { if (observer != null) observer.onAuthorization(step, text); });
    }
    private void request(TdApi.Function f, Client.ResultHandler handler) {
        if (client == null) { fail("Соединение ещё не готово"); return; }
        client.send(f, result -> {
            if (result instanceof TdApi.Error) {
                TdApi.Error e = (TdApi.Error) result;
                fail(e.code + ": " + e.message);
            } else if (handler != null) handler.onResult(result);
        });
    }

    @Override public void start(Observer observer) {
        this.observer = observer;
        if (BuildConfig.API_ID <= 0 || BuildConfig.API_HASH.isEmpty()) {
            state("configuration", "Для авторизации нужны POLYGON_API_ID и POLYGON_API_HASH в сборке.");
            return;
        }
        try {
            client = Client.create(this::onUpdate, e -> fail("TDLib: " + e), e -> fail("TDLib: " + e));
            request(new TdApi.GetAuthorizationState(), this::auth);
        } catch (Throwable ex) {
            fail("Не удалось загрузить TDLib: " + ex.getMessage());
        }
    }

    private void onUpdate(TdApi.Object item) {
        if (item instanceof TdApi.UpdateAuthorizationState) {
            auth(((TdApi.UpdateAuthorizationState) item).authorizationState);
        } else if (item instanceof TdApi.UpdateNewMessage) {
            TdApi.Message message = ((TdApi.UpdateNewMessage) item).message;
            loadHistory(message.chatId);
            loadConversations();
        } else if (item instanceof TdApi.UpdateNewChat ||
                   item instanceof TdApi.UpdateChatLastMessage ||
                   item instanceof TdApi.UpdateChatTitle) {
            loadConversations();
        }
    }

    private void auth(TdApi.Object result) {
        if (!(result instanceof TdApi.AuthorizationState)) return;
        TdApi.AuthorizationState a = (TdApi.AuthorizationState) result;
        if (a instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            File directory = new File(context.getFilesDir(), "polygon-transport");
            File files = new File(directory, "files");
            directory.mkdirs(); files.mkdirs();
            TdApi.SetTdlibParameters p = new TdApi.SetTdlibParameters();
            p.useTestDc = false;
            p.databaseDirectory = directory.getAbsolutePath();
            p.filesDirectory = files.getAbsolutePath();
            p.databaseEncryptionKey = new byte[0];
            p.useFileDatabase = true;
            p.useChatInfoDatabase = true;
            p.useMessageDatabase = true;
            p.useSecretChats = true;
            p.apiId = BuildConfig.API_ID;
            p.apiHash = BuildConfig.API_HASH;
            p.systemLanguageCode = "ru";
            p.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
            p.systemVersion = Build.VERSION.RELEASE;
            p.applicationVersion = BuildConfig.VERSION_NAME;
            request(p, null);
            state("connecting", "Инициализация защищённого соединения");
        } else if (a instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            state("phone", "Введите номер телефона");
        } else if (a instanceof TdApi.AuthorizationStateWaitCode) {
            state("code", "Введите код подтверждения");
        } else if (a instanceof TdApi.AuthorizationStateWaitPassword) {
            state("password", "Введите пароль двухэтапной проверки");
        } else if (a instanceof TdApi.AuthorizationStateReady) {
            authorized = true;
            state("ready", "Подключено");
            loadConversations();
        } else if (a instanceof TdApi.AuthorizationStateClosed) {
            authorized = false;
            state("closed", "Сессия завершена");
        } else {
            state("connecting", "Установка соединения…");
        }
    }

    @Override public void authenticatePhone(String phone) {
        if (phone == null || phone.trim().isEmpty()) { fail("Укажите телефон"); return; }
        TdApi.SetAuthenticationPhoneNumber p = new TdApi.SetAuthenticationPhoneNumber();
        p.phoneNumber = phone.trim();
        request(p, null);
    }
    @Override public void authenticateCode(String code) {
        TdApi.CheckAuthenticationCode p = new TdApi.CheckAuthenticationCode();
        p.code = code == null ? "" : code.trim();
        request(p, null);
    }
    @Override public void authenticatePassword(String password) {
        TdApi.CheckAuthenticationPassword p = new TdApi.CheckAuthenticationPassword();
        p.password = password == null ? "" : password;
        request(p, null);
    }

    private static String messagePreview(TdApi.Message message) {
        if (message == null) return "";
        if (message.content instanceof TdApi.MessageText) {
            TdApi.FormattedText t = ((TdApi.MessageText) message.content).text;
            return t == null ? "" : t.text;
        }
        return "Вложение";
    }

    @Override public void loadConversations() {
        if (!authorized) return;
        request(new TdApi.LoadChats(new TdApi.ChatListMain(), 100), ignored -> {});
        request(new TdApi.GetChats(new TdApi.ChatListMain(), 100), o -> {
            if (!(o instanceof TdApi.Chats)) return;
            long[] ids = ((TdApi.Chats) o).chatIds;
            if (ids == null || ids.length == 0) {
                post(() -> observer.onConversations(Collections.emptyList()));
                return;
            }
            List<Conversation> chats = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger remaining = new AtomicInteger(ids.length);
            for (long id : ids) {
                request(new TdApi.GetChat(id), result -> {
                    if (result instanceof TdApi.Chat) {
                        TdApi.Chat chat = (TdApi.Chat) result;
                        chats.add(new Conversation(chat.id, chat.title, messagePreview(chat.lastMessage)));
                    }
                    if (remaining.decrementAndGet() == 0) {
                        List<Conversation> sorted = new ArrayList<>();
                        for (long ordered : ids) for (Conversation c : chats)
                            if (c.id == ordered) sorted.add(c);
                        post(() -> observer.onConversations(sorted));
                    }
                });
            }
        });
    }

    @Override public void loadHistory(long chatId) {
        if (!authorized) return;
        request(new TdApi.GetChatHistory(chatId, 0, 0, 50, false), result -> {
            if (!(result instanceof TdApi.Messages)) return;
            TdApi.Message[] messages = ((TdApi.Messages) result).messages;
            List<Entry> entries = new ArrayList<>();
            if (messages != null) for (int i = messages.length - 1; i >= 0; i--) {
                TdApi.Message m = messages[i];
                entries.add(new Entry(m.id, chatId, m.isOutgoing, messagePreview(m)));
            }
            post(() -> observer.onHistory(chatId, entries));
        });
    }

    @Override public void sendText(long chatId, String text) {
        if (!authorized || text == null || text.trim().isEmpty()) return;
        TdApi.SendMessage msg = new TdApi.SendMessage();
        msg.chatId = chatId;
        TdApi.InputMessageText body = new TdApi.InputMessageText();
        body.text = new TdApi.FormattedText(text.trim(), new TdApi.TextEntity[0]);
        msg.inputMessageContent = body;
        request(msg, r -> loadHistory(chatId));
    }

    @Override public void close() {
        if (client != null) client.send(new TdApi.Close(), result -> {});
        client = null;
    }
}
