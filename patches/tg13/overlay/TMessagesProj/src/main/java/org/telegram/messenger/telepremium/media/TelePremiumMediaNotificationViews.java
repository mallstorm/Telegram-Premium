package org.telegram.messenger.telepremium.media;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.util.Locale;

/**
 * Custom shade / lock-screen card of the TelePremium player (used below Android 13, where the
 * system does not draw its own media controls): artwork square (station logo, or a note for
 * music), title, a seek bar you can tap anywhere on, elapsed/total time, and prev · play · next
 * centered. Colors follow the active TelePremium theme.
 */
final class TelePremiumMediaNotificationViews {
    static final int SEEK_SEGMENTS = 20;
    private static final int[] SEEK_IDS = {
            R.id.tp_notif_seek_0, R.id.tp_notif_seek_1, R.id.tp_notif_seek_2, R.id.tp_notif_seek_3, R.id.tp_notif_seek_4,
            R.id.tp_notif_seek_5, R.id.tp_notif_seek_6, R.id.tp_notif_seek_7, R.id.tp_notif_seek_8, R.id.tp_notif_seek_9,
            R.id.tp_notif_seek_10, R.id.tp_notif_seek_11, R.id.tp_notif_seek_12, R.id.tp_notif_seek_13, R.id.tp_notif_seek_14,
            R.id.tp_notif_seek_15, R.id.tp_notif_seek_16, R.id.tp_notif_seek_17, R.id.tp_notif_seek_18, R.id.tp_notif_seek_19};

    interface Actions {
        PendingIntent toggle();
        PendingIntent previous();
        PendingIntent next();
        PendingIntent close();
        PendingIntent record();
        PendingIntent seek(int segment);
    }

    private final Context context;
    private final float density;
    private TelePremiumMediaTheme theme;
    private String artKey;
    private Bitmap artBig, artSmall;
    private Bitmap playBitmap, pauseBitmap;
    private String themeId;

    TelePremiumMediaNotificationViews(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) { return Math.round(v * density); }

    private void ensureTheme() {
        TelePremiumMediaTheme fresh = TelePremiumMediaTheme.from(context);
        if (theme == null || themeId == null || !themeId.equals(fresh.preset.id)) {
            theme = fresh;
            themeId = fresh.preset.id;
            playBitmap = null;
            pauseBitmap = null;
            artKey = null;
        }
    }

