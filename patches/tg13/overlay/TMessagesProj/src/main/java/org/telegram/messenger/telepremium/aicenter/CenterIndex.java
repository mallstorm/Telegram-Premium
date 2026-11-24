package org.telegram.messenger.telepremium.aicenter;

import org.telegram.messenger.*;
import org.json.*;
import org.telegram.messenger.telepremium.ai.TPAiStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Worker-owned index snapshots; callbacks always publish on the UI thread. */
public final class CenterIndex {
    public interface Done {void run(String error);}
    private static final AtomicLong GENERATION=new AtomicLong();
    public final List<CenterData.Message> messages=new ArrayList<>();
    public final List<CenterData.Source> sources=new ArrayList<>();
    public CenterData.Scope scope=new CenterData.Scope();
    public static org.json.JSONObject settings(){try{String v=TPAiStore.getPrivate("center_settings");return new JSONObject(v.isEmpty()?"{}":v);}catch(Exception e){return new JSONObject();}}
    public static void setting(String key,Object value){try{JSONObject o=settings();o.put(key,value);TPAiStore.putPrivate("center_settings",o.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    public void load(Done done){final long generation=GENERATION.get();CenterStore.IO.execute(()->{
        String error=null;List<CenterData.Message> ms=new ArrayList<>();List<CenterData.Source> ss=new ArrayList<>();
        try{for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++){if(!UserConfig.getInstance(a).isClientActivated()){CenterStore.get().purge(a);continue;}long owner=UserConfig.getInstance(a).getClientUserId();
            for(JSONObject o:CenterStore.get().list(a,owner,"source"))ss.add(CenterData.Source.parse(o));
            long cutoff=System.currentTimeMillis()/1000-settings().optInt("retentionDays",90)*86400L;
            for(JSONObject o:CenterStore.get().list(a,owner,"message")){CenterData.Message m=CenterData.Message.parse(o);if(m.date<cutoff)CenterStore.get().remove(a,owner,"message",m.qualified());else ms.add(m);}}
        }catch(Exception e){error=e.getClass().getSimpleName();}
        final String result=error;AndroidUtilities.runOnUIThread(()->{if(generation!=GENERATION.get())return;messages.clear();messages.addAll(ms);sources.clear();sources.addAll(ss);try{String raw=TPAiStore.getPrivate("center_scope");if(!raw.isEmpty())scope=CenterData.Scope.parse(new JSONObject(raw));else for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated())scope.whole.add(a);}catch(Exception e){FileLog.e(e);}done.run(result);});
    });}
    public void saveScope(){try{TPAiStore.putPrivate("center_scope",scope.json().toString());}catch(Exception e){throw new IllegalStateException(e);}}
    public List<CenterData.Message> selected(){return CenterData.select(messages,sources,scope);}
    public static void logout(int a){GENERATION.incrementAndGet();CenterStore.IO.execute(()->CenterStore.get().purge(a));}
    public void clear(Done done){GENERATION.incrementAndGet();messages.clear();sources.clear();CenterStore.IO.execute(()->{CenterStore.get().clear();AndroidUtilities.runOnUIThread(()->done.run(null));});}
}
