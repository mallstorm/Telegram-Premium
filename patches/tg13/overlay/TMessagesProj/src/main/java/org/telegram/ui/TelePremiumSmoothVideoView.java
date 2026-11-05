package org.telegram.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Smooth-motion video output (motion-compensated frame interpolation, the idea behind SVP).
 *
 * <p>The decoder renders into a SurfaceTexture. On every display vsync this view draws an
 * in-between picture of the two latest decoded frames, so 24/25/30 fps video is shown at the
 * screen rate (60/90/120 Hz). Motion is estimated on the GPU for every new source frame:
 * frames are reduced to luma at 1/4 size, a 4×4-block three-step search (8,4,2,1,½ px) finds
 * per-block vectors, a 3×3 weighted filter smooths the field, and the output pass samples the
 * previous frame at p − a·v and the current one at p + (1 − a)·v. Where the two warped samples
 * disagree (occlusions, scene cuts) it falls back to the nearest original frame, which avoids
 * the classic "soap bubbles" artifacts.</p>
 *
 * <p>The picture is shown one source frame late (≈40 ms at 24 fps). Content that is already
 * close to the display rate is passed through untouched. Any GL failure falls back to the
 * ordinary SurfaceView output.</p>
 */
final class TelePremiumSmoothVideoView extends TelePremiumAspectSurfaceView implements SurfaceHolder.Callback {

