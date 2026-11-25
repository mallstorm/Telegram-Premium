package org.telegram.messenger.telepremium.aicenter;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Analyzer-owned working copies. Callers may delete the result, never the original Cloud/SAF file.
 */
public final class CenterInputFiles {
  private CenterInputFiles() {}

  public static File copy(InputStream input, File directory, long limit, AtomicBoolean cancelled)
      throws IOException {
    File result = null;
    try (InputStream in = input) {
      if (in == null || cancelled.get()) throw new IOException("Input unavailable");
      result = File.createTempFile("aic_input_", ".tmp", directory);
      try (OutputStream out = new FileOutputStream(result)) {
        byte[] buffer = new byte[65536];
        long size = 0;
        for (int count; (count = in.read(buffer)) >= 0; ) {
          if (cancelled.get() || count > limit - size)
            throw new IOException("Input budget or cancellation");
          out.write(buffer, 0, count);
          size += count;
        }
      }
      return result;
    } catch (IOException | RuntimeException error) {
      if (result != null) result.delete();
      throw error;
    }
  }
}
