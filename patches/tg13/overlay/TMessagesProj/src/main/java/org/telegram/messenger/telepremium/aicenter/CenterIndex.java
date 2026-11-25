package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.messenger.telepremium.ai.TPAiStore;

/** Worker-owned index snapshots; callbacks always publish on the UI thread. */
public final class CenterIndex {
  public interface Done {
    void run(String error);
  }

  private static boolean installed;
  private static JSONObject settingsCache;
  private static volatile Set<Integer> excludedCache;

  private static synchronized void observe() {
    if (installed) return;
    installed = true;
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
      final int account = a;
      NotificationCenter.getInstance(a)
          .addObserver(
              (id, acc, args) -> {
                if (args.length < 3 || Boolean.TRUE.equals(args[2])) return;
                @SuppressWarnings("unchecked")
                java.util.ArrayList<Integer> ids =
                    new java.util.ArrayList<>((java.util.ArrayList<Integer>) args[0]);
                long channel = ((Number) args[1]).longValue();
                long owner = UserConfig.getInstance(account).getClientUserId();
                CenterStore.IO.execute(
                    () -> {
                      try {
                        for (JSONObject o : CenterStore.get().list(account, owner, "message")) {
                          CenterData.Message m = CenterData.Message.parse(o);
                          if (ids.contains(m.id)
                              && (channel == 0
                                  ? !m.source.startsWith("c:")
                                  : m.dialog == -channel)) {
                            m.deleted = true;
                            if (settings().optBoolean("removeDeleted", false))
                              CenterStore.get().remove(account, owner, "message", m.qualified());
                            else
                              CenterStore.get()
                                  .put(account, owner, "message", m.qualified(), m.json());
                          }
                        }
                      } catch (Exception e) {
                        FileLog.e(e);
                      }
                    });
              },
              NotificationCenter.messagesDeleted);
    }
  }

  private static final AtomicLong GENERATION = new AtomicLong();
  public final List<CenterData.Message> messages = new ArrayList<>();
  public final List<CenterData.Source> sources = new ArrayList<>();
  public CenterData.Scope scope = new CenterData.Scope();

  public static boolean accountAllowed(int account, long owner) {
    if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || owner <= 0) return false;
    if (!UserConfig.getInstance(account).isClientActivated()
        || UserConfig.getInstance(account).getClientUserId() != owner) return false;
    if (excludedCache == null)
      synchronized (CenterIndex.class) {
        if (excludedCache == null)
          try {
            String raw = TPAiStore.getPrivate("center_scope");
            excludedCache =
                raw.isEmpty()
                    ? Collections.emptySet()
                    : new HashSet<>(CenterData.Scope.parse(new JSONObject(raw)).excluded);
          } catch (Exception e) {
            return false;
          }
      }
    return !excludedCache.contains(account);
  }

  public static synchronized org.json.JSONObject settings() {
    try {
      if (settingsCache == null) {
        String v = TPAiStore.getPrivate("center_settings");
        settingsCache = new JSONObject(v.isEmpty() ? "{}" : v);
      }
      return new JSONObject(settingsCache.toString());
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  public static synchronized void setting(String key, Object value) {
    try {
      JSONObject o = settings();
      o.put(key, value);
      TPAiStore.putPrivate("center_settings", o.toString());
      settingsCache = o;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void load(Done done) {
    observe();
    final long generation = GENERATION.get();
    CenterStore.IO.execute(
        () -> {
          CenterStore.get().prune();
          String error = null;
          List<CenterData.Message> ms = new ArrayList<>();
          List<CenterData.Source> ss = new ArrayList<>();
          try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
              if (!UserConfig.getInstance(a).isClientActivated()) {
                CenterStore.get().purge(a);
                continue;
              }
              long owner = UserConfig.getInstance(a).getClientUserId();
              for (JSONObject o : CenterStore.get().list(a, owner, "source"))
                ss.add(CenterData.Source.parse(o));
              long cutoff =
                  System.currentTimeMillis() / 1000
                      - settings().optInt("retentionDays", 90) * 86400L;
              for (JSONObject o : CenterStore.get().list(a, owner, "message")) {
                CenterData.Message m = CenterData.Message.parse(o);
                if (m.date < cutoff) CenterStore.get().remove(a, owner, "message", m.qualified());
                else ms.add(m);
              }
            }
          } catch (Exception e) {
            error = e.getClass().getSimpleName();
          }
          final String result = error;
          AndroidUtilities.runOnUIThread(
              () -> {
                if (generation != GENERATION.get()) return;
                messages.clear();
                messages.addAll(ms);
                sources.clear();
                sources.addAll(ss);
                try {
                  String raw = TPAiStore.getPrivate("center_scope");
                  if (!raw.isEmpty()) scope = CenterData.Scope.parse(new JSONObject(raw));
                  else
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
                      if (UserConfig.getInstance(a).isClientActivated()) scope.whole.add(a);
                } catch (Exception e) {
                  FileLog.e(e);
                }
                done.run(result);
              });
        });
  }

  public static long generation() {
    return GENERATION.get();
  }

  public void saveScope() {
    excludedCache = new HashSet<>(scope.excluded);
    try {
      TPAiStore.putPrivate("center_scope", scope.json().toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public List<CenterData.Message> readable() {
    List<CenterData.Message> selected = new ArrayList<>(messages);
    selected.removeIf(
        m -> {
          if (!UserConfig.getInstance(m.account).isClientActivated()
              || UserConfig.getInstance(m.account).getClientUserId() != m.owner
              || scope.excluded.contains(m.account)) return true;
          if (m.protectedMedia) return true;
          if (m.dialog < 0) {
            org.telegram.tgnet.TLRPC.Chat chat =
                MessagesController.getInstance(m.account).getChat(-m.dialog);
            if (chat != null && chat.noforwards && !m.kind.equals("text")) return true;
          }
          if (m.cloudNode.isEmpty()) return false;
          try {
            CenterMedia.check(m);
            return false;
          } catch (Exception e) {
            return true;
          }
        });
    return selected;
  }

  public List<CenterData.Message> selected() {
    return CenterData.select(readable(), sources, scope);
  }

  public static void cloudLocked() {
    CenterThumbnails.clear();
    CenterTaskService.stopWork();
    CenterStore.IO.execute(
        () -> {
          try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
              if (UserConfig.getInstance(a).isClientActivated()) {
                long owner = UserConfig.getInstance(a).getClientUserId();
                for (JSONObject o : CenterStore.get().list(a, owner, "message"))
                  if (!o.optString("cloudNode").isEmpty())
                    CenterStore.get()
                        .remove(a, owner, "message", CenterData.Message.parse(o).qualified());
                for (String kind :
                    new String[] {
                      "report", "thread", "chunk", "final", "report_image", "media_result", "job"
                    })
                  for (JSONObject o : CenterStore.get().list(a, owner, kind)) {
                    JSONObject m = o.optJSONObject("message");
                    if (o.optBoolean("cloud") || (m != null && !m.optString("cloudNode").isEmpty()))
                      CenterStore.get().remove(a, owner, kind, o.optString("id"));
                  }
              }
          } catch (Exception e) {
            FileLog.e(e);
          }
        });
  }

  public static void logout(int a) {
    GENERATION.incrementAndGet();
    CenterStore.IO.execute(() -> CenterStore.get().purge(a));
  }

  public void clear(Done done) {
    GENERATION.incrementAndGet();
    messages.clear();
    sources.clear();
    CenterStore.IO.execute(
        () -> {
          CenterStore.get().clear();
          TPAiStore.erasePrivatePrefix("center_set_");
          TPAiStore.erasePrivatePrefix("center_chat_");
          TPAiStore.erasePrivatePrefix("analyzer_chat_v2_");
          AndroidUtilities.runOnUIThread(() -> done.run(null));
        });
  }
}
