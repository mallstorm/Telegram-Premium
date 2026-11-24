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
    public static synchronized CenterStore get(){if(instance==null)instance=new CenterStore();return instance;}
    private CenterStore(){super(ApplicationLoader.applicationContext,"tp_ai_center_v1.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE records (account INTEGER NOT NULL, owner INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, value BLOB NOT NULL, PRIMARY KEY(account,owner,kind,id))");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int now){throw new IllegalStateException("Unsupported index migration");}
    private String alias(int account,long owner){return "tp_aic_"+account+"_"+owner;}
    private SecretKey key(int account,long owner)throws Exception{
        String a=alias(account,owner);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        java.security.Key k=ks.getKey(a,null);if(k instanceof SecretKey)return(SecretKey)k;
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(a,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return gen.generateKey();
    }
    private byte[] aad(int a,long o,String kind,String id){return ("AIC1|"+a+"|"+o+"|"+kind+"|"+id).getBytes(StandardCharsets.UTF_8);}
    private static String digest(String s)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}
    public synchronized void put(int a,long owner,String kind,String id,JSONObject json)throws Exception{
        if(owner<=0||!UserConfig.getInstance(a).isClientActivated()||UserConfig.getInstance(a).getClientUserId()!=owner)throw new SecurityException("Account unavailable");
        String h=digest(id);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(a,owner));c.updateAAD(aad(a,owner,kind,h));
        byte[] ct=c.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));byte[] sealed=new byte[12+ct.length];System.arraycopy(c.getIV(),0,sealed,0,12);System.arraycopy(ct,0,sealed,12,ct.length);
        ContentValues v=new ContentValues();v.put("account",a);v.put("owner",owner);v.put("kind",kind);v.put("id",h);v.put("value",sealed);getWritableDatabase().insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized List<JSONObject> list(int a,long owner,String kind)throws Exception{
        ArrayList<JSONObject> out=new ArrayList<>();if(owner<=0)return out;
        try(Cursor cur=getReadableDatabase().query("records",new String[]{"id","value"},"account=? AND owner=? AND kind=?",new String[]{""+a,""+owner,kind},null,null,null)){
            while(cur.moveToNext()){byte[] b=cur.getBlob(1);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(a,owner),new GCMParameterSpec(128,b,0,12));c.updateAAD(aad(a,owner,kind,cur.getString(0)));byte[] plain=c.doFinal(b,12,b.length-12);try{out.add(new JSONObject(new String(plain,StandardCharsets.UTF_8)));}finally{Arrays.fill(plain,(byte)0);}}
        }return out;
    }
    public synchronized void remove(int a,long owner,String kind,String id)throws Exception{getWritableDatabase().delete("records","account=? AND owner=? AND kind=? AND id=?",new String[]{""+a,""+owner,kind,digest(id)});}
    public synchronized void purge(int a){getWritableDatabase().delete("records","account=?",new String[]{""+a});try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);Enumeration<String> keys=ks.aliases();while(keys.hasMoreElements()){String k=keys.nextElement();if(k.startsWith("tp_aic_"+a+"_"))ks.deleteEntry(k);}}catch(Exception e){FileLog.e(e);}}
    public synchronized void clear(){for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)purge(a);}
    public synchronized long bytes(){return ApplicationLoader.applicationContext.getDatabasePath("tp_ai_center_v1.db").length();}
}
