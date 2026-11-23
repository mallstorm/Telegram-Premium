package org.telegram.messenger.telepremium.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Chat wallpapers are owned by TelePremium and live apart from both the shell and the colour preset:
 * the choice is kept in its own preferences and is re-applied to whichever theme is active, so switching
 * presets or the Classic/Modern shell never leaves a chat empty again.
 *
 * Modes: {@code auto} (a soft scene derived from the active preset), {@code design:<id>} (one of the
 * generated backgrounds below) and {@code stock} (whatever the user picked from Telegram's own catalog,
 * a photo or a colour - it is carried over to every preset).
 */
public final class TelePremiumChatWallpapers {
    private TelePremiumChatWallpapers() {}

    public static final String MODE_AUTO = "auto";
    public static final String MODE_STOCK = "stock";
    public static final String DESIGN_PREFIX = "design:";
    private static final String SLUG_PREFIX = "tp_wp_";
    private static final String PREFS = "telepremium_chat_wallpaper";
    private static final String KEY_MODE = "mode";

    public static final int P_NONE = 0, P_DOTS = 1, P_WAVES = 2, P_LEAVES = 3, P_DIAMOND = 4,
            P_TOPO = 5, P_STARS = 6, P_HEX = 7, P_DOODLE = 8, P_CLOUDS = 9;

    public static final class Design {
        public final String id;
        public final String ru;
        public final String en;
        public final boolean dark;
        final int[] colors;
        final int angle;
        final int pattern;
        final float strength;

        Design(String id, String ru, String en, boolean dark, int c0, int c1, int c2, int angle, int pattern, float strength) {
            this.id = id;
            this.ru = ru;
            this.en = en;
            this.dark = dark;
            this.colors = new int[]{c0, c1, c2};
            this.angle = angle;
            this.pattern = pattern;
            this.strength = strength;
        }
    }

    private static final List<Design> DESIGNS;

    static {
        ArrayList<Design> d = new ArrayList<>();
        d.add(new Design("mint", "Мята", "Mint", false, 0xFFDDF3EA, 0xFFCFE8F5, 0xFFE6E2F7, 35, P_DOODLE, 0.10f));
        d.add(new Design("peach", "Персик", "Peach", false, 0xFFFBE4D8, 0xFFF6D5E3, 0xFFE5DDF6, 60, P_CLOUDS, 0.16f));
        d.add(new Design("breeze", "Бриз", "Breeze", false, 0xFFD4ECF7, 0xFFC5DDF2, 0xFFE0F1F1, 120, P_WAVES, 0.12f));
        d.add(new Design("meadow", "Луг", "Meadow", false, 0xFFE3EFCF, 0xFFD2E7C6, 0xFFEDF2D3, 20, P_LEAVES, 0.12f));
        d.add(new Design("lavender", "Лаванда", "Lavender", false, 0xFFE6DDF6, 0xFFD9D3F2, 0xFFF1E4F3, 150, P_STARS, 0.14f));
        d.add(new Design("sand", "Песок", "Sand", false, 0xFFF3E8D6, 0xFFEBDCC4, 0xFFF6EDE0, 70, P_DIAMOND, 0.10f));
        d.add(new Design("frost", "Иней", "Frost", false, 0xFFE4EEF7, 0xFFD6E4F2, 0xFFEEF3F9, 90, P_TOPO, 0.10f));
        d.add(new Design("sunrise", "Рассвет", "Sunrise", false, 0xFFFDE3C8, 0xFFF8C9C4, 0xFFD9C6F0, 45, P_CLOUDS, 0.18f));
        d.add(new Design("paper", "Бумага", "Paper", false, 0xFFF4F5F7, 0xFFECEEF1, 0xFFF7F7F4, 80, P_DOTS, 0.10f));
        d.add(new Design("graphite", "Графит", "Graphite", true, 0xFF20252B, 0xFF1A1E23, 0xFF2A3037, 60, P_HEX, 0.09f));
        d.add(new Design("midnight", "Полночь", "Midnight", true, 0xFF101A2E, 0xFF16243F, 0xFF0E1424, 110, P_STARS, 0.22f));
        d.add(new Design("forest", "Хвоя", "Forest", true, 0xFF12241C, 0xFF173026, 0xFF0F1C17, 30, P_LEAVES, 0.12f));
        d.add(new Design("deepsea", "Глубина", "Deep sea", true, 0xFF0E2530, 0xFF12343F, 0xFF0B1D26, 130, P_WAVES, 0.12f));
        d.add(new Design("plum", "Слива", "Plum", true, 0xFF261A33, 0xFF2F2142, 0xFF1D1428, 50, P_DOODLE, 0.10f));
        d.add(new Design("ember", "Уголь", "Ember", true, 0xFF2B1D1A, 0xFF35221C, 0xFF221614, 75, P_DIAMOND, 0.09f));
        d.add(new Design("ink", "Тушь", "Ink", true, 0xFF0F1115, 0xFF14171C, 0xFF0B0D10, 90, P_DOTS, 0.08f));
        DESIGNS = Collections.unmodifiableList(d);
    }

