package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/** Structured claims and validated evidence. Provider prose never becomes a navigation target. */
public final class CenterReports {
  private CenterReports() {}

  public static boolean personal(String s) {
    String t = s.toLowerCase(Locale.ROOT);
    return t.matches(
        "(?s).*(соболезну|сочувствую|умерла? |похорон|condolence|rest in peace|my condolences).*");
  }

  public static String clean(String s) {
    return s == null ? "" : s.replaceAll("(?i)\\[(?:msg|message|source)\\s*:[^\\]]*\\]", "").trim();
  }

  public static String excerpt(String text, int limit) {
    int end = Math.min(text.length(), Math.max(0, limit));
    if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return text.substring(0, end);
  }

  public static final class Evidence {
    public final CenterData.Message message;
    public final String quote;
    public boolean localMetadata;
    public final long timeMs;
    public final int page;

    Evidence(CenterData.Message m, String q, long ms, int p) {
      message = m;
      quote = q;
      timeMs = ms;
      page = p;
    }

    public JSONObject json() throws JSONException {
      return new JSONObject()
          .put("account", message.account)
          .put("chat", message.dialog)
          .put("message", message.id)
          .put("quote", quote)
          .put("timeMs", timeMs)
          .put("page", page)
          .put("metadata", localMetadata);
    }
  }

  public static final class Claim {
    public String title = "", text = "", topic = "", certainty = "single";
    public boolean personal;
    public final List<Evidence> evidence = new ArrayList<>();

    public boolean verified() {
      return !evidence.isEmpty();
    }

    public JSONObject json() throws JSONException {
      JSONArray a = new JSONArray();
      for (Evidence e : evidence) a.put(e.json());
      return new JSONObject()
          .put("personal", personal)
          .put("title", title)
          .put("text", text)
          .put("topic", topic)
          .put("certainty", certainty)
          .put("evidence", a);
    }
  }

  public static final class Report {
    public String id = UUID.randomUUID().toString(), question = "";
    public long created = System.currentTimeMillis(), from, to;
    public JSONArray accountDependencies = new JSONArray();
    public int storageAccount = -1;
    public long storageOwner;
    public JSONObject statistics;
    public JSONArray sourceKeys = new JSONArray();
    public final List<CenterData.Message> transcriptions = new ArrayList<>();
    public final List<CenterData.Message> dataset = new ArrayList<>();
    public int indexed, analyzed;
    public boolean complete, retryNeeded, localReport;
    public final List<Claim> claims = new ArrayList<>();
    public final List<String> unclear = new ArrayList<>();

    public JSONObject json() throws JSONException {
      JSONArray a = new JSONArray();
      for (Claim c : claims) a.put(c.json());
      return new JSONObject()
          .put("id", id)
          .put("question", question)
          .put("created", created)
          .put("from", from)
          .put("to", to)
          .put("indexed", indexed)
          .put("analyzed", analyzed)
          .put("complete", complete)
          .put("retryNeeded", retryNeeded)
          .put("localReport", localReport)
          .put("claims", a)
          .put("unclear", new JSONArray(unclear));
    }
  }

