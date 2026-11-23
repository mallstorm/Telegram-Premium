package org.telegram.messenger.telepremium.theme;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Scenic backgrounds of the classic side menu: a vivid scene behind the profile header and the same
 * scene, much fainter, behind the list. One scene per preset ({@code wallpaperType}); colors come from
 * the preset, so a scene always matches the text/icon colors of the theme (dark text on light scenes,
 * light text on dark ones).
 */
public final class TelePremiumDrawerArt {
    private TelePremiumDrawerArt() {}

    public static Drawable header(TelePremiumThemePreset preset) {
        return new Art(preset, true);
    }

    public static Drawable body(TelePremiumThemePreset preset) {
        return new Art(preset, false);
    }

    private static int mix(int a, int b, float t) {
        return TelePremiumThemeRegistry.blend(a, b, t);
    }

    private static final class Art extends Drawable {
        private final TelePremiumThemePreset p;
        private final boolean header;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private float w;
        private float h;
        private float x0;
        private float y0;
        private float unit;
        private float k;

        Art(TelePremiumThemePreset preset, boolean header) {
            this.p = preset;
            this.header = header;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            x0 = b.left;
            y0 = b.top;
            w = b.width();
            h = b.height();
            unit = Math.max(1f, w / 320f);
            // header scenes are vivid, list scenes are a quiet watermark anchored to the lower part
            k = header ? 1f : 0.42f;
            if (!header) {
                // body scenes live in the lower 55% of the panel
                y0 = b.top + h * 0.45f;
                h = h * 0.55f;
            }
            Rect full = b;
            int base = header ? p.background : p.drawerBackground;
            float tint = header ? (p.isDark ? 0.24f : 0.16f) : (p.isDark ? 0.07f : 0.05f);
            paint.setShader(new LinearGradient(full.left, full.top, full.left + full.width() * 0.35f, full.bottom,
                    base, mix(base, p.accent, tint), Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(full, paint);
            paint.setShader(null);

            String type = p.wallpaperType == null ? "" : p.wallpaperType;
            switch (type) {
                case "frost": frost(c); break;
                case "leaves": leaves(c); break;
                case "twilight": twilight(c); break;
                case "waves": waves(c); break;
                case "sunset": sunset(c); break;
                case "dunes": dunes(c); break;
                case "paper": paper(c); break;
                case "ice": ice(c); break;
                case "silver": silver(c); break;
                default: graphite(c); break;
            }
            if (header) {
                // legibility scrim under the name/phone block: text always reads on top of the scene
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new LinearGradient(0, y0 + h * 0.45f, 0, y0 + h,
                        mix(base, base, 0f) & 0x00FFFFFF, (base & 0x00FFFFFF) | (0xA0 << 24), Shader.TileMode.CLAMP));
                paint.setAlpha(255);
                c.drawRect(full.left, y0 + h * 0.45f, full.right, full.bottom, paint);
                paint.setShader(null);
            }
        }

        // ---------------------------------------------------------------- helpers

        private int al(float alpha) {
            return Math.max(0, Math.min(255, (int) (alpha * k * (p.isDark ? 1.15f : 1f))));
        }

        private void fill(int color, float alpha) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha(al(alpha));
        }

        private void stroke(int color, float alpha, float width) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            paint.setAlpha(al(alpha));
        }

        private float px(float f) {
            return x0 + w * f;
        }

        private float py(float f) {
            return y0 + h * f;
        }

        /** Soft rolling hills filled down to the bottom of the drawable. */
        private void hills(Canvas c, float baseFrac, float ampFrac, float phase, int color, float alpha) {
            fill(color, alpha);
            path.reset();
            path.moveTo(x0, y0 + h * 2f);
            path.lineTo(x0, py(baseFrac));
            int n = 8;
            float prevX = x0;
            float prevY = py(baseFrac);
            for (int i = 1; i <= n; i++) {
                float x = x0 + w * i / n;
                float y = py(baseFrac) + h * ampFrac * (float) Math.sin(phase + i * 1.15f);
                path.quadTo(prevX + (x - prevX) * 0.5f, prevY + (y - prevY) * 0.1f - h * ampFrac * 0.3f, x, y);
                prevX = x;
                prevY = y;
            }
            path.lineTo(x0 + w, y0 + h * 2f);
            path.close();
            c.drawPath(path, paint);
        }

