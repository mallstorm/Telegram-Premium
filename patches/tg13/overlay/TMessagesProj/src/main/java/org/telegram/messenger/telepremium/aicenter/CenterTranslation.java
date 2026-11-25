package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/** Translation changes words only; times, order and anonymous speakers come from the original. */
public final class CenterTranslation {
  private CenterTranslation() {}

  public static List<JSONArray> batches(String transcript, int limit) throws Exception {
    List<JSONArray> result = new ArrayList<>();
    JSONArray batch = new JSONArray();
    int length = 0, index = 0;
    List<CenterTranscript.Segment> segments = CenterTranscript.parse(transcript);
    if (segments.isEmpty() && !transcript.trim().isEmpty())
      segments = Arrays.asList(new CenterTranscript.Segment(0, 35000, transcript));
    for (CenterTranscript.Segment segment : segments) {
      for (int offset = 0; offset < segment.text.length(); ) {
        int end = Math.min(segment.text.length(), offset + Math.max(256, limit / 2));
        if (end < segment.text.length() && Character.isHighSurrogate(segment.text.charAt(end - 1)))
          end--;
        String text = segment.text.substring(offset, end);
        JSONObject part = new JSONObject().put("id", index + ":" + offset).put("text", text);
        int size = part.toString().length();
        if (length + size > limit && batch.length() > 0) {
          result.add(batch);
          batch = new JSONArray();
          length = 0;
        }
        batch.put(part);
        length += size;
        offset = end;
      }
      index++;
    }
    if (batch.length() > 0) result.add(batch);
    return result;
  }

  public static JSONObject parse(String raw) throws Exception {
    String text = raw.trim();
    if (text.startsWith("```")) {
      int line = text.indexOf('\n'), end = text.lastIndexOf("```");
      if (line < 0 || end <= line) throw new JSONException("Invalid translation JSON");
      text = text.substring(line + 1, end).trim();
    }
    return new JSONObject(text);
  }

  public static JSONObject validate(JSONArray requested, JSONObject response) throws Exception {
    JSONArray parts = response.optJSONArray("parts");
    if (parts == null || parts.length() != requested.length())
      throw new JSONException("Translation parts mismatch");
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < requested.length(); i++)
      expected.add(requested.optJSONObject(i).optString("id"));
    JSONObject result = new JSONObject();
    for (int i = 0; i < parts.length(); i++) {
      JSONObject part = parts.optJSONObject(i);
      if (part == null
          || !expected.remove(part.optString("id"))
          || part.optString("text").trim().isEmpty())
        throw new JSONException("Invalid translation part");
      result.put(part.optString("id"), part.optString("text"));
    }
    if (!expected.isEmpty()) throw new JSONException("Missing translation part");
    return result;
  }

  public static String apply(String original, JSONObject translated, long duration)
      throws Exception {
    List<CenterTranscript.Segment> segments = CenterTranscript.parse(original, duration);
    boolean timed = !segments.isEmpty();
    if (!timed)
      segments =
          Arrays.asList(new CenterTranscript.Segment(0, duration > 0 ? duration : 35000, original));
    List<JSONArray> parts = batches(original, 12000);
    Map<Integer, SortedMap<Integer, String>> grouped = new HashMap<>();
    Set<Integer> incomplete = new HashSet<>();
    for (JSONArray batch : parts)
      for (int i = 0; i < batch.length(); i++) {
        String key = batch.optJSONObject(i).optString("id");
        String[] address = key.split(":");
        int segment = Integer.parseInt(address[0]), offset = Integer.parseInt(address[1]);
        if (!translated.has(key)) {
          incomplete.add(segment);
          continue;
        }
        grouped
            .computeIfAbsent(segment, ignored -> new TreeMap<>())
            .put(offset, translated.optString(key));
      }
    List<CenterTranscript.Segment> result = new ArrayList<>();
    for (int i = 0; i < segments.size(); i++)
      if (!incomplete.contains(i) && grouped.containsKey(i)) {
        CenterTranscript.Segment source = segments.get(i),
            target =
                new CenterTranscript.Segment(
                    source.start, source.end, String.join(" ", grouped.get(i).values()));
        target.speaker = source.speaker;
        result.add(target);
      }
    if (!timed) return result.isEmpty() ? "" : result.get(0).text;
    return CenterTranscript.encode(result);
  }
}
