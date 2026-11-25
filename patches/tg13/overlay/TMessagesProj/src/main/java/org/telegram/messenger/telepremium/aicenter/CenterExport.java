package org.telegram.messenger.telepremium.aicenter;

import java.io.*;
import java.util.*;
import org.json.*;

/** Export formatting is offline, deterministic and escapes untrusted model/source text. */
public final class CenterExport {
  private CenterExport() {}

  public interface Labels {
    String get(String key);
  }

  public interface Images {
    byte[] read(CenterReports.Evidence evidence) throws Exception;

    default byte[] chart(int mode) throws Exception {
      return null;
    }
  }

  public static final class Options {
    public boolean anonymize, images = true;
    public final List<String> blocks =
        new ArrayList<>(
            Arrays.asList("main", "charts", "authors", "media", "transcripts", "unclear"));

    public JSONObject json() throws JSONException {
      return new JSONObject()
          .put("anonymize", anonymize)
          .put("images", images)
          .put("blocks", new JSONArray(blocks));
    }

    public static Options parse(JSONObject o) {
      Options v = new Options();
      if (o == null) return v;
      v.anonymize = o.optBoolean("anonymize");
      v.images = o.optBoolean("images", true);
      JSONArray a = o.optJSONArray("blocks");
      if (a != null) {
        v.blocks.clear();
        Set<String> allowed =
            new HashSet<>(
                Arrays.asList("main", "charts", "authors", "media", "transcripts", "unclear"));
        for (int i = 0; i < a.length(); i++) {
          String k = a.optString(i);
          if (allowed.contains(k) && !v.blocks.contains(k)) v.blocks.add(k);
        }
      }
      return v;
    }
  }

  public static final class Names {
    private final Map<Long, String> names = new LinkedHashMap<>();
    private final Map<Integer, String> accounts = new LinkedHashMap<>();
    private final boolean anonymous;
    private final Map<String, String> replacements = new LinkedHashMap<>();
    private final Labels labels;

    public Names(Options o, Labels l) {
      anonymous = o.anonymize;
      labels = l;
    }

    public Names(Options options, Labels labels, CenterReports.Report report) {
      this(options, labels);
      seedStats(report.statistics);
      for (CenterData.Message m : report.dataset) seed(m);
      for (CenterData.Message m : CenterReports.transcripts(report)) seed(m);
      for (CenterReports.Claim c : report.claims)
        for (CenterReports.Evidence e : c.evidence) seed(e.message);
    }

    public void seedStats(JSONObject stats) {
      JSONArray authors = stats == null ? null : stats.optJSONArray("authors");
      for (int i = 0; authors != null && i < authors.length(); i++) {
        JSONObject author = authors.optJSONObject(i);
        if (author != null)
          remember(
              author.optString("name"), author(author.optLong("id"), author.optString("name")));
      }
    }

    private void seed(CenterData.Message m) {
      remember(m.authorName, author(m));
      remember(m.accountName, account(m));
    }

    private void remember(String original, String alias) {
      if (anonymous && original != null && !original.isEmpty())
        replacements.putIfAbsent(original, alias);
    }

    public String privatize(String text) {
      if (!anonymous || text == null) return text == null ? "" : text;
      List<String> keys = new ArrayList<>(replacements.keySet());
      keys.sort(Comparator.comparingInt(String::length).reversed());
      StringBuilder out = new StringBuilder();
      for (int at = 0; at < text.length(); ) {
        String match = null;
        for (String key : keys)
          if (text.startsWith(key, at)) {
            match = key;
            break;
          }
        if (match == null) out.append(text.charAt(at++));
        else {
          out.append(replacements.get(match));
          at += match.length();
        }
      }
      return out.toString();
    }

    public String author(CenterData.Message m) {
      return anonymous
          ? names.computeIfAbsent(m.author, k -> labels.get("author") + " " + (names.size() + 1))
          : m.authorName;
    }

    public String account(CenterData.Message m) {
      return anonymous
          ? accounts.computeIfAbsent(
              m.account, k -> labels.get("account") + " " + (accounts.size() + 1))
          : m.accountName;
    }

