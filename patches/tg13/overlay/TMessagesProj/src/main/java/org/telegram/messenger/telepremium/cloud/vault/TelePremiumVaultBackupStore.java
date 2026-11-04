package org.telegram.messenger.telepremium.cloud.vault;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Private same-device resilience cache. Stores encrypted bytes ONLY.
 * Does NOT protect against device loss, app uninstallation or compromise of
 * the underlying Android filesystem. True off-device resilience requires an
 * independently authorized replica (not provided by this local cache).
 */
public final class TelePremiumVaultBackupStore {
    private TelePremiumVaultBackupStore() {}

    private static File getDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "tpvault_encrypted_replicas_v1");
    }
    private static String requireId(String id) {
        try {return UUID.fromString(id).toString();}
        catch(Exception e){throw new IllegalArgumentException("Invalid Vault payload id",e);}
    }
    public static File find(Context context,String id) {
        try {
            File f=new File(getDir(context),requireId(id)+".tpv");
            return f.isFile()?f:null;
        }catch(Exception e){return null;}
    }
    public static void save(Context context,String id,File encrypted) throws Exception {
        if(encrypted==null || !encrypted.isFile() || encrypted.length()<=0)throw new IllegalArgumentException("Missing encrypted payload");
        File dir=getDir(context);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Cannot create Vault replica directory");
        File dest=new File(dir,requireId(id)+".tpv");
        File pending=new File(dir,requireId(id)+".partial");
        MessageDigest expected=MessageDigest.getInstance("SHA-256");
        MessageDigest observed=MessageDigest.getInstance("SHA-256");
        long bytes=0;
        try(FileInputStream in=new FileInputStream(encrypted);FileOutputStream out=new FileOutputStream(pending)){
            byte[] buf=new byte[65536];int n;
            while((n=in.read(buf))!=-1){
                if(n==0)continue;
                expected.update(buf,0,n);out.write(buf,0,n);observed.update(buf,0,n);bytes+=n;
            }
            out.getFD().sync();
        }catch(Exception e){pending.delete();throw e;}
        if(bytes!=encrypted.length() || !MessageDigest.isEqual(expected.digest(),observed.digest())){
            pending.delete();throw new SecurityException("Incomplete Vault encrypted replica");
        }
        if(dest.exists()&&!dest.delete()){pending.delete();throw new IllegalStateException("Cannot replace Vault replica");}
        if(!pending.renameTo(dest)){pending.delete();throw new IllegalStateException("Cannot commit Vault encrypted replica");}
    }
}
