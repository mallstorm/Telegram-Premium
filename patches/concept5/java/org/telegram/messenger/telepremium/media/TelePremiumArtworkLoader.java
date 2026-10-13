package org.telegram.messenger.telepremium.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight artwork/thumbnail loader for the TelePremium media library. */
public final class TelePremiumArtworkLoader {
    public interface Callback { void onLoaded(Bitmap bitmap); }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : Math.max(1, value.getByteCount() / 1024);
        }
    };
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TelePremiumArtworkLoader() {}

    public static void load(Context context, TelePremiumMediaItem item, int sizePx, Callback callback) {
        if (context == null || item == null || callback == null || item.sourceType != TelePremiumMediaItem.SOURCE_DEVICE || item.uri == null) {
            if (callback != null) MAIN.post(() -> callback.onLoaded(null));
            return;
        }
        final String key = item.id + ":" + sizePx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                Uri uri = Uri.parse(item.uri);
                if (item.isVideo()) bitmap = loadVideo(app, uri, sizePx);
                else bitmap = loadAudio(app, uri);
            } catch (Throwable ignore) {}
            if (bitmap != null) CACHE.put(key, bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> callback.onLoaded(result));
        });
    }

    private static Bitmap loadVideo(Context context, Uri uri, int sizePx) {
        try {
            ContentResolver resolver = context.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29 && "content".equalsIgnoreCase(uri.getScheme())) {
                return resolver.loadThumbnail(uri, new Size(sizePx, sizePx), null);
            }
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                File file = new File(uri.getPath());
                if (file.exists()) return ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private static Bitmap loadAudio(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            byte[] data = retriever.getEmbeddedPicture();
            if (data != null && data.length > 0) return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable ignore) {
        } finally {
            try { retriever.release(); } catch (Throwable ignore) {}
        }
        return null;
    }
}
