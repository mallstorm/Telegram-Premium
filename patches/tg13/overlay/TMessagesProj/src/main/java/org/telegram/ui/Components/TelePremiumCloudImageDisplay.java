package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.widget.ImageView;

/** ImageView also has a deprecated setAlpha(int): 1 there means 1/255, not full opacity. */
public final class TelePremiumCloudImageDisplay {
    public static void show(ImageView target,Bitmap bitmap,ImageView.ScaleType scale){
        target.setImageBitmap(bitmap);
        target.setScaleType(scale);
        target.setImageAlpha(255);
        target.setAlpha(1.0f);
    }
    private TelePremiumCloudImageDisplay(){}
}
