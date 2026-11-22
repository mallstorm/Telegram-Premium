package org.telegram.messenger.telepremium.analyzer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Color;
import android.os.ParcelFileDescriptor;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.telepremium.analyzer.AnalyzerHistoryReader.Entry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-device extraction from files already downloaded by the Telegram client.
 * Does not initiate downloads, circumvent protected messages or upload anything.
 * Caller authorizes external transmission separately via TPAiClient.completeVision.
 */
public final class AnalyzerMediaProcessor {
    private static final int MAX_DIMENSION = 1280;
    private static final int MAX_JPEG_BYTES = 850_000;
    private static final long MAX_VIDEO_BYTES = 400L * 1024L * 1024L;
    private static final long MAX_VIDEO_MS = 40L * 60L * 1000L;

    private AnalyzerMediaProcessor() { }

    public static final class Frame {
        public final long timeMs;
        public final int messageId;
        public final byte[] jpeg;
        public final String label;

        Frame(int messageId, long timeMs, byte[] jpeg) {
            this(messageId, timeMs, jpeg, "");
        }

        Frame(int messageId, long timeMs, byte[] jpeg, String label) {
            this.messageId = messageId;
            this.timeMs = timeMs;
            this.jpeg = jpeg;
            this.label = label;
        }

        public String citation() {
            return "[msg:" + messageId + "] " +
                    (label.isEmpty() ?
                            String.format(Locale.ROOT, "%02d:%02d", timeMs / 60000, (timeMs / 1000) % 60) : label);
        }
    }

    public static final class VideoResult {
        public final File source;
        public final long durationMs;
        public final List<Frame> frames;

        VideoResult(File source, long durationMs, List<Frame> frames) {
            this.source = source;
            this.durationMs = durationMs;
            this.frames = frames;
        }
    }

    public static File localMedia(Entry entry) throws IOException {
        if (entry == null || entry.sourceMessage == null || entry.dialogId >= 0) {
            throw new IOException("Недоступное сообщение");
        }
        if (!("photo".equals(entry.mediaType) || "image".equals(entry.mediaType) ||
                "video".equals(entry.mediaType) || "audio".equals(entry.mediaType) ||
                "pdf".equals(entry.mediaType))) {
            throw new IOException("Это не поддерживаемый формат медиа");
        }
        if (entry.sourceMessage.noforwards) throw new IOException("Автор запретил пересылку этого материала");
        File file = FileLoader.getInstance(entry.account).getPathToMessage(entry.sourceMessage);
        if (file == null || !file.isFile() || file.length() < 100) {
            throw new IOException("Сначала откройте и загрузите файл в Telegram, затем повторите анализ");
        }
        return file;
    }

    /** Explicit selection only: max four already cached pictures, no background bulk downloads. */
    public static List<Frame> photos(List<Entry> entries) throws IOException {
        if (entries == null || entries.isEmpty() || entries.size() > 4)
            throw new IOException("Выберите от 1 до 4 изображений");
        ArrayList<Frame> frames = new ArrayList<>();
        for (Entry e : entries) {
            if (!("photo".equals(e.mediaType) || "image".equals(e.mediaType)))
                throw new IOException("Выборка содержит не изображения");
            File file = localMedia(e);
            if (file.length() > 60L * 1024L * 1024L)
                throw new IOException("Слишком большой файл изображения");
            byte[] jpeg = jpegFromFile(file);
            frames.add(new Frame(e.messageId, 0, jpeg));
        }
        return frames;
    }

    /** Local PDF-to-JPEG conversion; at most five pages, no remote fetch. */
    public static List<Frame> pdfPages(Entry entry) throws IOException {
        if (entry == null || !"pdf".equals(entry.mediaType))
            throw new IOException("Выберите PDF-документ");
        File file = localMedia(entry);
        if (file.length() > 30L * 1024L * 1024L)
            throw new IOException("PDF больше 30 МБ: уменьшите размер документа");
        ArrayList<Frame> pages = new ArrayList<>();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(descriptor)) {
            int count = Math.min(5, pdf.getPageCount());
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = pdf.openPage(i)) {
                    int width = Math.min(1280, Math.max(1, page.getWidth() * 2));
                    int height = Math.max(1, Math.min(1800,
                            Math.round(page.getHeight() * ((float) width / Math.max(1, page.getWidth())))));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pages.add(new Frame(entry.messageId, 0, jpegFromBitmap(bitmap),
                                "страница " + (i + 1)));
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        }
        if (pages.isEmpty()) throw new IOException("PDF не содержит страниц для анализа");
        return pages;
    }

    /** At most five bounded JPEG frames from a local downloaded video. */
    public static VideoResult video(Entry entry) throws IOException {
        if (entry == null || !"video".equals(entry.mediaType))
            throw new IOException("Выберите видеопубликацию");
        File file = localMedia(entry);
        if (file.length() > MAX_VIDEO_BYTES)
            throw new IOException("Видео больше 400 МБ: выберите более короткий материал");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        ArrayList<Frame> frames = new ArrayList<>();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = duration == null ? 0 : Long.parseLong(duration);
            if (ms <= 0 || ms > MAX_VIDEO_MS)
                throw new IOException("Продолжительность видео не поддерживается (до 40 минут)");
            long[] times = new long[]{Math.min(1000, ms / 10), ms / 5, 2 * ms / 5, 3 * ms / 5, 4 * ms / 5};
            for (long stamp : times) {
                Bitmap frame = null;
                try {
                    frame = retriever.getFrameAtTime(Math.max(0, stamp) * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) frames.add(new Frame(entry.messageId, stamp, jpegFromBitmap(frame)));
                } catch (Throwable ignored) {
                    // One corrupted frame does not prevent an otherwise valid summary.
                } finally {
                    if (frame != null && !frame.isRecycled()) frame.recycle();
                }
            }
            if (frames.isEmpty()) throw new IOException("Не удалось извлечь кадры из видеопубликации");
            return new VideoResult(file, ms, frames);
        } catch (NumberFormatException failure) {
            throw new IOException("Не удалось определить продолжительность видео", failure);
        } finally {
            retriever.release();
        }
    }

    private static byte[] jpegFromFile(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("Не удалось прочитать изображение");
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION * 2)
            sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bitmap == null) throw new IOException("Не удалось декодировать изображение");
        try {
            return jpegFromBitmap(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] jpegFromBitmap(Bitmap bitmap) throws IOException {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0)
            throw new IOException("Пустой кадр");
        float scale = Math.min(1f, (float) MAX_DIMENSION /
                Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Bitmap scaled = scale < 1f ? Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true) : bitmap;
        try {
            for (int quality : new int[]{78, 65, 50, 38}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(128 * 1024);
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                    throw new IOException("Не удалось подготовить JPEG");
                if (bytes.size() <= MAX_JPEG_BYTES) return bytes.toByteArray();
            }
            throw new IOException("JPEG превышает допустимый размер Vision-запроса");
        } finally {
            if (scaled != bitmap) scaled.recycle();
        }
    }
}