        private void cloud(Canvas c, float cx, float cy, float r, int color, float alpha) {
            fill(color, alpha);
            c.drawCircle(cx - r * 0.9f, cy + r * 0.15f, r * 0.62f, paint);
            c.drawCircle(cx, cy - r * 0.1f, r * 0.85f, paint);
            c.drawCircle(cx + r * 1.0f, cy + r * 0.12f, r * 0.68f, paint);
            rect.set(cx - r * 1.5f, cy + r * 0.1f, cx + r * 1.65f, cy + r * 0.78f);
            c.drawRoundRect(rect, r * 0.35f, r * 0.35f, paint);
        }

        private void leaf(Canvas c, float cx, float cy, float len, float angle, int color, float alpha) {
            c.save();
            c.translate(cx, cy);
            c.rotate(angle);
            fill(color, alpha);
            path.reset();
            path.moveTo(0, 0);
            path.quadTo(len * 0.5f, -len * 0.34f, len, 0);
            path.quadTo(len * 0.5f, len * 0.34f, 0, 0);
            c.drawPath(path, paint);
            stroke(mix(color, Color.WHITE, 0.45f), alpha * 0.7f, Math.max(1f, unit * 0.8f));
            c.drawLine(0, 0, len * 0.9f, 0, paint);
            c.restore();
        }

        private void fir(Canvas c, float cx, float baseY, float ht, int color, float alpha) {
            float tw = ht * 0.46f;
            fill(mix(color, Color.BLACK, 0.35f), alpha);
            c.drawRect(cx - ht * 0.025f, baseY - ht * 0.1f, cx + ht * 0.025f, baseY, paint);
            for (int i = 0; i < 4; i++) {
                float top = baseY - ht * (1f - i * 0.2f);
                float bottom = baseY - ht * (0.62f - i * 0.2f) + ht * 0.08f;
                float half = tw * (0.30f + i * 0.2f);
                fill(color, alpha);
                path.reset();
                path.moveTo(cx, top);
                path.lineTo(cx - half, bottom);
                path.lineTo(cx + half, bottom);
                path.close();
                c.drawPath(path, paint);
                fill(Color.WHITE, alpha * 0.85f);
                path.reset();
                path.moveTo(cx, top);
                path.lineTo(cx - half * 0.55f, top + (bottom - top) * 0.55f);
                path.lineTo(cx + half * 0.2f, top + (bottom - top) * 0.5f);
                path.close();
                c.drawPath(path, paint);
            }
        }

        private void dots(Canvas c, int count, int color, float alpha, float maxR) {
            fill(color, alpha);
            for (int i = 0; i < count; i++) {
                float fx = frac(i * 0.6180339f + 0.13f);
                float fy = frac(i * 0.7548776f + 0.37f);
                float r = unit * (0.7f + maxR * frac(i * 0.3247f));
                c.drawCircle(px(fx), py(fy), r, paint);
            }
        }

        private float frac(float v) {
            return v - (float) Math.floor(v);
        }

        // ---------------------------------------------------------------- scenes

        private void frost(Canvas c) {
            int far = mix(p.background, p.accent, 0.40f);
            int mid = mix(p.background, p.accent, 0.22f);
            path.reset();
            fill(far, 120);
            path.moveTo(x0, py(1.2f));
            path.lineTo(x0, py(0.66f));
            path.lineTo(px(0.16f), py(0.40f));
            path.lineTo(px(0.30f), py(0.58f));
            path.lineTo(px(0.50f), py(0.28f));
            path.lineTo(px(0.70f), py(0.56f));
            path.lineTo(px(0.86f), py(0.40f));
            path.lineTo(x0 + w, py(0.58f));
            path.lineTo(x0 + w, py(1.2f));
            path.close();
            c.drawPath(path, paint);
            hills(c, 0.74f, 0.05f, 0.4f, mid, 150);
            hills(c, 0.86f, 0.04f, 2.1f, Color.WHITE, 190);
            int pine = mix(p.accent, p.textPrimary, 0.55f);
            float baseY = py(header ? 0.98f : 0.99f);
            fir(c, px(0.93f), baseY, h * 0.78f, pine, 150);
            fir(c, px(0.80f), baseY, h * 0.55f, pine, 130);
            fir(c, px(0.67f), baseY, h * 0.36f, pine, 105);
            fir(c, px(0.06f), baseY, h * 0.40f, pine, 95);
            dots(c, header ? 34 : 14, Color.WHITE, 200, 2.6f);
        }

