package org.telegram.messenger.telepremium.aicenter;

import java.text.SimpleDateFormat;
import java.util.*;

/** Local statistics over deduplicated account-qualified evidence. No AI or network dependency. */
public final class CenterStats {
  public static final class Group {
    public String id, title;
    public int messages, previous, media, links, concentration;
    public long last;
    public boolean currentComplete, previousComplete;
    public final Set<Long> authors = new HashSet<>();
    public final Set<Integer> accounts = new HashSet<>();
    public final Map<String, Integer> types = new LinkedHashMap<>(),
        daily = new TreeMap<>(),
        previousDaily = new TreeMap<>();
    public final Map<String, List<CenterData.Message>> dayEvidence = new HashMap<>(),
        hourEvidence = new HashMap<>();
    public final int[][] heat = new int[7][24];
    public final List<CenterData.Message> evidence = new ArrayList<>();

    public double growth() {
      return previous == 0
          ? (messages == 0 ? 0 : Double.POSITIVE_INFINITY)
          : (messages - previous) / (double) previous;
    }
  }

  public static final class Author {
    public long id;
    public String name;
    public boolean own;
    public int count, previous, media, links, replies, characters;
    public final Set<String> groups = new HashSet<>();
    public final Set<Integer> accounts = new HashSet<>();
    public final List<CenterData.Message> publications = new ArrayList<>();
    public final Map<String, Integer> daily = new TreeMap<>(), topics = new HashMap<>();
  }

  public final List<Group> groups = new ArrayList<>();
  public final List<Author> authors = new ArrayList<>();
  public final Map<String, List<CenterData.Message>> anomalies = new LinkedHashMap<>();
  public final Map<String, List<CenterData.Message>> domains = new TreeMap<>(),
      forwards = new TreeMap<>();
  public final List<CenterData.Message> messages;
  public final long from, to;
  public final int days;

  private CenterStats(List<CenterData.Message> m, CenterData.Scope scope) {
    messages = m;
    from = scope.from;
    to = scope.to;
    days = (int) Math.max(1, (to - from + 86399) / 86400);
  }

