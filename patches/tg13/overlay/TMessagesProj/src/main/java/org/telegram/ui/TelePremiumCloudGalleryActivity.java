package org.telegram.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.cloud.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Internal, authenticated lazy cloud gallery. No external activity/FileProvider hand-off.
 * Three slots (previous / current / next) follow the finger while dragging, so photos slide
 * one after another; neighbours are decoded ahead of time. Pinch / double-tap zoom works on the current photo.
 */
public final class TelePremiumCloudGalleryActivity extends Activity {
    public static final class Entry {
        final int account;final long user,epoch;final CloudNode node;
        public Entry(int account,CloudNode node)throws Exception{this.account=account;this.user=UserConfig.getInstance(account).getClientUserId();this.epoch=TelePremiumCloudResetState.epoch();this.node=CloudNode.fromJson(node.toJson());}
    }
    private static final Map<String,ArrayList<Entry>> SESSIONS=new ConcurrentHashMap<>();
    private static final ExecutorService DECODE=Executors.newFixedThreadPool(2);
    public static void open(Context context,List<Entry> entries,int selected){
        if(entries.isEmpty())return;String token=UUID.randomUUID().toString();SESSIONS.put(token,new ArrayList<>(entries));
        try{context.startActivity(new Intent(context,TelePremiumCloudGalleryActivity.class).putExtra("session",token).putExtra("position",selected));}
        catch(RuntimeException e){SESSIONS.remove(token);throw e;}
        new Handler(Looper.getMainLooper()).postDelayed(()->SESSIONS.remove(token),120000);
    }

    private static final class Slot {
        FrameLayout box;ImageView image;ProgressBar progress;TextView error;
        int index=-1,token;Bitmap bitmap;CloudDownloadManager download;
    }

