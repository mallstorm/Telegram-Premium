package org.telegram.messenger.telepremium.media;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import java.util.*;

/** Media preferences only. Device grants, unlock secrets and transient positions stay local. */
public final class TelePremiumMediaPreferences {
    private static final Map<String,Set<String>> KEYS=new LinkedHashMap<>();
    static {
        KEYS.put("telepremium_media_center",new HashSet<>(Arrays.asList("video","sort")));
        KEYS.put("telepremium_youtube",new HashSet<>(Collections.singletonList("max_quality")));
        KEYS.put("telepremium_player_ui",new HashSet<>(Collections.singletonList("smooth_motion")));
        KEYS.put("telepremium_queue",new HashSet<>(Arrays.asList("shuffle","repeat")));
        Set<String> audio=new HashSet<>(Arrays.asList("eq_enabled","preamp","bass","treble","width","balance","mono","limiter","skip_silence","preset"));
        for(int i=0;i<10;i++)audio.add("band_"+i);KEYS.put("telepremium_audio_dsp",audio);
    }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<String,SharedPreferences.OnSharedPreferenceChangeListener> OBSERVERS=new HashMap<>();
    private static final Map<String,Long> DIRTY=new HashMap<>();
    private static boolean applying;
    private static final Runnable FLUSH=TelePremiumMediaPreferences::flush;
    private static SharedPreferences prefs(String group){return ApplicationLoader.applicationContext.getSharedPreferences(group,0);}
    public static void start(){
        if(!OBSERVERS.isEmpty())return;
        for(Map.Entry<String,Set<String>> entry:KEYS.entrySet()){
            String group=entry.getKey();SharedPreferences.OnSharedPreferenceChangeListener listener=(p,key)->{
                if(applying||!entry.getValue().contains(key))return;long uid=TelePremiumMediaLibraryStore.owner();if(uid<=0)return;
                DIRTY.put(group+"/"+key,uid);MAIN.removeCallbacks(FLUSH);MAIN.postDelayed(FLUSH,500);
            };OBSERVERS.put(group,listener);prefs(group).registerOnSharedPreferenceChangeListener(listener);
        }
    }
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
    private static JSONObject encode(String group,String key,Object value)throws Exception{
        String type=value instanceof Boolean?"boolean":value instanceof Integer?"int":value instanceof Long?"long":value instanceof Float?"float":value instanceof String?"string":null;
        if(type==null||value instanceof Float&&!finite((Float)value))throw new IllegalArgumentException("Unsupported setting");
        return new JSONObject().put("group",group).put("key",key).put("type",type).put("value",value);
    }
    public static void flush(){
        MAIN.removeCallbacks(FLUSH);Map<String,Long> dirty=new HashMap<>(DIRTY);DIRTY.clear();long owner=TelePremiumMediaLibraryStore.owner();
        for(Map.Entry<String,Long> entry:dirty.entrySet())if(entry.getValue()==owner){String[] pair=entry.getKey().split("/",2);String group=pair[0],key=pair[1];
            try{Object value=prefs(group).getAll().get(key);if(value==null)TelePremiumMediaLibraryStore.remove("preferences",entry.getKey());else TelePremiumMediaLibraryStore.put("preferences",entry.getKey(),encode(group,key,value),true);}
            catch(Exception e){TelePremiumMediaLibrarySync.localError("Не удалось сохранить настройки плеера");}
        }
    }
    public static void applyAndSeed()throws Exception{
        long uid=TelePremiumMediaLibraryStore.owner();if(uid<=0)return;JSONArray state=TelePremiumMediaLibraryStore.state(uid);
        Map<String,JSONObject> live=new HashMap<>();Set<String> history=new HashSet<>();
        for(int i=0;i<state.length();i++){JSONObject row=state.getJSONObject(i);if(!"preferences".equals(row.optString("kind")))continue;String key=row.optString("key");String[] pair=key.split("/",2);
            if(pair.length!=2||!KEYS.containsKey(pair[0])||!KEYS.get(pair[0]).contains(pair[1]))continue;history.add(key);
            if(!row.optBoolean("deleted")){JSONObject prior=live.get(key);if(prior==null||row.optLong("clock")>prior.optLong("clock"))live.put(key,row);}
        }
        Set<String> changed=new HashSet<>();applying=true;
        try{
            for(Map.Entry<String,Set<String>> group:KEYS.entrySet()){
                SharedPreferences target=prefs(group.getKey());SharedPreferences.Editor edit=target.edit();boolean touched=false;
                for(String key:group.getValue()){
                    String id=group.getKey()+"/"+key;if(!history.contains(id))continue;JSONObject row=live.get(id);Object current=target.getAll().get(key);
                    if(row==null){if(current!=null){edit.remove(key);touched=true;}continue;}
                    JSONObject data=row.getJSONObject("value");Object value=data.opt("value");String type=data.optString("type");
                    if(current!=null&&current.toString().equals(String.valueOf(value)))continue;
                    if("boolean".equals(type)&&value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                    else if("int".equals(type)&&value instanceof Number)edit.putInt(key,((Number)value).intValue());
                    else if("long".equals(type)&&value instanceof Number)edit.putLong(key,((Number)value).longValue());
                    else if("float".equals(type)&&value instanceof Number&&finite(((Number)value).floatValue()))edit.putFloat(key,((Number)value).floatValue());
                    else if("string".equals(type)&&value instanceof String)edit.putString(key,(String)value);else continue;
                    touched=true;
                }
                if(touched){if(!edit.commit())throw new IllegalStateException("Could not persist media preferences");changed.add(group.getKey());}
            }
        }finally{applying=false;}
        if(changed.contains("telepremium_audio_dsp")){TelePremiumAudioEffects.reloadFromPreferences();TelePremiumPlaybackController player=TelePremiumPlaybackController.peekInstance();if(player!=null)player.applySkipSilence();}
        if(changed.contains("telepremium_queue"))TelePremiumQueueManager.getInstance().reloadPreferences();
        Map<String,JSONObject> seed=new LinkedHashMap<>();
        for(Map.Entry<String,Set<String>> group:KEYS.entrySet())for(String key:group.getValue()){
            String id=group.getKey()+"/"+key;if(history.contains(id))continue;Object value=prefs(group.getKey()).getAll().get(key);if(value!=null)seed.put(id,encode(group.getKey(),key,value));
        }
        TelePremiumMediaLibraryStore.seedPreferences(seed);
    }
}
