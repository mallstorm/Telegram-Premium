package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/** Transport-independent, account-qualified data. No Telegram sessions or telephone numbers. */
public final class CenterData {
  private CenterData() {}

  public static final class Source {
    public int account, topic, lastId, offset, total;
    public long owner, dialog, readAt, rangeFrom, rangeTo, loadedAt;
    public String canonical = "",
        title = "",
        accountName = "",
        type = "group",
        role = "member",
        folder = "";
    public boolean pinned, archived, muted, available = true, complete;

    public boolean covers(long from, long to) {
      return type.equals("device")
          || type.equals("cloud")
          || complete && rangeFrom <= from && rangeTo >= to;
    }

    public String key() {
      return dialog == 0 ? account + ":local:" + canonical : account + ":" + dialog + ":" + topic;
    }

    public JSONObject json() throws JSONException {
      return new JSONObject()
          .put("account", account)
          .put("owner", owner)
          .put("dialog", dialog)
          .put("topic", topic)
          .put("canonical", canonical)
          .put("title", title)
          .put("accountName", accountName)
          .put("type", type)
          .put("role", role)
          .put("folder", folder)
          .put("pinned", pinned)
          .put("archived", archived)
          .put("muted", muted)
          .put("loadedAt", loadedAt)
          .put("rangeFrom", rangeFrom)
          .put("rangeTo", rangeTo)
          .put("available", available)
          .put("readAt", readAt)
          .put("lastId", lastId)
          .put("offset", offset)
          .put("total", total)
          .put("complete", complete);
    }

    public static Source parse(JSONObject o) {
      Source s = new Source();
      s.account = o.optInt("account");
      s.owner = o.optLong("owner");
      s.dialog = o.optLong("dialog");
      s.topic = o.optInt("topic");
      s.canonical = o.optString("canonical");
      s.title = o.optString("title");
      s.accountName = o.optString("accountName");
      s.type = o.optString("type", "group");
      s.role = o.optString("role", "member");
      s.folder = o.optString("folder");
      s.pinned = o.optBoolean("pinned");
      s.archived = o.optBoolean("archived");
      s.muted = o.optBoolean("muted");
      s.available = o.optBoolean("available", true);
      s.readAt = o.optLong("readAt");
      s.lastId = o.optInt("lastId");
      s.loadedAt = o.optLong("loadedAt");
      s.rangeFrom = o.optLong("rangeFrom");
      s.rangeTo = o.optLong("rangeTo");
      s.offset = o.optInt("offset");
      s.total = o.optInt("total");
      s.complete = o.optBoolean("complete");
      return s;
    }
  }

  public static final class Message {
    public int account, id, date, topic, replies, replyTo, forwards, reactions, pages;
    public long owner, dialog, author, bytes, duration;
    public String source = "",
        sourceTitle = "",
        accountName = "",
        authorName = "",
        text = "",
        kind = "text",
        ocr = "",
        transcript = "",
        description = "",
        forwardSource = "",
        hash = "",
        mime = "",
        localUri = "",
        cloudNode = "",
        contentHash = "",
        previewBase = "";
    public long sceneHash;
    public JSONObject pageEvidence = new JSONObject(),
        frameEvidence = new JSONObject(),
        mediaAnalysis = new JSONObject();
    public final java.util.List<Long> frameTimes = new java.util.ArrayList<>();
    public boolean own, deleted, protectedMedia, personal;

    /** Original TL message, encrypted at rest; not included in provider payloads. */
    public String transport = "", mediaKey = "", content = "", transcriptKey = "";

    public String key() {
      return source + ":" + id;
    }

    public String sourceKey() {
      return dialog == 0 ? account + ":local:" + source : account + ":" + dialog + ":" + topic;
    }

    public String qualified() {
      return account + ":" + dialog + ":" + id;
    }

    public String searchable() {
      return text + "\n" + content + "\n" + ocr + "\n" + transcript + "\n" + description;
    }

