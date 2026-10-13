package org.telegram.messenger.telepremium.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

public final class TelePremiumThemeArtwork {
    private static final int MODE_DRAWER_HEADER = 1;
    private static final int MODE_DRAWER_BODY = 2;
    private static final int MODE_CLOUD = 3;

    private TelePremiumThemeArtwork() {}

    public static Drawable drawerHeader(Context context) {
        return new ArtworkDrawable(TelePremiumThemeController.getCurrent(context), MODE_DRAWER_HEADER);
    }

    public static Drawable drawerBody(Context context) {
        return new ArtworkDrawable(TelePremiumThemeController.getCurrent(context), MODE_DRAWER_BODY);
    }

    public static Drawable cloudBackground(Context context) {
        return new ArtworkDrawable(TelePremiumThemeController.getCurrent(context), MODE_CLOUD);
    }

    private static final class ArtworkDrawable extends Drawable {
        private final TelePremiumThemePreset preset;
        private final int mode;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ArtworkDrawable(TelePremiumThemePreset preset, int mode) {
            this.preset = preset;
            this.mode = mode;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            int base = mode == MODE_DRAWER_HEADER ? preset.background : (mode == MODE_DRAWER_BODY ? preset.drawerBackground : preset.background);
            float gradientMix = mode == MODE_DRAWER_HEADER ? (preset.isDark ? 0.18f : 0.11f) : (preset.isDark ? 0.08f : 0.045f);
            int second = blend(base, preset.accent, gradientMix);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, base, second, Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            paint.setShader(null);

            int alpha = mode == MODE_DRAWER_HEADER ? (preset.isDark ? 58 : 46) : (preset.isDark ? 24 : 18);
            if (mode == MODE_CLOUD) alpha = preset.isDark ? 27 : 19;
            paint.setColor(preset.accent);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(Math.max(1.4f, b.width() / 390f));
            paint.setStyle(Paint.Style.STROKE);

            String type = preset.wallpaperType == null ? "" : preset.wallpaperType;
            if ("waves".equals(type)) drawWaves(canvas, b);
            else if ("leaves".equals(type)) drawLeaves(canvas, b);
            else if ("frost".equals(type)) drawMountains(canvas, b, false);
            else if ("ice".equals(type)) drawIce(canvas, b);
            else if ("dunes".equals(type)) drawDunes(canvas, b);
            else if ("paper".equals(type)) drawPaper(canvas, b);
            else if ("sunset".equals(type)) drawSunset(canvas, b);
            else if ("twilight".equals(type)) drawTwilight(canvas, b);
            else if ("silver".equals(type)) drawSilver(canvas, b);
            else drawGraphite(canvas, b);
        }