    private final Handler main=new Handler(Looper.getMainLooper());
    private ArrayList<Entry> entries;
    private int position;
    private boolean closed;
    private final Slot[] slots=new Slot[3]; // 0 = previous, 1 = current, 2 = next
    private FrameLayout pager;
    private TextView title;
    private LinearLayout chrome;
    private final Matrix matrix=new Matrix();
    private float zoom=1,offset;
    private boolean scaling,dragging;
    private float downX,downY;
    private ValueAnimator settle;
    private VelocityTracker velocity;
    private int touchSlop;
    private final Runnable lockCheck=new Runnable(){public void run(){if(closed)return;if(!active()){finish();return;}main.postDelayed(this,1000);}};
    private String t(String en,String ru){return TPStrings.isRussian()?ru:en;}
    private int dp(float n){return AndroidUtilities.dp(n);}
    private int width(){int w=pager==null?0:pager.getWidth();return w>0?w:getResources().getDisplayMetrics().widthPixels;}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        entries=SESSIONS.remove(getIntent().getStringExtra("session"));
        if(entries==null||entries.isEmpty()){finish();return;}
        position=Math.max(0,Math.min(entries.size()-1,getIntent().getIntExtra("position",0)));
        touchSlop=ViewConfiguration.get(this).getScaledTouchSlop();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if(TelePremiumCloudSecurityStore.isProtectionEnabled(this)||folderProtected())getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        pager=new FrameLayout(this);pager.setClipChildren(true);root.addView(pager,new FrameLayout.LayoutParams(-1,-1));
        for(int i=0;i<3;i++){
            Slot s=new Slot();s.box=new FrameLayout(this);
            s.image=new ImageView(this);s.image.setScaleType(ImageView.ScaleType.FIT_CENTER);s.box.addView(s.image,new FrameLayout.LayoutParams(-1,-1));
            s.progress=new ProgressBar(this);s.progress.setVisibility(View.GONE);s.box.addView(s.progress,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));
            s.error=new TextView(this);s.error.setTextColor(Color.WHITE);s.error.setTextSize(16);s.error.setGravity(Gravity.CENTER);s.error.setPadding(dp(24),dp(20),dp(24),dp(20));s.error.setVisibility(View.GONE);s.box.addView(s.error,new FrameLayout.LayoutParams(-1,-1));
            slots[i]=s;pager.addView(s.box,new FrameLayout.LayoutParams(-1,-1));
        }
        chrome=new LinearLayout(this);chrome.setGravity(Gravity.CENTER_VERTICAL);chrome.setPadding(dp(8),dp(8),dp(16),dp(8));chrome.setBackgroundColor(0x99000000);
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(42);back.setTextColor(Color.WHITE);back.setGravity(Gravity.CENTER);back.setContentDescription(t("Close","Закрыть"));back.setOnClickListener(v->finish());chrome.addView(back,new LinearLayout.LayoutParams(dp(52),dp(48)));
        title=new TextView(this);title.setTextColor(Color.WHITE);title.setTextSize(15);title.setMaxLines(2);chrome.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(chrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){if(slots[1].bitmap==null)return false;beginTransform();scaling=true;if(dragging){dragging=false;settle(0);}return true;}
            @Override public boolean onScale(ScaleGestureDetector d){float next=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));float factor=next/zoom;zoom=next;matrix.postScale(factor,factor,d.getFocusX(),d.getFocusY());clamp();return true;}
            @Override public void onScaleEnd(ScaleGestureDetector d){scaling=false;if(zoom<=1.05f)fit();}
        });
        GestureDetector gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){
                if(slots[1].error.getVisibility()==View.VISIBLE){slots[1].index=-1;load(slots[1],position);return true;}
                chrome.setVisibility(chrome.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e){
                if(slots[1].bitmap!=null){if(zoom>1.1f)fit();else{beginTransform();zoom=2.8f;matrix.postScale(zoom,zoom,e.getX(),e.getY());clamp();}}return true;
            }
            @Override public boolean onScroll(MotionEvent first,MotionEvent current,float dx,float dy){
                if(zoom>1.05f&&!scaling){matrix.postTranslate(-dx,-dy);clamp();}return true;
            }
        });
        pager.setOnTouchListener((v,event)->{
            pinch.onTouchEvent(event);gestures.onTouchEvent(event);
            int action=event.getActionMasked();
            if(velocity==null)velocity=VelocityTracker.obtain();
            velocity.addMovement(event);
            if(action==MotionEvent.ACTION_DOWN){
                downX=event.getX();downY=event.getY();dragging=false;TelePremiumCloudSecuritySession.touch(this);
                if(settle!=null&&settle.isRunning())settle.end();
            }else if(action==MotionEvent.ACTION_MOVE){
                if(scaling||zoom>1.05f||event.getPointerCount()>1)return true;
                float dx=event.getX()-downX,dy=event.getY()-downY;
                if(!dragging&&Math.abs(dx)>touchSlop&&Math.abs(dx)>Math.abs(dy)*1.2f){dragging=true;v.getParent().requestDisallowInterceptTouchEvent(true);}
                if(dragging){
                    boolean edge=(position==0&&dx>0)||(position==entries.size()-1&&dx<0);
                    offset=edge?dx/3f:dx;layoutSlots();
                }
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                if(dragging){
                    velocity.computeCurrentVelocity(1000);float vx=velocity.getXVelocity();int w=width();int dir=0;
                    if(action==MotionEvent.ACTION_UP){if(offset<-w*0.25f||vx<-900)dir=1;else if(offset>w*0.25f||vx>900)dir=-1;}
                    if(position+dir<0||position+dir>=entries.size())dir=0;
                    dragging=false;settle(dir);
                }
                velocity.recycle();velocity=null;
            }
            return true;
        });
        pager.addOnLayoutChangeListener((v,l,tp,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-tp!=ob-ot){layoutSlots();fit();}});
        setContentView(root);
        if(!active()){finish();return;}
        updateTitle();layoutSlots();
        load(slots[1],position);load(slots[2],position+1);load(slots[0],position-1);
        main.post(lockCheck);
    }

    private boolean folderProtected(){
        try{Entry e=entries.get(position);return TelePremiumCloudManager.getInstance(e.account).isProtected(e.node);}catch(Throwable ignored){return false;}
    }
    private boolean valid(Entry e){return !closed&&e.account>=0&&UserConfig.getInstance(e.account).isClientActivated()&&UserConfig.getInstance(e.account).getClientUserId()==e.user&&TelePremiumCloudResetState.allowed(e.epoch)&&e.node.state!=CloudNode.STATE_DELETED&&TelePremiumCloudManager.getInstance(e.account).isVisible(e.node);}
    private boolean active(){return entries!=null&&position>=0&&position<entries.size()&&valid(entries.get(position))&&TelePremiumCloudAccessGate.canExposeNode(this,entries.get(position).node);}
    private void updateTitle(){Entry e=entries.get(position);title.setText((position+1)+" / "+entries.size()+"  ·  "+e.node.name);}

    private void layoutSlots(){
        int w=width();
        for(int i=0;i<3;i++)slots[i].box.setTranslationX((i-1)*w+offset);
    }

    private void settle(int dir){
        if(settle!=null)settle.cancel();
        final float from=offset,to=dir==0?0:-dir*width();
        settle=ValueAnimator.ofFloat(from,to);settle.setDuration(dir==0?180:240);settle.setInterpolator(new DecelerateInterpolator());
        final boolean[] done={false};
        settle.addUpdateListener(a->{if(closed)return;offset=(float)a.getAnimatedValue();layoutSlots();});
        settle.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator a){if(done[0]||closed)return;done[0]=true;if(dir==0){offset=0;layoutSlots();}else complete(dir);}
            @Override public void onAnimationCancel(android.animation.Animator a){done[0]=true;}
        });
        settle.start();
    }

    /** The drag reached the neighbour: rotate the slots and prefetch the new neighbour. */
    private void complete(int dir){
        offset=0;
        if(dir>0){Slot first=slots[0];slots[0]=slots[1];slots[1]=slots[2];slots[2]=first;position++;}
        else{Slot last=slots[2];slots[2]=slots[1];slots[1]=slots[0];slots[0]=last;position--;}
        for(int i=0;i<3;i++)if(i!=1)slots[i].image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        zoom=1;slots[1].image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layoutSlots();
        if(!active()){finish();return;}
        TelePremiumCloudSecuritySession.touch(this);updateTitle();
        load(slots[1],position);load(slots[dir>0?2:0],position+dir);
    }

    private void reset(Slot s){
        s.token++;
        if(s.download!=null){s.download.cancel();s.download=null;}
        s.image.setImageDrawable(null);
        if(s.bitmap!=null){s.bitmap.recycle();s.bitmap=null;}
        s.progress.setVisibility(View.GONE);s.error.setVisibility(View.GONE);
    }

    private void load(Slot s,int idx){
        if(idx<0||idx>=entries.size()){reset(s);s.index=-1;return;}
        if(s.index==idx&&(s.bitmap!=null||s.download!=null))return;
        reset(s);s.index=idx;
        final Entry e=entries.get(idx);
        if(!valid(e)){if(slots[1]==s)finish();return;}
        final int token=s.token;
        s.progress.setVisibility(View.VISIBLE);
        main.postDelayed(()->{
            if(closed||token!=s.token||s.bitmap!=null)return;
            s.token++;if(s.download!=null){s.download.cancel();s.download=null;}
            failed(s,t("Photo is taking too long to load. Tap to retry.","Фото не загрузилось. Нажмите для повтора."));
        },45000);
        s.download=new CloudDownloadManager(e.account,true);
        s.download.download(e.node,(state,file,message)->{
            if(closed||token!=s.token)return;
            if(!valid(e)){finish();return;}
            if(state==CloudDownloadManager.ERROR){s.download=null;failed(s,message);return;}
            if(state!=CloudDownloadManager.OFFLINE_AVAILABLE||file==null)return;
            DECODE.execute(()->{
                Bitmap result=null;
                try{result=decode(file);}catch(Throwable failure){org.telegram.messenger.FileLog.e(failure);}
                final Bitmap decoded=result;
                main.post(()->{
                    if(closed||token!=s.token){if(decoded!=null)decoded.recycle();return;}
                    if(!valid(e)){if(decoded!=null)decoded.recycle();finish();return;}
                    s.download=null;
                    if(decoded==null){failed(s,t("Image could not be decoded. Tap to retry.","Не удалось открыть изображение. Нажмите для повтора."));return;}
                    org.telegram.ui.Components.TelePremiumCloudImageDisplay.show(s.image,decoded,ImageView.ScaleType.FIT_CENTER);
                    s.bitmap=decoded;s.progress.setVisibility(View.GONE);
                    if(s==slots[1])zoom=1;
                });
            });
        });
    }

    private Bitmap decode(File file)throws Exception{return CloudImageDecoder.decode(file,4096,16L*1024*1024);}
    private void failed(Slot s,String message){
        s.progress.setVisibility(View.GONE);
        s.error.setText(message==null?t("Image unavailable. Tap to retry.","Изображение недоступно. Нажмите для повтора."):message);
        s.error.setVisibility(View.VISIBLE);
    }

    private void beginTransform(){
        ImageView image=slots[1].image;
        if(image.getScaleType()!=ImageView.ScaleType.MATRIX){matrix.set(image.getImageMatrix());image.setScaleType(ImageView.ScaleType.MATRIX);image.setImageMatrix(matrix);}
    }
    private void fit(){zoom=1;ImageView image=slots[1]==null?null:slots[1].image;if(image!=null){image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.invalidate();}}
    private void clamp(){
        ImageView image=slots[1].image;if(slots[1].bitmap==null)return;
        android.graphics.drawable.Drawable drawable=image.getDrawable();if(drawable==null)return;
        RectF bounds=new RectF(0,0,drawable.getIntrinsicWidth(),drawable.getIntrinsicHeight());matrix.mapRect(bounds);
        float dx=bounds.width()<image.getWidth()?(image.getWidth()-bounds.width())/2-bounds.left:bounds.left>0?-bounds.left:bounds.right<image.getWidth()?image.getWidth()-bounds.right:0;
        float dy=bounds.height()<image.getHeight()?(image.getHeight()-bounds.height())/2-bounds.top:bounds.top>0?-bounds.top:bounds.bottom<image.getHeight()?image.getHeight()-bounds.bottom:0;
        matrix.postTranslate(dx,dy);image.setImageMatrix(matrix);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);if(pager!=null)pager.post(()->{layoutSlots();fit();});}
    @Override protected void onDestroy(){
        closed=true;main.removeCallbacksAndMessages(null);
        if(settle!=null)settle.cancel();
        if(velocity!=null){velocity.recycle();velocity=null;}
        for(Slot s:slots)if(s!=null)reset(s);
        super.onDestroy();
    }
}