  public static JSONObject archive(Report report) throws JSONException {
    JSONObject saved = report.json();
    saved.put("accounts", report.accountDependencies);
    Set<String> sourceKeys = new LinkedHashSet<>();
    for (int n = 0; n < report.sourceKeys.length(); n++)
      sourceKeys.add(report.sourceKeys.optString(n));
    for (CenterData.Message m : report.dataset) sourceKeys.add(m.sourceKey());
    for (Claim c : report.claims)
      for (Evidence e : c.evidence) sourceKeys.add(e.message.sourceKey());
    report.sourceKeys = new JSONArray(sourceKeys);
    saved.put("sourceKeys", report.sourceKeys);
    JSONArray transcripts = new JSONArray();
    for (CenterData.Message m : transcripts(report)) {
      JSONObject snapshot = m.json();
      snapshot.put("transport", "").put("localUri", "").put("text", "").put("ocr", "");
      transcripts.put(snapshot);
    }
    saved.put("transcripts", transcripts);
    if (report.statistics != null) saved.put("statistics", report.statistics);
    org.json.JSONArray claims = saved.optJSONArray("claims");
    for (int i = 0; i < report.claims.size(); i++) {
      org.json.JSONArray evidence = claims.optJSONObject(i).optJSONArray("evidence");
      for (int n = 0; n < report.claims.get(i).evidence.size(); n++) {
        Evidence e = report.claims.get(i).evidence.get(n);
        CenterData.Message m = e.message, copy = new CenterData.Message();
        copy.account = m.account;
        copy.owner = m.owner;
        copy.dialog = m.dialog;
        copy.id = m.id;
        copy.date = m.date;
        copy.source = m.source;
        copy.sourceTitle = m.sourceTitle;
        copy.accountName = m.accountName;
        copy.author = m.author;
        copy.authorName = m.authorName;
        copy.kind = m.kind;
        copy.text = e.quote;
        copy.duration = m.duration;
        copy.pages = m.pages;
        copy.deleted = m.deleted;
        copy.description = m.description;
        copy.cloudNode = m.cloudNode;
        if (e.timeMs > 0) {
          copy.transcript = "[" + CenterTranscript.stamp(e.timeMs) + "] " + e.quote;
          copy.frameEvidence.put("" + e.timeMs, e.quote);
        }
        if (e.page > 0) copy.pageEvidence.put("" + e.page, e.quote);
        if (m.frameTimes.contains(e.timeMs)) copy.frameTimes.add(e.timeMs);
        evidence.optJSONObject(n).put("snapshot", copy.json());
      }
    }
    return saved;
  }

  public static Report restore(JSONObject saved, List<CenterData.Message> index) {
    Map<String, CenterData.Message> merged = new LinkedHashMap<>();
    for (CenterData.Message m : index) merged.put(m.qualified(), m);
    Map<String, CenterData.Message> snapshots = new LinkedHashMap<>();
    org.json.JSONArray claims = saved.optJSONArray("claims");
    for (int i = 0; claims != null && i < claims.length(); i++) {
      org.json.JSONArray evidence = claims.optJSONObject(i).optJSONArray("evidence");
      for (int n = 0; evidence != null && n < evidence.length(); n++) {
        JSONObject row = evidence.optJSONObject(n),
            snapshot = row == null ? null : row.optJSONObject("snapshot");
        if (snapshot == null) continue;
        CenterData.Message m = CenterData.Message.parse(snapshot),
            prior = snapshots.get(m.qualified());
        if (prior == null) snapshots.put(m.qualified(), m);
        else {
          prior.text += "\n" + m.text;
          prior.transcript += "\n" + m.transcript;
          java.util.Iterator<String> pages = m.pageEvidence.keys();
          try {
            while (pages.hasNext()) {
              String key = pages.next();
              prior.pageEvidence.put(
                  key, prior.pageEvidence.optString(key) + "\n" + m.pageEvidence.optString(key));
            }
            java.util.Iterator<String> times = m.frameEvidence.keys();
            while (times.hasNext()) {
              String key = times.next();
              prior.frameEvidence.put(
                  key, prior.frameEvidence.optString(key) + "\n" + m.frameEvidence.optString(key));
            }
          } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
          }
          for (long time : m.frameTimes)
            if (!prior.frameTimes.contains(time)) prior.frameTimes.add(time);
        }
      }
    }
    for (CenterData.Message m : snapshots.values()) {
      CenterData.Message live = merged.get(m.qualified());
      if (live != null) {
        m.deleted = live.deleted;
        m.protectedMedia = live.protectedMedia;
      }
      merged.put(m.qualified(), m);
    }
    JSONArray transcripts = saved.optJSONArray("transcripts");
    for (int i = 0; transcripts != null && i < transcripts.length(); i++) {
      CenterData.Message transcript = CenterData.Message.parse(transcripts.optJSONObject(i));
      CenterData.Message message = merged.get(transcript.qualified());
      if (message != null) message.transcript = transcript.transcript;
    }
    Report result = validate(saved, new ArrayList<>(merged.values()));
    if (saved.optBoolean("localReport")) {
      result.localReport = true;
      for (int c = 0; claims != null && c < Math.min(claims.length(), result.claims.size()); c++) {
        JSONArray evidence = claims.optJSONObject(c).optJSONArray("evidence");
        for (int n = 0; evidence != null && n < evidence.length(); n++) {
          JSONObject row = evidence.optJSONObject(n);
          if (row == null || !row.optBoolean("metadata")) continue;
          CenterData.Message m =
              merged.get(
                  row.optInt("account") + ":" + row.optLong("chat") + ":" + row.optInt("message"));
          if (m != null
              && !m.protectedMedia
              && result.claims.get(c).evidence.stream()
                  .noneMatch(e -> e.message.qualified().equals(m.qualified()))) {
            Evidence local = new Evidence(m, "", 0, 0);
            local.localMetadata = true;
            result.claims.get(c).evidence.add(local);
          }
        }
      }
    }

