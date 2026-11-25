package org.telegram.messenger.telepremium.aicenter;

import android.util.Base64;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.*;
import org.telegram.messenger.telepremium.cloud.TelePremiumServicePeerRegistry;
import org.telegram.tgnet.*;

/** One sequential pipeline per account. No markRead, send, join or media requests. */
public final class CenterSync {
  private boolean discoverAndLoad;

  public void discoverThenLoad() {
    discoverAndLoad = true;
    discover();
  }

  public interface Progress {
    void changed(String error);
  }

  private final long privacyEpoch = CenterIndex.generation();
  private final CenterData.Scope scope;
  private String lastError;
  private final CenterIndex index;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final int[] requests = new int[UserConfig.MAX_ACCOUNT_COUNT];
  private int active;
  private final Progress callback;
  private final Map<Integer, List<CenterData.Source>> forums = new HashMap<>();

  public CenterSync(CenterIndex index, Progress cb) {
    this.index = index;
    CenterData.Scope snapshot;
    try {
      snapshot = CenterData.Scope.parse(index.scope.json());
    } catch (Exception e) {
      snapshot = new CenterData.Scope();
    }
    this.scope = snapshot;
    callback = cb;
  }

  public void cancel() {
    cancelled.set(true);
    for (int a = 0; a < requests.length; a++)
      if (requests[a] != 0) ConnectionsManager.getInstance(a).cancelRequest(requests[a], true);
  }

  private boolean valid(int a, long owner) {
    return !cancelled.get()
        && privacyEpoch == CenterIndex.generation()
        && UserConfig.getInstance(a).isClientActivated()
        && CenterIndex.accountAllowed(a, owner)
        && !scope.excluded.contains(a);
  }

  public void discover() {
    active = 0;
    for (int a = 0; a < requests.length; a++)
      if (UserConfig.getInstance(a).isClientActivated() && !scope.excluded.contains(a)) active++;
    if (active == 0) {
      callback.changed(null);
      return;
    }
    for (int a = 0; a < requests.length; a++)
      if (UserConfig.getInstance(a).isClientActivated() && !scope.excluded.contains(a))
        dialogs(
            a, UserConfig.getInstance(a).getClientUserId(), 0, 0, 0, new TLRPC.TL_inputPeerEmpty());
  }

  private void finish(String error) {
    if (cancelled.get()) return;
    if (error != null) lastError = error;
    if (--active <= 0)
      index.load(
          e -> {
            if (discoverAndLoad) {
              discoverAndLoad = false;
              load();
            } else callback.changed(lastError == null ? e : lastError);
          });
  }

  private void dialogs(int a, long owner, int folder, int offset, int date, TLRPC.InputPeer peer) {
    if (!valid(a, owner)) {
      finish(null);
      return;
    }
    TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
    req.flags = 2;
    req.folder_id = folder;
    req.limit = 100;
    req.offset_id = offset;
    req.offset_date = date;
    req.offset_peer = peer;
    requests[a] =
        ConnectionsManager.getInstance(a)
            .sendRequest(
                req,
                (res, err) ->
                    AndroidUtilities.runOnUIThread(
                        () -> {
                          if (!valid(a, owner)) {
                            finish("historyUnavailable");
                            return;
                          }
                          if (err != null || !(res instanceof TLRPC.messages_Dialogs)) {
                            finish(err == null ? "historyUnavailable" : err.text);
                            return;
                          }
                          TLRPC.messages_Dialogs page = (TLRPC.messages_Dialogs) res;
                          MessagesController mc = MessagesController.getInstance(a);
                          mc.putUsers(page.users, false);
                          mc.putChats(page.chats, false);
                          ArrayList<CenterData.Source> batch = new ArrayList<>();
                          for (TLRPC.Dialog d : page.dialogs) {
                            long did = d.peer == null ? d.id : DialogObject.getPeerDialogId(d.peer);
                            CenterData.Source s = source(a, did, d, folder);
                            if (s != null) batch.add(s);
                          }
                          CenterStore.IO.execute(
                              () -> {
                                try {
                                  for (CenterData.Source s : batch) {
                                    CenterData.Source old = null;
                                    for (CenterData.Source q : index.sources)
                                      if (q.key().equals(s.key())) {
                                        old = q;
                                        break;
                                      }
                                    if (old != null) {
                                      s.loadedAt = old.loadedAt;
                                      s.rangeFrom = old.rangeFrom;
                                      s.rangeTo = old.rangeTo;
                                      s.offset = old.offset;
                                      s.lastId = old.lastId;
                                      s.readAt = old.readAt;
                                      s.complete = old.complete;
                                      s.total = old.total;
                                    }
                                    if (valid(a, owner))
                                      CenterStore.get().put(a, owner, "source", s.key(), s.json());
                                  }
                                } catch (Exception e) {
                                  FileLog.e(e);
                                }
                                AndroidUtilities.runOnUIThread(
                                    () -> {
                                      if (!valid(a, owner)) {
                                        finish("historyUnavailable");
                                        return;
                                      }
                                      if (page.dialogs.size() >= 100 && !page.messages.isEmpty()) {
                                        TLRPC.Message last =
                                            page.messages.get(page.messages.size() - 1);
                                        long did = DialogObject.getPeerDialogId(last.peer_id);
                                        TLRPC.InputPeer next = mc.getInputPeer(did);
                                        if (last.id != offset && next != null) {
                                          AndroidUtilities.runOnUIThread(
                                              () ->
                                                  dialogs(
                                                      a, owner, folder, last.id, last.date, next),
                                              650);
                                          return;
                                        }
                                      }
                                      if (folder == 0)
                                        dialogs(a, owner, 1, 0, 0, new TLRPC.TL_inputPeerEmpty());
                                      else topics(a, owner, 0, 0, 0, 0);
                                    });
                              });
                        }));
  }

