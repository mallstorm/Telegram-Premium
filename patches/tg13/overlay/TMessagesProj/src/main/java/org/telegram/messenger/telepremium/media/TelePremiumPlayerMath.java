package org.telegram.messenger.telepremium.media;

import java.util.Locale;

/** Pure player helpers (no Android dependencies): formatting and video box geometry. */
public final class TelePremiumPlayerMath {
    public static final int SCALE_FIT = 0;
    public static final int SCALE_CROP = 1;
    public static final int SCALE_STRETCH = 2;
    public static final int SCALE_ORIGINAL = 3;

    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".amr"};

    private TelePremiumPlayerMath() {}

    public static String formatTime(long ms) {
        long total = Math.max(0, ms / 1000), h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    public static String formatSpeed(float speed) {
        return speed == Math.round(speed) ? String.format(Locale.ROOT, "%d×", Math.round(speed)) : String.format(Locale.ROOT, "%.2g×", speed);
    }

    public static String stripAudioExtension(String title, String fallback) {
        if (title == null || title.isEmpty()) return fallback;
        String lower = title.toLowerCase(Locale.ROOT);
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) return title.substring(0, title.length() - extension.length());
        }
        return title;
    }

    /**
     * Size of the video surface inside a box for the given scale mode.
     * Returns {width, height}; unknown video size or STRETCH fills the box.
     */
    public static int[] fitVideo(int videoWidth, int videoHeight, int boxWidth, int boxHeight, int scaleMode) {
        if (videoWidth <= 0 || videoHeight <= 0 || boxWidth <= 0 || boxHeight <= 0 || scaleMode == SCALE_STRETCH) {
            return new int[]{boxWidth, boxHeight};
        }
        float vr = videoWidth / (float) videoHeight;
        float br = boxWidth / (float) boxHeight;
        int width = boxWidth, height = boxHeight;
        if (scaleMode == SCALE_CROP) {
            if (vr > br) width = Math.max(1, Math.round(boxHeight * vr));
            else height = Math.max(1, Math.round(boxWidth / vr));
        } else if (scaleMode == SCALE_ORIGINAL) {
            float scale = Math.min(1f, Math.min(boxWidth / (float) videoWidth, boxHeight / (float) videoHeight));
            width = Math.max(1, Math.round(videoWidth * scale));
            height = Math.max(1, Math.round(videoHeight * scale));
        } else {
            if (vr > br) height = Math.max(1, Math.round(boxWidth / vr));
            else width = Math.max(1, Math.round(boxHeight * vr));
        }
        return new int[]{width, height};
    }
}
