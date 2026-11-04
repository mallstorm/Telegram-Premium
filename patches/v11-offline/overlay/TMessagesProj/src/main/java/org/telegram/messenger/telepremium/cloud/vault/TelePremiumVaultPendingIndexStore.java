package org.telegram.messenger.telepremium.cloud.vault;

import android.content.Context;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.cloud.CloudNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A durable, authenticated outbox for payloads acknowledged by Telegram but not
 * yet acknowledged by the encrypted index. Each account has its own private folder
 * and every record is AES-256-GCM encrypted with the existing single Vault master key.
 * Never expose raw CloudNode JSON outside internal memory.
 */
public final class TelePremiumVaultPendingIndexStore {
    private TelePremiumVaultPendingIndexStore() {}

    private static File directory(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT)
            throw new IllegalArgumentException("Invalid Telegram account");
        Context context=ApplicationLoader.applicationContext;
        return new File(context.getFilesDir(), "tpvault_pending_index_v1/a"+account);
    }

    private static String requireId(String id) {
        try {return UUID.fromString(id).toString();}
        catch(Exception e) {throw new IllegalArgumentException("Invalid Vault node UUID",e);}
    }

    /** Persist before telling the UI that the payload is finished. */
    public static synchronized void save(int account,CloudNode node) throws Exception {
        if(node==null || node.encryptionVersion!=1 || node.telegramMessageId<=0 || node.telegramDocumentId==0)
            throw new IllegalArgumentException("Remote encrypted payload is not acknowledged");
        String id=requireId(node.id);
        File dir=directory(account);
        if(!dir.isDirectory() && !dir.mkdirs())throw new IllegalStateException("Cannot create Vault pending journal");
        File target=new File(dir,id+".tpv");
        File staged=new File(dir,id+".partial");
        JSONObject envelope=new JSONObject();
        envelope.put("accountUserId",UserConfig.getInstance(account).getClientUserId());
        envelope.put("node",node.toJson());
        byte[] plain=envelope.toString().getBytes(StandardCharsets.UTF_8);
        byte[] master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
        try(FileOutputStream out=new FileOutputStream(staged)) {
            TelePremiumVaultCrypto.encrypt(new ByteArrayInputStream(plain),out,master,TelePremiumVaultCrypto.Purpose.INDEX);
            out.getFD().sync();
        } catch(Exception e) {staged.delete();throw e;}
        finally {Arrays.fill(master,(byte)0);Arrays.fill(plain,(byte)0);}
        if(!staged.renameTo(target)){staged.delete();throw new IllegalStateException("Cannot commit Vault pending journal");}
    }

    /** All returned records have authenticated GCM tags and match this Telegram user. */
    public static synchronized List<CloudNode> load(int account) throws Exception {
        List<CloudNode> result=new ArrayList<>();
        File dir=directory(account);
        File[] files=dir.listFiles((d,n)->n.endsWith(".tpv"));
        if(files==null || files.length==0)return result;
        byte[] master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
        try {
            for(File file:files) {
                try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    TelePremiumVaultCrypto.decrypt(in,out,master,TelePremiumVaultCrypto.Purpose.INDEX);
                    JSONObject payload=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                    if(payload.getLong("accountUserId")!=UserConfig.getInstance(account).getClientUserId())
                        throw new SecurityException("Vault pending account owner mismatch");
                    CloudNode node=CloudNode.fromJson(payload.getJSONObject("node"));
                    if(!requireId(node.id).equals(file.getName().substring(0,36)) || node.encryptionVersion!=1 ||
                       node.telegramMessageId<=0 || node.telegramDocumentId==0)
                        throw new SecurityException("Invalid acknowledged Vault pending record");
                    result.add(node);
                }
            }
        } finally {Arrays.fill(master,(byte)0);}
        return result;
    }

    public static synchronized void remove(int account,String nodeId) {
        new File(directory(account),requireId(nodeId)+".tpv").delete();
    }
}