        private void leaves(Canvas c) {
            int leafColor = p.accent;
            int light = mix(p.accent, p.background, 0.45f);
            if (header) {
                branch(c, px(1.04f), py(0.02f), px(0.50f), py(0.62f), 8, leafColor, light);
                branch(c, px(1.04f), py(0.55f), px(0.62f), py(1.02f), 5, leafColor, light);
                branch(c, px(-0.04f), py(0.20f), px(0.22f), py(0.02f), 3, leafColor, light);
            } else {
                branch(c, px(1.05f), py(0.30f), px(0.40f), py(0.95f), 8, leafColor, light);
                branch(c, px(-0.05f), py(0.55f), px(0.30f), py(1.0f), 5, leafColor, light);
            }
        }

        private void branch(Canvas c, float sx, float sy, float ex, float ey, int leaves, int color, int light) {
            stroke(mix(color, Color.BLACK, 0.2f), 120, Math.max(1.5f, unit * 1.4f));
            path.reset();
            path.moveTo(sx, sy);
            path.quadTo((sx + ex) / 2f + (ey - sy) * 0.18f, (sy + ey) / 2f - (ex - sx) * 0.12f, ex, ey);
            c.drawPath(path, paint);
            float len = unit * 52f;
            for (int i = 0; i < leaves; i++) {
                float t = (i + 1f) / (leaves + 0.5f);
                float cx = sx + (ex - sx) * t;
                float cy = sy + (ey - sy) * t + (float) Math.sin(t * 3f) * unit * 4f;
                float base = (float) Math.toDegrees(Math.atan2(ey - sy, ex - sx));
                float side = i % 2 == 0 ? -48f : 48f;
                leaf(c, cx, cy, len * (1f - t * 0.25f), base + side, i % 3 == 0 ? light : color, 125);
            }
            leaf(c, ex, ey, len * 0.8f, (float) Math.toDegrees(Math.atan2(ey - sy, ex - sx)), color, 125);
        }