        private void drawWaves(Canvas c, Rect b) {
            for (int i = 0; i < 5; i++) {
                float y = b.top + b.height() * (0.18f + i * 0.17f);
                path.reset(); path.moveTo(b.left - b.width() * 0.10f, y);
                path.cubicTo(b.left + b.width()*0.18f, y-b.height()*0.10f, b.left+b.width()*0.42f, y+b.height()*0.09f, b.left+b.width()*0.66f, y);
                path.cubicTo(b.left+b.width()*0.84f, y-b.height()*0.08f, b.right+b.width()*0.03f, y+b.height()*0.04f, b.right+b.width()*0.12f, y);
                c.drawPath(path, paint);
            }
            if (mode == MODE_DRAWER_HEADER) {
                paint.setStyle(Paint.Style.FILL);
                for (int i=0;i<5;i++) c.drawCircle(b.left+b.width()*(0.62f+i*0.06f), b.top+b.height()*(0.24f-(i%2)*0.03f), Math.max(1.5f,b.width()*0.006f), paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        }

        private void drawLeaves(Canvas c, Rect b) {
            paint.setStyle(Paint.Style.FILL);
            int count = mode == MODE_DRAWER_HEADER ? 9 : 6;
            for (int i=0;i<count;i++) {
                float cx = b.left + b.width() * (i % 2 == 0 ? 0.82f : 0.91f);
                float cy = b.top + b.height() * (0.10f + (i / 2) * 0.17f);
                RectF r = new RectF(cx-b.width()*0.055f, cy-b.height()*0.032f, cx+b.width()*0.055f, cy+b.height()*0.032f);
                c.save(); c.rotate(-35 + (i%3)*24, cx, cy); c.drawOval(r, paint); c.restore();
            }
            if (mode != MODE_DRAWER_HEADER) {
                for (int i=0;i<5;i++) {
                    float cx=b.left+b.width()*(0.10f+(i%2)*0.08f); float cy=b.bottom-b.height()*(0.10f+(i/2)*0.10f);
                    RectF r=new RectF(cx-b.width()*0.045f,cy-b.height()*0.025f,cx+b.width()*0.045f,cy+b.height()*0.025f);
                    c.save(); c.rotate(25+i*9,cx,cy); c.drawOval(r,paint); c.restore();
                }
            }
            paint.setStyle(Paint.Style.STROKE);
        }

        private void drawMountains(Canvas c, Rect b, boolean twilight) {
            float baseY = b.top + b.height() * (mode == MODE_DRAWER_HEADER ? 0.82f : 0.72f);
            paint.setStyle(Paint.Style.FILL);
            int original = paint.getColor();
            int originalAlpha = paint.getAlpha();
            paint.setAlpha(Math.max(12, originalAlpha));
            path.reset(); path.moveTo(b.left, baseY);
            path.lineTo(b.left+b.width()*0.18f, baseY-b.height()*0.22f);
            path.lineTo(b.left+b.width()*0.32f, baseY-b.height()*0.09f);
            path.lineTo(b.left+b.width()*0.51f, baseY-b.height()*0.31f);
            path.lineTo(b.left+b.width()*0.69f, baseY-b.height()*0.12f);
            path.lineTo(b.left+b.width()*0.84f, baseY-b.height()*0.25f);
            path.lineTo(b.right, baseY-b.height()*0.08f); path.lineTo(b.right,b.bottom); path.lineTo(b.left,b.bottom); path.close(); c.drawPath(path,paint);
            paint.setColor(blend(original, preset.background, 0.38f)); paint.setAlpha(Math.max(10, originalAlpha-8));
            path.reset(); path.moveTo(b.left,baseY+b.height()*0.04f); path.lineTo(b.left+b.width()*0.25f,baseY-b.height()*0.08f); path.lineTo(b.left+b.width()*0.48f,baseY+b.height()*0.01f); path.lineTo(b.left+b.width()*0.72f,baseY-b.height()*0.11f); path.lineTo(b.right,baseY); path.lineTo(b.right,b.bottom); path.lineTo(b.left,b.bottom); path.close(); c.drawPath(path,paint);
            paint.setColor(original); paint.setAlpha(originalAlpha); paint.setStyle(Paint.Style.STROKE);
        }

        private void drawTwilight(Canvas c, Rect b) {
            drawMountains(c,b,true);
            if (mode == MODE_DRAWER_HEADER) {
                paint.setStyle(Paint.Style.FILL); float r=Math.min(b.width(),b.height())*0.075f; float cx=b.right-b.width()*0.17f; float cy=b.top+b.height()*0.24f; c.drawCircle(cx,cy,r,paint); paint.setColor(preset.background); c.drawCircle(cx+r*0.42f,cy-r*0.18f,r*0.92f,paint); paint.setColor(preset.accent); paint.setStyle(Paint.Style.STROKE);
            }
        }

        private void drawSunset(Canvas c, Rect b) {
            if (mode == MODE_DRAWER_HEADER) {
                paint.setStyle(Paint.Style.FILL); float r=Math.min(b.width(),b.height())*0.09f; c.drawCircle(b.left+b.width()*0.78f,b.top+b.height()*0.38f,r,paint); paint.setStyle(Paint.Style.STROKE);
            }
            drawMountains(c,b,false);
        }

        private void drawIce(Canvas c, Rect b) {
            float[][] p={{.04f,.12f,.34f,.35f},{.34f,.35f,.16f,.72f},{.34f,.35f,.68f,.18f},{.34f,.35f,.72f,.66f},{.72f,.66f,.96f,.42f},{.72f,.66f,.88f,.94f},{.16f,.72f,.50f,.90f}};
            for(float[] v:p)c.drawLine(b.left+b.width()*v[0],b.top+b.height()*v[1],b.left+b.width()*v[2],b.top+b.height()*v[3],paint);
        }

        private void drawDunes(Canvas c, Rect b) {
            for(int i=0;i<4;i++){float y=b.top+b.height()*(0.40f+i*0.14f);path.reset();path.moveTo(b.left-b.width()*.05f,y);path.cubicTo(b.left+b.width()*.20f,y-b.height()*.13f,b.left+b.width()*.48f,y+b.height()*.07f,b.left+b.width()*.72f,y-b.height()*.04f);path.cubicTo(b.left+b.width()*.86f,y-b.height()*.09f,b.right,y+b.height()*.03f,b.right+b.width()*.08f,y);c.drawPath(path,paint);}
        }

        private void drawPaper(Canvas c, Rect b) {
            for(int i=0;i<9;i++){float y=b.top+b.height()*(0.12f+i*.10f);c.drawLine(b.left+b.width()*.08f,y,b.right-b.width()*.08f,y+b.height()*.015f,paint);}
            drawDunes(c,b);
        }

        private void drawSilver(Canvas c, Rect b) {
            for(int i=0;i<5;i++){float x=b.left+b.width()*(.05f+i*.22f);path.reset();path.moveTo(x,b.bottom);path.lineTo(x+b.width()*.22f,b.top);c.drawPath(path,paint);}
        }

        private void drawGraphite(Canvas c, Rect b) {
            float step=Math.max(34f,b.width()/7f);for(float x=b.left-b.height();x<b.right+b.height();x+=step)c.drawLine(x,b.bottom,x+b.height(),b.top,paint);
        }

        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);} @Override public void setColorFilter(ColorFilter colorFilter){paint.setColorFilter(colorFilter);} @Override public int getOpacity(){return PixelFormat.OPAQUE;}
        private static int blend(int from,int to,float amount){amount=Math.max(0f,Math.min(1f,amount));int a=(int)(Color.alpha(from)+(Color.alpha(to)-Color.alpha(from))*amount);int r=(int)(Color.red(from)+(Color.red(to)-Color.red(from))*amount);int g=(int)(Color.green(from)+(Color.green(to)-Color.green(from))*amount);int bb=(int)(Color.blue(from)+(Color.blue(to)-Color.blue(from))*amount);return Color.argb(a,r,g,bb);}
    }
}
