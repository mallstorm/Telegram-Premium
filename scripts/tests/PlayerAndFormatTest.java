import org.telegram.messenger.telepremium.cloud.TelePremiumCloudFormat;
import org.telegram.messenger.telepremium.media.TelePremiumPlayerMath;

import java.util.Arrays;

/** Helpers extracted from the Player and Cloud screens. */
public final class PlayerAndFormatTest {
    private static int passed;

    public static void main(String[] args) {
        eq(TelePremiumPlayerMath.formatTime(0), "0:00", "time zero");
        eq(TelePremiumPlayerMath.formatTime(65_000), "1:05", "minutes");
        eq(TelePremiumPlayerMath.formatTime(3_725_000), "1:02:05", "hours");
        eq(TelePremiumPlayerMath.formatTime(-5), "0:00", "negative clamps");
        eq(TelePremiumPlayerMath.formatSpeed(2f), "2×", "integer speed");
        eq(TelePremiumPlayerMath.formatSpeed(1.25f), "1.3×", "fractional speed (2 significant digits)");
        eq(TelePremiumPlayerMath.stripAudioExtension("Song.MP3", "Музыка"), "Song", "audio extension stripped case-insensitively");
        eq(TelePremiumPlayerMath.stripAudioExtension("clip.mp4", "Музыка"), "clip.mp4", "video extension kept");
        eq(TelePremiumPlayerMath.stripAudioExtension(null, "Музыка"), "Музыка", "fallback title");

        box(1920, 1080, 1000, 1000, TelePremiumPlayerMath.SCALE_FIT, 1000, 563, "fit landscape into square");
        box(1080, 1920, 1000, 1000, TelePremiumPlayerMath.SCALE_FIT, 563, 1000, "fit portrait into square");
        box(1920, 1080, 1000, 1000, TelePremiumPlayerMath.SCALE_CROP, 1778, 1000, "crop fills height");
        box(1920, 1080, 1000, 1000, TelePremiumPlayerMath.SCALE_STRETCH, 1000, 1000, "stretch fills box");
        box(640, 360, 1000, 1000, TelePremiumPlayerMath.SCALE_ORIGINAL, 640, 360, "original never upscales");
        box(0, 0, 800, 600, TelePremiumPlayerMath.SCALE_FIT, 800, 600, "unknown video size fills box");

        eq(TelePremiumCloudFormat.compactCount(1), "1 файл", "plural 1");
        eq(TelePremiumCloudFormat.compactCount(3), "3 файла", "plural 2-4");
        eq(TelePremiumCloudFormat.compactCount(5), "5 файлов", "plural 5+");
        eq(TelePremiumCloudFormat.compactCount(12), "12 файлов", "plural 11-14");
        eq(TelePremiumCloudFormat.compactCount(21), "21 файл", "plural 21");
        eq(TelePremiumCloudFormat.compactCount(1500), "1,5 тыс.", "thousands");
        eq(TelePremiumCloudFormat.compactCount(2_000_000), "2 млн", "millions");
        eq(TelePremiumCloudFormat.humanSize(512), "512 Б", "bytes");
        eq(TelePremiumCloudFormat.humanSize(5 * 1024 * 1024), "5 МБ", "megabytes");
        eq(TelePremiumCloudFormat.humanSize(3L * 1024 * 1024 * 1024 / 2), "1,5 ГБ", "gigabytes, locale-independent");
        eq(TelePremiumCloudFormat.resolveMime("App.APK", "application/zip", ext -> "application/zip"), "application/vnd.android.package-archive", "apk override beats system table");
        eq(TelePremiumCloudFormat.resolveMime("photo.jpg", null, ext -> "jpg".equals(ext) ? "image/jpeg" : null), "image/jpeg", "system lookup used");
        eq(TelePremiumCloudFormat.resolveMime("noext", "text/plain", ext -> null), "text/plain", "stored mime fallback");
        eq(TelePremiumCloudFormat.resolveMime("file.", null, null), "application/octet-stream", "final fallback");
        eq(TelePremiumCloudFormat.shortError(null), "неизвестная ошибка", "empty error");
        check(TelePremiumCloudFormat.shortError("x".repeat(200)).length() == 91, "long error truncated");
        System.out.println("PlayerAndFormatTest OK: " + passed + " checks");
    }

    private static void box(int vw, int vh, int bw, int bh, int mode, int ew, int eh, String name) {
        int[] r = TelePremiumPlayerMath.fitVideo(vw, vh, bw, bh, mode);
        check(r[0] == ew && r[1] == eh, name + " -> " + Arrays.toString(r));
    }

    private static void eq(String actual, String expected, String name) {
        check(expected.equals(actual), name + ": expected '" + expected + "', got '" + actual + "'");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
        passed++;
    }
}
