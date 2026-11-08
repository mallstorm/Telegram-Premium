package org.telegram.messenger.telepremium.media;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Debounced V3 synchronization; account identity/reset/session are rechecked at each boundary. */
public final class TelePremiumMediaLibrarySync {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS=new CopyOnWriteArrayList<>();
    private static final Set<Long> IN_FLIGHT=new HashSet<>(), AGAIN=new HashSet<>();
    private static volatile String status="Синхронизация не запущена";
    public static String status(){return status;}
    public static void localError(String message){if(message.equals(status))return;status=message;AndroidUtilities.runOnUIThread(TelePremiumMediaLibrarySync::notifyChanged);}
    private static void notifyChanged(){for(Runnable listener:LISTENERS)listener.run();}
    public static void observe(Runnable listener){LISTENERS.addIfAbsent(listener);start();}
    public static void unobserve(Runnable listener){LISTENERS.remove(listener);if(LISTENERS.isEmpty())MAIN.removeCallbacks(POLL);}
    private static final Runnable POLL=new Runnable(){@Override public void run(){start();}};
    private static final Runnable DEBOUNCE=()->synchronize(TelePremiumMediaLibraryStore.owner());
    public static void changed(long uid){AndroidUtilities.runOnUIThread(()->{
        if(uid!=TelePremiumMediaLibraryStore.owner())return;status="Изменения сохранены на устройстве";notifyChanged();
        if(IN_FLIGHT.contains(uid))AGAIN.add(uid);MAIN.removeCallbacks(DEBOUNCE);MAIN.postDelayed(DEBOUNCE,1200);
    });}
    public static void start(){AndroidUtilities.runOnUIThread(()->{
        TelePremiumMediaPreferences.start();
        long uid=TelePremiumMediaLibraryStore.owner();if(uid<=0){status="Выберите подключённый аккаунт для синхронизации";notifyChanged();return;}
        TelePremiumMediaLibraryStore.migrateLegacy();synchronize(uid);
        MAIN.removeCallbacks(POLL);if(!LISTENERS.isEmpty())MAIN.postDelayed(POLL,45000);
    });}
    public static void onAccountAvailable(int account){
        MAIN.post(()->{if(UserConfig.getInstance(account).getClientUserId()==TelePremiumMediaLibraryStore.owner())start();});
    }
    private static boolean valid(long uid,int account,long epoch){return uid>0&&uid==TelePremiumMediaLibraryStore.owner()
            &&UserConfig.getInstance(account).isClientActivated()&&UserConfig.getInstance(account).getClientUserId()==uid
            &&TelePremiumCloudResetState.allowed(epoch)&&TelePremiumCloudSecuritySession.isUnlocked(ApplicationLoader.applicationContext);}
    private static void synchronize(long uid){
        if(uid<=0||IN_FLIGHT.contains(uid))return;
        TelePremiumMediaPreferences.flush();
        int slot=-1;for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()&&UserConfig.getInstance(a).getClientUserId()==uid)slot=a;
        if(slot<0)return;final int account=slot;final long epoch=TelePremiumCloudResetState.epoch();
        if(!valid(uid,account,epoch)){status="Разблокируйте Vault для синхронизации";notifyChanged();return;}
        IN_FLIGHT.add(uid);status="Синхронизация с Vault V3…";notifyChanged();
        TelePremiumCloudManager manager=TelePremiumCloudManager.getInstance(account);
        manager.bootstrap(error->{
            if(!valid(uid,account,epoch)){finish(uid,"Доступ к облаку изменился");return;}
            if(error!=null||manager.getSnapshot()==null||manager.getSnapshot().ownerUserId!=uid){finish(uid,error==null?"Облако недоступно":error);return;}
            try{
                JSONArray remote=manager.getSnapshot().mediaLibrary;
                TelePremiumMediaLibraryStore.merge(uid,remote);TelePremiumMediaPreferences.applyAndSeed();JSONArray local=TelePremiumMediaLibraryStore.state(uid);notifyChanged();
                if(local.toString().equals(remote.toString())){finish(uid,null);return;}
                manager.updateMediaLibrary(local,saveError->{
                    if(!valid(uid,account,epoch)){finish(uid,"Доступ к облаку изменился");return;}
                    if(saveError==null)try{TelePremiumMediaLibraryStore.merge(uid,manager.getSnapshot().mediaLibrary);}catch(Exception e){finish(uid,"Не удалось сохранить локальную копию");return;}
                    finish(uid,saveError);
                });
            }catch(Exception e){finish(uid,"Не удалось объединить настройки медиатеки");}
        });
    }
    private static void finish(long uid,String error){
        IN_FLIGHT.remove(uid);if(uid!=TelePremiumMediaLibraryStore.owner()){AGAIN.remove(uid);return;}
        status=error==null?"Настройки синхронизированы с Vault V3":"Ожидает синхронизации: "+error;
        notifyChanged();if(AGAIN.remove(uid)){MAIN.removeCallbacks(DEBOUNCE);MAIN.postDelayed(DEBOUNCE,error==null?800:10000);}
    }
}
