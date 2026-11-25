package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

public final class CenterTranscript {
  public static final class Segment {
    public long start, end;
    public String text;

    public Segment(long s, long e, String t) {
      start = s;
      end = e;
      text = t;
    }
  }

  public static String stamp(long ms) {
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60);
  }

  public static List<Segment> parse(String text) {
    List<Segment> out = new ArrayList<>();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?m)^\\[(\\d+):(\\d+)(?::(\\d+))?\\]\\s*(.*)$")
            .matcher(text);
    while (m.find()) {
      long start =
          m.group(3) == null
              ? (Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 1000
              : (Long.parseLong(m.group(1)) * 3600
                      + Long.parseLong(m.group(2)) * 60
                      + Long.parseLong(m.group(3)))
                  * 1000;
      out.add(new Segment(start, start + 35000, m.group(4)));
    }
    for (int i = 0; i + 1 < out.size(); i++) out.get(i).end = out.get(i + 1).start;
    return out;
  }

  public static String subtitles(List<Segment> segments, boolean vtt) {
    StringBuilder b = new StringBuilder(vtt ? "WEBVTT\n\n" : "");
    int i = 0;
    for (Segment s : segments) {
      if (s.start < 0 || s.end <= s.start) continue;
      if (!vtt) b.append(++i).append('\n');
      b.append(time(s.start, vtt))
          .append(" --> ")
          .append(time(s.end, vtt))
          .append('\n')
          .append(s.text.replace("-->", "→"))
          .append("\n\n");
    }
    return b.toString();
  }

  private static String time(long ms, boolean vtt) {
    return stamp(ms) + (vtt ? "." : ",") + String.format(Locale.ROOT, "%03d", ms % 1000);
  }
}
