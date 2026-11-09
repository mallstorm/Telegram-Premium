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
    public static String status(){return status;}
    private static JSONObject invoke(String method,JSONObject payload)throws Exception{
        if(!initialized){go.Seq.setContext(ApplicationLoader.applicationContext);initialized=true;}
        JSONObject response=new JSONObject(libXray.LibXray.invoke(new JSONObject().put("apiVersion",3).put("method",method).put("payload",payload).toString()));
        if(!response.optBoolean("success"))throw new IllegalArgumentException("Xray: "+method+" — проверьте параметры профиля");
        Object data=response.opt("data");return data instanceof JSONObject?(JSONObject)data:new JSONObject();
    }
    private static String fingerprint(XrayProfile p){return p.outbound.toString()+"|"+p.bypassLan;}
    public static int currentSocksPort(String id){
        try{XrayProfile p=XrayProfileStore.find(id);int[] ports=runningPorts.get(id);return p!=null&&ports!=null&&fingerprint(p).equals(fingerprints.get(id))?ports[0]:0;}
        catch(Exception e){return 0;}
    }
    private static String mediaProfile()throws Exception{
        String route=XrayProfileStore.mediaRoute();
        if(route.equals("account")){AccountNetworkProfile p=AccountNetworkStore.getInstance().load(UserConfig.selectedAccount);return p!=null&&p.mode==AccountNetworkProfile.MODE_XRAY?p.xrayProfileId:"";}
        return route;
    }
    private static synchronized boolean ensureCore()throws Exception{
        Set<String> wanted=new TreeSet<>();
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){
            AccountNetworkProfile p=AccountNetworkStore.getInstance().load(a);
            if(p!=null&&p.mode==AccountNetworkProfile.MODE_XRAY)wanted.add(p.xrayProfileId);
        }
        String media=mediaProfile();if(!media.isEmpty())wanted.add(media);
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
            try{changed=ensureCore();int[] pair=runningPorts.get(id);if(pair==null)throw new IllegalArgumentException("Профиль не назначен аккаунту");port=pair[0];}
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
            List<XrayProfile> profiles=XrayProfileStore.profiles();if(profiles.size()+nodes.length()>10)throw new IllegalArgumentException("Можно сохранить до 10 профилей");
            for(int i=0;i<nodes.length();i++){XrayProfile p=new XrayProfile();p.outbound=nodes.getJSONObject(i);p.name=p.outbound.optString("tag","Профиль "+(profiles.size()+1));if(p.name.length()>120)p.name=p.name.substring(0,120);XrayConfig.validateOutbound(p.outbound);
                Map<String,int[]> validationPorts=new HashMap<>();validationPorts.put(p.id,new int[]{20000,20001});
                String validation=XrayConfig.build(Collections.singletonList(p),validationPorts).toString();
                if(signature.isEmpty())invoke("testXray",new JSONObject().put("xrayJson",validation));profiles.add(p);}
            if(nodes.length()==0)throw new IllegalArgumentException("В конфигурации нет подходящих профилей");XrayProfileStore.saveAll(profiles);
        }catch(Throwable e){error=e instanceof IllegalArgumentException?e.getMessage():"Не удалось импортировать профили";}
            final String result=error;AndroidUtilities.runOnUIThread(()->callback.run(result));
        });
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
            Future<Proxy> result=WORKER.submit(()->{boolean changed=ensureCore();if(changed)AndroidUtilities.runOnUIThread(XrayRuntime::reapplyAccounts);int[] pair=runningPorts.get(id);if(pair==null)throw new IOException("Xray route unavailable");return new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",pair[1]));});
            return result.get(20,TimeUnit.SECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Route cancelled",e);}
        catch(Exception e){throw new IOException("Xray media route unavailable",e);}
    }
}