    public JSONObject json() throws JSONException {
      return new JSONObject()
          .put("account", account)
          .put("owner", owner)
          .put("dialog", dialog)
          .put("id", id)
          .put("date", date)
          .put("topic", topic)
          .put("source", source)
          .put("sourceTitle", sourceTitle)
          .put("accountName", accountName)
          .put("author", author)
          .put("authorName", authorName)
          .put("text", text)
          .put("kind", kind)
          .put("ocr", ocr)
          .put("transcript", transcript)
          .put("description", description)
          .put("forwardSource", forwardSource)
          .put("mediaAnalysis", mediaAnalysis)
          .put("pageEvidence", pageEvidence)
          .put("frameEvidence", frameEvidence)
          .put("frameTimes", new JSONArray(frameTimes))
          .put("contentHash", contentHash)
          .put("previewBase", previewBase)
          .put("mime", mime)
          .put("localUri", localUri)
          .put("cloudNode", cloudNode)
          .put("hash", hash)
          .put("sceneHash", sceneHash)
          .put("bytes", bytes)
          .put("duration", duration)
          .put("own", own)
          .put("deleted", deleted)
          .put("protectedMedia", protectedMedia)
          .put("personal", personal)
          .put("transport", transport)
          .put("mediaKey", mediaKey)
          .put("transcriptKey", transcriptKey)
          .put("content", content)
          .put("replyTo", replyTo)
          .put("replies", replies)
          .put("forwards", forwards)
          .put("reactions", reactions)
          .put("pages", pages);
    }

    public static Message parse(JSONObject o) {
      Message m = new Message();
      m.account = o.optInt("account");
      m.owner = o.optLong("owner");
      m.dialog = o.optLong("dialog");
      m.id = o.optInt("id");
      m.date = o.optInt("date");
      m.topic = o.optInt("topic");
      m.source = o.optString("source");
      m.sourceTitle = o.optString("sourceTitle");
      m.accountName = o.optString("accountName");
      m.author = o.optLong("author");
      m.authorName = o.optString("authorName");
      m.text = o.optString("text");
      m.kind = o.optString("kind", "text");
      m.ocr = o.optString("ocr");
      m.transcript = o.optString("transcript");
      m.description = o.optString("description");
      m.forwardSource = o.optString("forwardSource");
      m.mediaAnalysis =
          o.optJSONObject("mediaAnalysis") == null
              ? new JSONObject()
              : o.optJSONObject("mediaAnalysis");
      m.pageEvidence =
          o.optJSONObject("pageEvidence") == null
              ? new JSONObject()
              : o.optJSONObject("pageEvidence");
      m.frameEvidence =
          o.optJSONObject("frameEvidence") == null
              ? new JSONObject()
              : o.optJSONObject("frameEvidence");
      JSONArray frameTimes = o.optJSONArray("frameTimes");
      for (int i = 0; frameTimes != null && i < frameTimes.length(); i++)
        m.frameTimes.add(frameTimes.optLong(i));
      m.contentHash = o.optString("contentHash");
      m.previewBase = o.optString("previewBase");
      m.mime = o.optString("mime");
      m.localUri = o.optString("localUri");
      m.cloudNode = o.optString("cloudNode");
      m.hash = o.optString("hash");
      m.sceneHash = o.optLong("sceneHash");
      m.bytes = o.optLong("bytes");
      m.duration = o.optLong("duration");
      m.own = o.optBoolean("own");
      m.deleted = o.optBoolean("deleted");
      m.protectedMedia = o.optBoolean("protectedMedia");
      m.personal = o.optBoolean("personal");
      m.transport = o.optString("transport");
      m.mediaKey = o.optString("mediaKey");
      m.transcriptKey = o.optString("transcriptKey");
      m.content = o.optString("content");
      m.replyTo = o.optInt("replyTo");
      m.replies = o.optInt("replies");
      m.forwards = o.optInt("forwards");
      m.reactions = o.optInt("reactions");
      m.pages = o.optInt("pages");
      return m;
    }
  }

  public static final class Scope {
    public long from = System.currentTimeMillis() / 1000 - 7 * 86400,
        to = System.currentTimeMillis() / 1000;
    public boolean excludeOwn, includePersonal, includePrivate, sinceRead;
    public final Set<Integer> excluded = new HashSet<>(), whole = new HashSet<>();
    public final Set<String> sources = new HashSet<>(), kinds = new HashSet<>();
    public final Set<Long> authors = new HashSet<>();

    public boolean requests(Source s) {
      return !excluded.contains(s.account)
          && (!"private".equals(s.type) || includePrivate)
          && (whole.contains(s.account) || sources.contains(s.key()));
    }

    public boolean allows(Source s) {
      return s.available && requests(s);
    }

    public boolean matches(Message m) {
      return !m.deleted
          && !m.protectedMedia
          && !excluded.contains(m.account)
          && m.date >= from
          && m.date <= to
          && (!excludeOwn || !m.own)
          && (authors.isEmpty() || authors.contains(m.author))
          && (kinds.isEmpty() || kinds.contains(m.kind));
    }

