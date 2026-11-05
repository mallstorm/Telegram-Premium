package org.telegram.messenger.telepremium.media;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Background recording of whatever the TelePremium player plays (radio, music, any stream):
 * the decoded PCM after the equalizer is taken from {@link TelePremiumBalanceAudioProcessor},
 * encoded to AAC-LC 192 kbps and saved as .m4a into Music/TelePremium/Recordings (visible in
 * the Device tab and in any music app). Pausing playback simply pauses the recording.
 */
public final class TelePremiumStreamRecorder {
    public interface Listener { void onRecorderChanged(boolean recording, long elapsedMs, String savedName, String error); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Session session;

    private TelePremiumStreamRecorder() {}

    public static boolean isRecording() { return session != null; }

    public static long elapsedMs() {
        Session s = session;
        return s == null ? 0 : s.elapsedMs();
    }

    public static void addListener(Listener l) { if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l); }

    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    /** Starts recording; the name of the current item becomes the file name. */
    public static synchronized boolean start(String title) {
        if (session != null) return true;
        try {
            session = new Session(TextUtils.isEmpty(title) ? "TelePremium" : title);
            notifyListeners(true, 0, null, null);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            session = null;
            notifyListeners(false, 0, null, e.getMessage());
            return false;
        }
    }

    public static synchronized void stop() {
        Session s = session;
        session = null;
        if (s != null) s.finish();
    }

    public static void toggle(String title) {
        if (isRecording()) stop(); else start(title);
    }

    /** Called from the audio processor thread with interleaved 16-bit PCM. */
    static void feed(ByteBuffer pcm, int sampleRate, int channels) {
        Session s = session;
        if (s == null || pcm == null || !pcm.hasRemaining()) return;
        byte[] data = new byte[pcm.remaining()];
        pcm.duplicate().get(data);
        s.offer(data, sampleRate, channels);
    }

    private static void notifyListeners(boolean recording, long elapsed, String saved, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : LISTENERS) l.onRecorderChanged(recording, elapsed, saved, error);
        });
    }

    // ---------------------------------------------------------------------------------

    private static final class Session implements Runnable {
        private static final byte[] EOS = new byte[0];
        private final String title;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(512);
        private final File file;
        private final Thread thread;
        private volatile int sampleRate, channels;
        private volatile long samplesWritten;
        private MediaCodec codec;
        private MediaMuxer muxer;
        private int track = -1;
        private boolean muxerStarted;

        Session(String title) throws Exception {
            this.title = title;
            File dir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("No storage for recordings");
            file = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
            thread = new Thread(this, "TelePremiumRecorder");
            thread.start();
        }

        long elapsedMs() {
            int rate = sampleRate;
            return rate <= 0 ? 0 : samplesWritten * 1000L / rate;
        }

        void offer(byte[] data, int rate, int ch) {
            if (sampleRate == 0) { sampleRate = rate; channels = ch; }
            if (rate != sampleRate || ch != channels) return; // a different stream format: keep the file consistent
            queue.offer(data);
        }

        void finish() { queue.offer(EOS); }

        @Override public void run() {
            String error = null;
            try {
                long lastNotify = 0;
                while (true) {
                    byte[] data = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (data == null) continue;
                    if (data == EOS) break;
                    if (codec == null) prepare();
                    encode(data, false);
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 1000) {
                        lastNotify = now;
                        notifyListeners(true, elapsedMs(), null, null);
                    }
                }
                if (codec != null) encode(null, true);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() == null ? "Recording failed" : e.getMessage();
            } finally {
                release();
            }
            String saved = null;
            if (error == null && muxerStarted && file.length() > 0) {
                try {
                    saved = export();
                } catch (Throwable e) {
                    FileLog.e(e);
                    error = e.getMessage();
                }
            }
            file.delete();
            notifyListeners(false, elapsedMs(), saved, error);
        }

        private void prepare() throws Exception {
            int ch = Math.max(1, Math.min(2, channels));
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, ch);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 192_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        /** Channels > 2 are folded to stereo (front left / right). */
        private byte[] toStereo(byte[] data) {
            if (channels <= 2) return data;
            int frames = data.length / (channels * 2);
            byte[] out = new byte[frames * 4];
            for (int f = 0; f < frames; f++) {
                System.arraycopy(data, f * channels * 2, out, f * 4, 4);
            }
            return out;
        }

        private void encode(byte[] data, boolean end) {
            if (end) {
                // Queue end-of-stream (waiting for a free input buffer), then drain everything.
                for (int tries = 0; tries < 300; tries++) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        long pts = samplesWritten * 1_000_000L / Math.max(1, sampleRate);
                        codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }
                    drain(false);
                }
                drain(true);
                return;
            }
            byte[] pcm = toStereo(data);
            int outChannels = Math.max(1, Math.min(2, channels));
            int offset = 0;
            while (offset < pcm.length) {
                int inIndex = codec.dequeueInputBuffer(10_000);
                if (inIndex >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIndex);
                    long pts = samplesWritten * 1_000_000L / Math.max(1, sampleRate);
                    int size = Math.min(in.remaining(), pcm.length - offset);
                    size -= size % (outChannels * 2);
                    if (size <= 0) { codec.queueInputBuffer(inIndex, 0, 0, pts, 0); break; }
                    in.put(pcm, offset, size);
                    offset += size;
                    samplesWritten += size / (outChannels * 2);
                    codec.queueInputBuffer(inIndex, 0, size, pts, 0);
                }
                drain(false);
            }
        }

        private void drain(boolean untilEos) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int idle = 0;
            while (true) {
                int outIndex = codec.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos || ++idle > 300) return;
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                    continue;
                }
                if (outIndex < 0) continue;
                ByteBuffer out = codec.getOutputBuffer(outIndex);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && out != null) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    muxer.writeSampleData(track, out, info);
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        private void release() {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignore) {}
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Throwable ignore) {}
            codec = null;
            muxer = null;
        }

        /** Copies the finished file into Music/TelePremium/Recordings and returns its display name. */
        private String export() throws Exception {
            String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safe.length() > 80) safe = safe.substring(0, 80);
            String name = safe + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(new Date()) + ".m4a";
            Context context = ApplicationLoader.applicationContext;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                values.put(MediaStore.Audio.Media.TITLE, safe);
                values.put(MediaStore.Audio.Media.ARTIST, "TelePremium · REC");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/TelePremium/Recordings");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore refused the recording");
                try (InputStream in = new FileInputStream(file); OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    copy(in, out);
                }
                values.clear();
                values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "TelePremium/Recordings");
                if (!dir.isDirectory()) dir.mkdirs();
                File target = new File(dir, name);
                try (InputStream in = new FileInputStream(file); OutputStream out = new java.io.FileOutputStream(target)) {
                    copy(in, out);
                }
                android.media.MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, new String[]{"audio/mp4"}, null);
            }
            return name;
        }

        private static void copy(InputStream in, OutputStream out) throws Exception {
            if (out == null) throw new IllegalStateException("Cannot write the recording");
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }
}