    public static List<Design> designs() {
        return DESIGNS;
    }

    public static Design design(String id) {
        for (Design d : DESIGNS) if (d.id.equals(id)) return d;
        return null;
    }

    // ------------------------------------------------------------------ state

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String mode() {
        return prefs().getString(KEY_MODE, MODE_AUTO);
    }

    private static void setMode(String mode) {
        prefs().edit().putString(KEY_MODE, mode).apply();
    }

    private static boolean busy;
    private static boolean installed;

    private static final NotificationCenter.NotificationCenterDelegate LISTENER = (id, account, args) -> {
        if (id != NotificationCenter.didSetNewWallpapper || busy) return;
        Theme.ThemeInfo t = Theme.getActiveTheme();
        if (t == null) return;
        Theme.OverrideWallpaperInfo cur = t.overrideWallpaper;
        if (cur == null) {
            // the user reset the wallpaper in Telegram's own UI: never leave the chat empty
            setMode(MODE_AUTO);
            sync(null);
        } else if (!isOurs(cur)) {
            setMode(MODE_STOCK);
        }
    };

    public static void install() {
        if (installed) return;
        installed = true;
        NotificationCenter.getGlobalInstance().addObserver(LISTENER, NotificationCenter.didSetNewWallpapper);
    }

    private static boolean isOurs(Theme.OverrideWallpaperInfo info) {
        return info != null && info.slug != null && info.slug.startsWith(SLUG_PREFIX);
    }

    /** Selects a generated background (or "auto") and applies it to the chats right away. */
    public static void select(String mode) {
        setMode(mode == null ? MODE_AUTO : mode);
        sync(null);
    }

    /** Called after a preset has been applied; {@code carry} is the wallpaper of the previously active theme. */
    public static void afterThemeApplied(Theme.OverrideWallpaperInfo carry) {
        install();
        sync(carry);
    }

    /** Makes the active theme show the wallpaper chosen in {@link #mode()}. */
    public static void sync(Theme.OverrideWallpaperInfo carry) {
        if (busy) return;
        Theme.ThemeInfo t = Theme.getActiveTheme();
        if (t == null) return;
        busy = true;
        try {
            String mode = mode();
            Theme.OverrideWallpaperInfo cur = t.overrideWallpaper;
            if (MODE_STOCK.equals(mode)) {
                if (cur != null && !isOurs(cur)) return; // the user's own wallpaper is already in place
                if (carry != null && !isOurs(carry) && carry.parentTheme != t) {
                    t.setOverrideWallpaper(new Theme.OverrideWallpaperInfo(carry, t, t.getAccent(false)));
                    Theme.reloadWallpaper(true);
                    return;
                }
                mode = MODE_AUTO; // nothing to carry over
                setMode(MODE_AUTO);
            }
            TelePremiumThemePreset preset = null;
            String slug;
            Design design = null;
            if (mode.startsWith(DESIGN_PREFIX)) {
                design = design(mode.substring(DESIGN_PREFIX.length()));
            }
            if (design != null) {
                slug = SLUG_PREFIX + design.id;
            } else {
                preset = TelePremiumThemeRegistry.get(currentPresetId());
                slug = SLUG_PREFIX + "auto_" + preset.id;
            }
            if (isOurs(cur) && slug.equals(cur.slug) && cur.fileName != null && !cur.fileName.isEmpty()
                    && new File(ApplicationLoader.getFilesDirFixed(), cur.fileName).exists()) {
                return;
            }
            Bitmap bitmap = design != null ? render(design, 720, 1440) : renderAuto(preset, 720, 1440);
            String name = t.generateWallpaperName(t.getAccent(false), false);
            File file = new File(ApplicationLoader.getFilesDirFixed(), name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            } finally {
                bitmap.recycle();
            }
            Theme.OverrideWallpaperInfo info = new Theme.OverrideWallpaperInfo();
            info.fileName = name;
            info.originalFileName = name;
            info.slug = slug;
            info.color = 0;
            info.intensity = 0.5f;
            info.rotation = 45;
            t.setOverrideWallpaper(info);
            Theme.reloadWallpaper(true);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        } finally {
            busy = false;
        }
    }

