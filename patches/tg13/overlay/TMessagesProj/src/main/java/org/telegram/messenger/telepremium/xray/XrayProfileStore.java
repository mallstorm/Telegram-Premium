package org.telegram.messenger.telepremium.xray;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

/** Keystore-encrypted profile credentials; no plaintext config is written to disk. */
public final class XrayProfileStore {
    private static final String ALIAS="telepremium_xray_profiles_v1";
    private static JSONObject cached;
    private static SecretKey deviceKey;
    private static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences(ALIAS,Context.MODE_PRIVATE);}
    private static synchronized SecretKey key() throws Exception {
        if(deviceKey!=null)return deviceKey;
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return deviceKey=(SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return deviceKey=generator.generateKey();
    }
    public static void commitEncrypted(SharedPreferences preferences,String name,String value)throws Exception {
        String previous=preferences.getString(name,null);
        if(preferences.edit().putString(name,value).commit())return;
        SharedPreferences.Editor rollback=preferences.edit();if(previous==null)rollback.remove(name);else rollback.putString(name,previous);rollback.commit();
        throw new IllegalStateException("Could not persist encrypted settings");
    }
    public static String seal(String value,String purpose)throws Exception {
        byte[] plain=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(plain),raw=new byte[12+encrypted.length];System.arraycopy(cipher.getIV(),0,raw,0,12);System.arraycopy(encrypted,0,raw,12,encrypted.length);
            return Base64.encodeToString(raw,Base64.NO_WRAP);
        }finally{java.util.Arrays.fill(plain,(byte)0);}
    }
    public static String unseal(String value,String purpose)throws Exception {
        byte[] raw=Base64.decode(value,Base64.NO_WRAP);if(raw.length<29)throw new IllegalArgumentException("Damaged local settings");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,raw,0,12));cipher.updateAAD(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] plain=cipher.doFinal(raw,12,raw.length-12);
        try{return new String(plain,java.nio.charset.StandardCharsets.UTF_8);}finally{java.util.Arrays.fill(plain,(byte)0);}
    }
    private static synchronized JSONObject read() throws Exception {
        if(cached==null){
            String value=prefs().getString("payload",null);
            if(value==null)cached=new JSONObject().put("profiles",new JSONArray()).put("mediaRoute","");
            else {
                cached=new JSONObject(unseal(value,"profiles"));
            }
        }
        return new JSONObject(cached.toString());
    }
    private static synchronized void write(JSONObject state) throws Exception {
        String encrypted=seal(state.toString(),"profiles");
        commitEncrypted(prefs(),"payload",encrypted);
        cached=new JSONObject(state.toString());
    }

    /** Explicit user recovery after OS backup loses the device Keystore key. Never delete key aliases. */
    public static synchronized void resetAfterKeyLoss() throws Exception {
        String previous=prefs().getString("payload",null);
        if(previous!=null)commitEncrypted(prefs(),"unreadable_archive",previous);
        write(new JSONObject().put("profiles",new JSONArray()).put("mediaRoute",""));
    }

    public static synchronized List<XrayProfile> profiles() throws Exception {
        JSONArray a=read().getJSONArray("profiles");ArrayList<XrayProfile> out=new ArrayList<>();
        if(a.length()>XrayConfig.MAX_PROFILES)throw new IllegalStateException("Too many stored profiles");
        for(int i=0;i<a.length();i++)out.add(XrayProfile.fromJson(a.getJSONObject(i)));return out;
    }
    public static synchronized XrayProfile find(String id)throws Exception{for(XrayProfile p:profiles())if(p.id.equals(id))return p;return null;}
    public static synchronized void saveAll(List<XrayProfile> profiles)throws Exception{
        if(profiles.size()>XrayConfig.MAX_PROFILES)throw new IllegalArgumentException("Можно сохранить до 50 профилей");
        JSONArray a=new JSONArray();java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(XrayProfile p:profiles){XrayConfig.validateOutbound(p.outbound);if(!ids.add(p.id))throw new IllegalArgumentException("Duplicate profile");a.put(p.toJson());}
        JSONObject state=read();state.put("profiles",a);write(state);
    }
    public static synchronized String mediaRoute()throws Exception{return read().optString("mediaRoute","");}
    public static synchronized void setMediaRoute(String id)throws Exception{
        if(!id.isEmpty()&&!id.equals("account")&&!id.equals("auto-pool")&&find(id)==null)throw new IllegalArgumentException("Unknown profile");
        JSONObject state=read();state.put("mediaRoute",id);write(state);
    }
}
