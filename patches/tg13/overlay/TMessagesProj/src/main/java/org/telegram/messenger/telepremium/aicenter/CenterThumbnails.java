package org.telegram.messenger.telepremium.aicenter;

import android.graphics.*;
import android.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import org.telegram.messenger.AndroidUtilities;

public final class CenterThumbnails {
  private static final ExecutorService IO = Executors.newFixedThreadPool(2);
  private static final LruCache<String, Bitmap> CACHE =
      new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
          return b.getByteCount();
        }
      };

  public static void clear() {
    CACHE.evictAll();
  }

  public static void load(CenterData.Message m, long time, int page, Consumer<Bitmap> done) {
    load(m, time, page, 4, done);
  }

  public static void loadFull(CenterData.Message m, long time, int page, Consumer<Bitmap> done) {
    load(m, time, page, 1, done);
  }

  private static void load(
      CenterData.Message m, long time, int page, int sample, Consumer<Bitmap> done) {
    String id = (m.previewBase.isEmpty() ? m.qualified() : m.previewBase) + ":" + time + ":" + page;
    IO.execute(
        () -> {
          Bitmap b = null;
          try {
            if (!CenterMedia.readableDerivative(m)) throw new SecurityException();
            b = CACHE.get(m.account + ":" + m.owner + ":" + sample + ":" + id);
            if (b == null) {
              JSONObject o = CenterStore.get().get(m.account, m.owner, "preview", id);
              if (o != null) {
                byte[] jpeg = Base64.decode(o.optString("jpeg"), Base64.NO_WRAP);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                if (b != null) CACHE.put(m.account + ":" + m.owner + ":" + sample + ":" + id, b);
                java.util.Arrays.fill(jpeg, (byte) 0);
              }
            }
          } catch (Exception ignore) {
          }
          Bitmap result = b;
          AndroidUtilities.runOnUIThread(() -> done.accept(result));
        });
  }
}