  public static CenterStats compute(
      List<CenterData.Message> index,
      List<CenterData.Source> sources,
      CenterData.Scope scope,
      TimeZone zone)
      throws Exception {
    List<CenterData.Message> current = CenterData.select(index, sources, scope);
    CenterStats result = new CenterStats(current, scope);
    CenterData.Scope before = CenterData.Scope.parse(scope.json());
    long length = Math.max(1, scope.to - scope.from + 1);
    before.from = scope.from - length;
    before.to = scope.from - 1;
    before.sinceRead = false;
    List<CenterData.Message> previous = CenterData.select(index, sources, before);
    Map<String, Group> gm = new LinkedHashMap<>();
    Map<Long, Author> am = new HashMap<>();
    SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    day.setTimeZone(zone);
    Calendar clock = Calendar.getInstance(zone);
    for (CenterData.Source s : sources)
      if (scope.allows(s)) {
        Group g = gm.get(s.canonical);
        if (g == null) {
          g = new Group();
          g.id = s.canonical;
          g.title = s.title;
          gm.put(s.canonical, g);
        }
        g.accounts.add(s.account);
        g.currentComplete |= s.covers(scope.from, scope.to);
        g.previousComplete |= s.covers(before.from, before.to);
      }
    for (CenterData.Message m : current) {
      Group g = gm.get(m.source);
      if (g == null) continue;
      g.messages++;
      g.evidence.add(m);
      g.last = Math.max(g.last, m.date);
      String date = day.format(new Date(m.date * 1000L));
      g.daily.put(date, g.daily.getOrDefault(date, 0) + 1);
      g.types.put(m.kind, g.types.getOrDefault(m.kind, 0) + 1);
      g.authors.add(m.author);
      clock.setTimeInMillis(m.date * 1000L);
      g.heat[(clock.get(Calendar.DAY_OF_WEEK) + 5) % 7][clock.get(Calendar.HOUR_OF_DAY)]++;
      g.dayEvidence.computeIfAbsent(date, k -> new ArrayList<>()).add(m);
      g.hourEvidence
          .computeIfAbsent(
              ((clock.get(Calendar.DAY_OF_WEEK) + 5) % 7) + ":" + clock.get(Calendar.HOUR_OF_DAY),
              k -> new ArrayList<>())
          .add(m);
      boolean media = !m.kind.equals("text") && !m.kind.equals("link");
      if (media) g.media++;
      java.util.regex.Matcher url =
          java.util.regex.Pattern.compile("(?i)https?://([^/\\s?#]+)[^\\s]*")
              .matcher(m.text + "\n" + m.content);
      boolean linked = false;
      while (url.find()) {
        linked = true;
        String domain = url.group(1).toLowerCase(Locale.ROOT);
        result.domains.computeIfAbsent(domain, k -> new ArrayList<>()).add(m);
      }
      if (linked) g.links++;
      if (!m.forwardSource.isEmpty())
        result.forwards.computeIfAbsent(m.forwardSource, k -> new ArrayList<>()).add(m);
      if (m.author == 0 || m.source.startsWith("p:")) continue;
      Author a = am.get(m.author);
      if (a == null) {
        a = new Author();
        a.id = m.author;
        a.name = m.authorName;
        am.put(m.author, a);
      }
      a.own |= m.own;
      a.count++;
      a.media += media ? 1 : 0;
      a.links += linked ? 1 : 0;
      a.replies += m.replyTo > 0 ? 1 : 0;
      a.characters += m.text.length();
      a.groups.add(m.source);
      a.accounts.add(m.account);
      a.publications.add(m);
      a.daily.put(date, a.daily.getOrDefault(date, 0) + 1);
      for (String topic : CenterTopics.words(m.text))
        a.topics.put(topic, a.topics.getOrDefault(topic, 0) + 1);
    }
    for (CenterData.Message m : previous) {
      Group g = gm.get(m.source);
      if (g != null) {
        g.previous++;
        String date = day.format(new Date(m.date * 1000L));
        g.previousDaily.put(date, g.previousDaily.getOrDefault(date, 0) + 1);
        g.last = Math.max(g.last, m.date);
      }
      Author a = am.get(m.author);
      if (a != null) a.previous++;
    }
    for (Group g : gm.values()) {
      if (!g.daily.isEmpty() || g.currentComplete) {
        Calendar dates = Calendar.getInstance(zone);
        try {
          dates.setTime(
              g.currentComplete
                  ? new Date(scope.from * 1000L)
                  : day.parse(g.daily.keySet().iterator().next()));
          Date end =
              g.currentComplete
                  ? new Date(scope.to * 1000L)
                  : day.parse(((TreeMap<String, Integer>) g.daily).lastKey());
          for (int n = 0; !dates.getTime().after(end) && n < 36600; n++) {
            String date = day.format(dates.getTime());
            g.daily.putIfAbsent(date, 0);
            dates.add(Calendar.DAY_OF_YEAR, 1);
          }
        } catch (Exception ignored) {
        }
      }
      Map<Long, Integer> counts = new HashMap<>();
      for (CenterData.Message m : g.evidence)
        counts.put(m.author, counts.getOrDefault(m.author, 0) + 1);
      List<Integer> ranked = new ArrayList<>(counts.values());
      ranked.sort(Collections.reverseOrder());
      int sum = 0;
      for (int n : ranked) {
        if (sum >= Math.ceil(g.messages * .8)) break;
        sum += n;
        g.concentration++;
      }
      if (g.previousComplete && result.days >= 7) {
        double mean = g.previous / (double) result.days, squares = 0;
        for (int n : g.previousDaily.values()) squares += n * (double) n;
        double deviation = Math.sqrt(Math.max(0, squares / result.days - mean * mean));
        for (Map.Entry<String, Integer> entry : g.daily.entrySet())
          if (Math.abs(entry.getValue() - mean) > 3 * Math.max(.25, deviation)) {
            List<CenterData.Message> evidence = new ArrayList<>();
            for (CenterData.Message m : g.evidence)
              if (day.format(new Date(m.date * 1000L)).equals(entry.getKey())) evidence.add(m);
            result.anomalies.put(g.title + " · " + entry.getKey(), evidence);
          }
      }
    }
    result.groups.addAll(gm.values());
    result.groups.sort(Comparator.comparingInt((Group g) -> g.messages).reversed());
    result.authors.addAll(am.values());
    result.authors.sort(Comparator.comparingInt((Author a) -> a.count).reversed());
    return result;
  }

  public org.json.JSONObject json() throws org.json.JSONException {
    org.json.JSONArray gs = new org.json.JSONArray(), as = new org.json.JSONArray();
    for (Group g : groups) {
      org.json.JSONObject daily = new org.json.JSONObject(), types = new org.json.JSONObject();
      for (Map.Entry<String, Integer> e : g.daily.entrySet()) daily.put(e.getKey(), e.getValue());
      for (Map.Entry<String, Integer> e : g.types.entrySet()) types.put(e.getKey(), e.getValue());
      org.json.JSONArray heat = new org.json.JSONArray();
      for (int[] day : g.heat) {
        org.json.JSONArray hours = new org.json.JSONArray();
        for (int n : day) hours.put(n);
        heat.put(hours);
      }
      gs.put(
          new org.json.JSONObject()
              .put("id", g.id)
              .put("title", g.title)
              .put("messages", g.messages)
              .put("previous", g.previous)
              .put("previousComplete", g.previousComplete)
              .put("currentComplete", g.currentComplete)
              .put("authors", g.authors.size())
              .put("media", g.media)
              .put("links", g.links)
              .put("concentration", g.concentration)
              .put("daily", daily)
              .put("types", types)
              .put("heat", heat));
    }
    for (Author a : authors)
      as.put(
          new org.json.JSONObject()
              .put("id", a.id)
              .put("name", a.name)
              .put("count", a.count)
              .put("previous", a.previous)
              .put("media", a.media)
              .put("links", a.links)
              .put("replies", a.replies)
              .put("characters", a.characters));
    return new org.json.JSONObject()
        .put("from", from)
        .put("to", to)
        .put("groups", gs)
        .put("authors", as);
  }

  public static List<CenterData.Message> window(
      List<CenterData.Message> messages, String source, long from, long to, long author) {
    List<CenterData.Message> out = new ArrayList<>();
    for (CenterData.Message m : messages)
      if ((source == null || source.equals(m.source))
          && m.date >= from
          && m.date <= to
          && (author == 0 || m.author == author)) out.add(m);
    return out;
  }
}
