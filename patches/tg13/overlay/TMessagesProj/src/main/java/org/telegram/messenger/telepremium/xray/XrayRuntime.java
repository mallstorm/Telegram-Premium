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
    private static volatile String lastSummary="";
    public static String lastImportSummary(){return lastSummary;}
    private static boolean isUrl(String t){String l=t.trim().toLowerCase(Locale.ROOT);return (l.startsWith("https://")||l.startsWith("http://"))&&!l.contains("\n")&&!l.contains(" ");}
    private static boolean isShareLink(String line){
        String l=line.toLowerCase(Locale.ROOT);
        for(String p:new String[]{"vless://","vmess://","trojan://","ss://","hysteria2://","socks://"})if(l.startsWith(p))return true;
        return false;
    }
    private static String fetchSubscription(String url)throws Exception{
        Exception direct=null;
        try{return fetch(url,Proxy.NO_PROXY);}catch(Exception e){direct=e;}
        Map<String,int[]> ports=runningPorts;
        if(!ports.isEmpty()){
            int[] pair=ports.values().iterator().next();
            try{return fetch(url,new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",pair[1])));}catch(Exception ignored){}
        }
        throw direct;
    }
    private static String fetch(String url,Proxy proxy)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(proxy);
        try{
            c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","v2rayN/6.42");c.setInstanceFollowRedirects(true);
            if(c.getResponseCode()/100!=2)throw new IOException("HTTP "+c.getResponseCode());
            java.io.InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>2*1024*1024)throw new IllegalArgumentException("Подписка слишком большая");}
            return out.toString("UTF-8");
        }finally{c.disconnect();}
    }
    private static String decodeSubscription(String body){
        String t=body.trim();
        if(t.contains("://")||t.startsWith("{")||t.startsWith("["))return t;
        String clean=t.replaceAll("\\s+","");
        for(int flags:new int[]{android.util.Base64.DEFAULT,android.util.Base64.URL_SAFE}){
            try{String s=new String(android.util.Base64.decode(clean,flags),java.nio.charset.StandardCharsets.UTF_8);if(s.contains("://"))return s;}catch(IllegalArgumentException ignored){}
        }
        throw new IllegalArgumentException("Не удалось прочитать подписку: ожидается список ссылок");
    }
    private static JSONArray nodesFromText(String text)throws Exception{
        String t=text.trim();
        if(t.startsWith("{")||t.startsWith("["))return XrayConfig.importJson(t);
        JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
        for(String line:t.split("[\\r\\n]+")){
            line=line.trim();if(!isShareLink(line))continue;
            try{
                JSONArray one=invoke("convertShareLinksToXrayJson",new JSONObject().put("text",line)).getJSONArray("outbounds");
                for(int i=0;i<one.length();i++){JSONObject o=one.getJSONObject(i);JSONObject copy=new JSONObject(o.toString());copy.remove("tag");if(seen.add(copy.toString()))out.put(o);}
            }catch(Exception ignored){}
        }
        return out;
    }
    private static boolean isReferenced(String id){
        try{
            if(id.equals(XrayProfileStore.mediaRoute()))return true;
            for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++){AccountNetworkProfile r=AccountNetworkStore.getInstance().load(a);if(r!=null&&r.mode==AccountNetworkProfile.MODE_XRAY&&id.equals(r.xrayProfileId))return true;}
        }catch(Exception e){return true;}
        return false;
    }
    private static XrayProfile prepared(JSONObject outbound,String fallbackName)throws Exception{
        XrayProfile p=new XrayProfile();p.outbound=outbound;p.name=outbound.optString("tag",fallbackName).trim();
        if(p.name.isEmpty())p.name=fallbackName;if(p.name.length()>120)p.name=p.name.substring(0,120);
        XrayConfig.validateOutbound(p.outbound);
        Map<String,int[]> validationPorts=new HashMap<>();validationPorts.put(p.id,new int[]{20000,20001});
        String validation=XrayConfig.build(Collections.singletonList(p),validationPorts).toString();
        if(signature.isEmpty())invoke("testXray",new JSONObject().put("xrayJson",validation));
        return p;
    }
    private static void finish(String error,org.telegram.messenger.Utilities.Callback<String> callback){final String result=error;AndroidUtilities.runOnUIThread(()->callback.run(result));}
    /** Single links, several links, Xray JSON or a subscription URL (https://…). */
    public static void importProfiles(String text,org.telegram.messenger.Utilities.Callback<String> callback){
        if(text!=null&&text.length()<=4096&&isUrl(text)){
            final String url=text.trim();
            Thread t=new Thread(()->{
                String body=null,error=null;
                try{body=decodeSubscription(fetchSubscription(url));}
                catch(IllegalArgumentException e){error=e.getMessage();}
                catch(Throwable e){error="Не удалось загрузить подписку. Проверьте ссылку и интернет";}
                if(error!=null){finish(error,callback);return;}
                importInternal(body,url,callback);
            },"tp-xray-sub");t.setDaemon(true);t.start();return;
        }
        importInternal(text,null,callback);
    }
    /** Re-download a saved subscription: updates keys by name, adds new ones, drops unused vanished ones. */
    public static void refreshSubscription(String subId,org.telegram.messenger.Utilities.Callback<String> callback){
        Thread t=new Thread(()->{
            String url=null,body=null,error=null;
            try{for(String[] s:XrayProfileStore.subscriptions())if(s[0].equals(subId))url=s[1];
                if(url==null)throw new IllegalArgumentException("Подписка не найдена");
                body=decodeSubscription(fetchSubscription(url));}
            catch(IllegalArgumentException e){error=e.getMessage();}
            catch(Throwable e){error="Не удалось загрузить подписку. Проверьте интернет";}
            if(error!=null){finish(error,callback);return;}
            importInternal(body,url,callback);
        },"tp-xray-sub");t.setDaemon(true);t.start();
    }
    private static void importInternal(String text,String subUrl,org.telegram.messenger.Utilities.Callback<String> callback){
        WORKER.execute(()->{String error=null;try{
            if(text==null||text.length()>262144*8)throw new IllegalArgumentException("Слишком большой профиль");
            JSONArray nodes=nodesFromText(text);
            if(nodes.length()==0)throw new IllegalArgumentException("В конфигурации нет подходящих профилей");
            List<XrayProfile> profiles=XrayProfileStore.profiles();
            if(subUrl==null){
                if(text.length()>262144)throw new IllegalArgumentException("Слишком большой профиль");
                if(profiles.size()+nodes.length()>XrayConfig.MAX_PROFILES)throw new IllegalArgumentException("Можно сохранить до "+XrayConfig.MAX_PROFILES+" профилей");
                for(int i=0;i<nodes.length();i++)profiles.add(prepared(nodes.getJSONObject(i),"Профиль "+(profiles.size()+1)));
                XrayProfileStore.saveAll(profiles);lastSummary="Добавлено ключей: "+nodes.length();
            }else{
                String subId=XrayProfileStore.subscriptionId(subUrl);
                Map<String,XrayProfile> mine=new LinkedHashMap<>();List<XrayProfile> others=new ArrayList<>();
                for(XrayProfile p:profiles){if(subId.equals(p.sub)&&!mine.containsKey(p.name))mine.put(p.name,p);else if(subId.equals(p.sub)){others.add(p);}else others.add(p);}
                List<XrayProfile> result=new ArrayList<>(others);List<XrayProfile> fresh=new ArrayList<>();
                Set<String> usedNames=new HashSet<>();int updated=0,added=0,skipped=0,kept=0;
                for(int i=0;i<nodes.length();i++){
                    try{
                        XrayProfile p=prepared(nodes.getJSONObject(i),"Ключ "+(i+1));
                        XrayProfile old=usedNames.contains(p.name)?null:mine.get(p.name);usedNames.add(p.name);
                        if(old!=null){old.outbound=p.outbound;old.sub=subId;fresh.add(old);updated++;mine.remove(p.name);}
                        else{p.sub=subId;fresh.add(p);added++;}
                    }catch(Exception e){skipped++;}
                }
                for(XrayProfile gone:mine.values())if(isReferenced(gone.id)){fresh.add(gone);kept++;}
                int room=XrayConfig.MAX_PROFILES-result.size();
                if(fresh.size()>room){skipped+=fresh.size()-room;fresh=new ArrayList<>(fresh.subList(0,Math.max(0,room)));}
                if(fresh.isEmpty())throw new IllegalArgumentException("Подписка не содержит подходящих ключей");
                result.addAll(fresh);XrayProfileStore.saveAll(result);XrayProfileStore.saveSubscription(subUrl);
                lastSummary="Добавлено: "+added+", обновлено: "+updated+(skipped>0?", пропущено: "+skipped:"");
                refresh();
            }
        }catch(Throwable e){error=e instanceof IllegalArgumentException?e.getMessage():"Не удалось импортировать профили";}
            finish(error,callback);
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
