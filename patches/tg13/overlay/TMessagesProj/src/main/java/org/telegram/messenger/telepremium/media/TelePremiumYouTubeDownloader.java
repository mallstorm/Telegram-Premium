package org.telegram.messenger.telepremium.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads a YouTube video in the chosen quality with the chosen audio track:
 * both streams are fetched in 8 MB range chunks (no throttling, resumable per chunk, 3 retries),
 * then joined into one file with MediaMuxer without re-encoding (MP4 for H.264 + AAC, WebM for
 * VP9 + Opus) and saved to Movies/TelePremium. "Audio only" saves the audio track to
 * Music/TelePremium. Progress is shown as a notification.
 */
public final class TelePremiumYouTubeDownloader {
    private static final String CHANNEL = "telepremium_downloads";
    private static final long CHUNK = 8L * 1024 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicInteger IDS = new AtomicInteger(7800);

    public interface Callback { void onDone(String savedName, String error); }

    private TelePremiumYouTubeDownloader() {}

    /** @param video null = audio only; @param audio null = muxed video (already has sound). */
    public static void download(String title, TelePremiumYouTube.VideoOption video, TelePremiumYouTube.AudioOption audio, Callback callback) {
        int notificationId = IDS.incrementAndGet();
        EXECUTOR.execute(() -> {
            Context context = ApplicationLoader.applicationContext;
            File dir = new File(context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir(), "telepremium_yt");
            dir.mkdirs();
            File videoFile = null, audioFile = null, out = null;
            String saved = null, error = null;
            try {
                String safe = title == null ? "video" : title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (safe.length() > 90) safe = safe.substring(0, 90);
                long total = (video == null ? 0 : video.size) + (audio == null ? 0 : audio.size);
                long[] done = {0};
                Progress progress = new Progress(notificationId, safe, total);
                if (video != null) {
                    videoFile = new File(dir, "v_" + notificationId + "." + video.format);
                    fetch(video.url, videoFile, done, progress);
                }
                if (audio != null) {
                    audioFile = new File(dir, "a_" + notificationId + "." + audio.format);
                    fetch(audio.url, audioFile, done, progress);
                }
                progress.text(org.telegram.messenger.telepremium.TPStrings.get("TP_YT_MUXING", R.string.TP_YT_MUXING));
                if (video != null && audio != null && video.videoOnly) {
                    boolean webm = video.webm;
                    out = new File(dir, "o_" + notificationId + (webm ? ".webm" : ".mp4"));
                    mux(videoFile, audioFile, out, webm);
                    saved = export(out, safe + (webm ? ".webm" : ".mp4"), webm ? "video/webm" : "video/mp4", true);
                } else if (video != null) {
                    saved = export(videoFile, safe + "." + video.format, video.webm ? "video/webm" : "video/mp4", true);
                } else if (audio != null) {
                    saved = export(audioFile, safe + "." + (audio.webm ? "webm" : "m4a"), audio.webm ? "audio/webm" : "audio/mp4", false);
                }
                progress.finish(org.telegram.messenger.telepremium.TPStrings.format("TP_REC_SAVED", R.string.TP_REC_SAVED, saved));
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                new Progress(notificationId, title, 0).finish(org.telegram.messenger.telepremium.TPStrings.get("TP_YT_FAILED", R.string.TP_YT_FAILED) + ": " + error);
            } finally {
                if (videoFile != null) videoFile.delete();
                if (audioFile != null) audioFile.delete();
                if (out != null) out.delete();
            }
            String s = saved, err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onDone(s, err));
        });
    }

    // ------------------------------------------------------------------ network

    private static void fetch(String url, File target, long[] done, Progress progress) throws Exception {
        long position = 0, length = -1;
        try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
            file.setLength(0);
            while (length < 0 || position < length) {
                long end = position + CHUNK - 1;
                if (length > 0) end = Math.min(end, length - 1);
                int attempt = 0;
                while (true) {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection(org.telegram.messenger.telepremium.xray.XrayRuntime.mediaProxy());
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0");
                        c.setRequestProperty("Range", "bytes=" + position + "-" + end);
                        int code = c.getResponseCode();
                        if (code != 206 && code != 200) throw new java.io.IOException("HTTP " + code);
                        if (length < 0) {
                            String range = c.getHeaderField("Content-Range");
                            if (range != null && range.contains("/")) {
                                try { length = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim()); } catch (NumberFormatException ignore) {}
                            }
                            if (length < 0) length = code == 200 ? c.getContentLengthLong() : -1;
                            if (length > 0) progress.addTotal(length);
                        }
                        file.seek(position);
                        byte[] buffer = new byte[256 * 1024];
                        long got = 0;
                        try (InputStream in = c.getInputStream()) {
                            int n;
                            while ((n = in.read(buffer)) > 0) {
                                file.write(buffer, 0, n);
                                got += n;
                                done[0] += n;
                                progress.update(done[0]);
                            }
                        }
                        position += got;
                        if (code == 200) length = position; // server ignored Range: whole file received
                        if (got == 0) throw new java.io.IOException("Empty response");
                        break;
                    } catch (Exception e) {
                        if (++attempt >= 3) throw e;
                        Thread.sleep(1500L * attempt);
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
                if (length < 0) break;
            }
        }
    }

    // ------------------------------------------------------------------ mux

    private static void mux(File video, File audio, File out, boolean webm) throws Exception {
        MediaExtractor ve = new MediaExtractor(), ae = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vTrack = selectTrack(ve, "video/"), aTrack = selectTrack(ae, "audio/");
            if (vTrack < 0 || aTrack < 0) throw new IllegalStateException("Unsupported stream container");
            ve.selectTrack(vTrack);
            ae.selectTrack(aTrack);
            muxer = new MediaMuxer(out.getAbsolutePath(), webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = muxer.addTrack(ve.getTrackFormat(vTrack));
            int aOut = muxer.addTrack(ae.getTrackFormat(aTrack));
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean vDone = false, aDone = false;
            while (!vDone || !aDone) {
                // interleave by timestamp
                boolean takeVideo = !vDone && (aDone || ve.getSampleTime() <= ae.getSampleTime());
                MediaExtractor e = takeVideo ? ve : ae;
                int size = e.readSampleData(buffer, 0);
                if (size < 0) {
                    if (takeVideo) vDone = true; else aDone = true;
                    continue;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = e.getSampleTime();
                info.flags = (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(takeVideo ? vOut : aOut, buffer, info);
                e.advance();
            }
            muxer.stop();
        } finally {
            ve.release();
            ae.release();
            if (muxer != null) try { muxer.release(); } catch (Throwable ignore) {}
        }
    }

    private static int selectTrack(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String mime = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ export

    private static String export(File file, String name, String mime, boolean video) throws Exception {
        Context context = ApplicationLoader.applicationContext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC) + "/TelePremium");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) throw new IllegalStateException("MediaStore refused the file");
            try (InputStream in = new FileInputStream(file); OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                copy(in, out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC), "TelePremium");
            dir.mkdirs();
            File target = new File(dir, name);
            try (InputStream in = new FileInputStream(file); OutputStream out = new java.io.FileOutputStream(target)) {
                copy(in, out);
            }
            android.media.MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
        }
        return name;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        if (out == null) throw new IllegalStateException("Cannot write the file");
        byte[] buffer = new byte[512 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    // ------------------------------------------------------------------ notification

    private static final class Progress {
        private final int id;
        private final String title;
        private long total;
        private boolean totalKnownFromOptions;
        private long lastPost;
        private final NotificationManager nm;

        Progress(int id, String title, long total) {
            this.id = id;
            this.title = title;
            this.total = total;
            this.totalKnownFromOptions = total > 0;
            Context context = ApplicationLoader.applicationContext;
            nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "TelePremium Downloads", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
            post(0, null, true);
        }

        void addTotal(long bytes) { if (!totalKnownFromOptions) total += bytes; }

        void update(long done) {
            long now = System.currentTimeMillis();
            if (now - lastPost < 700) return;
            lastPost = now;
            post(total > 0 ? (int) Math.min(100, done * 100 / total) : 0, total > 0 ? TelePremiumYouTube.size(done) + " / " + TelePremiumYouTube.size(total) : TelePremiumYouTube.size(done), true);
        }

        void text(String text) { post(100, text, true); }

        void finish(String text) { post(-1, text, false); }

        private void post(int percent, String text, boolean ongoing) {
            if (nm == null) return;
            Context context = ApplicationLoader.applicationContext;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
            b.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text == null ? "YouTube" : text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(!ongoing);
            if (percent >= 0 && ongoing) b.setProgress(100, percent, percent == 0 && total <= 0);
            try { nm.notify(id, b.build()); } catch (Throwable ignore) {}
        }
    }
}
