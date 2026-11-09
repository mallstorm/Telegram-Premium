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
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TelePremiumArtworkLoader() {}

    public static void load(Context context, TelePremiumMediaItem item, int sizePx, Callback callback) {
        if (context != null && item != null && callback != null && item.artworkUri != null
                && (item.artworkUri.startsWith("https://") || item.artworkUri.startsWith("http://"))) {
            loadRemote(item.artworkUri, sizePx, callback);
            return;
        }
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
            } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
            if (bitmap != null) CACHE.put(key, bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> callback.onLoaded(result));
        });
    }

    /** Site icon service used when a station logo is missing or cannot be decoded (SVG, broken link). */
    public static String siteIconUrl(String host) {
        return "https://www.google.com/s2/favicons?sz=256&domain=" + host;
    }

    /** Station / channel logos (radio favicon, IPTV tvg-logo). Small, cached, never blocks the UI. */
    private static void loadRemote(String url, int sizePx, Callback callback) {
        final String key = "remote:" + url + ":" + sizePx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = fetchBitmap(url, sizePx);
            if (bitmap == null) {
                // SVG / dead favicon: fall back to the site's icon.
                try {
                    String host = Uri.parse(url).getHost();
                    if (host != null && !url.startsWith("https://www.google.com/s2/")) bitmap = fetchBitmap(siteIconUrl(host), sizePx);
                } catch (Throwable ignore) {
                }
            }
            // 16 px favicons and the generic globe for unknown sites are not logos: keep the fallback tile.
            if (bitmap != null && Math.max(bitmap.getWidth(), bitmap.getHeight()) < 32) bitmap = null;
            if (bitmap != null) CACHE.put(key, bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> callback.onLoaded(result));
        });
    }

    private static Bitmap fetchBitmap(String url, int sizePx) {
        java.net.HttpURLConnection c = null;
        try {
            String current = url;
            // HttpURLConnection never follows http <-> https redirects by itself; logos often need one.
            for (int hop = 0; hop < 5; hop++) {
                c = (java.net.HttpURLConnection) new java.net.URL(current).openConnection(org.telegram.messenger.telepremium.xray.XrayRuntime.mediaProxy());
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TelePremium/MediaCenter");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (location == null) return null;
                    current = new java.net.URL(new java.net.URL(current), location).toString();
                    continue;
                }
                if (code / 100 != 2 || c.getContentLength() > 3 * 1024 * 1024) return null;
                try (java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 3 * 1024 * 1024) out.write(buf, 0, n);
                    byte[] data = out.toByteArray();
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    int sample = 1;
                    while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2;
                    opts.inSampleSize = sample;
                    return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                }
            }
        } catch (Throwable e) {
            // Broken logos are common in public catalogs; the UI keeps its fallback icon.
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
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
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
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