    RemoteViews big(TelePremiumMediaItem item, Bitmap logo, boolean playing, long position, long duration, long buffered, Actions actions) {
        ensureTheme();
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.tp_media_notification_big);
        bindCommon(v, item, logo, playing, actions, true);
        boolean rec = TelePremiumStreamRecorder.isRecording();
        v.setTextViewText(R.id.tp_notif_app, rec ? "● REC " + formatTime(TelePremiumStreamRecorder.elapsedMs()) + " · " + kindLabel(item) : "TelePremium · " + kindLabel(item));
        v.setTextColor(R.id.tp_notif_app, rec ? 0xffe53935 : theme.accent);
        v.setInt(R.id.tp_notif_rec, "setColorFilter", rec ? 0xffe53935 : theme.withAlpha(0xffe53935, 0x99));
        v.setOnClickPendingIntent(R.id.tp_notif_rec, actions.record());
        v.setOnClickPendingIntent(R.id.tp_notif_close, actions.close());
        v.setInt(R.id.tp_notif_close, "setColorFilter", theme.textSecondary);
        boolean live = item != null && item.live || duration <= 0;
        if (live) {
            v.setViewVisibility(R.id.tp_notif_progress_box, View.GONE);
            v.setViewVisibility(R.id.tp_notif_times, View.GONE);
            v.setViewVisibility(R.id.tp_notif_live, View.VISIBLE);
            v.setTextViewText(R.id.tp_notif_live, "●  " + (item != null && item.live
                    ? TPStrings.get("TP_N_LIVE", R.string.TP_N_LIVE) : TPStrings.get("TP_L10N_316", R.string.TP_L10N_316)));
            v.setTextColor(R.id.tp_notif_live, theme.onAccent);
            v.setImageViewBitmap(R.id.tp_notif_progress, null);
            v.setInt(R.id.tp_notif_live, "setBackgroundColor", theme.accent);
        } else {
            v.setViewVisibility(R.id.tp_notif_progress_box, View.VISIBLE);
            v.setViewVisibility(R.id.tp_notif_times, View.VISIBLE);
            v.setViewVisibility(R.id.tp_notif_live, View.GONE);
            v.setImageViewBitmap(R.id.tp_notif_progress, progressBitmap(position, duration, buffered));
            v.setTextViewText(R.id.tp_notif_elapsed, formatTime(position));
            v.setTextViewText(R.id.tp_notif_total, formatTime(duration));
            v.setTextColor(R.id.tp_notif_elapsed, theme.textSecondary);
            v.setTextColor(R.id.tp_notif_total, theme.textSecondary);
            for (int i = 0; i < SEEK_IDS.length; i++) v.setOnClickPendingIntent(SEEK_IDS[i], actions.seek(i));
        }
        return v;
    }

    RemoteViews small(TelePremiumMediaItem item, Bitmap logo, boolean playing, Actions actions) {
        ensureTheme();
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.tp_media_notification_small);
        bindCommon(v, item, logo, playing, actions, false);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Android 12+: collapsed custom content is limited to 48 dp inside the system frame.
            v.setViewLayoutHeight(R.id.tp_notif_root, 48f, android.util.TypedValue.COMPLEX_UNIT_DIP);
            v.setViewLayoutWidth(R.id.tp_notif_art, 40f, android.util.TypedValue.COMPLEX_UNIT_DIP);
            v.setViewLayoutHeight(R.id.tp_notif_art, 40f, android.util.TypedValue.COMPLEX_UNIT_DIP);
            v.setViewPadding(R.id.tp_notif_root, 0, 0, 0, 0);
        }
        return v;
    }

    private void bindCommon(RemoteViews v, TelePremiumMediaItem item, Bitmap logo, boolean playing, Actions actions, boolean big) {
        v.setInt(R.id.tp_notif_root, "setBackgroundColor", theme.surface);
        v.setTextViewText(R.id.tp_notif_title, item == null ? "TelePremium" : item.title);
        v.setTextColor(R.id.tp_notif_title, theme.textPrimary);
        v.setTextViewText(R.id.tp_notif_subtitle, subtitle(item));
        v.setTextColor(R.id.tp_notif_subtitle, theme.textSecondary);
        v.setImageViewBitmap(R.id.tp_notif_art, artwork(item, logo, big));
        v.setInt(R.id.tp_notif_prev, "setColorFilter", theme.textPrimary);
        v.setInt(R.id.tp_notif_next, "setColorFilter", theme.textPrimary);
        v.setImageViewBitmap(R.id.tp_notif_play, playing ? pauseBitmap() : playBitmap());
        v.setOnClickPendingIntent(R.id.tp_notif_prev, actions.previous());
        v.setOnClickPendingIntent(R.id.tp_notif_play, actions.toggle());
        v.setOnClickPendingIntent(R.id.tp_notif_next, actions.next());
    }

    private static String subtitle(TelePremiumMediaItem item) {
        if (item == null) return "";
        StringBuilder b = new StringBuilder();
        if (!TextUtils.isEmpty(item.artist)) b.append(item.artist);
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK && !TextUtils.isEmpty(item.album)) {
            if (b.length() > 0) b.append(" · ");
            b.append(item.album);
        }
        return b.length() == 0 ? item.sourceLabel() : b.toString();
    }

    private static String kindLabel(TelePremiumMediaItem item) {
        if (item == null) return TPStrings.get("TP_L10N_304", R.string.TP_L10N_304);
        if (item.live && !item.isVideo()) return TPStrings.get("TP_NET_RADIO", R.string.TP_NET_RADIO);
        if (item.live) return "IPTV";
        return item.isVideo() ? TPStrings.get("TP_L10N_098", R.string.TP_L10N_098) : TPStrings.get("TP_L10N_485", R.string.TP_L10N_485);
    }

    private static boolean isStation(TelePremiumMediaItem item) {
        return item != null && item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK && item.live;
    }

    // ------------------------------------------------------------------ bitmaps

    /** Station → its logo on a rounded tile; music → a note in a square; video → thumbnail or film glyph. */
    private Bitmap artwork(TelePremiumMediaItem item, Bitmap logo, boolean big) {
        String key = (item == null ? "-" : item.id) + ":" + (logo == null ? 0 : logo.getGenerationId()) + ":" + System.identityHashCode(logo);
        if (key.equals(artKey) && artBig != null && artSmall != null) return big ? artBig : artSmall;
        artKey = key;
        artBig = drawArtwork(item, logo, dp(64));
        artSmall = drawArtwork(item, logo, dp(46));
        return big ? artBig : artSmall;
    }

    private Bitmap drawArtwork(TelePremiumMediaItem item, Bitmap logo, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF bounds = new RectF(0, 0, size, size);
        float radius = size * 0.22f;
        boolean station = isStation(item);
        boolean useLogo = logo != null && (station || (item != null && item.isVideo()));
        if (useLogo && station) {
            // Logos are often transparent or not square: show them whole on a light tile.
            p.setColor(0xfff4f4f6);
            c.drawRoundRect(bounds, radius, radius, p);
            int pad = Math.round(size * 0.1f);
            Rect dst = fitCenter(logo.getWidth(), logo.getHeight(), size - pad * 2);
            dst.offset(pad, pad);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(logo, null, dst, bp);
            return out;
        }
        if (useLogo) {
            Bitmap rounded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas rc = new Canvas(rounded);
            p.setColor(Color.WHITE);
            rc.drawRoundRect(bounds, radius, radius, p);
            p.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            Rect src = centerCropSource(logo.getWidth(), logo.getHeight());
            rc.drawBitmap(logo, src, new Rect(0, 0, size, size), p);
            return rounded;
        }
        // Theme-colored square with a glyph: note for music, radio for a station without logo.
        android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(0, 0, size, size,
                theme.accent, darker(theme.accent), android.graphics.Shader.TileMode.CLAMP);
        p.setShader(gradient);
        c.drawRoundRect(bounds, radius, radius, p);
        p.setShader(null);
        int glyph = station ? R.drawable.tp_ic_radio : item != null && item.isVideo() ? R.drawable.tp_ic_video : R.drawable.tp_ic_music;
        drawGlyph(c, glyph, theme.onAccent, size, 0.5f);
        return out;
    }

    private Bitmap playBitmap() {
        if (playBitmap == null) playBitmap = drawPlayButton(R.drawable.tp_ic_play);
        return playBitmap;
    }

    private Bitmap pauseBitmap() {
        if (pauseBitmap == null) pauseBitmap = drawPlayButton(R.drawable.tp_ic_pause);
        return pauseBitmap;
    }

    private Bitmap drawPlayButton(int icon) {
        int size = dp(64);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(theme.accent);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        drawGlyph(c, icon, theme.onAccent, size, 0.5f);
        return out;
    }

    private void drawGlyph(Canvas c, int res, int color, int size, float fraction) {
        try {
            Drawable d = context.getResources().getDrawable(res).mutate();
            d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            int g = Math.round(size * fraction);
            int o = (size - g) / 2;
            d.setBounds(o, o, o + g, o + g);
            d.draw(c);
        } catch (Throwable ignore) {
        }
    }

    /** Seek bar picture: track, buffered range, progress and a round thumb (taps go to the overlay segments). */
    private Bitmap progressBitmap(long position, long duration, long buffered) {
        int width = Math.max(dp(160), context.getResources().getDisplayMetrics().widthPixels - dp(64));
        int height = dp(28);
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float thumb = dp(7);
        float left = thumb, right = width - thumb, cy = height / 2f, track = dp(4);
        float fraction = duration <= 0 ? 0 : Math.max(0f, Math.min(1f, position / (float) duration));
        float bufferedFraction = duration <= 0 ? 0 : Math.max(fraction, Math.min(1f, buffered / (float) duration));
        p.setColor(theme.timelineTrack);
        c.drawRoundRect(new RectF(left, cy - track / 2, right, cy + track / 2), track, track, p);
        p.setColor(theme.withAlpha(theme.accent, 0x66));
        c.drawRoundRect(new RectF(left, cy - track / 2, left + (right - left) * bufferedFraction, cy + track / 2), track, track, p);
        float x = left + (right - left) * fraction;
        p.setColor(theme.accent);
        c.drawRoundRect(new RectF(left, cy - track / 2, x, cy + track / 2), track, track, p);
        c.drawCircle(x, cy, thumb, p);
        return out;
    }

    private static Rect fitCenter(int w, int h, int box) {
        if (w <= 0 || h <= 0) return new Rect(0, 0, box, box);
        float scale = Math.min(box / (float) w, box / (float) h);
        int dw = Math.round(w * scale), dh = Math.round(h * scale);
        int l = (box - dw) / 2, t = (box - dh) / 2;
        return new Rect(l, t, l + dw, t + dh);
    }

    private static Rect centerCropSource(int w, int h) {
        int side = Math.min(w, h);
        int l = (w - side) / 2, t = (h - side) / 2;
        return new Rect(l, t, l + side, t + side);
    }

    private static int darker(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, hsv[2] * 0.72f);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000, h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
