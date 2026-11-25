package org.telegram.messenger.telepremium.aicenter;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.messenger.telepremium.TelePremiumLocalTranscriber;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable user-authorized queue. No checkpoint advances before its result is committed. */
public final class CenterTaskService extends Service {
    private static final int NOTIFICATION=7258,JOB=72581;
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static volatile AtomicBoolean cancellation=new AtomicBoolean();
    public static void enqueue(List<CenterData.Message> selected,String operation){enqueue(selected,operation,"");}
    public static void enqueue(List<CenterData.Message> selected,String operation,String question){
        List<CenterData.Message> copy=new ArrayList<>(selected);CenterStore.IO.execute(()->{
            try{for(CenterData.Message m:copy){CenterMedia.check(m);String signature=question+"|"+operation+"|"+m.qualified()+"|"+m.transport.hashCode()+"|"+m.localUri;boolean existing=false;
                for(JSONObject old:CenterStore.get().list(m.account,m.owner,"job"))if(signature.equals(old.optString("signature"))&&!"cancelled".equals(old.optString("state"))){existing=true;if("error".equals(old.optString("state"))||"paused".equals(old.optString("state"))){old.put("state","queued").put("retryAt",0);CenterStore.get().put(m.account,m.owner,"job",old.optString("id"),old);}break;}
                if(existing)continue;String id=UUID.randomUUID().toString();JSONObject job=new JSONObject().put("id",id).put("signature",signature).put("op",operation).put("query",question).put("account",m.account).put("owner",m.owner).put("message",m.json()).put("state","queued").put("cursor",0).put("percent",0).put("created",System.currentTimeMillis()).put("accounts",CenterAi.accounts(Collections.singletonList(m)));
                CenterStore.get().put(m.account,m.owner,"job",id,job);
            }AndroidUtilities.runOnUIThread(CenterTaskService::startManual);}catch(Exception e){FileLog.e(e);}
        });
    }
    public static void startManual(){Context c=ApplicationLoader.applicationContext;Intent i=new Intent(c,CenterTaskService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);schedule();}
    public static void schedule(){try{JobScheduler scheduler=(JobScheduler)ApplicationLoader.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);JobInfo.Builder b=new JobInfo.Builder(JOB,new ComponentName(ApplicationLoader.applicationContext,CenterBackgroundJob.class)).setPersisted(true).setMinimumLatency(60000).setRequiredNetworkType(CenterIndex.settings().optBoolean("wifi",true)?JobInfo.NETWORK_TYPE_UNMETERED:JobInfo.NETWORK_TYPE_ANY).setRequiresCharging(CenterIndex.settings().optBoolean("charging",false));scheduler.schedule(b.build());}catch(Exception e){FileLog.e(e);}}
    public static void stopWork(){cancellation.set(true);}
    public static void change(JSONObject job,String state){CenterStore.IO.execute(()->{try{job.put("state",state).put("retryAt",0);CenterStore.get().put(job.optInt("account"),job.optLong("owner"),"job",job.optString("id"),job);stopWork();schedule();}catch(Exception e){FileLog.e(e);}});}
    public static void startWorker(Runnable finished){if(!RUNNING.compareAndSet(false,true)){finished.run();return;}cancellation=new AtomicBoolean();final AtomicBoolean cancel=cancellation;
        new Thread(()->{try{recover();while(!cancel.get()){
            JSONObject job=next();if(job==null)break;int a=job.optInt("account");long owner=job.optLong("owner");
            try{job.put("state","running");save(job);notifyProgress(job.optInt("percent"));run(job,cancel);job.put("state",cancel.get()?"paused":"done");job.put("error","");save(job);}
            catch(Exception e){if(UserConfig.getInstance(a).isClientActivated()&&UserConfig.getInstance(a).getClientUserId()==owner){int tries=job.optInt("attempts")+1;job.put("state",cancel.get()?"paused":"error").put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).put("attempts",tries).put("retryAt",System.currentTimeMillis()+Math.min(3600000L,30000L*(1L<<Math.min(tries,6))));save(job);}}
        }}catch(Exception e){FileLog.e(e);}finally{RUNNING.set(false);schedule();AndroidUtilities.runOnUIThread(finished);}},"tp-ai-center-work").start();
    }
    private static void recover()throws Exception{for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){long o=UserConfig.getInstance(a).getClientUserId();for(JSONObject job:CenterStore.get().list(a,o,"job"))if("running".equals(job.optString("state"))){job.put("state","queued");save(job);}}}
    private static JSONObject next()throws Exception{JSONObject best=null;for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){long o=UserConfig.getInstance(a).getClientUserId();for(JSONObject job:CenterStore.get().list(a,o,"job")){String s=job.optString("state");if((s.equals("queued")||s.equals("error")&&job.optLong("retryAt")<=System.currentTimeMillis())&&(best==null||job.optLong("created")<best.optLong("created")))best=job;}}return best;}
    private static void save(JSONObject job)throws Exception{CenterStore.get().put(job.optInt("account"),job.optLong("owner"),"job",job.optString("id"),job);}
    private static void checkpoint(JSONObject job,long cursor,int percent)throws Exception{job.put("cursor",cursor).put("percent",percent);save(job);notifyProgress(percent);}
    private static void run(JSONObject job,AtomicBoolean cancel)throws Exception{
        CenterData.Message initial=CenterData.Message.parse(job.getJSONObject("message"));JSONObject stored=CenterStore.get().get(initial.account,initial.owner,"message",initial.qualified());CenterData.Message m=stored==null?initial:CenterData.Message.parse(stored);CenterMedia.check(m);
        File file=null;try{
            String op=job.optString("op");
            file=CenterMedia.file(m,cancel);
            if(op.equals("describe")){
                if(m.frameTimes.isEmpty()&&m.pages==0){if(m.kind.equals("photo")||m.kind.equals("image"))CenterMedia.image(m,file,false);else if(m.kind.equals("video")||m.kind.equals("round"))CenterMedia.frames(m,file,0,cancel,(cursor,p)->checkpoint(job,cursor,p));else if(m.kind.equals("pdf"))CenterMedia.document(m,file,0,cancel,(cursor,p)->checkpoint(job,cursor,p));}
                CenterMedia.describe(m,job.optString("query"));checkpoint(job,job.optLong("cursor"),100);return;
            }
            if((op.equals("download")||op.equals("ocr"))&&(m.kind.equals("photo")||m.kind.equals("image")))CenterMedia.image(m,file,op.equals("ocr"));
            else if(op.equals("frames"))CenterMedia.frames(m,file,job.optLong("cursor"),cancel,(cursor,p)->checkpoint(job,cursor,p));
            else if(op.equals("document"))CenterMedia.document(m,file,job.optLong("cursor"),cancel,(cursor,p)->checkpoint(job,cursor,p));
            else if(op.equals("transcribe")){
                android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();try{metadata.setDataSource(file.getAbsolutePath());m.duration=Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{metadata.release();}
                if(m.duration>CenterIndex.settings().optInt("maxVideo",40)*60000L)throw new IOException(CenterText.get("maxVideo"));
                for(long start=job.optLong("cursor");start<m.duration;start+=35000){if(cancel.get())throw new InterruptedException();CenterMedia.check(m);CountDownLatch wait=new CountDownLatch(1);String[] result={null,null};
                    TelePremiumLocalTranscriber.transcribeSegment(file,m.account,m.id,start,Math.min(start+35000,m.duration),CenterIndex.settings().optString("language","auto"),cancel,new TelePremiumLocalTranscriber.Callback(){public void onSuccess(String text){result[0]=text;wait.countDown();}public void onError(String error){result[1]=error;wait.countDown();}});
                    while(!wait.await(500,TimeUnit.MILLISECONDS)){if(cancel.get())throw new InterruptedException();}
                    if(result[1]!=null)throw new IOException(result[1]);m.transcript+="["+CenterTranscript.stamp(start)+"] "+(result[0]==null?"":result[0])+"\n";CenterMedia.save(m);checkpoint(job,start+35000,(int)Math.min(100,100*(start+35000)/m.duration));
                }
            }
            checkpoint(job,job.optLong("cursor"),100);
        }finally{if(file!=null&&(!m.localUri.isEmpty()||!m.cloudNode.isEmpty()))file.delete();}
    }
    private static Notification notification(int progress){Context c=ApplicationLoader.applicationContext;NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("tp_ai_center",CenterText.get("notification"),NotificationManager.IMPORTANCE_LOW));Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,"tp_ai_center"):new Notification.Builder(c);Intent open=new Intent(c,org.telegram.ui.LaunchActivity.class).setAction("org.telegram.aicenter");PendingIntent pi=PendingIntent.getActivity(c,7258,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(org.telegram.messenger.R.drawable.notification).setContentTitle(CenterText.get("notification")).setContentText(CenterText.get("progress")).setProgress(100,progress,false).setContentIntent(pi).setOngoing(true).build();}
    private static void notifyProgress(int p){try{((NotificationManager)ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(p));}catch(Exception e){FileLog.e(e);}}
    @Override public int onStartCommand(Intent i,int flags,int startId){startForeground(NOTIFICATION,notification(0));startWorker(()->{stopForeground(true);stopSelf();});return START_STICKY;}
    @Override public void onDestroy(){stopWork();super.onDestroy();}
    @Override public void onTimeout(int startId,int type){stopWork();stopSelf();}
    @Override public IBinder onBind(Intent i){return null;}
}
