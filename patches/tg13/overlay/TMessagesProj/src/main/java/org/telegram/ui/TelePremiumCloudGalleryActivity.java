package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.cloud.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Internal, authenticated cloud photo viewer. Photos live in a horizontal pager: the neighbours of the
 * current photo are loaded in advance, a swipe drags the pages with the finger and settles with a fling
 * animation (like any gallery), pinch / double-tap zoom works per photo.
 */
public final class TelePremiumCloudGalleryActivity extends Activity {
    public static final class Entry {
        final int account;
        final long user, epoch;
        final CloudNode node;

        public Entry(int account, CloudNode node) throws Exception {
            this.account = account;
            this.user = UserConfig.getInstance(account).getClientUserId();
            this.epoch = TelePremiumCloudResetState.epoch();
            this.node = CloudNode.fromJson(node.toJson());
        }
    }

    private static final Map<String, ArrayList<Entry>> SESSIONS = new ConcurrentHashMap<>();
    private static final ExecutorService DECODE = Executors.newFixedThreadPool(2);

    public static void open(Context context, List<Entry> entries, int selected) {
        if (entries.isEmpty()) return;
        String token = UUID.randomUUID().toString();
        SESSIONS.put(token, new ArrayList<>(entries));
        try {
            context.startActivity(new Intent(context, TelePremiumCloudGalleryActivity.class).putExtra("session", token).putExtra("position", selected));
        } catch (RuntimeException e) {
            SESSIONS.remove(token);
            throw e;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> SESSIONS.remove(token), 120000);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ArrayList<Entry> entries;
    private int position;
    private boolean closed;
    private FrameLayout root;
    private LinearLayout chrome;
    private TextView title;
    private final SparseArray<Page> pages = new SparseArray<>();

    // pager state
    private float offset;
    private boolean dragging;
    private boolean scaling;
    private long suppressSwipeUntil;
    private float downX, downY, lastX;
    private VelocityTracker tracker;
    private ValueAnimator settle;
    private int touchSlop;

    private final Runnable lockCheck = new Runnable() {
        public void run() {
            if (closed) return;
            if (!active()) {
                finish();
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    private String t(String en, String ru) {
        return TPStrings.isRussian() ? ru : en;
    }

    private int dp(float n) {
        return AndroidUtilities.dp(n);
    }

    // ------------------------------------------------------------------ one photo

    private final class Page {
        final int index;
        final Entry entry;
        final FrameLayout view;
        final ImageView image;
        final ProgressBar progress;
        final TextView error;
        final Matrix matrix = new Matrix();
        Bitmap bitmap;
        float zoom = 1f;
        int gen;
        boolean loaded, loading;
        CloudDownloadManager download;

        Page(int index) {
            this.index = index;
            this.entry = entries.get(index);
            Context c = TelePremiumCloudGalleryActivity.this;
            view = new FrameLayout(c);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.addView(image, new FrameLayout.LayoutParams(-1, -1));
            progress = new ProgressBar(c);
            view.addView(progress, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
            error = new TextView(c);
            error.setTextColor(Color.WHITE);
            error.setTextSize(16);
            error.setGravity(Gravity.CENTER);
            error.setPadding(dp(24), dp(20), dp(24), dp(20));
            error.setVisibility(View.GONE);
            view.addView(error, new FrameLayout.LayoutParams(-1, -1));
            progress.setVisibility(View.GONE);
        }

        void load() {
            if (loaded || loading || closed) return;
            if (!valid(entry) || !TelePremiumCloudAccessGate.canExposeNode(TelePremiumCloudGalleryActivity.this, entry.node)) {
                if (index == position) finish();
                return;
            }
            loading = true;
            final int token = ++gen;
            progress.setVisibility(View.VISIBLE);
            error.setVisibility(View.GONE);
            main.postDelayed(() -> {
                if (!closed && token == gen && loading) {
                    gen++;
                    if (download != null) download.cancel();
                    fail(t("Photo is taking too long to load. Tap to retry.", "Фото не загрузилось. Нажмите для повтора."));
                }
            }, 45000);
            download = new CloudDownloadManager(entry.account, true);
            download.download(entry.node, (state, file, message) -> {
                if (closed || token != gen) return;
                if (index == position && !active()) {
                    finish();
                    return;
                }
                if (state == CloudDownloadManager.ERROR) {
                    fail(message);
                    return;
                }
                if (state != CloudDownloadManager.OFFLINE_AVAILABLE || file == null) return;
                DECODE.execute(() -> {
                    Bitmap result = null;
                    try {
                        result = CloudImageDecoder.decode(file, 4096, 16L * 1024 * 1024);
                    } catch (Throwable failure) {
                        org.telegram.messenger.FileLog.e(failure);
                    }
                    final Bitmap decoded = result;
                    main.post(() -> {
                        if (closed || token != gen || pages.get(index) != this) {
                            if (decoded != null) decoded.recycle();
                            return;
                        }
                        if (decoded == null) {
                            fail(t("Image could not be decoded. Tap to retry.", "Не удалось открыть изображение. Нажмите для повтора."));
                            return;
                        }
                        Bitmap prior = bitmap;
                        bitmap = decoded;
                        image.setImageBitmap(decoded);
                        image.setImageAlpha(255);
                        image.setAlpha(1f);
                        fit();
                        loaded = true;
                        loading = false;
                        progress.setVisibility(View.GONE);
                        if (prior != null && prior != decoded) prior.recycle();
                        if (index == position) loadNeighbours();
                    });
                });
            });
        }

        void fail(String message) {
            loading = false;
            progress.setVisibility(View.GONE);
            error.setText(message == null ? t("Image unavailable. Tap to retry.", "Изображение недоступно. Нажмите для повтора.") : message);
            error.setVisibility(View.VISIBLE);
        }

        void retry() {
            loading = false;
            loaded = false;
            error.setVisibility(View.GONE);
            load();
        }

        void fit() {
            zoom = 1f;
            matrix.reset();
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.invalidate();
        }

        void beginTransform() {
            if (image.getScaleType() != ImageView.ScaleType.MATRIX) {
                matrix.set(image.getImageMatrix());
                image.setScaleType(ImageView.ScaleType.MATRIX);
                image.setImageMatrix(matrix);
            }
        }

        void clamp() {
            if (bitmap == null) return;
            android.graphics.drawable.Drawable drawable = image.getDrawable();
            if (drawable == null) return;
            RectF b = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            matrix.mapRect(b);
            int w = image.getWidth(), h = image.getHeight();
            float dx = b.width() < w ? (w - b.width()) / 2 - b.left : b.left > 0 ? -b.left : b.right < w ? w - b.right : 0;
            float dy = b.height() < h ? (h - b.height()) / 2 - b.top : b.top > 0 ? -b.top : b.bottom < h ? h - b.bottom : 0;
            matrix.postTranslate(dx, dy);
            image.setImageMatrix(matrix);
        }

        void release() {
            gen++;
            loading = false;
            if (download != null) download.cancel();
            image.setImageDrawable(null);
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        entries = SESSIONS.remove(getIntent().getStringExtra("session"));
        if (entries == null || entries.isEmpty()) {
            finish();
            return;
        }
        position = Math.max(0, Math.min(entries.size() - 1, getIntent().getIntExtra("position", 0)));
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (TelePremiumCloudSecurityStore.isProtectionEnabled(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        chrome = new LinearLayout(this);
        chrome.setGravity(Gravity.CENTER_VERTICAL);
        chrome.setPadding(dp(8), dp(8), dp(16), dp(8));
        chrome.setBackgroundColor(0x99000000);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(42);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(t("Close", "Закрыть"));
        back.setOnClickListener(v -> finish());
        chrome.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setMaxLines(2);
        chrome.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(chrome, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                Page p = pages.get(position);
                if (p == null || p.bitmap == null) return false;
                if (dragging || offset != 0f) return false;
                p.beginTransform();
                scaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                Page p = pages.get(position);
                if (p == null) return false;
                float next = Math.max(1f, Math.min(6f, p.zoom * d.getScaleFactor()));
                float factor = next / p.zoom;
                p.zoom = next;
                p.matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                p.clamp();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
                Page p = pages.get(position);
                if (p != null && p.zoom <= 1.05f) p.fit();
                suppressSwipeUntil = SystemClock.elapsedRealtime() + 250;
            }
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                Page p = pages.get(position);
                if (p != null && p.error.getVisibility() == View.VISIBLE) {
                    p.retry();
                    return true;
                }
                chrome.setVisibility(chrome.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Page p = pages.get(position);
                if (p != null && p.bitmap != null && offset == 0f) {
                    if (p.zoom > 1.1f) {
                        p.fit();
                    } else {
                        p.beginTransform();
                        p.zoom = 2.8f;
                        p.matrix.postScale(p.zoom, p.zoom, e.getX(), e.getY());
                        p.clamp();
                    }
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                Page p = pages.get(position);
                if (p != null && p.zoom > 1.05f && !scaling) {
                    p.matrix.postTranslate(-dx, -dy);
                    p.clamp();
                }
                return true;
            }
        });

        root.setOnTouchListener((v, event) -> {
            onPagerTouch(event);
            pinch.onTouchEvent(event);
            gestures.onTouchEvent(event);
            return true;
        });
        root.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - tp != ob - ot) {
                for (int i = 0; i < pages.size(); i++) pages.valueAt(i).fit();
                layoutPages();
            }
        });
        setContentView(root);
        onPositionChanged();
        main.post(lockCheck);
    }

    private boolean valid(Entry e) {
        return !closed && e.account >= 0 && UserConfig.getInstance(e.account).isClientActivated()
                && UserConfig.getInstance(e.account).getClientUserId() == e.user
                && TelePremiumCloudResetState.allowed(e.epoch) && e.node.state != CloudNode.STATE_DELETED
                && TelePremiumCloudManager.getInstance(e.account).isVisible(e.node);
    }

    private boolean active() {
        return entries != null && position >= 0 && position < entries.size() && valid(entries.get(position))
                && TelePremiumCloudAccessGate.canExposeNode(this, entries.get(position).node);
    }

    // ------------------------------------------------------------------ pager

    private int step() {
        return Math.max(1, root.getWidth()) + dp(12);
    }

    private void layoutPages() {
        int step = step();
        int width = Math.max(1, root.getWidth());
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.valueAt(i);
            float tx = (p.index - position) * step + offset;
            p.view.setTranslationX(tx);
            p.view.setVisibility(Math.abs(tx) < width + dp(12) ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Keeps exactly the current photo and its two neighbours alive; everything else is released. */
    private void ensureWindow() {
        for (int i = pages.size() - 1; i >= 0; i--) {
            Page p = pages.valueAt(i);
            if (Math.abs(p.index - position) > 1) {
                p.release();
                root.removeView(p.view);
                pages.removeAt(i);
            }
        }
        for (int idx = position - 1; idx <= position + 1; idx++) {
            if (idx < 0 || idx >= entries.size() || pages.get(idx) != null) continue;
            Page p = new Page(idx);
            pages.put(idx, p);
            root.addView(p.view, root.indexOfChild(chrome), new FrameLayout.LayoutParams(-1, -1));
        }
        layoutPages();
    }

    private void onPositionChanged() {
        if (closed) return;
        if (!active()) {
            finish();
            return;
        }
        TelePremiumCloudSecuritySession.touch(this);
        ensureWindow();
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.valueAt(i);
            if (p.index != position) p.fit();
        }
        Entry entry = entries.get(position);
        title.setText((position + 1) + " / " + entries.size() + "  ·  " + entry.node.name);
        Page current = pages.get(position);
        if (current != null) {
            current.load();
            if (current.loaded) loadNeighbours();
        }
    }

    private void loadNeighbours() {
        Page next = pages.get(position + 1);
        if (next != null) next.load();
        Page prev = pages.get(position - 1);
        if (prev != null) prev.load();
    }

    private void onPagerTouch(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                TelePremiumCloudSecuritySession.touch(this);
                if (settle != null) {
                    // caught mid-animation: keep the current offset and continue from there
                    settle.removeAllListeners();
                    settle.cancel();
                    settle = null;
                }
                downX = lastX = event.getX();
                downY = event.getY();
                dragging = offset != 0f;
                if (tracker != null) tracker.recycle();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(event);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (dragging) {
                    dragging = false;
                    settleTo(0f, 0f);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (tracker != null) tracker.addMovement(event);
                if (event.getPointerCount() != 1 || scaling) break;
                Page p = pages.get(position);
                boolean zoomed = p != null && p.zoom > 1.05f;
                float x = event.getX();
                if (!dragging && !zoomed && SystemClock.elapsedRealtime() > suppressSwipeUntil
                        && Math.abs(x - downX) > touchSlop && Math.abs(x - downX) > Math.abs(event.getY() - downY)) {
                    dragging = true;
                    lastX = x;
                }
                if (dragging) {
                    float dx = x - lastX;
                    lastX = x;
                    boolean atEdge = (position == 0 && offset + dx > 0) || (position == entries.size() - 1 && offset + dx < 0);
                    offset += atEdge ? dx * 0.35f : dx;
                    layoutPages();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (tracker != null) {
                    tracker.addMovement(event);
                    tracker.computeCurrentVelocity(1000);
                }
                float vx = tracker == null ? 0f : tracker.getXVelocity();
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
                if (dragging) {
                    dragging = false;
                    int step = step();
                    int dir = 0;
                    boolean cancel = action == MotionEvent.ACTION_CANCEL;
                    if (!cancel && (offset < -step * 0.25f || (vx < -dp(700) && offset < 0)) && position < entries.size() - 1) dir = 1;
                    else if (!cancel && (offset > step * 0.25f || (vx > dp(700) && offset > 0)) && position > 0) dir = -1;
                    settleTo(-dir * step, vx);
                }
                break;
            }
        }
    }

    /** Animates the drag offset to a page boundary (or back to 0) and commits the page change at the end. */
    private void settleTo(final float target, float vx) {
        if (settle != null) {
            settle.removeAllListeners();
            settle.cancel();
        }
        final float from = offset;
        if (from == target) {
            commit(target);
            return;
        }
        float distance = Math.abs(target - from);
        long duration = (long) Math.max(140f, Math.min(320f, 320f * distance / Math.max(1, step())));
        if (Math.abs(vx) > dp(900)) {
            duration = (long) Math.max(120f, Math.min(duration, distance / Math.abs(vx) * 1000f * 1.3f));
        }
        settle = ValueAnimator.ofFloat(from, target);
        settle.setDuration(duration);
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            layoutPages();
        });
        settle.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                settle = null;
                commit(target);
            }
        });
        settle.start();
    }

    private void commit(float target) {
        int moved = target == 0f ? 0 : (target < 0 ? 1 : -1);
        offset = 0f;
        if (moved != 0) {
            position = Math.max(0, Math.min(entries.size() - 1, position + moved));
            onPositionChanged();
        } else {
            layoutPages();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (root != null) root.post(() -> {
            for (int i = 0; i < pages.size(); i++) pages.valueAt(i).fit();
            layoutPages();
        });
    }

    @Override
    protected void onDestroy() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (settle != null) {
            settle.removeAllListeners();
            settle.cancel();
        }
        for (int i = 0; i < pages.size(); i++) pages.valueAt(i).release();
        pages.clear();
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        super.onDestroy();
    }
}