    public JSONObject json() throws JSONException {
      return new JSONObject()
          .put("from", from)
          .put("to", to)
          .put("excludeOwn", excludeOwn)
          .put("includePersonal", includePersonal)
          .put("includePrivate", includePrivate)
          .put("sinceRead", sinceRead)
          .put("excluded", new JSONArray(excluded))
          .put("whole", new JSONArray(whole))
          .put("sources", new JSONArray(sources))
          .put("kinds", new JSONArray(kinds))
          .put("authors", new JSONArray(authors));
    }

    public static Scope parse(JSONObject o) {
      Scope s = new Scope();
      s.from = o.optLong("from", s.from);
      s.to = o.optLong("to", s.to);
      s.excludeOwn = o.optBoolean("excludeOwn");
      s.includePersonal = o.optBoolean("includePersonal");
      s.includePrivate = o.optBoolean("includePrivate");
      s.sinceRead = o.optBoolean("sinceRead");
      JSONArray a = o.optJSONArray("excluded");
      for (int i = 0; a != null && i < a.length(); i++) s.excluded.add(a.optInt(i));
      a = o.optJSONArray("whole");
      for (int i = 0; a != null && i < a.length(); i++) s.whole.add(a.optInt(i));
      a = o.optJSONArray("sources");
      for (int i = 0; a != null && i < a.length(); i++) s.sources.add(a.optString(i));
      a = o.optJSONArray("kinds");
      for (int i = 0; a != null && i < a.length(); i++) s.kinds.add(a.optString(i));
      a = o.optJSONArray("authors");
      for (int i = 0; a != null && i < a.length(); i++) s.authors.add(a.optLong(i));
      return s;
    }
  }

  public static List<Message> select(
      Collection<Message> input, Collection<Source> sources, Scope scope) {
    Map<String, Source> allowed = new HashMap<>();
    for (Source s : sources) if (scope.allows(s)) allowed.put(s.key(), s);
    Map<String, Message> unique = new LinkedHashMap<>();
    for (Message m : input) {
      Source s =
          allowed.get(
              m.dialog == 0
                  ? m.account + ":local:" + m.source
                  : m.account + ":" + m.dialog + ":" + m.topic);
      if (s == null) s = allowed.get(m.account + ":" + m.dialog + ":0");
      if (s == null || !scope.matches(m) || (scope.sinceRead && m.date <= s.readAt / 1000))
        continue;
      Message old = unique.get(m.key());
      if (old == null || m.searchable().length() > old.searchable().length())
        unique.put(m.key(), m);
    }
    ArrayList<Message> out = new ArrayList<>(unique.values());
    out.sort(Comparator.comparingInt((Message m) -> m.date).thenComparing(m -> m.key()));
    return out;
  }

  public static long reviewFrom(
      Collection<Source> sources, Scope scope, long retentionCutoff, long until) {
    long earliest = until;
    boolean found = false;
    for (Source source : sources)
      if (scope.requests(source)) {
        earliest =
            Math.min(
                earliest,
                Math.max(
                    retentionCutoff, source.readAt > 0 ? source.readAt / 1000 : retentionCutoff));
        found = true;
      }
    return found ? earliest : retentionCutoff;
  }

  /** Separate filters combine by AND; selection itself is independent of a temporary search. */
  public static final class SourceFilter {
    public String text = "", type = "", role = "", folder = "", status = "", activity = "";
    public int minPerDay;
    public final Set<Integer> accounts = new HashSet<>();

    public boolean matches(Source s, List<Message> messages, long from, long to) {
      if (!accounts.isEmpty() && !accounts.contains(s.account)
          || !s.title.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))
          || !type.isEmpty() && !type.equals(s.type)
          || !role.isEmpty() && !role.equals(s.role)
          || !folder.isEmpty() && !s.folder.contains(folder)) return false;
      if ("pinned".equals(status) && !s.pinned
          || "archive".equals(status) && !s.archived
          || "muted".equals(status) && !s.muted) return false;
      Set<String> seen = new HashSet<>();
      int count = 0;
      for (Message m : messages)
        if (m.source.equals(s.canonical)
            && (s.topic == 0 || s.topic == m.topic)
            && m.date >= from
            && m.date <= to
            && !m.deleted
            && !m.protectedMedia
            && seen.add(m.key())) count++;
      return (!"active".equals(activity) || count > 0)
          && (!"quiet".equals(activity) || count == 0)
          && count >= minPerDay * Math.max(1, (to - from) / 86400);
    }
  }
}