  private void topics(int a, long owner, int n, int offsetTopic, int offsetId, int offsetDate) {
    List<CenterData.Source> fs = forums.get(a);
    if (fs == null || n >= fs.size()) {
      finish(null);
      return;
    }
    CenterData.Source parent = fs.get(n);
    org.telegram.tgnet.tl.TL_forum.TL_messages_getForumTopics req =
        new org.telegram.tgnet.tl.TL_forum.TL_messages_getForumTopics();
    req.peer = MessagesController.getInstance(a).getInputPeer(parent.dialog);
    req.offset_topic = offsetTopic;
    req.offset_id = offsetId;
    req.offset_date = offsetDate;
    req.limit = 100;
    requests[a] =
        ConnectionsManager.getInstance(a)
            .sendRequest(
                req,
                (res, err) ->
                    AndroidUtilities.runOnUIThread(
                        () -> {
                          if (!valid(a, owner)) {
                            finish("historyUnavailable");
                            return;
                          }
                          if (err != null || !(res instanceof TLRPC.TL_messages_forumTopics)) {
                            topics(a, owner, n + 1, 0, 0, 0);
                            return;
                          }
                          TLRPC.TL_messages_forumTopics page = (TLRPC.TL_messages_forumTopics) res;
                          CenterStore.IO.execute(
                              () -> {
                                try {
                                  for (TLRPC.TL_forumTopic topic : page.topics) {
                                    if (topic instanceof TLRPC.TL_forumTopicDeleted) continue;
                                    CenterData.Source s = CenterData.Source.parse(parent.json());
                                    s.topic = topic.id;
                                    s.type = "topic";
                                    s.title = parent.title + " / " + topic.title;
                                    if (valid(a, owner))
                                      CenterStore.get().put(a, owner, "source", s.key(), s.json());
                                  }
                                } catch (Exception e) {
                                  FileLog.e(e);
                                }
                                AndroidUtilities.runOnUIThread(
                                    () -> {
                                      if (!valid(a, owner)) {
                                        finish("historyUnavailable");
                                        return;
                                      }
                                      if (page.topics.size() >= 100) {
                                        TLRPC.TL_forumTopic last =
                                            page.topics.get(page.topics.size() - 1);
                                        if (last.id != offsetTopic) {
                                          AndroidUtilities.runOnUIThread(
                                              () ->
                                                  topics(
                                                      a,
                                                      owner,
                                                      n,
                                                      last.id,
                                                      last.top_message,
                                                      last.date),
                                              650);
                                          return;
                                        }
                                      }
                                      topics(a, owner, n + 1, 0, 0, 0);
                                    });
                              });
                        }));
  }

