package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.TelePremiumShellTint;
import org.telegram.ui.ActionBar.Theme;

/**
 * TelePremium gradient shell-tint control used from Chat Settings.
 */
public class TelePremiumShellTintCell extends LinearLayout {

    private final TextView titleView;
    private final TextView subtitleView;
    private final TintSlider slider;
    private Runnable onTintChanged;

    public TelePremiumShellTintCell(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(15), AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        titleView = new TextView(context);
        titleView.setText("Оттенок оболочки");
        titleView.setTextSize(16);
        titleView.setGravity(Gravity.START);
        addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setText("Белый → тёплые оттенки → цветные → чёрный");
        subtitleView.setTextSize(13);
        subtitleView.setGravity(Gravity.START);
        LayoutParams sub = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sub.topMargin = AndroidUtilities.dp(3);
        addView(subtitleView, sub);

        slider = new TintSlider(context);
        LayoutParams bar = new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(44));
        bar.topMargin = AndroidUtilities.dp(8);
        addView(slider, bar);

        slider.setOnPositionChanged(position -> {
            TelePremiumShellTint.setPosition(position);
            if (onTintChanged != null) {
                onTintChanged.run();
            }
            updateColors();
        });

        setOnLongClickListener(v -> {
            TelePremiumShellTint.reset();
            slider.setPosition(TelePremiumShellTint.getPosition());
            if (onTintChanged != null) {
                onTintChanged.run();
            }
            updateColors();
            return true;
        });

        updateColors();
    }

    public void setOnTintChanged(Runnable callback) {
        onTintChanged = callback;
    }

    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        slider.setPosition(TelePremiumShellTint.getPosition());
        slider.invalidate();
    }

    private interface OnPositionChanged {
        void onChanged(int position);
    }

    private static class TintSlider extends View {

        private static final int[] COLORS = {
                0xFFFDFDFD,
                0xFFF5EEE2,
                0xFFF2D878,
                0xFFED9B69,
                0xFFD86C9F,
                0xFF9A6BD0,
                0xFF607FDC,
                0xFF55B7D4,
                0xFF3E7775,
                0xFF090A0C
        };

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private int position;
        private OnPositionChanged listener;

        TintSlider(Context context) {
            super(context);
            position = TelePremiumShellTint.getPosition();
            setFocusable(true);
            setContentDescription("Оттенок оболочки");
            thumbPaint.setStyle(Paint.Style.FILL);
        }

        void setOnPositionChanged(OnPositionChanged listener) {
            this.listener = listener;
        }

        void setPosition(int position) {
            this.position = Math.max(0, Math.min(1000, position));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = AndroidUtilities.dp(3);
            float right = getWidth() - AndroidUtilities.dp(3);
            float cy = getHeight() / 2f;
            float half = AndroidUtilities.dp(8);
            track.set(left, cy - half, right, cy + half);

            paint.setShader(new LinearGradient(left, 0, right, 0, COLORS, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(track, half, half, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(1));
            paint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(track, half, half, paint);
            paint.setStyle(Paint.Style.FILL);

            float x = left + (right - left) * position / 1000f;
            thumbPaint.setColor(Color.WHITE);
            canvas.drawCircle(x, cy, AndroidUtilities.dp(11), thumbPaint);
            thumbPaint.setColor(TelePremiumShellTint.getTargetColor(position));
            canvas.drawCircle(x, cy, AndroidUtilities.dp(7.5f), thumbPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE
                    || event.getActionMasked() == MotionEvent.ACTION_UP) {
                float left = AndroidUtilities.dp(3);
                float right = getWidth() - AndroidUtilities.dp(3);
                float normalized = (event.getX() - left) / Math.max(1f, right - left);
                int newPosition = Math.round(Math.max(0f, Math.min(1f, normalized)) * 1000f);
                if (newPosition != position) {
                    position = newPosition;
                    invalidate();
                    if (listener != null) {
                        listener.onChanged(position);
                    }
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
