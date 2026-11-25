package org.telegram.messenger.telepremium.aicenter;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import org.telegram.messenger.*;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Each account has an independent Keystore key. All content including source names and TL handles is AEAD encrypted. */
public final class CenterStore extends SQLiteOpenHelper {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(r,"tp-ai-center-index"));
    private static CenterStore instance;
    private final Map<String,SecretKey> keyCache=new HashMap<>();
    public static synchronized CenterStore get(){if(instance==null)instance=new CenterStore();return instance;}
    private CenterStore(){super(ApplicationLoader.applicationContext,"tp_ai_center_v1.db",null,1);}
    @Override public void onConfigure(SQLiteDatabase db){db.execSQL("PRAGMA secure_delete=ON");}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE records (account INTEGER NOT NULL, owner INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, value BLOB NOT NULL, PRIMARY KEY(account,owner,kind,id))");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int now){throw new IllegalStateException("Unsupported index migration");}
    private String alias(int account,long owner){return "tp_aic_"+account+"_"+owner;}
    private SecretKey key(int account,long owner)throws Exception{
        String a=alias(account,owner);if(keyCache.containsKey(a))return keyCache.get(a);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        java.security.Key k=ks.getKey(a,null);if(k instanceof SecretKey){keyCache.put(a,(SecretKey)k);return(SecretKey)k;}
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(a,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());SecretKey generated=gen.generateKey();keyCache.put(a,generated);return generated;
    }
    private byte[] aad(int a,long o,String kind,String id){return ("AIC1|"+a+"|"+o+"|"+kind+"|"+id).getBytes(StandardCharsets.UTF_8);}
    private static String digest(String s)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}
    public synchronized void put(int a,long owner,String kind,String id,JSONObject json)throws Exception{
        if(owner<=0||!CenterIndex.accountAllowed(a,owner))throw new SecurityException("Account unavailable");
        String h=digest(id);byte[] plain=json.toString().getBytes(StandardCharsets.UTF_8);byte[] sealed;
        try{sealed=CenterEnvelope.seal(key(a,owner),aad(a,owner,kind,h),plain);}finally{Arrays.fill(plain,(byte)0);}
        ContentValues v=new ContentValues();v.put("account",a);v.put("owner",owner);v.put("kind",kind);v.put("id",h);v.put("value",sealed);getWritableDatabase().insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized JSONObject get(int a,long owner,String kind,String id)throws Exception{
        String h=digest(id);try(Cursor c=getReadableDatabase().query("records",new String[]{"value"},"account=? AND owner=? AND kind=? AND id=?",new String[]{""+a,""+owner,kind,h},null,null,null)){
            if(!c.moveToFirst())return null;byte[] plain=CenterEnvelope.open(key(a,owner),aad(a,owner,kind,h),c.getBlob(0));try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}finally{Arrays.fill(plain,(byte)0);}}
    }
    public synchronized List<JSONObject> list(int a,long owner,String kind)throws Exception{
        ArrayList<JSONObject> out=new ArrayList<>();if(owner<=0)return out;
        try(Cursor cur=getReadableDatabase().query("records",new String[]{"id","value"},"account=? AND owner=? AND kind=?",new String[]{""+a,""+owner,kind},null,null,null)){
            while(cur.moveToNext()){byte[] b=cur.getBlob(1);byte[] plain=CenterEnvelope.open(key(a,owner),aad(a,owner,kind,cur.getString(0)),b);try{out.add(new JSONObject(new String(plain,StandardCharsets.UTF_8)));}finally{Arrays.fill(plain,(byte)0);}}
        }return out;
    }
    public synchronized void putMessages(java.util.List<CenterData.Message> messages)throws Exception{SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(CenterData.Message m:messages){JSONObject old=get(m.account,m.owner,"message",m.qualified());if(old!=null){CenterData.Message prior=CenterData.Message.parse(old);if(prior.transport.equals(m.transport)){m.ocr=prior.ocr;m.transcript=prior.transcript;m.description=prior.description;m.hash=prior.hash;m.pages=prior.pages;m.pageEvidence=prior.pageEvidence;m.frameEvidence=prior.frameEvidence;m.frameTimes.addAll(prior.frameTimes);}}put(m.account,m.owner,"message",m.qualified(),m.json());}db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized void removeKind(int a,long owner,String kind){getWritableDatabase().delete("records","account=? AND owner=? AND kind=?",new String[]{""+a,""+owner,kind});}
    public synchronized void remove(int a,long owner,String kind,String id)throws Exception{getWritableDatabase().delete("records","account=? AND owner=? AND kind=? AND id=?",new String[]{""+a,""+owner,kind,digest(id)});}
    public synchronized void purge(int a){
        for(int other=0;other<UserConfig.MAX_ACCOUNT_COUNT;other++)if(other!=a&&UserConfig.getInstance(other).isClientActivated()){long owner=UserConfig.getInstance(other).getClientUserId();for(String kind:new String[]{"report","thread","chunk","job"})try{for(JSONObject o:list(other,owner,kind)){org.json.JSONArray deps=o.optJSONArray("accounts");boolean remove=false;for(int i=0;deps!=null&&i<deps.length();i++){JSONObject dep=deps.optJSONObject(i);if(dep!=null&&dep.optInt("account")==a)remove=true;}if(remove)remove(other,owner,kind,o.optString("id"));}}catch(Exception e){FileLog.e(e);}}
        getWritableDatabase().delete("records","account=?",new String[]{""+a});try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);Enumeration<String> keys=ks.aliases();while(keys.hasMoreElements()){String k=keys.nextElement();if(k.startsWith("tp_aic_"+a+"_")){ks.deleteEntry(k);keyCache.remove(k);}}}catch(Exception e){FileLog.e(e);}}
    public synchronized void clear(){for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)purge(a);}
    public synchronized long bytes(){try(Cursor c=getReadableDatabase().rawQuery("SELECT coalesce(sum(length(value)),0) FROM records",null)){return c.moveToFirst()?c.getLong(0):0;}}
}
