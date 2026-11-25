package org.telegram.messenger.telepremium.aicenter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.telegram.messenger.ApplicationLoader;

/** Plain files exist only after an explicit export. The working archive remains encrypted. */
public final class CenterFiles {
  private CenterFiles() {}

  private static File file(String extension) throws Exception {
    File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sharing");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Export directory");
    File[] previous = dir.listFiles();
    if (previous != null)
      for (File f : previous)
        if (f.getName().startsWith("AI-Center-")
            && System.currentTimeMillis() - f.lastModified() > 3600000) f.delete();
    return new File(dir, "AI-Center-" + System.nanoTime() + "." + extension);
  }

  public static File transcript(CenterData.Message m, String format) throws Exception {
    File file = file(format);
    String text =
        format.equals("txt")
            ? m.transcript
            : CenterTranscript.subtitles(
                CenterTranscript.parse(m.transcript), format.equals("vtt"));
    try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
      w.write(text);
    }
    return file;
  }

  public static File export(
      CenterReports.Report report, CenterExport.Options options, String format) throws Exception {
    CenterAssets.save(report, "report", options);
    File file = file(format);
    CenterExport.Labels labels = CenterText::get;
    CenterExport.Names names = new CenterExport.Names(options, labels, report);
    CenterExport.Images images =
        new CenterExport.Images() {
          public byte[] read(CenterReports.Evidence e) throws Exception {
            return CenterAssets.image(report, e);
          }

          public byte[] chart(int mode) throws Exception {
            return CenterRaster.chart(report.statistics, mode, names, labels);
          }
        };
    if (format.equals("pdf"))
      CenterPdf.write(report, options, labels, report.statistics, images, file);
    else if (format.equals("png")) {
      byte[] image = CenterRaster.chart(report.statistics, 0, names, labels);
      if (image == null) throw new IOException(CenterText.get("empty"));
      try (OutputStream out = new FileOutputStream(file)) {
        out.write(image);
      }
      Arrays.fill(image, (byte) 0);
    } else
      try (Writer out =
          new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
        if (format.equals("html"))
          CenterExport.html(report, options, labels, report.statistics, images, out);
        else if (format.equals("md"))
          CenterExport.md(report, options, labels, report.statistics, images, out);
        else if (format.equals("csv"))
          out.write(CenterExport.csv(report.statistics, options, labels));
        else out.write(CenterExport.text(report, options, labels, report.statistics));
      }
    return file;
  }

  public static String mime(String extension) {
    switch (extension) {
      case "pdf":
        return "application/pdf";
      case "png":
        return "image/png";
      case "html":
        return "text/html";
      case "md":
        return "text/markdown";
      case "csv":
        return "text/csv";
      case "srt":
        return "application/x-subrip";
      case "vtt":
        return "text/vtt";
      default:
        return "text/plain";
    }
  }
}
