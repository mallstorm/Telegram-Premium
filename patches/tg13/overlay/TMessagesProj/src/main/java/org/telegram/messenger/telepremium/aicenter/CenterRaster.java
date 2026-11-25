package org.telegram.messenger.telepremium.aicenter;

import android.graphics.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Standalone chart raster shared by HTML, PDF and image export. */
public final class CenterRaster {
  public static byte[] chart(
      JSONObject stats, int mode, CenterExport.Names names, CenterExport.Labels labels)
      throws Exception {
    if (stats == null) return null;
    Bitmap bitmap = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bitmap);
    Paint p = new Paint(3);
    c.drawColor(Color.WHITE);
    p.setColor(Color.BLACK);
    p.setTextSize(30);
    c.drawText(
        labels.get(new String[] {"rank", "days", "heatmap", "content", "concentration"}[mode]),
        35,
        45,
        p);
    int[] colors = {0xff277ac0, 0xff22a088, 0xffa56ab2, 0xffd08735};
    JSONArray groups = stats.optJSONArray("groups"), authors = stats.optJSONArray("authors");
    if (groups == null) groups = new JSONArray();
    int count = Math.min(mode == 1 ? 4 : 10, groups.length());
    if (mode == 0 || mode == 3 || mode == 4) {
      JSONArray rows = mode == 4 ? authors : groups;
      if (rows == null) rows = new JSONArray();
      count = Math.min(10, rows.length());
      int max = 1;
      for (int i = 0; i < count; i++)
        max = Math.max(max, rows.optJSONObject(i).optInt(mode == 4 ? "count" : "messages"));
      for (int i = 0; i < count; i++) {
        JSONObject row = rows.optJSONObject(i);
        float y = 90 + i * 55;
        String title =
            mode == 4
                ? names.author(row.optLong("id"), row.optString("name"))
                : row.optString("title");
        p.setColor(Color.BLACK);
        p.setTextSize(22);
        c.drawText(title.length() > 25 ? title.substring(0, 25) + "…" : title, 25, y + 28, p);
        float x = 360, total = 760f * row.optInt(mode == 4 ? "count" : "messages") / max;
        if (mode == 3) {
          JSONObject types = row.optJSONObject("types");
          Iterator<String> keys = types == null ? Collections.emptyIterator() : types.keys();
          int n = 0;
          while (keys.hasNext()) {
            String type = keys.next();
            float width = 760f * types.optInt(type) / max;
            p.setColor(colors[n++ % colors.length]);
            c.drawRect(x, y, x + width, y + 35, p);
            x += width;
          }
        } else {
          p.setColor(colors[i % colors.length]);
          c.drawRect(x, y, x + total, y + 35, p);
        }
        p.setColor(Color.BLACK);
        c.drawText(
            "" + row.optInt(mode == 4 ? "count" : "messages"),
            Math.min(1125, 365 + total),
            y + 25,
            p);
      }
    } else if (mode == 1) {
      SortedSet<String> dates = new TreeSet<>();
      int max = 1;
      for (int i = 0; i < count; i++) {
        JSONObject d = groups.optJSONObject(i).optJSONObject("daily");
        if (d != null) {
          Iterator<String> keys = d.keys();
          while (keys.hasNext()) {
            String day = keys.next();
            dates.add(day);
            max = Math.max(max, d.optInt(day));
          }
        }
      }
      List<String> days = new ArrayList<>(dates);
      for (int i = 0; i < count; i++) {
        JSONObject group = groups.optJSONObject(i), daily = group.optJSONObject("daily");
        Path path = new Path();
        for (int n = 0; n < days.size(); n++) {
          float x = 60 + 1080f * n / Math.max(1, days.size() - 1),
              y = 610 - 430f * (daily == null ? 0 : daily.optInt(days.get(n))) / max;
          if (n == 0) path.moveTo(x, y);
          else path.lineTo(x, y);
        }
        p.setColor(colors[i % colors.length]);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(21);
        c.drawText(group.optString("title"), 50, 90 + i * 30, p);
      }
      p.setColor(Color.BLACK);
      if (!days.isEmpty()) {
        c.drawText(days.get(0), 40, 660, p);
        c.drawText(days.get(days.size() - 1), 960, 660, p);
      }
    } else if (mode == 2 && groups.length() > 0) {
      JSONObject group = groups.optJSONObject(0);
      JSONArray heat = group.optJSONArray("heat");
      int max = 1;
      for (int d = 0; heat != null && d < heat.length(); d++) {
        JSONArray hours = (JSONArray) heat.opt(d);
        for (int h = 0; h < hours.length(); h++) max = Math.max(max, hours.optInt(h));
      }
      String[] weekdays = new java.text.DateFormatSymbols().getShortWeekdays();
      for (int d = 0; d < 7; d++) {
        p.setColor(Color.BLACK);
        p.setTextSize(21);
        c.drawText(weekdays[(d + 1) % 7 + 1], 10, 135 + d * 65, p);
        JSONArray hours = heat == null ? null : (JSONArray) heat.opt(d);
        for (int h = 0; h < 24; h++) {
          int n = hours == null ? 0 : hours.optInt(h);
          p.setColor(Color.rgb(235 - 200 * n / max, 245 - 130 * n / max, 255 - 30 * n / max));
          c.drawRect(90 + h * 44, 90 + d * 65, 132 + h * 44, 151 + d * 65, p);
        }
      }
      p.setColor(Color.BLACK);
      for (int h = 0; h < 24; h += 3) c.drawText("" + h, 90 + h * 44, 585, p);
      c.drawText(group.optString("title"), 90, 640, p);
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      return out.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }
}
