package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.exifinterface.media.ExifInterface;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.cloud.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Internal, authenticated lazy cloud gallery. No external activity/FileProvider hand-off. */
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
    private final Handler main=new Handler(Looper.getMainLooper());
    private ArrayList<Entry> entries;private int position,generation;private boolean closed;
    private ImageView image;private ProgressBar progress;private TextView title,error;private LinearLayout chrome;
    private Bitmap bitmap;private final Matrix matrix=new Matrix();private float zoom=1;private boolean scaling;private long suppressSwipeUntil;
    private float downX,downY;private CloudDownloadManager download,prefetch;
    private final Runnable lockCheck=new Runnable(){public void run(){if(closed)return;if(!active()){finish();return;}main.postDelayed(this,1000);}};
    private String t(String en,String ru){return TPStrings.isRussian()?ru:en;}
    private int dp(float n){return AndroidUtilities.dp(n);}
    @Override public void onCreate(Bundle state){super.onCreate(state);entries=SESSIONS.remove(getIntent().getStringExtra("session"));
        if(entries==null||entries.isEmpty()){finish();return;}position=Math.max(0,Math.min(entries.size()-1,getIntent().getIntExtra("position",0)));
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if(TelePremiumCloudSecurityStore.isProtectionEnabled(this))getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new FrameLayout.LayoutParams(-1,-1));
        progress=new ProgressBar(this);FrameLayout.LayoutParams spinner=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER);root.addView(progress,spinner);
        error=new TextView(this);error.setTextColor(Color.WHITE);error.setTextSize(16);error.setGravity(Gravity.CENTER);error.setPadding(dp(24),dp(20),dp(24),dp(20));error.setVisibility(View.GONE);root.addView(error,new FrameLayout.LayoutParams(-1,-1));error.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){float dx=event.getX()-downX,dy=event.getY()-downY;if(Math.abs(dx)>dp(70)&&Math.abs(dx)>Math.abs(dy)*1.3f)showPosition(position+(dx<0?1:-1));else showPosition(position);return true;}return true;
        });
        chrome=new LinearLayout(this);chrome.setGravity(Gravity.CENTER_VERTICAL);chrome.setPadding(dp(8),dp(8),dp(16),dp(8));chrome.setBackgroundColor(0x99000000);
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(42);back.setTextColor(Color.WHITE);back.setGravity(Gravity.CENTER);back.setContentDescription(t("Close","Закрыть"));back.setOnClickListener(v->finish());chrome.addView(back,new LinearLayout.LayoutParams(dp(52),dp(48)));
        title=new TextView(this);title.setTextColor(Color.WHITE);title.setTextSize(15);title.setMaxLines(2);chrome.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(chrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){if(bitmap==null)return false;beginTransform();scaling=true;return true;}
            @Override public boolean onScale(ScaleGestureDetector d){float next=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));float factor=next/zoom;zoom=next;matrix.postScale(factor,factor,d.getFocusX(),d.getFocusY());clamp();return true;}
            @Override public void onScaleEnd(ScaleGestureDetector d){scaling=false;if(zoom<=1.05f)fit();suppressSwipeUntil=SystemClock.elapsedRealtime()+250;}
        });
        GestureDetector gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(android.view.MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){chrome.setVisibility(chrome.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);return true;}
            @Override public boolean onDoubleTap(MotionEvent e){if(bitmap!=null){if(zoom>1.1f)fit();else{beginTransform();zoom=2.8f;matrix.postScale(zoom,zoom,e.getX(),e.getY());clamp();}}return true;}
            @Override public boolean onScroll(MotionEvent first,MotionEvent current,float dx,float dy){if(zoom>1.05f&&!scaling){matrix.postTranslate(-dx,-dy);clamp();}return true;}
        });
        image.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();TelePremiumCloudSecuritySession.touch(this);}
            pinch.onTouchEvent(event);gestures.onTouchEvent(event);
            if(event.getActionMasked()==MotionEvent.ACTION_UP&&!scaling&&zoom<=1.05f&&SystemClock.elapsedRealtime()>suppressSwipeUntil){float dx=event.getX()-downX,dy=event.getY()-downY;if(Math.abs(dx)>dp(70)&&Math.abs(dx)>Math.abs(dy)*1.3f)showPosition(position+(dx<0?1:-1));}
            return true;
        });image.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)fit();});setContentView(root);showPosition(position);main.post(lockCheck);
    }
    private boolean valid(Entry e){return !closed&&e.account>=0&&UserConfig.getInstance(e.account).isClientActivated()&&UserConfig.getInstance(e.account).getClientUserId()==e.user&&TelePremiumCloudResetState.allowed(e.epoch)&&e.node.state!=CloudNode.STATE_DELETED;}
    private boolean active(){return entries!=null&&position>=0&&position<entries.size()&&valid(entries.get(position))&&TelePremiumCloudAccessGate.canExposeNode(this,entries.get(position).node);}
    private void showPosition(int next){
        if(next<0||next>=entries.size()||closed)return;position=next;if(!active()){finish();return;}TelePremiumCloudSecuritySession.touch(this);int token=++generation;
        if(download!=null)download.cancel();if(prefetch!=null)prefetch.cancel();Entry entry=entries.get(position);
        title.setText((position+1)+" / "+entries.size()+"  ·  "+entry.node.name);progress.setVisibility(View.VISIBLE);error.setVisibility(View.GONE);image.setAlpha(1f);zoom=1;image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        main.postDelayed(()->{if(!closed&&token==generation&&progress.getVisibility()==View.VISIBLE){++generation;if(download!=null)download.cancel();failed(t("Photo is taking too long to load. Tap to retry.","Фото не загрузилось. Нажмите для повтора."));}},45000);
        download=new CloudDownloadManager(entry.account,true);download.download(entry.node,(state,file,message)->{
            if(closed||token!=generation)return;if(!active()){finish();return;}
            if(state==CloudDownloadManager.ERROR){failed(message);return;}
            if(state!=CloudDownloadManager.OFFLINE_AVAILABLE||file==null)return;
            DECODE.execute(()->{Bitmap result=null;try{result=decode(file);}catch(Throwable failure){org.telegram.messenger.FileLog.e(failure);}final Bitmap decoded=result;
                main.post(()->{if(closed||token!=generation||!active()){if(decoded!=null)decoded.recycle();if(!closed&&!active())finish();return;}if(decoded==null){failed(t("Image could not be decoded. Tap to retry.","Не удалось открыть изображение. Нажмите для повтора."));return;}
                    Bitmap prior=bitmap;bitmap=decoded;image.setImageBitmap(bitmap);image.setAlpha(1);progress.setVisibility(View.GONE);fit();if(prior!=null&&prior!=decoded)prior.recycle();prefetchNext();
                });
            });
        });
    }
    private Bitmap decode(File file)throws Exception{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0||options.outHeight<=0)throw new IllegalArgumentException("Image format");int sample=1;
        while((long)((options.outWidth+sample-1)/sample)*((options.outHeight+sample-1)/sample)*4>16L*1024*1024||Math.max(options.outWidth,options.outHeight)/sample>4096)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap result=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(result==null)return null;
        try{ExifInterface exif=new ExifInterface(file.getAbsolutePath());Matrix rotate=new Matrix();if(exif.isFlipped())rotate.postScale(-1,1);rotate.postRotate(exif.getRotationDegrees());if(!rotate.isIdentity()){Bitmap oriented=Bitmap.createBitmap(result,0,0,result.getWidth(),result.getHeight(),rotate,true);if(oriented!=result)result.recycle();result=oriented;}}catch(java.io.IOException ignored){}return result;
    }
    private void failed(String message){progress.setVisibility(View.GONE);error.setText(message==null?t("Image unavailable. Tap to retry.","Изображение недоступно. Нажмите для повтора."):message);error.setVisibility(View.VISIBLE);}
    private void prefetchNext(){if(position+1>=entries.size()||!active())return;Entry next=entries.get(position+1);if(!valid(next))return;prefetch=new CloudDownloadManager(next.account,true);prefetch.download(next.node,(s,f,e)->{});}
    private void beginTransform(){
        if(image.getScaleType()!=ImageView.ScaleType.MATRIX){matrix.set(image.getImageMatrix());image.setScaleType(ImageView.ScaleType.MATRIX);image.setImageMatrix(matrix);}
    }
    /** ImageView fits only after its drawable and viewport are ready; no one-shot post/layout race. */
    private void fit(){zoom=1;if(image!=null){image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.invalidate();}}
    private void clamp(){if(bitmap==null)return;android.graphics.drawable.Drawable drawable=image.getDrawable();if(drawable==null)return;RectF bounds=new RectF(0,0,drawable.getIntrinsicWidth(),drawable.getIntrinsicHeight());matrix.mapRect(bounds);float dx=bounds.width()<image.getWidth()?(image.getWidth()-bounds.width())/2-bounds.left:bounds.left>0?-bounds.left:bounds.right<image.getWidth()?image.getWidth()-bounds.right:0;float dy=bounds.height()<image.getHeight()?(image.getHeight()-bounds.height())/2-bounds.top:bounds.top>0?-bounds.top:bounds.bottom<image.getHeight()?image.getHeight()-bounds.bottom:0;matrix.postTranslate(dx,dy);image.setImageMatrix(matrix);}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);if(image!=null)image.post(this::fit);}
    @Override protected void onDestroy(){closed=true;generation++;main.removeCallbacksAndMessages(null);if(download!=null)download.cancel();if(prefetch!=null)prefetch.cancel();if(image!=null)image.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}super.onDestroy();}
}