    for (int i = 0; transcripts != null && i < transcripts.length(); i++)
      result.transcriptions.add(CenterData.Message.parse(transcripts.optJSONObject(i)));
    result.sourceKeys =
        saved.optJSONArray("sourceKeys") == null
            ? new JSONArray()
            : saved.optJSONArray("sourceKeys");
    result.storageAccount = saved.optInt("storageAccount", -1);
    result.storageOwner = saved.optLong("storageOwner");
    result.statistics = saved.optJSONObject("statistics");
    result.accountDependencies =
        saved.optJSONArray("accounts") == null ? new JSONArray() : saved.optJSONArray("accounts");
    return result;
  }

  public static Report validate(JSONObject response, List<CenterData.Message> index) {
    Map<String, CenterData.Message> lookup = new HashMap<>();
    for (CenterData.Message m : index) lookup.put(m.qualified(), m);
    Report r = new Report();
    r.id = response.optString("id", r.id);
    r.question = clean(response.optString("question"));
    r.created = response.optLong("created", r.created);
    r.from = response.optLong("from");
    r.to = response.optLong("to");
    r.indexed = response.optInt("indexed");
    r.analyzed = response.optInt("analyzed");
    r.complete = response.optBoolean("complete");
    r.retryNeeded = response.optBoolean("retryNeeded");
    JSONArray claims = response.optJSONArray("claims");
    for (int i = 0; claims != null && i < Math.min(500, claims.length()); i++) {
      JSONObject c = claims.optJSONObject(i);
      if (c == null) continue;
      Claim claim = new Claim();
      claim.personal = c.optBoolean("personal");
      claim.title = clean(c.optString("title"));
      claim.text = clean(c.optString("text"));
      claim.topic = clean(c.optString("topic"));
      String certainty = c.optString("certainty", "single");
      claim.certainty = "rumor".equals(certainty) ? "rumor" : "single";
      JSONArray evidence = c.optJSONArray("evidence");
      Set<String> seen = new HashSet<>();
      for (int j = 0; evidence != null && j < Math.min(100, evidence.length()); j++) {
        JSONObject e = evidence.optJSONObject(j);
        if (e == null) continue;
        CenterData.Message m =
            lookup.get(e.optInt("account") + ":" + e.optLong("chat") + ":" + e.optInt("message"));
        if (m == null || m.protectedMedia || !seen.add(m.qualified())) continue;
        String quote = e.optString("quote").trim();
        String actual = m.text + "\n" + m.content + "\n" + m.ocr + "\n" + m.transcript;
        boolean visual =
            quote.isEmpty()
                && ((e.optInt("page") > 0
                        && e.optInt("page") <= m.pages
                        && m.pageEvidence.has("" + e.optInt("page")))
                    || m.frameTimes.contains(e.optLong("timeMs")));
        if (!visual && (quote.isEmpty() || !actual.contains(quote))) continue;
        // Display a slice of the original, never the provider's fabricated formatting.
        int start = actual.indexOf(quote);
        String excerpt = visual ? "" : excerpt(quote, 160);
        long time = Math.max(0, e.optLong("timeMs"));
        int page = Math.max(0, e.optInt("page"));
        if (time > 0 && (m.duration <= 0 || time >= m.duration)
            || page > 0 && (m.pages <= 0 || page > m.pages)) continue;
        if (!quote.isEmpty() && page > 0 && !m.pageEvidence.optString("" + page).contains(quote))
          continue;
        if (!quote.isEmpty() && time > 0) {
          boolean timed = m.frameEvidence.optString("" + time).contains(quote);
          for (CenterTranscript.Segment segment : CenterTranscript.parse(m.transcript))
            if (time >= segment.start && time < segment.end && segment.text.contains(quote))
              timed = true;
          if (!timed) continue;
        }
        claim.evidence.add(new Evidence(m, excerpt, time, page));
      }
      if (claim.evidence.size() > 1 && !"rumor".equals(claim.certainty))
        claim.certainty = "multiple";
      if (!claim.title.isEmpty() || !claim.text.isEmpty()) r.claims.add(claim);
    }
    JSONArray unclear = response.optJSONArray("unclear");
    for (int i = 0; unclear != null && i < unclear.length(); i++)
      r.unclear.add(clean(unclear.optString(i)));
    return r;
  }

  public static List<CenterData.Message> transcripts(Report report) {
    Map<String, CenterData.Message> messages = new LinkedHashMap<>();
    for (CenterData.Message m : report.transcriptions)
      if (!m.transcript.isEmpty()) messages.put(m.qualified(), m);
    for (CenterData.Message m : report.dataset)
      if (!m.transcript.isEmpty()) messages.put(m.qualified(), m);
    for (Claim c : report.claims)
      for (Evidence e : c.evidence)
        if (!e.message.transcript.isEmpty()) messages.put(e.message.qualified(), e.message);
    return new ArrayList<>(messages.values());
  }

  public static JSONObject parse(String raw) throws JSONException {
    String s = raw.trim();
    if (s.startsWith("```")) {
      int nl = s.indexOf('\n'), end = s.lastIndexOf("```");
      if (nl < 0 || end <= nl) throw new JSONException("Invalid structured response");
      s = s.substring(nl + 1, end).trim();
    }
    JSONObject o = new JSONObject(s);
    if (o.optJSONArray("claims") == null) throw new JSONException("Missing report claims");
    return o;
  }

  /** Every character participates, including messages longer than one model chunk. */
  public static List<JSONArray> chunks(
      List<CenterData.Message> messages, boolean personal, int maxChars) throws JSONException {
    ArrayList<JSONArray> batches = new ArrayList<>();
    JSONArray batch = new JSONArray();
    int length = 0;
    for (CenterData.Message m : messages) {
      if (m.deleted || m.protectedMedia || (!personal && m.personal)) continue;
      String text = m.searchable();
      for (int off = 0; off < Math.max(1, text.length()); ) {
        int end = Math.min(text.length(), off + Math.max(512, maxChars / 2));
        String part = text.substring(off, end);
        JSONObject o =
            new JSONObject()
                .put("account", m.account)
                .put("chat", m.dialog)
                .put("message", m.id)
                .put("source", m.sourceTitle)
                .put("date", m.date)
                .put("author", m.authorName)
                .put("kind", m.kind)
                .put("partStart", off)
                .put("text", part);
        int size = o.toString().length();
        if (length + size > maxChars && batch.length() > 0) {
          batches.add(batch);
          batch = new JSONArray();
          length = 0;
        }
        batch.put(o);
        length += size;
        if (end == text.length()) break;
        off = end;
      }
    }
    if (batch.length() > 0) batches.add(batch);
    return batches;
  }

  public static List<CenterData.Message> context(
      List<CenterData.Message> index, CenterData.Message anchor) {
    ArrayList<CenterData.Message> same = new ArrayList<>();
    for (CenterData.Message m : index)
      if (m.account == anchor.account && m.dialog == anchor.dialog) same.add(m);
    if (same.stream().noneMatch(m -> m.id == anchor.id)) same.add(anchor);
    same.sort(Comparator.comparingInt(m -> m.id));
    int pos = 0;
    for (int i = 0; i < same.size(); i++)
      if (same.get(i).id == anchor.id) {
        pos = i;
        break;
      }
    return new ArrayList<>(same.subList(Math.max(0, pos - 5), Math.min(same.size(), pos + 6)));
  }
}