    private static String currentPresetId() {
        return TelePremiumThemeStore.getThemeId(ApplicationLoader.applicationContext, TelePremiumThemeRegistry.DEFAULT_THEME);
    }

    // ------------------------------------------------------------------ rendering

    public static Bitmap renderAuto(TelePremiumThemePreset p, int w, int h) {
        int base = TelePremiumThemeRegistry.blend(p.background, p.accent, p.isDark ? 0.10f : 0.07f);
        int c0 = TelePremiumThemeRegistry.blend(base, p.accent, p.isDark ? 0.10f : 0.12f);
        int c2 = TelePremiumThemeRegistry.blend(base, p.isDark ? Color.BLACK : Color.WHITE, 0.25f);
        int pattern;
        String type = p.wallpaperType == null ? "" : p.wallpaperType;
        switch (type) {
            case "leaves": pattern = P_LEAVES; break;
            case "waves": pattern = P_WAVES; break;
            case "frost": case "ice": pattern = P_TOPO; break;
            case "sunset": pattern = P_CLOUDS; break;
            case "twilight": pattern = P_STARS; break;
            case "dunes": pattern = P_DIAMOND; break;
            case "paper": pattern = P_DOTS; break;
            case "silver": pattern = P_HEX; break;
            default: pattern = P_DOODLE; break;
        }
        Design d = new Design("auto", "", "", p.isDark, c0, base, c2, 55, pattern, p.isDark ? 0.10f : 0.11f);
        return render(d, w, h, TelePremiumThemeRegistry.blend(p.accent, p.isDark ? Color.WHITE : Color.BLACK, 0.2f));
    }

    public static Bitmap render(Design d, int w, int h) {
        int ink = d.dark ? 0xFFFFFFFF : TelePremiumThemeRegistry.blend(d.colors[1], 0xFF1B2733, 0.75f);
        return render(d, w, h, ink);
    }

