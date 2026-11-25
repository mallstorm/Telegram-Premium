package org.telegram.messenger.telepremium.aicenter;
import android.graphics.*;
import android.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import org.telegram.messenger.AndroidUtilities;
public final class CenterThumbnails {
 private static final ExecutorService IO=Executors.newFixedThreadPool(2);
 private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
 public static void clear(){CACHE.evictAll();}
 public static void load(CenterData.Message m,long time,int page,Consumer<Bitmap> done){String id=m.qualified()+":"+time+":"+page;IO.execute(()->{Bitmap b=null;try{CenterMedia.check(m);b=CACHE.get(id);if(b==null){JSONObject o=CenterStore.get().get(m.account,m.owner,"preview",id);if(o!=null){byte[] jpeg=Base64.decode(o.optString("jpeg"),Base64.NO_WRAP);BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;b=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);if(b!=null)CACHE.put(id,b);java.util.Arrays.fill(jpeg,(byte)0);}}}catch(Exception ignore){}Bitmap result=b;AndroidUtilities.runOnUIThread(()->done.accept(result));});}
}
