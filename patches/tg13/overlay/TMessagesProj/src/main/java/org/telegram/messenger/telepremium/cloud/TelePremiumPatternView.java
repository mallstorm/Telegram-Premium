package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;

import java.util.ArrayList;

/** Compact Xiaomi-style 3x3 pattern input with large invisible hit targets. */
public final class TelePremiumPatternView extends View {
    public interface Listener {
        void onPatternComplete(String pattern);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ArrayList<Integer> selected = new ArrayList<>();
    private final float[] centersX = new float[9];
    private final float[] centersY = new float[9];

    private Listener listener;
    private float touchX;
    private float touchY;
    private float previousX;
    private float previousY;
    private float cellWidth;
    private float cellHeight;
    private boolean tracking;
    private boolean error;
    private final TelePremiumThemePreset theme;

    public TelePremiumPatternView(Context context) {
        super(context);
        theme = TelePremiumThemeController.getCurrent(context);
        setFocusable(true);
        setClickable(true);
        setContentDescription("Графический ключ. Девять точек");
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void showError() {
        error = true;
        invalidate();
        postDelayed(() -> {
            error = false;
            clearPattern();
        }, 650);
    }

    public void clearPattern() {
        selected.clear();
        tracking = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Keep the grid visually compact even when the host gives us a larger panel.
        float side = Math.min(w, h);
        float inset = Math.max(AndroidUtilities.dp(24), side * 0.115f);
        float usable = Math.max(AndroidUtilities.dp(150), side - inset * 2f);
        cellWidth = usable / 3f;
        cellHeight = usable / 3f;
        float left = (w - usable) / 2f;
        float top = (h - usable) / 2f;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                centersX[i] = left + cellWidth * (col + 0.5f);
                centersY[i] = top + cellHeight * (row + 0.5f);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int active = error ? 0xffd94b55 : theme.accent;
        int passive = theme.iconSecondary;
        float selectedOuter = AndroidUtilities.dp(8.5f);
        float dotRadius = AndroidUtilities.dp(3.6f);

        if (!selected.isEmpty()) {
            path.reset();
            int first = selected.get(0);
            path.moveTo(centersX[first], centersY[first]);
            for (int i = 1; i < selected.size(); i++) {
                int index = selected.get(i);
                path.lineTo(centersX[index], centersY[index]);
            }
            if (tracking) path.lineTo(touchX, touchY);
            paint.setColor(active);
            paint.setStrokeWidth(AndroidUtilities.dp(2.2f));
            paint.setAlpha(205);
            canvas.drawPath(path, paint);
            paint.setAlpha(255);
        }

        for (int i = 0; i < 9; i++) {
            boolean chosen = selected.contains(i);
            if (chosen) {
                paint.setColor(active);
                paint.setStrokeWidth(AndroidUtilities.dp(1.7f));
                canvas.drawCircle(centersX[i], centersY[i], selectedOuter, paint);
                fillPaint.setColor(active);
                canvas.drawCircle(centersX[i], centersY[i], dotRadius, fillPaint);
            } else {
                fillPaint.setColor(passive);
                fillPaint.setAlpha(theme.isDark ? 215 : 175);
                canvas.drawCircle(centersX[i], centersY[i], dotRadius, fillPaint);
                fillPaint.setAlpha(255);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestParentInterception(false);
                selected.clear();
                error = false;
                tracking = true;
                touchX = previousX = event.getX();
                touchY = previousY = event.getY();
                addHitAt(touchX, touchY);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                int history = event.getHistorySize();
                for (int i = 0; i <= history; i++) {
                    float x = i < history ? event.getHistoricalX(i) : event.getX();
                    float y = i < history ? event.getHistoricalY(i) : event.getY();
                    processSegment(previousX, previousY, x, y);
                    previousX = x;
                    previousY = y;
                    touchX = x;
                    touchY = y;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                processSegment(previousX, previousY, event.getX(), event.getY());
                touchX = event.getX();
                touchY = event.getY();
                tracking = false;
                requestParentInterception(true);
                invalidate();
                performClick();
                if (listener != null && !selected.isEmpty()) {
                    StringBuilder pattern = new StringBuilder(selected.size());
                    for (int index : selected) pattern.append(index);
                    listener.onPatternComplete(pattern.toString());
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                requestParentInterception(true);
                selected.clear();
                invalidate();
                return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void requestParentInterception(boolean allow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(!allow);
    }

    private void processSegment(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float distance = (float) Math.hypot(dx, dy);
        float step = Math.max(AndroidUtilities.dp(3), Math.min(cellWidth, cellHeight) * 0.045f);
        int count = Math.max(1, (int) Math.ceil(distance / step));
        for (int i = 1; i <= count; i++) {
            float t = i / (float) count;
            addHitAt(x0 + dx * t, y0 + dy * t);
        }
    }

    private void addHitAt(float x, float y) {
        int hit = findHit(x, y);
        if (hit < 0 || selected.contains(hit)) return;

        if (!selected.isEmpty()) {
            int last = selected.get(selected.size() - 1);
            int lastRow = last / 3;
            int lastCol = last % 3;
            int row = hit / 3;
            int col = hit % 3;
            int dRow = row - lastRow;
            int dCol = col - lastCol;
            int fillRow = lastRow;
            int fillCol = lastCol;
            boolean hasGap = false;

            if (Math.abs(dRow) == 2 && Math.abs(dCol) != 1) {
                fillRow += dRow > 0 ? 1 : -1;
                hasGap = true;
            }
            if (Math.abs(dCol) == 2 && Math.abs(dRow) != 1) {
                fillCol += dCol > 0 ? 1 : -1;
                hasGap = true;
            }

            if (hasGap) {
                int gap = fillRow * 3 + fillCol;
                if (!selected.contains(gap)) selected.add(gap);
            }
        }

        selected.add(hit);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private int findHit(float x, float y) {
        // The visible dots are intentionally tiny; hit targets remain forgiving.
        float halfW = cellWidth * 0.36f;
        float halfH = cellHeight * 0.36f;
        for (int i = 0; i < 9; i++) {
            if (Math.abs(x - centersX[i]) <= halfW && Math.abs(y - centersY[i]) <= halfH) {
                return i;
            }
        }
        return -1;
    }
}
