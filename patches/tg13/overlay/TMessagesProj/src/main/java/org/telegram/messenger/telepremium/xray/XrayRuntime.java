package org.telegram.messenger.telepremium.xray;

import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.AccountNetworkController;
import org.telegram.messenger.telepremium.AccountNetworkProfile;
import org.telegram.messenger.telepremium.AccountNetworkStore;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Single Go runtime. Account MTProto uses SOCKS; media HTTP uses its own listener/route. */
public final class XrayRuntime {
    public interface Ready {void complete(int port,String error);}
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->new Thread(r,"tp-xray"));
    private static volatile Map<String,int[]> runningPorts=Collections.emptyMap();
    private static volatile Map<String,String> fingerprints=Collections.emptyMap();
    private static String signature="";
    private static boolean initialized;
    private static volatile String status="Выключен";
    /** Pseudo profile id: use the best responding profile from the whole list and rotate on failure. */
    public static final String AUTO="auto-pool";
    private static volatile String activeAuto="";
    private static volatile boolean autoInUse;
    private static final Map<String,Integer> latency=new ConcurrentHashMap<>();
    private static final Map<String,Long> banned=new ConcurrentHashMap<>();
    private static ScheduledExecutorService monitor;
    private static ExecutorService probePool;
    private static int stuckTicks;
    private static long lastFull,lastQuick;
    public static String autoActiveId(){return activeAuto;}
    /** Milliseconds of the last check; 0 = not checked yet, -1 = no response. */
    public static int latency(String id){Integer v=latency.get(id);return v==null?0:v;}
    public static String status(){return status;}
    private static JSONObject invoke(String method,JSONObject payload)throws Exception{
        if(!initialized){go.Seq.setContext(ApplicationLoader.applicationContext);initialized=true;}
        JSONObject response=new JSONObject(libXray.LibXray.invoke(new JSONObject().put("apiVersion",3).put("method",method).put("payload",payload).toString()));
        if(!response.optBoolean("success"))throw new IllegalArgumentException("Xray: "+method+" — проверьте параметры профиля");
        Object data=response.opt("data");return data instanceof JSONObject?(JSONObject)data:new JSONObject();
    }
    private static String fingerprint(XrayProfile p){return p.outbound.toString()+"|"+p.bypassLan;}
    private static String resolve(String id){
        if(!AUTO.equals(id))return id;
        Map<String,int[]> ports=runningPorts;String a=activeAuto;
        if(!a.isEmpty()&&ports.containsKey(a))return a;
        String best=pickBest(ports.keySet());
        if(best!=null){activeAuto=best;return best;}
        if(ports.isEmpty())return "";
        String first=ports.keySet().iterator().next();activeAuto=first;return first;
    }
    private static boolean isBanned(String id){Long until=banned.get(id);return until!=null&&until>System.currentTimeMillis();}
    private static String pickBest(Collection<String> ids){
        String best=null;int bestMs=Integer.MAX_VALUE;
        for(String id:ids){Integer ms=latency.get(id);if(ms==null||ms<=0||isBanned(id))continue;if(ms<bestMs){bestMs=ms;best=id;}}
        return best;
    }
    private static void addWanted(Set<String> wanted,String id)throws Exception{
        if(id==null||id.isEmpty())return;
        if(AUTO.equals(id)){for(XrayProfile p:XrayProfileStore.profiles())wanted.add(p.id);}else wanted.add(id);
    }
    public static int currentSocksPort(String id){
        id=resolve(id);
        try{XrayProfile p=XrayProfileStore.find(id);int[] ports=runningPorts.get(id);return p!=null&&ports!=null&&fingerprint(p).equals(fingerprints.get(id))?ports[0]:0;}
        catch(Exception e){return 0;}
    }
    private static String mediaProfile()throws Exception{
        String route=XrayProfileStore.mediaRoute();
        if(route.equals("account")){AccountNetworkProfile p=AccountNetworkStore.getInstance().load(UserConfig.selectedAccount);return p!=null&&p.mode==AccountNetworkProfile.MODE_XRAY?p.xrayProfileId:"";}
        return route;
    }
    private static synchronized boolean ensureCore()throws Exception{
        Set<String> wanted=new TreeSet<>();boolean usesAuto=false;
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){
            AccountNetworkProfile p=AccountNetworkStore.getInstance().load(a);
            if(p!=null&&p.mode==AccountNetworkProfile.MODE_XRAY){addWanted(wanted,p.xrayProfileId);if(AUTO.equals(p.xrayProfileId))usesAuto=true;}
        }
        String media=mediaProfile();addWanted(wanted,media);if(AUTO.equals(media))usesAuto=true;
        setMonitor(usesAuto);
        List<XrayProfile> profiles=new ArrayList<>();StringBuilder hash=new StringBuilder();Map<String,String> nextFingerprints=new HashMap<>();
        for(String id:wanted){XrayProfile p=XrayProfileStore.find(id);if(p==null)throw new IllegalStateException("Выбранный профиль Xray отсутствует");profiles.add(p);String f=fingerprint(p);nextFingerprints.put(id,f);hash.append(id).append(f);}
        if(hash.toString().equals(signature))return false;
        status="Переключение маршрутов…";
        Map<String,int[]> ports=new HashMap<>();String configuration=null;
        // Validate the replacement while existing listeners still carry other accounts.
        if(!profiles.isEmpty()){
            JSONArray free=invoke("getFreePorts",new JSONObject().put("count",profiles.size()*2)).getJSONArray("ports");
            for(int i=0;i<profiles.size();i++)ports.put(profiles.get(i).id,new int[]{free.getInt(i*2),free.getInt(i*2+1)});
            configuration=XrayConfig.build(profiles,ports).toString();
            if(signature.isEmpty())invoke("testXray",new JSONObject().put("xrayJson",configuration));
        }
        latency.keySet().retainAll(wanted);
        if(!signature.isEmpty())invoke("stopXray",new JSONObject());
        signature="";runningPorts=Collections.emptyMap();fingerprints=Collections.emptyMap();
        if(profiles.isEmpty()){status="Выключен";return true;}
        try{invoke("runXray",new JSONObject().put("xrayJson",configuration));}
        catch(Exception e){try{invoke("stopXray",new JSONObject());}catch(Exception ignored){}status="Ошибка запуска; прямой маршрут не включён";throw e;}
        runningPorts=Collections.unmodifiableMap(ports);fingerprints=Collections.unmodifiableMap(nextFingerprints);signature=hash.toString();status="Работает · маршрутов: "+profiles.size();return true;
    }
    private static void reapplyAccounts(){
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){
            AccountNetworkProfile p=AccountNetworkStore.getInstance().load(a);if(p!=null&&p.mode==AccountNetworkProfile.MODE_XRAY)AccountNetworkController.getInstance().applyProfile(a);
        }
    }
    public static void ensureSocks(String id,Ready callback){
        WORKER.execute(()->{int port=0;String error=null;boolean changed=false;
            try{changed=ensureCore();int[] pair=runningPorts.get(resolve(id));if(pair==null)throw new IllegalArgumentException("Профиль не назначен аккаунту");port=pair[0];}
            catch(Throwable e){status="Ошибка маршрута; соединение остановлено";error="Не удалось запустить Xray. Проверьте профиль";}
            final int result=port;final String message=error;final boolean reload=changed;
            AndroidUtilities.runOnUIThread(()->{callback.complete(result,message);if(reload)reapplyAccounts();});
        });
    }
    public static void refresh(){WORKER.execute(()->{try{ensureCore();}catch(Throwable e){status="Ошибка маршрута; соединение остановлено";}AndroidUtilities.runOnUIThread(XrayRuntime::reapplyAccounts);});}
    public static void importProfiles(String text,org.telegram.messenger.Utilities.Callback<String> callback){
        WORKER.execute(()->{String error=null;try{
            if(text==null||text.length()>262144)throw new IllegalArgumentException("Слишком большой профиль");
            String trimmed=text.trim();
            JSONArray nodes=trimmed.startsWith("{")||trimmed.startsWith("[")?XrayConfig.importJson(trimmed):invoke("convertShareLinksToXrayJson",new JSONObject().put("text",trimmed)).getJSONArray("outbounds");
            List<XrayProfile> profiles=XrayProfileStore.profiles();if(profiles.size()+nodes.length()>XrayConfig.MAX_PROFILES)throw new IllegalArgumentException("Можно сохранить до "+XrayConfig.MAX_PROFILES+" профилей");
            for(int i=0;i<nodes.length();i++){XrayProfile p=new XrayProfile();p.outbound=nodes.getJSONObject(i);p.name=p.outbound.optString("tag","Профиль "+(profiles.size()+1));if(p.name.length()>120)p.name=p.name.substring(0,120);XrayConfig.validateOutbound(p.outbound);
                Map<String,int[]> validationPorts=new HashMap<>();validationPorts.put(p.id,new int[]{20000,20001});
                String validation=XrayConfig.build(Collections.singletonList(p),validationPorts).toString();
                if(signature.isEmpty())invoke("testXray",new JSONObject().put("xrayJson",validation));profiles.add(p);}
            if(nodes.length()==0)throw new IllegalArgumentException("В конфигурации нет подходящих профилей");XrayProfileStore.saveAll(profiles);
        }catch(Throwable e){error=e instanceof IllegalArgumentException?e.getMessage():"Не удалось импортировать профили";}
            final String result=error;AndroidUtilities.runOnUIThread(()->callback.run(result));
        });
    }
    // ---- Key health checks and automatic rotation ----
    private static synchronized void setMonitor(boolean on){
        autoInUse=on;
        if(on&&monitor==null){
            ThreadFactory tf=r->{Thread t=new Thread(r,"tp-xray-probe");t.setDaemon(true);return t;};
            probePool=Executors.newFixedThreadPool(6,tf);
            ScheduledThreadPoolExecutor m=new ScheduledThreadPoolExecutor(1,tf);monitor=m;
            lastFull=0;lastQuick=0;stuckTicks=0;
            m.scheduleWithFixedDelay(XrayRuntime::tick,4,15,TimeUnit.SECONDS);
        }else if(!on&&monitor!=null){
            monitor.shutdownNow();monitor=null;if(probePool!=null){probePool.shutdownNow();probePool=null;}
            latency.clear();banned.clear();activeAuto="";
        }
    }
    private static int attempt(int httpPort){
        HttpURLConnection c=null;long t0=System.nanoTime();
        try{
            c=(HttpURLConnection)new URL("https://api.telegram.org/").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",httpPort)));
            c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setUseCaches(false);c.getResponseCode();
            return Math.max(1,(int)((System.nanoTime()-t0)/1000000));
        }catch(Exception e){return -1;}
        finally{if(c!=null)c.disconnect();}
    }
    private static int probeOne(String id){
        int[] ports=runningPorts.get(id);if(ports==null)return -1;
        int first=attempt(ports[1]);if(first<0)return -1;
        int second=attempt(ports[1]);return second<0?first:Math.min(first,second);
    }
    private static void probeAll(){
        ExecutorService pool=probePool;if(pool==null)return;
        List<String> ids=new ArrayList<>(runningPorts.keySet());List<Callable<Void>> jobs=new ArrayList<>();
        for(String id:ids)jobs.add(()->{latency.put(id,probeOne(id));return null;});
        try{pool.invokeAll(jobs,40,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RejectedExecutionException ignored){}
        lastFull=System.currentTimeMillis();
    }
    private static void selectBest(){
        String cur=activeAuto;Integer cl=latency.get(cur);
        boolean curOk=!cur.isEmpty()&&runningPorts.containsKey(cur)&&cl!=null&&cl>0&&!isBanned(cur);
        String best=pickBest(runningPorts.keySet());if(best==null||best.equals(cur))return;
        Integer bl=latency.get(best);
        if(!curOk||(bl!=null&&bl<cl*0.6&&cl-bl>200))switchTo(best);
    }
    private static void switchTo(String id){
        activeAuto=id;Integer ms=latency.get(id);String name=id;
        try{XrayProfile p=XrayProfileStore.find(id);if(p!=null)name=p.name;}catch(Exception ignored){}
        status="Авто: "+name+(ms!=null&&ms>0?" · "+ms+" мс":"");
        AndroidUtilities.runOnUIThread(()->{reapplyAccounts();org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged();});
    }
    private static void tick(){
        try{
            if(!autoInUse)return;
            long now=System.currentTimeMillis();boolean stuck=false;
            for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){
                AccountNetworkProfile p=AccountNetworkStore.getInstance().load(a);
                if(p==null||p.mode!=AccountNetworkProfile.MODE_XRAY||!AUTO.equals(p.xrayProfileId))continue;
                int st=org.telegram.tgnet.ConnectionsManager.getInstance(a).getConnectionState();
                if(st==org.telegram.tgnet.ConnectionsManager.ConnectionStateConnecting||st==org.telegram.tgnet.ConnectionsManager.ConnectionStateConnectingToProxy)stuck=true;
            }
            stuckTicks=stuck?stuckTicks+1:0;
            boolean full=latency.isEmpty()||now-lastFull>600000;
            if(stuckTicks>=3){String cur=activeAuto;if(!cur.isEmpty()){banned.put(cur,now+300000);latency.put(cur,-1);}stuckTicks=0;full=true;}
            else if(now-lastQuick>60000){
                lastQuick=now;String cur=activeAuto;
                if(!cur.isEmpty()){int ms=probeOne(cur);latency.put(cur,ms);if(ms<0)full=true;}
            }
            if(full)probeAll();
            selectBest();
        }catch(Throwable ignored){}
    }
    /** Re-check every profile now (used by the profile list). */
    public static void probeNow(Runnable done){
        ExecutorService pool=probePool;
        if(!autoInUse||pool==null){AndroidUtilities.runOnUIThread(done);return;}
        try{pool.submit(()->{probeAll();selectBest();AndroidUtilities.runOnUIThread(done);});}
        catch(RejectedExecutionException e){AndroidUtilities.runOnUIThread(done);}
    }
    public static void selectedAccountChanged(){
        AndroidUtilities.runOnUIThread(()->{
            try { if (!XrayProfileStore.mediaRoute().equals("account")) return; }
            catch(Exception e){return;}
            refresh();org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged();
        });
    }
    public static Proxy mediaProxy()throws IOException{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("Route resolution requires a worker thread");
        try{
            String id=mediaProfile();if(id.isEmpty())return Proxy.NO_PROXY;
            Future<Proxy> result=WORKER.submit(()->{boolean changed=ensureCore();if(changed)AndroidUtilities.runOnUIThread(XrayRuntime::reapplyAccounts);int[] pair=runningPorts.get(resolve(id));if(pair==null)throw new IOException("Xray route unavailable");return new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",pair[1]));});
            return result.get(20,TimeUnit.SECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Route cancelled",e);}
        catch(Exception e){throw new IOException("Xray media route unavailable",e);}
    }
}
