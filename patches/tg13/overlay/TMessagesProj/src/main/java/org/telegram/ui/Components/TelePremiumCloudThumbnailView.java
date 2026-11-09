package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.*;

/** Requests a real photo only when its row becomes visible; restores/cancels on window lifecycle. */
public final class TelePremiumCloudThumbnailView extends ImageView {
    private final int account,placeholder;private final CloudNode node;private final long owner,epoch;
    private CloudPhotoThumbnailLoader.Request request;private boolean requested,observing,initialized;private int generation;
    private final Rect visible=new Rect();
    private final ViewTreeObserver.OnPreDrawListener listener=()->{maybeLoad();return true;};
    public TelePremiumCloudThumbnailView(Context context,int account,CloudNode node,int placeholder,int background){
        super(context);this.account=account;this.node=node;this.placeholder=placeholder;owner=UserConfig.getInstance(account).getClientUserId();epoch=TelePremiumCloudResetState.epoch();
        GradientDrawable shape=new GradientDrawable();shape.setColor(background);shape.setCornerRadius(AndroidUtilities.dp(10));setBackground(shape);setClipToOutline(true);initialized=true;fallback();
    }
    private void fallback(){int padding=AndroidUtilities.dp(12);setPadding(padding,padding,padding,padding);setImageResource(placeholder);setImageAlpha(255);setAlpha(1.0f);}
    private boolean valid(){return owner==UserConfig.getInstance(account).getClientUserId()&&TelePremiumCloudResetState.allowed(epoch)&&TelePremiumCloudAccessGate.canExposeNode(getContext(),node);}
    private void observe(){if(!observing&&!requested&&isAttachedToWindow()){getViewTreeObserver().addOnPreDrawListener(listener);observing=true;}}
    private void unobserve(){if(observing&&getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(listener);observing=false;}
    private boolean visibleNow(){return isAttachedToWindow()&&getWindowVisibility()==View.VISIBLE&&isShown()&&getGlobalVisibleRect(visible);}
    private void maybeLoad(){
        if(!visibleNow()||!valid()){if(requested)clearRequest();return;}
        if(requested)return;requested=true;int token=++generation;
        request=CloudPhotoThumbnailLoader.load(account,node,this::visibleNow,bitmap->{if(token!=generation||!visibleNow()||!valid())return;
            if(bitmap!=null){setPadding(0,0,0,0);TelePremiumCloudImageDisplay.show(this,bitmap,ScaleType.CENTER_CROP);}
        });
    }
    private void clearRequest(){generation++;if(request!=null)request.cancel();request=null;requested=false;fallback();}
    private void stop(){unobserve();clearRequest();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();observe();}
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(!initialized)return;if(visibility==View.VISIBLE)observe();else stop();}
}