    public String author(long id, String name) {
      return anonymous
          ? names.computeIfAbsent(id, k -> labels.get("author") + " " + (names.size() + 1))
          : name;
    }
  }

  public static String date(long seconds) {
    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(new Date(seconds * 1000));
  }

  public static String link(CenterData.Message m) {
    if (m.dialog == 0) return "";
    return m.source.startsWith("c:")
        ? "https://t.me/c/" + (-m.dialog) + "/" + m.id
        : "tg://openmessage?"
            + (m.dialog > 0 ? "user_id=" + m.dialog : "chat_id=" + (-m.dialog))
            + "&message_id="
            + m.id;
  }

  public static String escape(String s) {
    return s == null
        ? ""
        : s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }

  public static String markdown(String s) {
    return s.replace("\\", "\\\\")
        .replaceAll("([`*_{}\\[\\]()#+.!>|~-])", "\\\\$1")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  public static String text(CenterReports.Report r, Options o, Labels l, JSONObject stats) {
    StringBuilder b = new StringBuilder();
    Names names = new Names(o, l, r);
    b.append(names.privatize(r.question))
        .append('\n')
        .append(date(r.from))
        .append(" — ")
        .append(date(r.to))
        .append('\n')
        .append(l.get("coverage"))
        .append(": ")
        .append(r.analyzed)
        .append('/')
        .append(r.indexed)
        .append(" · ")
        .append(r.complete ? l.get("done") : l.get("partial"))
        .append("\n\n");
    for (String block : o.blocks) {
      if (block.equals("main"))
        for (CenterReports.Claim c : r.claims) {
          b.append(names.privatize(c.title))
              .append('\n')
              .append(names.privatize(c.text))
              .append('\n')
              .append(l.get(c.verified() ? c.certainty : "unverified"))
              .append('\n');
          for (CenterReports.Evidence e : c.evidence) {
            CenterData.Message m = e.message;
            b.append(names.author(m))
                .append(" · ")
                .append(names.privatize(m.sourceTitle))
                .append(" · ")
                .append(names.account(m))
                .append(" · ")
                .append(date(m.date))
                .append('\n');
            if (!names.privatize(e.quote).isEmpty())
              b.append('“').append(names.privatize(e.quote)).append("”\n");
            if (e.timeMs > 0) b.append(CenterTranscript.stamp(e.timeMs)).append('\n');
            if (e.page > 0) b.append(l.get("page")).append(' ').append(e.page).append('\n');
            b.append(link(m)).append('\n');
          }
          b.append('\n');
        }
      if (block.equals("unclear")) {
        b.append(l.get("unknown")).append('\n');
        for (String s : r.unclear) b.append(s).append('\n');
      }
      if (block.equals("charts") && stats != null) {
        b.append(l.get("activity")).append('\n');
        JSONArray a = stats.optJSONArray("groups");
        for (int i = 0; a != null && i < a.length(); i++) {
          JSONObject g = a.optJSONObject(i);
          b.append(names.privatize(g.optString("title")))
              .append(" · ")
              .append(g.optInt("messages"))
              .append(" · ")
              .append(l.get("authors"))
              .append(": ")
              .append(g.optInt("authors"))
              .append('\n');
        }
      }
      if (block.equals("transcripts"))
        for (CenterData.Message transcript : CenterReports.transcripts(r))
          b.append(names.privatize(transcript.sourceTitle))
              .append('\n')
              .append(names.privatize(transcript.transcript))
              .append('\n');
      if (block.equals("authors") && stats != null) {
        b.append(l.get("authorWarning")).append('\n');
        JSONArray a = stats.optJSONArray("authors");
        for (int i = 0; a != null && i < a.length(); i++) {
          JSONObject author = a.optJSONObject(i);
          b.append(names.author(author.optLong("id"), author.optString("name")))
              .append(" · ")
              .append(author.optInt("count"))
              .append('\n');
        }
      }
    }
    return CenterReports.clean(b.toString());
  }

  public static void html(
      CenterReports.Report r, Options o, Labels l, JSONObject stats, Images images, Writer out)
      throws Exception {
    Names names = new Names(o, l, r);
    out.write(
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\""
            + " content=\"width=device-width\"><meta http-equiv=\"Content-Security-Policy\""
            + " content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\"><title>"
            + escape(names.privatize(r.question))
            + "</title><style>body{font:16px"
            + " system-ui;max-width:960px;margin:auto;padding:24px;line-height:1.6}img{max-width:100%}article{border-bottom:1px"
            + " solid #ccc;padding:16px 0}blockquote{border-left:3px solid"
            + " #78a;padding-left:16px}small{color:#666}table{border-collapse:collapse}td,th{padding:8px;border:1px"
            + " solid #ccc}</style></head><body>");
    out.write(
        "<h1>"
            + escape(names.privatize(r.question))
            + "</h1><p>"
            + escape(date(r.from) + " — " + date(r.to))
            + "</p><p>"
            + escape(l.get("coverage"))
            + ": "
            + r.analyzed
            + " / "
            + r.indexed
            + " · "
            + escape(r.complete ? l.get("done") : l.get("partial"))
            + "</p>");
    for (String block : o.blocks) {
      if (block.equals("main"))
        for (CenterReports.Claim c : r.claims) {
          out.write(
              "<article><h2>"
                  + escape(names.privatize(c.title))
                  + "</h2><p>"
                  + escape(names.privatize(c.text)).replace("\n", "<br>")
                  + "</p><small>"
                  + escape(l.get(c.verified() ? c.certainty : "unverified"))
                  + "</small><details><summary>"
                  + escape(l.get("evidence"))
                  + " ("
                  + c.evidence.size()
                  + ")</summary>");
          for (CenterReports.Evidence e : c.evidence) {
            CenterData.Message m = e.message;
            out.write(
                "<p>"
                    + escape(
                        names.author(m)
                            + " · "
                            + names.privatize(m.sourceTitle)
                            + " · "
                            + names.account(m)
                            + " · "
                            + date(m.date))
                    + "</p><blockquote>"
                    + escape(names.privatize(e.quote))
                    + "</blockquote>");
            if (!link(m).isEmpty())
              out.write("<a href=\"" + escape(link(m)) + "\">" + escape(l.get("open")) + "</a>");
            if (e.page > 0) out.write("<p>" + escape(l.get("page")) + " " + e.page + "</p>");
            if (e.timeMs > 0) out.write("<p>" + CenterTranscript.stamp(e.timeMs) + "</p>");
          }
          out.write("</details></article>");
        }
      if (block.equals("media") && o.images && images != null) {
        Set<String> seen = new HashSet<>();
        for (CenterReports.Claim c : r.claims)
          for (CenterReports.Evidence e : c.evidence)
            if (seen.add(e.message.qualified() + ":" + e.timeMs + ":" + e.page)) {
              byte[] bytes = images.read(e);
              if (bytes != null) {
                out.write(
                    "<figure><img alt=\""
                        + escape(names.privatize(e.message.sourceTitle))
                        + "\" src=\"data:image/jpeg;base64,");
                out.write(java.util.Base64.getEncoder().encodeToString(bytes));
                out.write(
                    "\"><figcaption>"
                        + escape(
                            names.author(e.message)
                                + " · "
                                + names.privatize(e.message.sourceTitle)
                                + " · "
                                + date(e.message.date)
                                + " · "
                                + names.privatize(e.message.description))
                        + "</figcaption></figure>");
                Arrays.fill(bytes, (byte) 0);
              }
            }
      }
      if (block.equals("charts") && stats != null) {
        if (o.images && images != null)
          for (int mode : chartModes(stats)) {
            byte[] chart = images.chart(mode);
            if (chart != null) {
              out.write(
                  "<img alt=\""
                      + escape(l.get("chart"))
                      + "\" src=\"data:image/png;base64,"
                      + java.util.Base64.getEncoder().encodeToString(chart)
                      + "\">");
              Arrays.fill(chart, (byte) 0);
            }
          }
        out.write(
            "<h2>"
                + escape(l.get("activity"))
                + "</h2><table><tr><th>"
                + escape(l.get("groups"))
                + "</th><th>"
                + escape(l.get("messages"))
                + "</th><th>"
                + escape(l.get("authors"))
                + "</th></tr>");
        JSONArray a = stats.optJSONArray("groups");
        for (int i = 0; a != null && i < a.length(); i++) {
          JSONObject g = a.optJSONObject(i);
          out.write(
              "<tr><td>"
                  + escape(names.privatize(g.optString("title")))
                  + "</td><td>"
                  + g.optInt("messages")
                  + "</td><td>"
                  + g.optInt("authors")
                  + "</td></tr>");
        }
        out.write("</table>");
        for (int mode : chartModes(stats)) {
          out.write("<table>");
          for (List<Object> row : chartRows(stats, mode, o, l)) {
            out.write("<tr>");
            for (Object cell : row) out.write("<td>" + escape(String.valueOf(cell)) + "</td>");
            out.write("</tr>");
          }
          out.write("</table>");
        }
      }
      if (block.equals("authors") && stats != null) {
        out.write(
            "<h2>"
                + escape(l.get("authors"))
                + "</h2><p>"
                + escape(l.get("authorWarning"))
                + "</p>");
        JSONArray a = stats.optJSONArray("authors");
        for (int i = 0; a != null && i < a.length(); i++) {
          JSONObject author = a.optJSONObject(i);
          out.write(
              "<p>"
                  + escape(names.author(author.optLong("id"), author.optString("name")))
                  + " · "
                  + author.optInt("count")
                  + "</p>");
        }
      }
      if (block.equals("transcripts")) {
        for (CenterData.Message transcript : CenterReports.transcripts(r))
          out.write(
              "<h3>"
                  + escape(names.privatize(transcript.sourceTitle))
                  + "</h3><pre>"
                  + escape(names.privatize(transcript.transcript))
                  + "</pre>");
      }
      if (block.equals("unclear")) {
        out.write("<h2>" + escape(l.get("unknown")) + "</h2><ul>");
        for (String v : r.unclear) out.write("<li>" + escape(v) + "</li>");
        out.write("</ul>");
      }
    }
    out.write("</body></html>");
  }

  public static void md(
      CenterReports.Report r,
      Options options,
      Labels labels,
      JSONObject stats,
      Images images,
      Writer out)
      throws Exception {
    Names names = new Names(options, labels, r);
    out.write(
        "# "
            + markdown(names.privatize(r.question))
            + "\n\n"
            + markdown(date(r.from) + " — " + date(r.to))
            + "\n\n"
            + markdown(labels.get("coverage"))
            + ": "
            + r.analyzed
            + " / "
            + r.indexed
            + "\n\n");
    for (String block : options.blocks) {
      if (block.equals("main"))
        for (CenterReports.Claim c : r.claims) {
          out.write(
              "## "
                  + markdown(names.privatize(c.title))
                  + "\n\n"
                  + markdown(names.privatize(c.text))
                  + "\n\n"
                  + markdown(labels.get(c.verified() ? c.certainty : "unverified"))
                  + "\n\n");
          for (CenterReports.Evidence e : c.evidence) {
            out.write(
                "- "
                    + markdown(
                        names.author(e.message)
                            + " · "
                            + names.privatize(e.message.sourceTitle)
                            + " · "
                            + names.account(e.message)
                            + " · "
                            + date(e.message.date))
                    + "\n");
            if (!names.privatize(e.quote).isEmpty())
              out.write("  > " + markdown(names.privatize(e.quote)).replace("\n", "\n  > ") + "\n");
            if (!link(e.message).isEmpty())
              out.write("  [" + markdown(labels.get("open")) + "](" + link(e.message) + ")\n");
            if (e.timeMs > 0) out.write("  " + CenterTranscript.stamp(e.timeMs) + "\n");
            if (e.page > 0) out.write("  " + markdown(labels.get("page")) + " " + e.page + "\n");
          }
          out.write("\n");
        }
      if (block.equals("media") && options.images && images != null) {
        Set<String> seen = new HashSet<>();
        for (CenterReports.Claim c : r.claims)
          for (CenterReports.Evidence e : c.evidence)
            if (seen.add(e.message.qualified() + ":" + e.page + ":" + e.timeMs)) {
              byte[] image = images.read(e);
              if (image != null) {
                out.write(
                    "!["
                        + markdown(
                            names.author(e.message)
                                + " · "
                                + names.privatize(e.message.sourceTitle)
                                + " · "
                                + date(e.message.date))
                        + "](data:image/jpeg;base64,"
                        + java.util.Base64.getEncoder().encodeToString(image)
                        + ")\n\n"
                        + markdown(names.privatize(e.message.description))
                        + "\n\n");
                Arrays.fill(image, (byte) 0);
              }
            }
      }
      if (block.equals("charts") && stats != null) {
        out.write("## " + markdown(labels.get("activity")) + "\n\n");
        if (options.images && images != null)
          for (int mode : chartModes(stats)) {
            byte[] image = images.chart(mode);
            if (image != null) {
              out.write(
                  "!["
                      + markdown(labels.get("chart"))
                      + "](data:image/png;base64,"
                      + java.util.Base64.getEncoder().encodeToString(image)
                      + ")\n\n");
              Arrays.fill(image, (byte) 0);
            }
          }
        JSONArray groups = stats.optJSONArray("groups");
        out.write(
            "|"
                + markdown(labels.get("groups"))
                + "|"
                + markdown(labels.get("messages"))
                + "|\n|---|---:|\n");
        for (int i = 0; groups != null && i < groups.length(); i++) {
          JSONObject g = groups.optJSONObject(i);
          out.write(
              "|"
                  + markdown(names.privatize(g.optString("title")))
                  + "|"
                  + g.optInt("messages")
                  + "|\n");
        }
        for (int mode : chartModes(stats)) {
          List<List<Object>> rows = chartRows(stats, mode, options, labels);
          for (int row = 0; row < rows.size(); row++) {
            out.write("|");
            for (Object cell : rows.get(row))
              out.write(markdown(String.valueOf(cell)).replace("|", "\\|") + "|");
            out.write("\n");
            if (row == 0) {
              out.write("|");
              for (Object cell : rows.get(row)) out.write("---|");
              out.write("\n");
            }
          }
          out.write("\n");
        }
      }
      if (block.equals("authors") && stats != null) {
        out.write("\n" + markdown(labels.get("authorWarning")) + "\n\n");
        JSONArray a = stats.optJSONArray("authors");
        for (int i = 0; a != null && i < a.length(); i++) {
          JSONObject author = a.optJSONObject(i);
          out.write(
              "- "
                  + markdown(names.author(author.optLong("id"), author.optString("name")))
                  + " · "
                  + author.optInt("count")
                  + "\n");
        }
      }
      if (block.equals("transcripts")) {
        for (CenterData.Message transcript : CenterReports.transcripts(r))
          out.write(
              "\n### "
                  + markdown(names.privatize(transcript.sourceTitle))
                  + "\n\n"
                  + markdown(names.privatize(transcript.transcript))
                  + "\n\n");
      }
      if (block.equals("unclear")) {
        out.write("\n## " + markdown(labels.get("unknown")) + "\n\n");
        for (String line : r.unclear) out.write("- " + markdown(line) + "\n");
      }
    }
  }

  public static int[] chartModes(JSONObject stats) {
    JSONArray modes = stats == null ? null : stats.optJSONArray("chartModes");
    if (modes == null) return new int[] {0, 1, 2, 3, 4};
    List<Integer> result = new ArrayList<>();
    for (int n = 0; n < modes.length(); n++) {
      int mode = modes.optInt(n, -1);
      if (mode >= 0 && mode < 5 && !result.contains(mode)) result.add(mode);
    }
    int[] values = new int[result.size()];
    for (int n = 0; n < values.length; n++) values[n] = result.get(n);
    return values;
  }

  /** Exact numbers underlying every selected chart, also used when images are omitted. */
  public static List<List<Object>> chartRows(
      JSONObject stats, int mode, Options options, Labels l) {
    List<List<Object>> rows = new ArrayList<>();
    Names names = new Names(options, l);
    names.seedStats(stats);
    String[] keys =
        mode == 1
            ? new String[] {"groups", "date", "messages"}
            : mode == 2
                ? new String[] {"groups", "day", "hour", "messages"}
                : mode == 3
                    ? new String[] {"groups", "type", "messages"}
                    : mode == 4
                        ? new String[] {"groups", "concentration", "messages"}
                        : new String[] {
                          "groups", "messages", "previousPeriod", "authors", "media", "links"
                        };
    List<Object> header = new ArrayList<>();
    for (String key : keys) header.add(l.get(key));
    rows.add(header);
    JSONArray groups = stats == null ? null : stats.optJSONArray("groups");
    for (int i = 0; groups != null && i < groups.length(); i++) {
      JSONObject g = groups.optJSONObject(i);
      if (g == null) continue;
      String group = names.privatize(g.optString("title"));
      if (mode == 1 || mode == 3) {
        JSONObject values = g.optJSONObject(mode == 1 ? "daily" : "types");
        List<String> sorted = new ArrayList<>();
        if (values != null) {
          Iterator<String> iterator = values.keys();
          while (iterator.hasNext()) sorted.add(iterator.next());
        }
        Collections.sort(sorted);
        for (String key : sorted)
          rows.add(Arrays.asList(group, mode == 1 ? key : l.get(key), values.optInt(key)));
      } else if (mode == 2) {
        JSONArray heat = g.optJSONArray("heat");
        for (int day = 0; heat != null && day < heat.length(); day++) {
          JSONArray hours = heat.optJSONArray(day);
          for (int hour = 0; hours != null && hour < hours.length(); hour++)
            rows.add(Arrays.asList(group, day + 1, hour, hours.optInt(hour)));
        }
      } else if (mode == 4)
        rows.add(Arrays.asList(group, g.optInt("concentration"), g.optInt("messages")));
      else
        rows.add(
            Arrays.asList(
                group,
                g.optInt("messages"),
                g.optBoolean("previousComplete")
                    ? (Object) g.optInt("previous")
                    : l.get("baselineMissing"),
                g.optInt("authors"),
                g.optInt("media"),
                g.optInt("links")));
    }
    return rows;
  }

  public static String csv(JSONObject stats, Options o, Labels l) {
    StringBuilder out = new StringBuilder();
    Names names = new Names(o, l);
    names.seedStats(stats);
    out.append(
        csvRow(
            l.get("groups"), l.get("messages"), l.get("authors"), l.get("media"), l.get("links")));
    JSONArray groups = stats == null ? null : stats.optJSONArray("groups");
    for (int i = 0; groups != null && i < groups.length(); i++) {
      JSONObject g = groups.optJSONObject(i);
      out.append(
          csvRow(
              names.privatize(g.optString("title")),
              g.optInt("messages"),
              g.optInt("authors"),
              g.optInt("media"),
              g.optInt("links")));
    }
    out.append('\n')
        .append(
            csvRow(
                l.get("authors"),
                l.get("messages"),
                l.get("media"),
                l.get("links"),
                l.get("replies")));
    JSONArray authors = stats == null ? null : stats.optJSONArray("authors");
    for (int i = 0; authors != null && i < authors.length(); i++) {
      JSONObject a = authors.optJSONObject(i);
      out.append(
          csvRow(
              names.author(a.optLong("id"), a.optString("name")),
              a.optInt("count"),
              a.optInt("media"),
              a.optInt("links"),
              a.optInt("replies")));
    }
    for (int mode : chartModes(stats)) {
      out.append('\n')
          .append(
              csvRow(
                  l.get(
                      new String[] {"rank", "days", "heatmap", "content", "concentration"}[mode])));
      for (List<Object> row : chartRows(stats, mode, o, l)) out.append(csvRow(row.toArray()));
    }
    return out.toString();
  }

  public static String csvRow(Object... cells) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) out.append(',');
      String s = String.valueOf(cells[i]);
      String first = s.replaceFirst("^[\\s\\p{Z}\uFEFF]+", "");
      if (cells[i] instanceof String
          && !first.isEmpty()
          && "=+-@\t\r\n".indexOf(first.charAt(0)) >= 0) s = "'" + s;
      out.append('"').append(s.replace("\"", "\"\"")).append('"');
    }
    return out.append("\r\n").toString();
  }
}
