package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;
import org.telegram.messenger.telepremium.xray.XrayProfileStore;
import java.util.*;

/** Per-user encrypted media settings. Tombstones survive reinstall and stale offline edits. */
public final class TelePremiumMediaLibraryStore {
    private static final Map<Long,JSONArray> CACHE=new HashMap<>();
    private static final Set<Long> MIGRATING=new HashSet<>();
    private static long failures;
    private static final Map<Long,JSONObject> DEVICE_BINDINGS=new HashMap<>();
    private static final Map<String,TelePremiumMediaItem> DEVICE_ITEMS=new HashMap<>();
    private static final String PREFIX="telepremium_media_library_v3_u";
    private static SharedPreferences prefs(long uid){return ApplicationLoader.applicationContext.getSharedPreferences(PREFIX+uid,Context.MODE_PRIVATE);}
    private static SharedPreferences binding(){return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_media_sync_owner",0);}
    public static long owner(){
        long uid=binding().getLong("owner",0);
        if(uid==0){uid=UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();if(uid==0)for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){uid=UserConfig.getInstance(a).getClientUserId();break;}
            if(uid>0&&!binding().edit().putLong("owner",uid).commit())return 0;}
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()&&UserConfig.getInstance(a).getClientUserId()==uid)return uid;
        return 0;
    }
    public static synchronized void setOwner(long uid){
        boolean active=false;for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)active|=UserConfig.getInstance(a).isClientActivated()&&UserConfig.getInstance(a).getClientUserId()==uid;
        if(!active)throw new IllegalStateException("Не удалось выбрать аккаунт синхронизации");
        long previous=owner();
        try {
            if(previous>0&&previous!=uid){
                merge(uid,state(previous));JSONObject merged=new JSONObject(bindings(uid).toString());JSONObject old=bindings(previous);
                java.util.Iterator<String> keys=old.keys();while(keys.hasNext()){String key=keys.next();merged.put(key,old.optString(key));}
                XrayProfileStore.commitEncrypted(prefs(uid),"deviceBindings",XrayProfileStore.seal(merged.toString(),PREFIX+uid+".bindings"));DEVICE_BINDINGS.put(uid,merged);
            }
        }catch(Exception e){throw new IllegalStateException("Не удалось перенести настройки медиатеки",e);}
        if(!binding().edit().putLong("owner",uid).commit()){binding().edit().putLong("owner",previous).commit();throw new IllegalStateException("Не удалось выбрать аккаунт синхронизации");}
    }
    public static synchronized JSONArray state(long uid)throws Exception{
        if(uid<=0)throw new IllegalStateException("Подключите Telegram-аккаунт");
        JSONArray state=CACHE.get(uid);
        if(state==null){String raw=prefs(uid).getString("encrypted",null);state=raw==null?new JSONArray():new JSONArray(XrayProfileStore.unseal(raw,PREFIX+uid));CACHE.put(uid,state);}
        return new JSONArray(state.toString());
    }
    public static synchronized void merge(long uid,JSONArray remote)throws Exception {
        JSONArray verified=CloudSnapshot.mergeMediaLibrary(new JSONArray(),remote),merged;
        try{merged=CloudSnapshot.mergeMediaLibrary(state(uid),verified);}
        catch(Exception e){
            String unreadable=prefs(uid).getString("encrypted",null);
            if(unreadable!=null)XrayProfileStore.commitEncrypted(prefs(uid),"unreadable_archive",unreadable);
            merged=verified;
        }
        write(uid,merged);
    }
    private static void write(long uid,JSONArray state)throws Exception{
        JSONArray stable=CloudSnapshot.mergeMediaLibrary(new JSONArray(),state);
        if(stable.toString().equals(CACHE.containsKey(uid)?CACHE.get(uid).toString():""))return;
        String raw=XrayProfileStore.seal(stable.toString(),PREFIX+uid);
        XrayProfileStore.commitEncrypted(prefs(uid),"encrypted",raw);
        CACHE.put(uid,stable);
    }
    private static String id(String kind,String key){
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((kind+"\n"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(kind+":");for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static long clock(JSONArray state){long now=System.currentTimeMillis();for(int i=0;i<state.length();i++)now=Math.max(now,state.optJSONObject(i).optLong("clock")+1);return now;}
    private static JSONObject current(JSONArray state,String id){JSONObject best=null;for(int i=0;i<state.length();i++){JSONObject row=state.optJSONObject(i);if(row.optString("id").equals(id)&&!row.optBoolean("deleted")&&(best==null||row.optLong("clock")>best.optLong("clock")))best=row;}return best;}
    public static synchronized List<JSONObject> values(String kind){
        migrateLegacy();
        ArrayList<JSONObject> out=new ArrayList<>();try{JSONArray state=state(owner());Map<String,JSONObject> live=new HashMap<>();for(int i=0;i<state.length();i++){JSONObject row=state.getJSONObject(i);if(!kind.equals(row.optString("kind"))||row.optBoolean("deleted"))continue;JSONObject before=live.get(row.optString("id"));if(before==null||row.optLong("clock")>before.optLong("clock"))live.put(row.optString("id"),row);}
            ArrayList<JSONObject> ordered=new ArrayList<>(live.values());ordered.sort((a,b)->Long.compare(b.optLong("clock"),a.optLong("clock")));for(JSONObject row:ordered)out.add(new JSONObject(row.getJSONObject("value").toString()));
        }catch(Exception e){TelePremiumMediaLibrarySync.localError("Не удалось прочитать локальную медиатеку");}return out;
    }
    public static synchronized boolean readd(String kind,String key,JSONObject value){return putInternal(kind,key,value,true,true);}
    public static synchronized boolean put(String kind,String key,JSONObject value,boolean explicitAdd){return putInternal(kind,key,value,explicitAdd,false);}
    private static boolean putInternal(String kind,String key,JSONObject value,boolean explicitAdd,boolean fresh){
        try{long uid=owner();JSONArray state=state(uid);String id=id(kind,key);JSONObject prior=current(state,id);if(prior==null&&!explicitAdd)return false;
            if(!fresh&&prior!=null&&prior.getJSONObject("value").toString().equals(value.toString()))return true;
            if(fresh){JSONArray retired=new JSONArray();long retirement=clock(state);for(int i=0;i<state.length();i++){JSONObject row=state.getJSONObject(i);if(row.optString("id").equals(id)&&!row.optBoolean("deleted"))retired.put(new JSONObject(row.toString()).put("deleted",true).put("value",new JSONObject()).put("clock",retirement).put("actor",actor(uid)));}state=CloudSnapshot.mergeMediaLibrary(state,retired);prior=null;}
            JSONObject row=new JSONObject().put("id",id).put("birth",prior==null?UUID.randomUUID().toString():prior.getString("birth"))
                    .put("kind",kind).put("key",key).put("value",value).put("clock",clock(state)).put("actor",actor(uid)).put("deleted",false);
            if(prior!=null&&prior.optBoolean("legacy"))row.put("legacy",true);
            write(uid,CloudSnapshot.mergeMediaLibrary(state,new JSONArray().put(row)));TelePremiumMediaLibrarySync.changed(uid);return true;
        }catch(Exception e){failures++;TelePremiumMediaLibrarySync.localError("Не удалось сохранить локальную медиатеку");return false;}
    }
    public static synchronized void remove(String kind,String key){
        try{long uid=owner();JSONArray state=state(uid),removed=new JSONArray();String id=id(kind,key);long clock=clock(state);
            for(int i=0;i<state.length();i++){JSONObject row=state.getJSONObject(i);if(id.equals(row.optString("id"))&&!row.optBoolean("deleted"))removed.put(new JSONObject(row.toString()).put("deleted",true).put("value",new JSONObject()).put("clock",clock).put("actor",actor(uid)));}
            write(uid,CloudSnapshot.mergeMediaLibrary(state,removed));TelePremiumMediaLibrarySync.changed(uid);
        }catch(Exception e){failures++;TelePremiumMediaLibrarySync.localError("Не удалось сохранить удаление");}
    }
    private static String actor(long uid){String actor=prefs(uid).getString("actor",null);if(actor==null){actor=UUID.randomUUID().toString();prefs(uid).edit().putString("actor",actor).commit();}return actor;}
    public static boolean hasYoutube(String url){for(JSONObject row:values("youtube"))if(url.equals(row.optString("url")))return true;return false;}
    private static JSONObject bindings(long uid)throws Exception{
        JSONObject json=DEVICE_BINDINGS.get(uid);
        if(json==null){String raw=prefs(uid).getString("deviceBindings",null);if(raw==null)json=new JSONObject();else try{json=new JSONObject(XrayProfileStore.unseal(raw,PREFIX+uid+".bindings"));}catch(Exception e){XrayProfileStore.commitEncrypted(prefs(uid),"bindings_archive",raw);json=new JSONObject();}DEVICE_BINDINGS.put(uid,json);}return json;
    }
    public static synchronized void bindDevice(String id,String uri)throws Exception {
        if(uri==null||id==null)return;long uid=owner();JSONObject next=new JSONObject(bindings(uid).toString());if(uri.equals(next.optString(id)))return;
        next.put(id,uri);XrayProfileStore.commitEncrypted(prefs(uid),"deviceBindings",XrayProfileStore.seal(next.toString(),PREFIX+uid+".bindings"));DEVICE_BINDINGS.put(uid,next);
    }
    public static synchronized void registerDeviceItems(List<TelePremiumMediaItem> items){DEVICE_ITEMS.clear();if(items==null)return;for(TelePremiumMediaItem item:items)DEVICE_ITEMS.put(item.id,item);}
    public static synchronized String deviceUri(TelePremiumMediaItem item){
        try{String saved=bindings(owner()).optString(item.id,null);if(saved!=null)return saved;}catch(Exception ignored){}
        TelePremiumMediaItem local=DEVICE_ITEMS.get(item.id);
        return local!=null&&java.util.Objects.equals(local.title,item.title)&&local.size==item.size&&Math.abs(local.duration-item.duration)<2000?local.uri:null;
    }
    public static synchronized void seedPreferences(Map<String,JSONObject> settings)throws Exception {
        long uid=owner();JSONArray current=state(uid),rows=new JSONArray();Set<String> known=new HashSet<>();for(int i=0;i<current.length();i++)known.add(current.getJSONObject(i).getString("id"));long clock=clock(current);
        for(Map.Entry<String,JSONObject> entry:settings.entrySet())legacyRow(rows,known,"preferences",entry.getKey(),entry.getValue(),clock++);
        if(rows.length()>0){write(uid,CloudSnapshot.mergeMediaLibrary(current,rows));TelePremiumMediaLibrarySync.changed(uid);}
    }
    private static void legacyRow(JSONArray rows,java.util.Set<String> known,String kind,String key,JSONObject value,long clock)throws Exception {
        String id=id(kind,key);if(!known.add(id))return;
        rows.put(new JSONObject().put("id",id).put("birth","legacy-"+UUID.nameUUIDFromBytes((kind+"\n"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .put("clock",clock).put("actor","legacy").put("kind",kind).put("key",key).put("value",value).put("deleted",false).put("legacy",true));
    }
    public static synchronized void migrateLegacy(){
        long uid=owner();if(uid<=0||MIGRATING.contains(uid)||prefs(uid).getBoolean("migrated",false))return;
        SharedPreferences marker=ApplicationLoader.applicationContext.getSharedPreferences("telepremium_media_migration",0);
        long priorOwner=marker.getLong("owner",0);if(priorOwner!=0&&priorOwner!=uid){prefs(uid).edit().putBoolean("migrated",true).commit();return;}
        MIGRATING.add(uid);
        try {
            JSONArray current=state(uid),rows=new JSONArray();java.util.Set<String> known=new java.util.HashSet<>();
            for(int i=0;i<current.length();i++)known.add(current.getJSONObject(i).getString("id"));long timestamp=clock(current);
            SharedPreferences old=ApplicationLoader.applicationContext.getSharedPreferences("telepremium_network_library",0);
            for(String kind:new String[]{"youtube","iptv"}){JSONArray list=new JSONArray(old.getString(kind,"[]"));for(int i=0;i<list.length();i++){JSONObject row=list.getJSONObject(i);legacyRow(rows,known,kind,row.getString("url"),row,timestamp++);}}
            JSONArray radio=new JSONArray(old.getString("radio_fav","[]"));for(int i=0;i<radio.length();i++){JSONObject row=radio.getJSONObject(i);legacyRow(rows,known,"radio",row.getString("uri"),row,timestamp++);}
            String code=old.getString("radio_country",null);if(code!=null)legacyRow(rows,known,"preferences","radio",new JSONObject().put("code",code).put("name",old.getString("radio_country_name",null)),timestamp++);
            JSONArray playlists=new JSONArray(ApplicationLoader.applicationContext.getSharedPreferences("telepremium_media_playlists_v1",0).getString("playlists","[]"));
            for(int i=0;i<playlists.length();i++){
                JSONObject row=playlists.getJSONObject(i);JSONArray items=row.optJSONArray("items");
                if(items!=null)for(int j=0;j<items.length();j++){
                    JSONObject item=items.getJSONObject(j);int source=item.optInt("sourceType");
                    if(source==TelePremiumMediaItem.SOURCE_DEVICE){bindDevice(item.optString("id"),item.optString("uri",null));item.put("uri",JSONObject.NULL);}
                    else if(source==TelePremiumMediaItem.SOURCE_CLOUD){int slot=item.optInt("cloudAccount",-1);long owner=item.optLong("cloudUserId");if(owner==0&&slot>=0&&slot<UserConfig.MAX_ACCOUNT_COUNT)owner=UserConfig.getInstance(slot).getClientUserId();item.put("cloudUserId",owner).put("cloudAccount",-1).put("cloudEpoch",-1);}
                }
                legacyRow(rows,known,"playlist",row.getString("id"),row,timestamp++);
            }
            write(uid,CloudSnapshot.mergeMediaLibrary(current,rows));
            if(!marker.edit().putLong("owner",uid).commit()||!prefs(uid).edit().putBoolean("migrated",true).commit())throw new IllegalStateException("Migration marker was not saved");
        }catch(Exception e){TelePremiumMediaLibrarySync.localError("Не удалось перенести старую медиатеку");}finally{MIGRATING.remove(uid);}
    }
}