    private static Bitmap render(Design d, int w, int h, int ink) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        double a = Math.toRadians(d.angle);
        float cx = w / 2f, cy = h / 2f;
        float len = (float) (Math.abs(Math.cos(a)) * w + Math.abs(Math.sin(a)) * h) / 2f;
        float dx = (float) Math.cos(a) * len, dy = (float) Math.sin(a) * len;
        paint.setShader(new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, d.colors, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        // soft colour blobs give the flat gradient some depth without drawing attention
        paint.setShader(new RadialGradient(w * 0.85f, h * 0.12f, w * 0.9f, withAlpha(d.colors[2], 150), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.1f, h * 0.9f, w * 1.0f, withAlpha(d.colors[0], 150), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        drawPattern(c, paint, d.pattern, w, h, withAlpha(ink, (int) (255 * d.strength)), d.dark);
        return bitmap;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static void drawPattern(Canvas c, Paint p, int pattern, int w, int h, int ink, boolean dark) {
        if (pattern == P_NONE) return;
        Random r = new Random(7 + pattern);
        float u = w / 360f;
        p.setColor(ink);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(1.2f, u * 1.3f));
        Path path = new Path();
        switch (pattern) {
            case P_DOTS: {
                p.setStyle(Paint.Style.FILL);
                float step = 26 * u;
                for (float y = step / 2; y < h; y += step) {
                    for (float x = ((int) (y / step) % 2 == 0 ? step / 2 : 0); x < w; x += step) c.drawCircle(x, y, 1.7f * u, p);
                }
                break;
            }
            case P_WAVES: {
                float step = 40 * u;
                for (float y = 20 * u; y < h + step; y += step) {
                    path.reset();
                    path.moveTo(-10, y);
                    for (float x = -10; x < w + 60 * u; x += 60 * u) {
                        path.quadTo(x + 15 * u, y - 12 * u, x + 30 * u, y);
                        path.quadTo(x + 45 * u, y + 12 * u, x + 60 * u, y);
                    }
                    c.drawPath(path, p);
                }
                break;
            }
            case P_LEAVES: {
                p.setStyle(Paint.Style.FILL);
                int n = (int) (w * h / (64f * 64f * u * u));
                for (int i = 0; i < n; i++) {
                    float x = r.nextFloat() * w, y = r.nextFloat() * h, ln = (14 + r.nextFloat() * 14) * u;
                    c.save();
                    c.translate(x, y);
                    c.rotate(r.nextFloat() * 360);
                    path.reset();
                    path.moveTo(0, 0);
                    path.quadTo(ln * 0.5f, -ln * 0.32f, ln, 0);
                    path.quadTo(ln * 0.5f, ln * 0.32f, 0, 0);
                    c.drawPath(path, p);
                    c.restore();
                }
                break;
            }
            case P_DIAMOND: {
                float step = 46 * u;
                for (float y = 0; y < h + step; y += step) {
                    for (float x = ((int) (y / step) % 2 == 0 ? 0 : step / 2); x < w + step; x += step) {
                        path.reset();
                        path.moveTo(x, y - 6 * u);
                        path.lineTo(x + 6 * u, y);
                        path.lineTo(x, y + 6 * u);
                        path.lineTo(x - 6 * u, y);
                        path.close();
                        c.drawPath(path, p);
                    }
                }
                break;
            }
            case P_TOPO: {
                for (int k = 0; k < 2; k++) {
                    float ox = k == 0 ? w * 0.85f : w * 0.1f, oy = k == 0 ? h * 0.15f : h * 0.85f;
                    for (float rad = 26 * u; rad < w * 1.3f; rad += 26 * u) c.drawCircle(ox, oy, rad, p);
                }
                break;
            }
            case P_STARS: {
                p.setStyle(Paint.Style.FILL);
                int n = (int) (w * h / (46f * 46f * u * u));
                for (int i = 0; i < n; i++) {
                    float x = r.nextFloat() * w, y = r.nextFloat() * h, s = (1.2f + r.nextFloat() * 2.2f) * u;
                    if (i % 5 == 0) {
                        path.reset();
                        path.moveTo(x, y - s * 3f);
                        path.lineTo(x + s * 0.8f, y - s * 0.8f);
                        path.lineTo(x + s * 3f, y);
                        path.lineTo(x + s * 0.8f, y + s * 0.8f);
                        path.lineTo(x, y + s * 3f);
                        path.lineTo(x - s * 0.8f, y + s * 0.8f);
                        path.lineTo(x - s * 3f, y);
                        path.lineTo(x - s * 0.8f, y - s * 0.8f);
                        path.close();
                        c.drawPath(path, p);
                    } else {
                        c.drawCircle(x, y, s * 0.6f, p);
                    }
                }
                break;
            }
            case P_HEX: {
                float rad = 22 * u, hh = (float) (Math.sqrt(3) * rad);
                int row = 0;
                for (float y = 0; y < h + hh; y += hh / 2, row++) {
                    for (float x = (row % 2 == 0 ? 0 : rad * 1.5f); x < w + rad * 3; x += rad * 3) {
                        path.reset();
                        for (int i = 0; i < 6; i++) {
                            double ang = Math.PI / 3 * i;
                            float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
                            if (i == 0) path.moveTo(px, py);
                            else path.lineTo(px, py);
                        }
                        path.close();
                        c.drawPath(path, p);
                    }
                }
                break;
            }
            case P_CLOUDS: {
                p.setStyle(Paint.Style.FILL);
                int n = 14;
                for (int i = 0; i < n; i++) {
                    float x = r.nextFloat() * w, y = r.nextFloat() * h, s = (16 + r.nextFloat() * 22) * u;
                    c.drawCircle(x - s * 0.9f, y + s * 0.15f, s * 0.6f, p);
                    c.drawCircle(x, y - s * 0.1f, s * 0.8f, p);
                    c.drawCircle(x + s, y + s * 0.12f, s * 0.65f, p);
                    c.drawRect(x - s * 1.5f, y + s * 0.1f, x + s * 1.65f, y + s * 0.75f, p);
                }
                break;
            }
            case P_DOODLE:
            default: {
                int n = (int) (w * h / (58f * 58f * u * u));
                for (int i = 0; i < n; i++) {
                    float x = r.nextFloat() * w, y = r.nextFloat() * h, s = (6 + r.nextFloat() * 7) * u;
                    switch (i % 5) {
                        case 0: c.drawCircle(x, y, s, p); break;
                        case 1:
                            c.drawLine(x - s, y, x + s, y, p);
                            c.drawLine(x, y - s, x, y + s, p);
                            break;
                        case 2: c.drawRect(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f, p); break;
                        case 3:
                            path.reset();
                            path.moveTo(x - s, y);
                            path.quadTo(x - s / 2, y - s, x, y);
                            path.quadTo(x + s / 2, y + s, x + s, y);
                            c.drawPath(path, p);
                            break;
                        default:
                            path.reset();
                            path.moveTo(x, y - s);
                            path.lineTo(x + s, y + s * 0.8f);
                            path.lineTo(x - s, y + s * 0.8f);
                            path.close();
                            c.drawPath(path, p);
                            break;
                    }
                }
                break;
            }
        }
    }
}