        private void twilight(Canvas c) {
            if (header) {
                dots(c, 16, Color.WHITE, 190, 1.4f);
                float r = Math.min(w, h) * 0.17f;
                float cx = px(0.80f);
                float cy = py(0.30f);
                path.reset();
                path.addCircle(cx, cy, r, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(cx + r * 0.55f, cy - r * 0.22f, r * 0.92f, Path.Direction.CW);
                path.op(cut, Path.Op.DIFFERENCE);
                fill(mix(Color.WHITE, p.accent, 0.12f), 235);
                c.drawPath(path, paint);
            }
            hills(c, 0.80f, 0.05f, 0.9f, mix(p.background, p.accent, 0.28f), 140);
            lavender(c, 0.86f, header ? 1f : 0.9f);
            lavender(c, 0.96f, header ? 1.25f : 1.1f);
        }

        private void lavender(Canvas c, float baseFrac, float scale) {
            int stem = mix(p.accent, Color.BLACK, 0.25f);
            int bloom = mix(p.accent, Color.WHITE, 0.15f);
            int count = 22;
            for (int i = 0; i < count; i++) {
                float fx = (i + 0.4f * frac(i * 0.77f)) / count;
                float bx = px(fx);
                float by = py(baseFrac) + (float) Math.sin(i * 1.7f) * unit * 3f;
                float len = h * 0.20f * scale * (0.75f + 0.25f * frac(i * 0.41f));
                float sway = (float) Math.sin(i * 0.9f) * unit * 6f;
                stroke(stem, 130, Math.max(1f, unit * 0.9f));
                path.reset();
                path.moveTo(bx, by);
                path.quadTo(bx + sway * 0.4f, by - len * 0.5f, bx + sway, by - len);
                c.drawPath(path, paint);
                fill(bloom, 170);
                for (int j = 0; j < 4; j++) {
                    c.drawOval(bx + sway - unit * 2.2f, by - len + j * unit * 4.5f - unit * 1.5f,
                            bx + sway + unit * 2.2f, by - len + j * unit * 4.5f + unit * 4f, paint);
                }
            }
        }

        private void waves(Canvas c) {
            for (int i = 0; i < 4; i++) {
                float base = header ? 0.38f + i * 0.17f : 0.20f + i * 0.20f;
                hills(c, base, 0.06f, i * 1.3f, mix(p.accent, p.background, 0.15f * (3 - i)), 55 + i * 28);
                stroke(Color.WHITE, 140, Math.max(1.2f, unit * 1.1f));
                path.reset();
                float yy = py(base);
                path.moveTo(x0, yy);
                path.cubicTo(px(0.25f), yy - h * 0.08f, px(0.5f), yy + h * 0.06f, px(0.75f), yy - h * 0.03f);
                path.quadTo(px(0.9f), yy - h * 0.07f, x0 + w, yy);
                c.drawPath(path, paint);
            }
            if (header) {
                stroke(Color.WHITE, 220, Math.max(1.5f, unit * 1.3f));
                for (int g = 0; g < 2; g++) {
                    float gx = px(0.70f + g * 0.12f);
                    float gy = py(0.20f + g * 0.06f);
                    path.reset();
                    path.moveTo(gx - unit * 9, gy + unit * 3);
                    path.quadTo(gx - unit * 4, gy - unit * 5, gx, gy);
                    path.quadTo(gx + unit * 4, gy - unit * 5, gx + unit * 9, gy + unit * 3);
                    c.drawPath(path, paint);
                }
            }
            dots(c, header ? 16 : 6, Color.WHITE, 170, 2.2f);
        }

        private void sunset(Canvas c) {
            float sx = px(0.72f);
            float sy = py(header ? 0.46f : 0.30f);
            float r = h * (header ? 0.20f : 0.22f);
            int sun = mix(p.accent, Color.WHITE, 0.30f);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(sx, sy, r * 2.6f, mix(p.accent, Color.WHITE, 0.5f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            paint.setAlpha(al(210));
            c.drawCircle(sx, sy, r * 2.6f, paint);
            paint.setShader(null);
            fill(sun, 245);
            c.drawCircle(sx, sy, r, paint);
            cloud(c, px(0.22f), py(0.30f), unit * 24f, Color.WHITE, 190);
            cloud(c, px(0.50f), py(0.58f), unit * 18f, Color.WHITE, 150);
            cloud(c, px(0.92f), py(0.22f), unit * 15f, Color.WHITE, 130);
            hills(c, 0.78f, 0.06f, 0.5f, mix(p.accent, p.background, 0.55f), 130);
            hills(c, 0.90f, 0.05f, 2.2f, mix(p.accent, p.textPrimary, 0.25f), 120);
        }

        private void dunes(Canvas c) {
            hills(c, 0.55f, 0.07f, 0.3f, mix(p.accent, p.background, 0.70f), 110);
            hills(c, 0.72f, 0.07f, 1.6f, mix(p.accent, p.background, 0.50f), 120);
            hills(c, 0.88f, 0.06f, 2.9f, mix(p.accent, p.background, 0.30f), 120);
            if (header) {
                stroke(mix(p.accent, p.textPrimary, 0.4f), 170, Math.max(1.5f, unit * 1.3f));
                path.reset();
                path.moveTo(px(1.02f), py(0.02f));
                path.quadTo(px(0.82f), py(0.16f), px(0.62f), py(0.12f));
                c.drawPath(path, paint);
                for (int i = 0; i < 7; i++) {
                    float t = i / 6f;
                    blossom(c, px(1.0f - 0.38f * t) - unit * 2f, py(0.04f + 0.10f * t + 0.07f * (i % 2)), unit * (7f - t * 2f));
                }
            }
        }

        private void blossom(Canvas c, float cx, float cy, float r) {
            fill(mix(p.accent, Color.WHITE, 0.55f), 215);
            for (int i = 0; i < 5; i++) {
                double a = i * Math.PI * 2 / 5;
                c.drawCircle(cx + (float) Math.cos(a) * r * 0.7f, cy + (float) Math.sin(a) * r * 0.7f, r * 0.55f, paint);
            }
            fill(mix(p.accent, Color.BLACK, 0.1f), 230);
            c.drawCircle(cx, cy, r * 0.28f, paint);
        }

        private void paper(Canvas c) {
            stroke(p.accent, 40, Math.max(1f, unit * 0.8f));
            for (int i = 0; i < 9; i++) {
                float yy = py(0.10f + i * 0.105f);
                c.drawLine(px(0.06f), yy, px(0.94f), yy + h * 0.012f, paint);
            }
            hills(c, 0.76f, 0.05f, 0.7f, mix(p.accent, p.background, 0.7f), 90);
            hills(c, 0.90f, 0.05f, 2.4f, mix(p.accent, p.background, 0.5f), 90);
        }

        private void ice(Canvas c) {
            float[][] tri = {
                    {0.55f, 0.00f, 1.00f, 0.00f, 0.82f, 0.45f},
                    {0.55f, 0.00f, 0.82f, 0.45f, 0.40f, 0.52f},
                    {0.82f, 0.45f, 1.00f, 0.00f, 1.00f, 0.80f},
                    {0.40f, 0.52f, 0.82f, 0.45f, 0.70f, 1.00f},
                    {0.82f, 0.45f, 1.00f, 0.80f, 0.70f, 1.00f},
                    {0.00f, 0.60f, 0.40f, 0.52f, 0.25f, 1.00f},
                    {0.40f, 0.52f, 0.70f, 1.00f, 0.25f, 1.00f}};
            for (int i = 0; i < tri.length; i++) {
                float[] t = tri[i];
                fill(i % 2 == 0 ? p.accent : mix(p.accent, Color.WHITE, 0.55f), 38 + (i % 3) * 14);
                path.reset();
                path.moveTo(px(t[0]), py(t[1]));
                path.lineTo(px(t[2]), py(t[3]));
                path.lineTo(px(t[4]), py(t[5]));
                path.close();
                c.drawPath(path, paint);
                stroke(Color.WHITE, 120, Math.max(1f, unit * 0.9f));
                c.drawPath(path, paint);
            }
            dots(c, header ? 14 : 5, Color.WHITE, 190, 2f);
        }

        private void silver(Canvas c) {
            for (int i = 0; i < 4; i++) {
                float sx = px(0.1f + i * 0.28f);
                fill(i % 2 == 0 ? Color.WHITE : p.accent, i % 2 == 0 ? 120 : 36);
                path.reset();
                path.moveTo(sx, y0 + h);
                path.lineTo(sx + w * 0.16f, y0 + h);
                path.lineTo(sx + w * 0.40f, y0);
                path.lineTo(sx + w * 0.24f, y0);
                path.close();
                c.drawPath(path, paint);
            }
        }

        private void graphite(Canvas c) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(px(0.85f), py(0.10f), Math.max(w, h) * 0.9f, p.accent, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            paint.setAlpha(al(header ? 60 : 30));
            c.drawRect(x0, y0, x0 + w, y0 + h, paint);
            paint.setShader(null);
            stroke(p.accent, 34, Math.max(1f, unit * 0.9f));
            float step = Math.max(26f * unit, w / 8f);
            for (float x = x0 - h; x < x0 + w + h; x += step) {
                c.drawLine(x, y0 + h, x + h, y0, paint);
            }
        }

        // ---------------------------------------------------------------- Drawable

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
