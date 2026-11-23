package org.telegram.messenger.telepremium.cloud;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Memory-only photo thumbnails; at most two authenticated preview operations at once. */
public final class CloudPhotoThumbnailLoader {
    public interface Callback{void loaded(Bitmap bitmap);}
    public interface Request{void cancel();}
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService DECODE=Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}};
    /** Decoded thumbnails of protected images must not outlive their folder's session. */
    public static void clearCache(){CACHE.evictAll();}
    private static final ArrayDeque<Job> PENDING=new ArrayDeque<>();
    private static int running;
    private static boolean pumping;
    public static Request load(int account,CloudNode node,Callback callback){return load(account,node,()->true,callback);}
    public static Request load(int account,CloudNode node,java.util.function.BooleanSupplier wanted,Callback callback){
        try{Job job=new Job(account,CloudNode.fromJson(node.toJson()),wanted,callback);Bitmap cached=job.valid()?CACHE.get(job.key):null;
            if(cached!=null){callback.loaded(cached);return ()->{};}
            PENDING.add(job);pump();return job;
        }catch(Exception e){callback.loaded(null);return ()->{};}
    }
    private static void pump(){if(pumping)return;pumping=true;try{while(running<2&&!PENDING.isEmpty()){
        Job job=PENDING.removeFirst();if(job.done)continue;if(!job.valid()){job.finish(null);continue;}
        job.started=true;running++;job.download=new CloudDownloadManager(job.account,true);MAIN.postDelayed(job.timeout,45000);
        job.download.download(job.node,(state,file,error)->{
            if(state==CloudDownloadManager.ERROR){MAIN.post(()->job.finish(null));return;}
            if(state!=CloudDownloadManager.OFFLINE_AVAILABLE||file==null)return;
            DECODE.execute(()->{Bitmap bitmap=null;try{if(!job.done)bitmap=CloudImageDecoder.decode(file,256,256L*256*4);}catch(Throwable ignored){}final Bitmap result=bitmap;MAIN.post(()->job.finish(result));});
        });
    }}finally{pumping=false;}}
    private static final class Job implements Request{
        final int account;final long user,epoch;final CloudNode node;final java.util.function.BooleanSupplier wanted;final Callback callback;final String key;
        CloudDownloadManager download;volatile boolean done;boolean started;
        final Runnable timeout=()->{if(download!=null)download.cancel();finish(null);};
        Job(int account,CloudNode node,java.util.function.BooleanSupplier wanted,Callback callback){this.wanted=wanted;this.account=account;this.node=node;this.callback=callback;user=UserConfig.getInstance(account).getClientUserId();epoch=TelePremiumCloudResetState.epoch();key=user+":"+epoch+":"+node.id+":"+node.telegramDocumentId+":"+node.sha256+":"+node.size;}
        boolean valid(){return wanted.getAsBoolean()&&user>0&&user==UserConfig.getInstance(account).getClientUserId()&&TelePremiumCloudResetState.allowed(epoch)&&node.state!=CloudNode.STATE_DELETED&&TelePremiumCloudAccessGate.canExposeNode(ApplicationLoader.applicationContext,node);}
        void finish(Bitmap bitmap){if(done)return;done=true;MAIN.removeCallbacks(timeout);if(started)running--;else PENDING.remove(this);
            boolean allowed=valid();if(allowed&&bitmap!=null)CACHE.put(key,bitmap);
            try{callback.loaded(allowed?bitmap:null);}finally{pump();}
        }
        @Override public void cancel(){if(done)return;done=true;MAIN.removeCallbacks(timeout);PENDING.remove(this);if(download!=null)download.cancel();if(started)running--;pump();}
    }
    private CloudPhotoThumbnailLoader(){}
}