  private CenterData.Source source(int a, long did, TLRPC.Dialog d, int folder) {
    if (did == 0
        || DialogObject.isEncryptedDialog(did)
        || TelePremiumServicePeerRegistry.isServicePeer(a, did)) return null;
    MessagesController mc = MessagesController.getInstance(a);
    CenterData.Source s = new CenterData.Source();
    s.account = a;
    s.owner = UserConfig.getInstance(a).getClientUserId();
    s.dialog = did;
    s.accountName = UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser());
    s.archived = folder == 1;
    s.pinned = d.pinned;
    s.muted = mc.isDialogMuted(did, 0);
    if (did < 0) {
      TLRPC.Chat c = mc.getChat(-did);
      if (c == null || c.left || c.kicked || c.deactivated) return null;
      // Vault channels are never an analyzer source, including renamed registered service peers.
      if (c.title != null
          && (c.title.startsWith("TelePremium Vault")
              || c.title.startsWith("TelePremium Cloud Storage"))) return null;
      s.title = c.title;
      s.type = ChatObject.isChannel(c) ? (c.megagroup ? "supergroup" : "channel") : "group";
      s.canonical = (ChatObject.isChannel(c) ? "c:" : "g:") + (-did);
      if (c.forum) {
        List<CenterData.Source> fs = forums.computeIfAbsent(a, k -> new ArrayList<>());
        if (fs.stream().noneMatch(q -> q.dialog == did)) fs.add(s);
      }
      s.role = c.creator ? "owner" : c.admin_rights != null ? "admin" : "member";
    } else {
      if (!scope.includePrivate) return null;
      TLRPC.User u = mc.getUser(did);
      if (u == null) return null;
      s.title = UserObject.getUserName(u);
      s.type = "private";
      s.canonical = "p:" + Math.min(s.owner, did) + ":" + Math.max(s.owner, did);
    }
    for (MessagesController.DialogFilter f : mc.getDialogFilters())
      if (f.includesDialog(AccountInstance.getInstance(a), did, d)) s.folder += f.name + "\n";
    return s;
  }

  public void load() {
    Map<Integer, List<CenterData.Source>> per = new HashMap<>();
    Set<String> unique = new HashSet<>();
    ArrayList<CenterData.Source> sorted = new ArrayList<>(index.sources);
    sorted.sort(
        Comparator.comparingInt(
            s -> !s.available ? 3 : "owner".equals(s.role) ? 0 : "admin".equals(s.role) ? 1 : 2));
    Set<String> wholePeers = new HashSet<>();
    for (CenterData.Source s : sorted)
      if (s.topic == 0 && scope.requests(s)) wholePeers.add(s.canonical);
    for (CenterData.Source s : sorted)
      if ((s.topic == 0 || !wholePeers.contains(s.canonical))
          && scope.requests(s)
          && unique.add(s.canonical + ":" + s.topic))
        per.computeIfAbsent(s.account, k -> new ArrayList<>()).add(s);
    active = per.size();
    if (active == 0) {
      callback.changed(null);
      return;
    }
    for (List<CenterData.Source> list : per.values()) nextSource(list, 0);
  }

  private void nextSource(List<CenterData.Source> list, int n) {
    if (cancelled.get()) return;
    if (n >= list.size()) {
      finish(null);
      return;
    }
    CenterData.Source s = list.get(n);
    int budget = Math.max(100, CenterIndex.settings().optInt("messageBudget", 20000));
    boolean resume = !s.complete && s.rangeFrom == scope.from && s.rangeTo == scope.to;
    s.rangeFrom = scope.from;
    s.rangeTo = scope.to;
    history(list, n, s, resume ? s.offset : 0, 0, budget);
  }

  private void history(
      List<CenterData.Source> list, int n, CenterData.Source s, int offset, int count, int budget) {
    if (!valid(s.account, s.owner)) {
      finish("historyUnavailable");
      return;
    }
    TLObject req;
    if (s.topic > 0) {
      TLRPC.TL_messages_getReplies q = new TLRPC.TL_messages_getReplies();
      q.peer = MessagesController.getInstance(s.account).getInputPeer(s.dialog);
      q.msg_id = s.topic;
      q.offset_id = offset;
      q.limit = 100;
      req = q;
    } else {
      TLRPC.TL_messages_getHistory q = new TLRPC.TL_messages_getHistory();
      q.peer = MessagesController.getInstance(s.account).getInputPeer(s.dialog);
      q.offset_id = offset;
      q.limit = 100;
      req = q;
    }
    requests[s.account] =
        ConnectionsManager.getInstance(s.account)
            .sendRequest(
                req,
                (res, err) ->
                    AndroidUtilities.runOnUIThread(
                        () -> {
                          if (!valid(s.account, s.owner)) {
                            finish("historyUnavailable");
                            return;
                          }
                          if (err != null || !(res instanceof TLRPC.messages_Messages)) {
                            if (err != null
                                && err.text != null
                                && err.text.startsWith("FLOOD_WAIT_")) {
                              int wait = Utilities.parseInt(err.text);
                              AndroidUtilities.runOnUIThread(
                                  () -> history(list, n, s, offset, count, budget),
                                  Math.max(1000L, wait * 1000L));
                              return;
                            }
                            s.complete = false;
                            s.available = false;
                            CenterStore.IO.execute(
                                () -> {
                                  try {
                                    CenterStore.get()
                                        .put(s.account, s.owner, "source", s.key(), s.json());
                                  } catch (Exception ignored) {
                                  }
                                });
                            lastError = err == null ? "historyUnavailable" : err.text;
                            nextSource(list, n + 1);
                            return;
                          }
                          TLRPC.messages_Messages page = (TLRPC.messages_Messages) res;
                          MessagesController mc = MessagesController.getInstance(s.account);
                          mc.putUsers(page.users, false);
                          mc.putChats(page.chats, false);
                          List<CenterData.Message> batch = new ArrayList<>();
                          boolean reached = false;
                          int oldest = 0;
                          for (TLRPC.Message m : page.messages) {
                            if (m.id <= 0) continue;
                            oldest = oldest == 0 ? m.id : Math.min(oldest, m.id);
                            if (m.date < scope.from) {
                              reached = true;
                              continue;
                            }
                            if (m.date > scope.to
                                || m instanceof TLRPC.TL_messageService
                                || m instanceof TLRPC.TL_messageEmpty) continue;
                            CenterData.Message data = convert(s, m);
                            if (data != null) batch.add(data);
                          }
                          final int next = oldest;
                          final boolean end =
                              reached || page.messages.size() < 100 || next == 0 || next == offset;
                          CenterStore.IO.execute(
                              () -> {
                                String error = null;
                                try {
                                  if (valid(s.account, s.owner)) {
                                    CenterStore.get().putMessages(batch);
                                    s.loadedAt = System.currentTimeMillis();
                                    s.offset = next;
                                    s.total = Math.max(s.total, page.count);
                                    s.complete = end;
                                    s.available = true;
                                    s.lastId =
                                        Math.max(
                                            s.lastId,
                                            page.messages.isEmpty() ? 0 : page.messages.get(0).id);
                                    CenterStore.get()
                                        .put(s.account, s.owner, "source", s.key(), s.json());
                                  }
                                } catch (Exception e) {
                                  error = e.getClass().getSimpleName();
                                }
                                final String failure = error;
                                AndroidUtilities.runOnUIThread(
                                    () -> {
                                      if (!valid(s.account, s.owner)) {
                                        finish("historyUnavailable");
                                        return;
                                      }
                                      if (failure != null) {
                                        lastError = failure;
                                        nextSource(list, n + 1);
                                        return;
                                      }
                                      if (end
                                          || count + page.messages.size() >= budget
                                          || CenterStore.get().bytes()
                                              > CenterIndex.settings().optInt("indexMb", 256)
                                                  * 1024L
                                                  * 1024L) nextSource(list, n + 1);
                                      else
                                        AndroidUtilities.runOnUIThread(
                                            () ->
                                                history(
                                                    list,
                                                    n,
                                                    s,
                                                    next,
                                                    count + page.messages.size(),
                                                    budget),
                                            650);
                                    });
                              });
                        }));
  }

  public static CenterData.Message convert(CenterData.Source s, TLRPC.Message m) {
    TLRPC.Chat chat =
        s.dialog < 0 ? MessagesController.getInstance(s.account).getChat(-s.dialog) : null;
    boolean protectedMedia =
        m.noforwards
            || (chat != null && chat.noforwards)
            || m.ttl_period > 0
            || (m.media != null && m.media.ttl_seconds > 0);
    if (protectedMedia && m.media != null && !(m.media instanceof TLRPC.TL_messageMediaEmpty))
      return null;
    CenterData.Message d = new CenterData.Message();
    d.account = s.account;
    d.owner = s.owner;
    d.dialog = s.dialog;
    d.id = m.id;
    d.date = m.date;
    d.source = s.canonical;
    d.sourceTitle = s.title;
    d.accountName = s.accountName;
    d.topic =
        m.reply_to != null && m.reply_to.forum_topic
            ? (m.reply_to.reply_to_top_id != 0
                ? m.reply_to.reply_to_top_id
                : m.reply_to.reply_to_msg_id)
            : s.topic;
    if (m.from_id != null)
      d.author =
          m.from_id.user_id != 0
              ? m.from_id.user_id
              : -(m.from_id.channel_id != 0 ? m.from_id.channel_id : m.from_id.chat_id);
    if (d.author == 0 && m.peer_id != null && m.peer_id.channel_id != 0)
      d.author = -m.peer_id.channel_id;
    TLRPC.User u =
        d.author > 0 ? MessagesController.getInstance(s.account).getUser(d.author) : null;
    TLRPC.Chat ac =
        d.author < 0 ? MessagesController.getInstance(s.account).getChat(-d.author) : null;
    d.authorName =
        u != null
            ? ContactsController.formatName(u.first_name, u.last_name)
            : ac != null ? ac.title : s.title;
    if (d.authorName == null || d.authorName.isEmpty()) d.authorName = CenterText.get("authors");
    d.own = false;
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated()
          && d.author == UserConfig.getInstance(a).getClientUserId()) d.own = true;
    d.text = m.message == null ? "" : m.message;
    d.protectedMedia = protectedMedia;
    d.personal = CenterReports.personal(d.text);
    if (m.media instanceof TLRPC.TL_messageMediaPhoto) {
      d.kind = "photo";
      TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) m.media).photo;
      if (photo != null) d.mediaKey = "photo:" + photo.id;
    } else if (m.media instanceof TLRPC.TL_messageMediaDocument) {
      TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) m.media).document;
      if (doc != null) {
        String mime = doc.mime_type == null ? "" : doc.mime_type;
        d.mime = mime;
        d.bytes = doc.size;
        d.mediaKey = "document:" + doc.id + ":" + doc.size;
        d.kind =
            mime.startsWith("video/")
                ? "video"
                : mime.startsWith("audio/")
                    ? "audio"
                    : mime.startsWith("image/")
                        ? "image"
                        : mime.equals("application/pdf") ? "pdf" : "document";
        for (TLRPC.DocumentAttribute at : doc.attributes) {
          if (at instanceof TLRPC.TL_documentAttributeAudio) {
            d.duration = (long) ((TLRPC.TL_documentAttributeAudio) at).duration * 1000;
            d.kind = ((TLRPC.TL_documentAttributeAudio) at).voice ? "voice" : "audio";
          }
          if (at instanceof TLRPC.TL_documentAttributeVideo) {
            d.duration = (long) ((TLRPC.TL_documentAttributeVideo) at).duration * 1000;
            if (((TLRPC.TL_documentAttributeVideo) at).round_message) d.kind = "round";
          }
        }
      }
    } else if (m.media instanceof TLRPC.TL_messageMediaPoll) {
      d.kind = "poll";
      TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) m.media;
      StringBuilder text = new StringBuilder();
      if (poll.poll != null) {
        text.append(poll.poll.question.text);
        for (TLRPC.PollAnswer answer : poll.poll.answers) {
          text.append("\n").append(answer.text.text);
          if (poll.results != null)
            for (TLRPC.PollAnswerVoters result : poll.results.results)
              if (java.util.Arrays.equals(answer.option, result.option))
                text.append(" — ").append(result.voters);
        }
      }
      if (poll.results != null)
        text.append("\n")
            .append(CenterText.get("polls"))
            .append(": ")
            .append(poll.results.total_voters);
      d.content = text.toString();
    } else if (m.media instanceof TLRPC.TL_messageMediaWebPage) {
      d.kind = "link";
      TLRPC.WebPage page = ((TLRPC.TL_messageMediaWebPage) m.media).webpage;
      if (page != null)
        d.content =
            (page.url == null ? "" : page.url)
                + "\n"
                + (page.title == null ? "" : page.title)
                + "\n"
                + (page.site_name == null ? "" : page.site_name);
    }
    if (m.fwd_from != null && m.fwd_from.from_id != null) {
      long peer = DialogObject.getPeerDialogId(m.fwd_from.from_id);
      TLRPC.Chat origin =
          peer < 0 ? MessagesController.getInstance(s.account).getChat(-peer) : null;
      TLRPC.User sender = peer > 0 ? MessagesController.getInstance(s.account).getUser(peer) : null;
      d.forwardSource =
          origin != null
              ? origin.title
              : sender != null
                  ? ContactsController.formatName(sender.first_name, sender.last_name)
                  : CenterText.get("forwarded");
      d.content += "\n" + CenterText.get("forwarded") + ": " + d.forwardSource;
    }
    d.replyTo = m.reply_to == null ? 0 : m.reply_to.reply_to_msg_id;
    d.replies = m.replies == null ? 0 : m.replies.replies;
    d.forwards = m.forwards;
    if (m.reactions != null)
      for (TLRPC.ReactionCount r : m.reactions.results) d.reactions += r.count;
    SerializedData serial = new SerializedData();
    m.serializeToStream(serial);
    d.transport = Base64.encodeToString(serial.toByteArray(), Base64.NO_WRAP);
    serial.cleanup();
    return d;
  }

  /** Explicit single-message refresh for archived media; never changes read state. */
  public static void resolve(CenterData.Message message, AtomicBoolean cancel) throws Exception {
    if (!CenterIndex.accountAllowed(message.account, message.owner)) throw new SecurityException();
    MessagesController controller = MessagesController.getInstance(message.account);
    TLRPC.Chat chat = message.dialog < 0 ? controller.getChat(-message.dialog) : null;
    ArrayList<Integer> ids = new ArrayList<>();
    ids.add(message.id);
    TLObject request;
    if (ChatObject.isChannel(chat)) {
      TLRPC.TL_channels_getMessages q = new TLRPC.TL_channels_getMessages();
      q.channel = controller.getInputChannel(chat);
      q.id = ids;
      request = q;
    } else {
      TLRPC.TL_messages_getMessages q = new TLRPC.TL_messages_getMessages();
      q.id = ids;
      request = q;
    }
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    TLRPC.Message[] found = {null};
    String[] error = {null};
    int requestId =
        ConnectionsManager.getInstance(message.account)
            .sendRequest(
                request,
                (response, failure) -> {
                  if (failure != null) error[0] = failure.text;
                  if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
                    for (TLRPC.Message item : messages.messages)
                      if (item.id == message.id && !(item instanceof TLRPC.TL_messageEmpty)) {
                        long peer = DialogObject.getPeerDialogId(item.peer_id);
                        if (peer == message.dialog
                            || (message.dialog > 0
                                && peer == message.owner
                                && item.from_id != null
                                && item.from_id.user_id == message.dialog)) found[0] = item;
                      }
                  }
                  latch.countDown();
                });
    try {
      long deadline = System.currentTimeMillis() + 30000;
      while (!latch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        if (cancel.get()
            || System.currentTimeMillis() > deadline
            || !CenterIndex.accountAllowed(message.account, message.owner))
          throw new InterruptedException();
      }
      if (found[0] == null) {
        if (error[0] == null) message.deleted = true;
        throw new java.io.IOException(error[0] == null ? CenterText.get("deleted") : error[0]);
      }
      SerializedData data = new SerializedData();
      try {
        found[0].serializeToStream(data);
        message.transport = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        message.protectedMedia = found[0].noforwards;
      } finally {
        data.cleanup();
      }
    } finally {
      ConnectionsManager.getInstance(message.account).cancelRequest(requestId, true);
    }
  }

  public static TLRPC.Message original(CenterData.Message m) {
    try {
      SerializedData d = new SerializedData(Base64.decode(m.transport, Base64.NO_WRAP));
      try {
        return TLRPC.Message.TLdeserialize(d, d.readInt32(true), true);
      } finally {
        d.cleanup();
      }
    } catch (Exception e) {
      return null;
    }
  }
}
