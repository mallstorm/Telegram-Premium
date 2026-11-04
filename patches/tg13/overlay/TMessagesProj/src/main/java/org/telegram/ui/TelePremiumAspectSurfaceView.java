package org.telegram.ui;

import android.content.Context;
import android.view.SurfaceView;

import org.telegram.messenger.telepremium.media.TelePremiumPlayerMath;

/** Video surface that keeps the aspect ratio for fit / crop / stretch / original scale modes. */
final class TelePremiumAspectSurfaceView extends SurfaceView {
    private int videoWidth, videoHeight;
    private int scaleMode = TelePremiumPlayerMath.SCALE_FIT;

    TelePremiumAspectSurfaceView(Context context) { super(context); }

    void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        requestLayout();
    }

    void setScaleMode(int mode) {
        scaleMode = mode;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int[] size = TelePremiumPlayerMath.fitVideo(videoWidth, videoHeight,
                MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec), scaleMode);
        setMeasuredDimension(size[0], size[1]);
    }
}