    static boolean isSupported(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x20000;
        } catch (Throwable e) {
            return false;
        }
    }

    private Renderer renderer;

    TelePremiumSmoothVideoView(Context context) {
        super(context);
        getHolder().addCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        float refresh = 60f;
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) refresh = wm.getDefaultDisplay().getRefreshRate();
        } catch (Throwable ignore) {
        }
        renderer = new Renderer(holder.getSurface(), refresh, this::fallBackToPlainOutput);
        renderer.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (renderer != null) renderer.setSize(width, height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (renderer != null) {
            TelePremiumPlaybackController.getInstance().bindOutputSurface(null);
            renderer.quitAndWait();
            renderer = null;
        }
    }

    /** GL is unavailable: give the SurfaceView to the decoder directly. */
    private void fallBackToPlainOutput() {
        post(() -> {
            if (renderer != null) {
                renderer.quitAndWait();
                renderer = null;
            }
            TelePremiumPlaybackController.getInstance().bindSurface(this);
        });
    }

    // =====================================================================================

    private static final class Renderer implements SurfaceTexture.OnFrameAvailableListener, Choreographer.FrameCallback {
        private static final String VERTEX =
                "attribute vec2 aPos;\n" +
                "varying vec2 vUv;\n" +
                "void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

        private static final String FRAG_OES =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTex;\n" +
                "uniform mat4 uMatrix;\n" +
                "varying vec2 vUv;\n" +
                "void main(){ gl_FragColor = texture2D(uTex, (uMatrix * vec4(vUv, 0.0, 1.0)).xy); }\n";

        private static final String FRAG_LUMA =
                "precision mediump float;\n" +
                "uniform sampler2D uTex;\n" +
                "uniform vec2 uTexel;\n" +
                "varying vec2 vUv;\n" +
                "void main(){\n" +
                "  vec3 c = texture2D(uTex, vUv + uTexel * vec2(-1.0, -1.0)).rgb + texture2D(uTex, vUv + uTexel * vec2(1.0, -1.0)).rgb\n" +
                "         + texture2D(uTex, vUv + uTexel * vec2(-1.0, 1.0)).rgb + texture2D(uTex, vUv + uTexel * vec2(1.0, 1.0)).rgb;\n" +
                "  float y = dot(c * 0.25, vec3(0.299, 0.587, 0.114));\n" +
                "  gl_FragColor = vec4(y, y, y, 1.0);\n" +
                "}\n";

        /** Three-step search on 4×4 luma blocks; output (v + 32) / 64 in RG, SAD in B. */
        private static final String FRAG_SEARCH =
                "precision highp float;\n" +
                "uniform sampler2D uPrev;\n" +
                "uniform sampler2D uCur;\n" +
                "uniform vec2 uTexel;\n" +
                "varying vec2 vUv;\n" +
                "float sad(vec2 v){\n" +
                "  float s = 0.0;\n" +
                "  for (int y = 0; y < 4; y++) {\n" +
                "    for (int x = 0; x < 4; x++) {\n" +
                "      vec2 o = (vec2(float(x), float(y)) - 1.5) * uTexel;\n" +
                "      s += abs(texture2D(uPrev, vUv + o).r - texture2D(uCur, vUv + o + v * uTexel).r);\n" +
                "    }\n" +
                "  }\n" +
                "  return s;\n" +
                "}\n" +
                "void main(){\n" +
                "  vec2 best = vec2(0.0);\n" +
                "  float bestS = sad(best);\n" +
                "  float stepSize = 8.0;\n" +
                "  for (int i = 0; i < 5; i++) {\n" +
                "    vec2 c = best;\n" +
                "    for (int dy = -1; dy <= 1; dy++) {\n" +
                "      for (int dx = -1; dx <= 1; dx++) {\n" +
                "        if (dx == 0 && dy == 0) continue;\n" +
                "        vec2 v = clamp(c + vec2(float(dx), float(dy)) * stepSize, -31.0, 31.0);\n" +
                "        float s = sad(v) + 0.002 * length(v);\n" +
                "        if (s < bestS) { bestS = s; best = v; }\n" +
                "      }\n" +
                "    }\n" +
                "    stepSize *= 0.5;\n" +
                "  }\n" +
                "  gl_FragColor = vec4((best + 32.0) / 64.0, clamp(bestS / 4.0, 0.0, 1.0), 1.0);\n" +
                "}\n";

        /** 3×3 confidence-weighted vector smoothing. */
        private static final String FRAG_SMOOTH =
                "precision highp float;\n" +
                "uniform sampler2D uMotion;\n" +
                "uniform vec2 uTexel;\n" +
                "varying vec2 vUv;\n" +
                "void main(){\n" +
                "  vec2 acc = vec2(0.0);\n" +
                "  float sum = 0.0;\n" +
                "  for (int y = -1; y <= 1; y++) {\n" +
                "    for (int x = -1; x <= 1; x++) {\n" +
                "      vec4 m = texture2D(uMotion, vUv + vec2(float(x), float(y)) * uTexel);\n" +
                "      float w = 1.0 / (0.02 + m.b);\n" +
                "      if (x == 0 && y == 0) w *= 2.0;\n" +
                "      acc += (m.rg * 64.0 - 32.0) * w;\n" +
                "      sum += w;\n" +
                "    }\n" +
                "  }\n" +
                "  vec2 v = acc / sum;\n" +
                "  gl_FragColor = vec4((v + 32.0) / 64.0, texture2D(uMotion, vUv).b, 1.0);\n" +
                "}\n";

        /** Output: motion-compensated blend with an occlusion fallback to the nearest frame. */
        private static final String FRAG_INTERP =
                "precision highp float;\n" +
                "uniform sampler2D uPrev;\n" +
                "uniform sampler2D uCur;\n" +
                "uniform sampler2D uMotion;\n" +
                "uniform float uAlpha;\n" +
                "uniform float uUseMotion;\n" +
                "uniform vec2 uLumaTexel;\n" +
                "varying vec2 vUv;\n" +
                "void main(){\n" +
                "  if (uUseMotion < 0.5) { gl_FragColor = texture2D(uCur, vUv); return; }\n" +
                "  vec2 v = (texture2D(uMotion, vUv).rg * 64.0 - 32.0) * uLumaTexel;\n" +
                "  vec4 a = texture2D(uPrev, vUv - uAlpha * v);\n" +
                "  vec4 b = texture2D(uCur, vUv + (1.0 - uAlpha) * v);\n" +
                "  vec4 mc = mix(a, b, uAlpha);\n" +
                "  float diff = dot(abs(a.rgb - b.rgb), vec3(0.3333));\n" +
                "  vec4 nearest = uAlpha < 0.5 ? texture2D(uPrev, vUv) : texture2D(uCur, vUv);\n" +
                "  gl_FragColor = mix(mc, nearest, smoothstep(0.10, 0.30, diff));\n" +
                "}\n";

        private final Surface window;
        private final float displayHz;
        private final Runnable onFailure;
        private final HandlerThread thread = new HandlerThread("TelePremiumSmoothVideo");
        private Handler handler;
        private Choreographer choreographer;

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        private int oesTex;
        private SurfaceTexture surfaceTexture;
        private Surface decoderSurface;
        private final float[] matrix = new float[16];
        private FloatBuffer quad;

        private int progOes, progLuma, progSearch, progSmooth, progInterp;
        // frames[0/1], luma[0/1], motionRaw, motionSmooth: texture + fbo
        private final int[] frameTex = new int[2], frameFbo = new int[2];
        private final int[] lumaTex = new int[2], lumaFbo = new int[2];
        private final int[] motionTex = new int[2], motionFbo = new int[2];
        private int procW, procH, lumaW, lumaH, gridW, gridH;
        private int cur = 0;          // index of the current frame in frameTex/lumaTex
        private boolean havePrev, haveCur, motionValid;

        private volatile int viewW, viewH;
        private volatile boolean frameAvailable, quitting;
        private boolean loopRunning;
        private long arrivalNs, intervalNs = 41_666_667L, lastTimestamp, lastFrameSeenNs;

        Renderer(Surface window, float displayHz, Runnable onFailure) {
            this.window = window;
            this.displayHz = displayHz <= 1 ? 60f : displayHz;
            this.onFailure = onFailure;
        }

        void start() {
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(() -> {
                try {
                    initGl();
                } catch (Throwable e) {
                    FileLog.e(e);
                    releaseGl();
                    onFailure.run();
                }
            });
        }

        void setSize(int w, int h) {
            viewW = w;
            viewH = h;
            if (handler != null) handler.post(this::ensureLoop);
        }

        void quitAndWait() {
            quitting = true;
            if (handler == null) return;
            CountDownLatch latch = new CountDownLatch(1);
            handler.post(() -> {
                try {
                    if (choreographer != null) choreographer.removeFrameCallback(this);
                    releaseGl();
                } finally {
                    latch.countDown();
                }
            });
            try { latch.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignore) {}
            thread.quitSafely();
        }

        // ---- setup ----

        private void initGl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("eglInitialize");
            int[] attribs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) throw new RuntimeException("eglChooseConfig");
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], window, new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw new RuntimeException("eglMakeCurrent");

            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            progOes = program(FRAG_OES);
            progLuma = program(FRAG_LUMA);
            progSearch = program(FRAG_SEARCH);
            progSmooth = program(FRAG_SMOOTH);
            progInterp = program(FRAG_INTERP);

            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            oesTex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            surfaceTexture = new SurfaceTexture(oesTex);
            surfaceTexture.setOnFrameAvailableListener(this, handler);
            decoderSurface = new Surface(surfaceTexture);
            choreographer = Choreographer.getInstance();
            Surface target = decoderSurface;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!quitting) TelePremiumPlaybackController.getInstance().bindOutputSurface(target);
            });
        }

        private int program(String fragment) {
            int vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glBindAttribLocation(p, 0, "aPos");
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            return p;
        }

        private int shader(int type, String source) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, source);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("compile: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        private void allocate(int w, int h) {
            freeTargets();
            procW = w;
            procH = h;
            gridW = Math.max(8, procW / 16);
            gridH = Math.max(8, procH / 16);
            lumaW = gridW * 4;
            lumaH = gridH * 4;
            for (int i = 0; i < 2; i++) {
                int[] f = target(procW, procH, GLES20.GL_LINEAR);
                frameTex[i] = f[0];
                frameFbo[i] = f[1];
                int[] l = target(lumaW, lumaH, GLES20.GL_LINEAR);
                lumaTex[i] = l[0];
                lumaFbo[i] = l[1];
            }
            int[] raw = target(gridW, gridH, GLES20.GL_NEAREST);
            motionTex[0] = raw[0];
            motionFbo[0] = raw[1];
            int[] smooth = target(gridW, gridH, GLES20.GL_LINEAR);
            motionTex[1] = smooth[0];
            motionFbo[1] = smooth[1];
            havePrev = haveCur = motionValid = false;
        }

        private int[] target(int w, int h, int filter) {
            int[] t = new int[1], f = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("fbo");
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return new int[]{t[0], f[0]};
        }

        private void freeTargets() {
            int[][] all = {frameTex, lumaTex, motionTex};
            for (int[] arr : all) for (int i = 0; i < arr.length; i++) if (arr[i] != 0) { GLES20.glDeleteTextures(1, arr, i); arr[i] = 0; }
            int[][] fbos = {frameFbo, lumaFbo, motionFbo};
            for (int[] arr : fbos) for (int i = 0; i < arr.length; i++) if (arr[i] != 0) { GLES20.glDeleteFramebuffers(1, arr, i); arr[i] = 0; }
            procW = procH = 0;
        }

        private void releaseGl() {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                    freeTargets();
                    int[] progs = {progOes, progLuma, progSearch, progSmooth, progInterp};
                    for (int p : progs) if (p != 0) GLES20.glDeleteProgram(p);
                    if (oesTex != 0) GLES20.glDeleteTextures(1, new int[]{oesTex}, 0);
                }
            } catch (Throwable ignore) {
            }
            if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
            if (decoderSurface != null) { decoderSurface.release(); decoderSurface = null; }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
        }

        // ---- frame loop ----

        @Override public void onFrameAvailable(SurfaceTexture st) {
            frameAvailable = true;
            ensureLoop();
        }

        private void ensureLoop() {
            if (loopRunning || quitting || choreographer == null) return;
            loopRunning = true;
            choreographer.postFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (quitting || eglSurface == EGL14.EGL_NO_SURFACE) { loopRunning = false; return; }
            try {
                renderFrame(frameTimeNanos);
            } catch (Throwable e) {
                FileLog.e(e);
                loopRunning = false;
                quitting = true;
                releaseGl();
                onFailure.run();
                return;
            }
            // Keep ticking while frames arrive or an interpolation is still in progress.
            boolean idle = !frameAvailable && frameTimeNanos - lastFrameSeenNs > 3 * intervalNs + 250_000_000L;
            if (idle) loopRunning = false;
            else choreographer.postFrameCallback(this);
        }

        private void renderFrame(long now) {
            int w = viewW, h = viewH;
            if (w <= 0 || h <= 0) return;
            // Work at the on-screen size (the view is already sized to the video), at most 1920 px.
            float scale = Math.min(1f, 1920f / Math.max(w, h));
            int pw = Math.max(16, Math.round(w * scale)), ph = Math.max(16, Math.round(h * scale));
            if (pw != procW || ph != procH) allocate(pw, ph);

            if (frameAvailable) {
                frameAvailable = false;
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(matrix);
                long ts = surfaceTexture.getTimestamp();
                long delta = ts - lastTimestamp;
                // Presentation-time jump = seek / new item / resume: no interpolation across it.
                boolean continuous = lastTimestamp != 0 && delta > 0 && delta < 250_000_000L;
                lastTimestamp = ts;
                // Pace by real arrival time (follows playback speed), smoothed over a few frames.
                long arrivalDelta = now - arrivalNs;
                if (continuous && arrivalDelta > 4_000_000L && arrivalDelta < 150_000_000L) intervalNs = (intervalNs * 3 + arrivalDelta) / 4;
                cur = 1 - cur;
                havePrev = haveCur && continuous; // seek / pause: never interpolate across a jump
                haveCur = true;
                arrivalNs = now;
                lastFrameSeenNs = now;
                copyOes(cur);
                makeLuma(cur);
                motionValid = havePrev && interpolationWanted();
                if (motionValid) estimateMotion();
            }
            if (!haveCur) return;

            float alpha = 1f;
            if (motionValid) alpha = Math.max(0f, Math.min(1f, (now - arrivalNs) / (float) intervalNs));
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(progInterp);
            bindTex(progInterp, "uPrev", 0, frameTex[1 - cur]);
            bindTex(progInterp, "uCur", 1, frameTex[cur]);
            bindTex(progInterp, "uMotion", 2, motionTex[1]);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progInterp, "uAlpha"), alpha);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progInterp, "uUseMotion"), motionValid && alpha < 1f ? 1f : 0f);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progInterp, "uLumaTexel"), 1f / lumaW, 1f / lumaH);
            drawQuad();
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }

        /** Only when the source is clearly slower than the screen (24/25/30 fps on 60 Hz+). */
        private boolean interpolationWanted() {
            float sourceFps = 1e9f / Math.max(1, intervalNs);
            return sourceFps < displayHz * 0.75f && sourceFps > 8f;
        }

        private void copyOes(int index) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[index]);
            GLES20.glViewport(0, 0, procW, procH);
            GLES20.glUseProgram(progOes);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "uTex"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uMatrix"), 1, false, matrix, 0);
            drawQuad();
        }

        private void makeLuma(int index) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lumaFbo[index]);
            GLES20.glViewport(0, 0, lumaW, lumaH);
            GLES20.glUseProgram(progLuma);
            bindTex(progLuma, "uTex", 0, frameTex[index]);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progLuma, "uTexel"), 1f / procW, 1f / procH);
            drawQuad();
        }

        private void estimateMotion() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo[0]);
            GLES20.glViewport(0, 0, gridW, gridH);
            GLES20.glUseProgram(progSearch);
            bindTex(progSearch, "uPrev", 0, lumaTex[1 - cur]);
            bindTex(progSearch, "uCur", 1, lumaTex[cur]);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progSearch, "uTexel"), 1f / lumaW, 1f / lumaH);
            drawQuad();

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo[1]);
            GLES20.glUseProgram(progSmooth);
            bindTex(progSmooth, "uMotion", 0, motionTex[0]);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progSmooth, "uTexel"), 1f / gridW, 1f / gridH);
            drawQuad();
        }

        private void bindTex(int program, String name, int unit, int texture) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit);
        }

        private void drawQuad() {
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }
}
