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
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "(?ms)^\\[(\\d+):(\\d+)(?::(\\d+))?\\][ \\t]*(.*?)(?=^\\[\\d+:\\d+(?::\\d+)?\\]|\\z)").matcher(text);
    while (matcher.find()) {
      try {
        long first = Long.parseLong(matcher.group(1));
        long second = Long.parseLong(matcher.group(2));
        long third = matcher.group(3) == null ? -1 : Long.parseLong(matcher.group(3));
        if (first > 1000000 || second >= 60 || third >= 60) continue;
        long start = (third < 0 ? first * 60 + second : first * 3600 + second * 60 + third) * 1000;
        out.add(new Segment(start, start + 35000, matcher.group(4).trim()));
      } catch (NumberFormatException invalid) {
        // Ignore malformed timestamps rather than misrouting player seeks.
      }
    }
    out.sort(Comparator.comparingLong(segment -> segment.start));
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
