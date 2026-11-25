package org.telegram.messenger.telepremium.aicenter;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import java.io.*;
import java.util.*;
import org.json.*;

/** Paginated printable report with Cyrillic rendered by Android's font stack. */
public final class CenterPdf implements AutoCloseable {
  private final PdfDocument document = new PdfDocument();
  private PdfDocument.Page page;
  private Canvas canvas;
  private float y;
  private int number;
  private final Paint paint = new Paint(3);
  private final CenterExport.Labels labels;

  private CenterPdf(CenterExport.Labels l) {
    labels = l;
    newPage();
  }

  private void newPage() {
    if (page != null) {
      paint.setTextSize(9);
      paint.setColor(Color.GRAY);
      canvas.drawText("" + number, 555, 817, paint);
      document.finishPage(page);
    }
    page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, ++number).create());
    canvas = page.getCanvas();
    canvas.drawColor(Color.WHITE);
    y = 38;
  }

  private void room(float height) {
    if (y + height > 800) newPage();
  }

  private void text(String raw, float size, boolean bold) {
    paint.setColor(Color.BLACK);
    paint.setTextSize(size);
    paint.setTypeface(
        bold
            ? Typeface.create("sans-serif", Typeface.BOLD)
            : Typeface.create("sans-serif", Typeface.NORMAL));
    for (String original : raw.split("\n", -1)) {
      String line = original.isEmpty() ? " " : original;
      while (!line.isEmpty()) {
        int n = Math.max(1, paint.breakText(line, true, 515, null));
        if (n < line.length() && n > 1 && Character.isHighSurrogate(line.charAt(n - 1))) n--;
        int space = line.lastIndexOf(' ', n - 1);
        if (n < line.length() && space > n / 2) n = space;
        room(size * 1.5f);
        canvas.drawText(line.substring(0, n), 40, y + size, paint);
        y += size * 1.4f;
        line = line.substring(n);
        if (line.startsWith(" ")) line = line.substring(1);
      }
    }
    y += 5;
  }

  private void image(byte[] bytes, float maxHeight) {
    if (bytes == null) return;
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inSampleSize = 2;
    Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    if (b == null) return;
    try {
      float scale = Math.min(515f / b.getWidth(), maxHeight / b.getHeight()),
          h = b.getHeight() * scale,
          w = b.getWidth() * scale;
      room(h + 30);
      canvas.drawBitmap(b, null, new RectF(40, y, 40 + w, y + h), paint);
      y += h + 8;
    } finally {
      b.recycle();
    }
  }

  public static void write(
      CenterReports.Report r,
      CenterExport.Options options,
      CenterExport.Labels labels,
      JSONObject stats,
      CenterExport.Images images,
      File file)
      throws Exception {
    try (CenterPdf pdf = new CenterPdf(labels)) {
      CenterExport.Names names = new CenterExport.Names(options, labels);
      pdf.text(r.question, 21, true);
      pdf.text(CenterExport.date(r.from) + " — " + CenterExport.date(r.to), 11, false);
      pdf.text(
          labels.get("coverage")
              + ": "
              + r.analyzed
              + " / "
              + r.indexed
              + " · "
              + labels.get(r.complete ? "done" : "partial"),
          11,
          false);
      for (String block : options.blocks) {
        if (block.equals("main"))
          for (CenterReports.Claim claim : r.claims) {
            pdf.text(claim.title, 15, true);
            pdf.text(claim.text, 11, false);
            pdf.text(labels.get(claim.verified() ? claim.certainty : "unverified"), 10, false);
            for (CenterReports.Evidence e : claim.evidence) {
              pdf.text(
                  names.author(e.message)
                      + " · "
                      + e.message.sourceTitle
                      + " · "
                      + names.account(e.message)
                      + " · "
                      + CenterExport.date(e.message.date),
                  9,
                  true);
              if (!e.quote.isEmpty()) pdf.text("“" + e.quote + "”", 10, false);
              if (e.timeMs > 0) pdf.text(CenterTranscript.stamp(e.timeMs), 9, false);
              if (e.page > 0) pdf.text(labels.get("page") + " " + e.page, 9, false);
              if (!CenterExport.link(e.message).isEmpty())
                pdf.text(CenterExport.link(e.message), 8, false);
            }
          }
        if (block.equals("charts") && stats != null) {
          for (int mode = 0; mode < 5; mode++) {
            pdf.text(
                labels.get(
                    new String[] {"rank", "days", "heatmap", "content", "concentration"}[mode]),
                15,
                true);
            byte[] chart = CenterRaster.chart(stats, mode, names, labels);
            pdf.image(chart, 330);
            if (chart != null) Arrays.fill(chart, (byte) 0);
          }
          JSONArray groups = stats.optJSONArray("groups");
          for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            pdf.text(
                g.optString("title")
                    + " · "
                    + g.optInt("messages")
                    + " · "
                    + labels.get("authors")
                    + ": "
                    + g.optInt("authors"),
                10,
                false);
          }
        }
        if (block.equals("authors") && stats != null) {
          pdf.text(labels.get("authorWarning"), 10, false);
          JSONArray authors = stats.optJSONArray("authors");
          for (int i = 0; authors != null && i < authors.length(); i++) {
            JSONObject a = authors.optJSONObject(i);
            pdf.text(
                names.author(a.optLong("id"), a.optString("name"))
                    + " · "
                    + a.optInt("count")
                    + " · "
                    + labels.get("media")
                    + ": "
                    + a.optInt("media"),
                10,
                false);
          }
        }
        if (block.equals("media") && options.images && images != null) {
          Set<String> seen = new HashSet<>();
          for (CenterReports.Claim claim : r.claims)
            for (CenterReports.Evidence e : claim.evidence)
              if (seen.add(e.message.qualified() + ":" + e.timeMs + ":" + e.page)) {
                byte[] bytes = images.read(e);
                if (bytes == null) continue;
                pdf.image(bytes, 330);
                Arrays.fill(bytes, (byte) 0);
                pdf.text(
                    names.author(e.message)
                        + " · "
                        + e.message.sourceTitle
                        + " · "
                        + CenterExport.date(e.message.date),
                    9,
                    true);
                pdf.text(e.message.description, 10, false);
                pdf.text(CenterExport.link(e.message), 8, false);
              }
        }
        if (block.equals("transcripts")) {
          for (CenterData.Message transcript : CenterReports.transcripts(r)) {
            pdf.text(transcript.sourceTitle, 14, true);
            pdf.text(transcript.transcript, 10, false);
          }
        }
        if (block.equals("unclear")) {
          pdf.text(labels.get("unknown"), 14, true);
          for (String line : r.unclear) pdf.text(line, 11, false);
        }
      }
      if (pdf.page != null) {
        pdf.document.finishPage(pdf.page);
        pdf.page = null;
      }
      try (OutputStream out = new FileOutputStream(file)) {
        pdf.document.writeTo(out);
      }
    }
  }

  @Override
  public void close() {
    if (page != null) {
      document.finishPage(page);
      page = null;
    }
    document.close();
  }
}
