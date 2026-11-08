package org.telegram.messenger.telepremium.cloud;

import android.net.Uri;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.VaultFileOps;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultBackupStore;
import org.telegram.messenger.telepremium.cloud.vault.VaultTransportStaging;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultPendingIndexStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;

public final class CloudPayloadRepository implements NotificationCenter.NotificationCenterDelegate {
    private final long ownerUserId;
    private final long epoch = TelePremiumCloudResetState.epoch();
    private static final java.util.Set<CloudPayloadRepository> LIVE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static void cancelAllForReset() {
        synchronized (LIVE) { for (CloudPayloadRepository operation : new java.util.ArrayList<>(LIVE)) operation.cancel(); }
    }
    private boolean active() { return TelePremiumCloudResetState.allowed(epoch) && ownerUserId == org.telegram.messenger.UserConfig.getInstance(account).getClientUserId(); }
    private final int account;
    private final TelePremiumCloudPeerManager peerManager;
    private final CloudTransferManager transferManager;
    private Pending pending;
    private boolean retryScheduled;

    public interface ImportCallback { void onComplete(CloudNode node, String error); }
    public interface DedupLookup { CloudNode find(String sha256); }
    private DedupLookup dedupLookup;
    public void setDedupLookup(DedupLookup lookup){this.dedupLookup=lookup;}

    private static final class Pending {
        CloudNode node;
        ImportCallback callback;
        long dialogId;
        Uri uri;
        String parentId;
        String displayName;
        String mime;
        String lastLocation;
        int retryCount;
        File encryptedPayload;
        String encryptedFilename;
        String encryptedCaption;
        /** Non-null while a file larger than the Telegram limit is uploaded part by part. */
        Multipart multipart;
        Pending(CloudNode node, ImportCallback callback, long dialogId, Uri uri, String parentId, String displayName, String mime){
            this.node=node; this.callback=callback; this.dialogId=dialogId; this.uri=uri;
            this.parentId=parentId; this.displayName=displayName; this.mime=mime;
        }
    }

    private static final class Multipart {
        int count;
        int index;
        long doneBytes;
        boolean vault;
        java.security.MessageDigest digest;
    }

    public CloudPayloadRepository(int account){
        this.account=account;
        this.ownerUserId=org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        synchronized (LIVE) { LIVE.add(this); }
        this.peerManager=TelePremiumCloudPeerManager.getVaultInstance(account);
        this.transferManager=CloudTransferManager.getInstance(account);
    }

    public void importUri(Uri uri, String parentId, String displayName, String mime, ImportCallback callback) {
        if (!active()) { callback.onComplete(null, TelePremiumCloudResetState.MESSAGE); return; }
        if(pending!=null){callback.onComplete(null,"Уже выполняется загрузка");return;}
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (!active()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
                long declaredSize=querySize(uri);
                long limit=maxUploadBytes(account);
                String safe=(displayName==null||displayName.trim().isEmpty())?"cloud-file":displayName.replaceAll("[\\\\/:*?\"<>|]","_");
                if(declaredSize>0 && declaredSize>limit){
                    // Larger than one Telegram document: store it as several balanced parts.
                    importMultipart(uri,parentId,safe,mime,declaredSize,limit,callback);
                    return;
                }
                // A secure file is encrypted BEFORE it reaches SendMessagesHelper.
                // Plaintext never goes to the Telegram service peer in Vault mode.
                // The standard Telegram document sender rejects app-internal /data/data paths.
                // Store ONLY encrypted bytes in the app-specific external cache for upload.
                File encrypted=VaultTransportStaging.createCiphertextTarget(
                        ApplicationLoader.applicationContext, "tpv_");
                byte[] master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
                VaultFileOps.UploadResult result;
                try(InputStream original=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)){
                    if(original==null)throw new IllegalStateException("Не удалось открыть выбранный файл");
                    TelePremiumVaultKeyManager.markKeyUsed(ApplicationLoader.applicationContext);
                    result=VaultFileOps.encryptToFile(original,encrypted,master);
                } catch(Exception e) { encrypted.delete(); throw e; }
                    finally { Arrays.fill(master,(byte)0); }
                    CloudNode secured=CloudNode.file(parentId,safe,mime,result.originalSize,result.originalSha256);
                    secured.encryptionVersion=1;
                    transferManager.beginUpload(uri.toString(),parentId,safe,mime,result.originalSize);
                    transferManager.updateProgress(0,result.encryptedSize);
                    CloudNode existing=dedupLookup==null?null:dedupLookup.find(secured.sha256);
                    if(existing!=null && existing.telegramDocumentId!=0 && existing.encryptionVersion==1){
                        secured.telegramDocumentId=existing.telegramDocumentId;
                        secured.telegramMessageId=existing.telegramMessageId;
                        secured.localBackupId=existing.localBackupId;
                        encrypted.delete();
                        AndroidUtilities.runOnUIThread(()->{
                            try {
                                if (!active()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
                                // Preserve the remote receipt even if the encrypted
                                // outbox write later fails (never re-upload blindly).
                                transferManager.markIndexing(secured.id,secured.telegramMessageId);
                                TelePremiumVaultPendingIndexStore.save(account,secured);
                                callback.onComplete(secured,null);
                            } catch(Exception e) {
                                FileLog.e(e);
                                transferManager.failRetryable("Не удалось сохранить защищённую запись загрузки");
                                callback.onComplete(null,"Не удалось сохранить защищённую запись загрузки");
                            }
                        });
                        return;
                    }
                    AndroidUtilities.runOnUIThread(()->peerManager.ensure((dialogId,error)->{
                        if (!active()) { encrypted.delete(); callback.onComplete(null, TelePremiumCloudResetState.MESSAGE); return; }
                        if(error!=null || dialogId==0){
                            encrypted.delete();
                            transferManager.waitingNetwork(error==null?"Cloud service peer unavailable":error);
                            callback.onComplete(null,error==null?"Cloud service peer unavailable":error);
                            return;
                        }
                        pending=new Pending(secured,callback,dialogId,uri,parentId,safe,mime);
                        pending.encryptedPayload=encrypted;
                        pending.encryptedFilename=encrypted.getName();
                        pending.encryptedCaption="#TPV3F:"+encrypted.getName().substring(4,16);
                        startObservers();transferManager.markUploading();sendToCloudPeer();
                    }));
            } catch(Exception e) {
                FileLog.e(e);
                String error=e.getMessage()==null || e.getMessage().isEmpty()
                        ? "Не удалось зашифровать и загрузить файл Vault" : e.getMessage();
                transferManager.failRetryable(error);
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(null,error));
            }
        });
    }

    /** Telegram document size limit for the account: 2000 MiB, 4000 MiB with Premium (minus Vault envelope overhead). */
    public static long maxUploadBytes(int account) {
        long mib = UserConfig.getInstance(account).isPremium() ? 4000L : 2000L;
        return (mib - 1L) * 1024L * 1024L; // 1 MiB margin for the Vault chunk tags and header
    }

    private static long querySize(Uri uri) {
        try (android.database.Cursor c = ApplicationLoader.applicationContext.getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return -1;
    }

    public void resumePending(ImportCallback callback) {
        if (!active()) { callback.onComplete(null, TelePremiumCloudResetState.MESSAGE); return; }
        // An active repository already owns its upload and reconnect timer.
        if (pending != null) return;
        CloudTransferManager.Task task=transferManager.getTask();
        if(task==null || task.type!=CloudTransferManager.TYPE_UPLOAD) return;
        if(task.state!=CloudTransferManager.WAITING_NETWORK && task.state!=CloudTransferManager.FAILED_RETRYABLE) return;
        if(task.uri==null || task.uri.trim().isEmpty()) return;
        try {
            importUri(Uri.parse(task.uri), task.parentId==null?"root":task.parentId,
                    task.fileName, task.mime, callback);
        } catch(Throwable e) {
            transferManager.failRetryable(e.getMessage());
        }
    }

    private void sendToCloudPeer() {
        if (!active()) return;
        Pending p=pending;
        if(p==null)return;
        if(p.encryptedPayload==null)return; // no plaintext upload path, including during part preparation
        SendMessagesHelper.prepareSendingDocument(
                AccountInstance.getInstance(account),
                p.encryptedPayload.getAbsolutePath(),
                p.encryptedPayload.getAbsolutePath(),
                null,
                p.encryptedCaption,
                "application/octet-stream",
                p.dialogId,
                null,
                null,
                null,
                null,
                null,
                false,
                0,
                null,
                null,
                false
        );
    }

    private void startObservers(){
        NotificationCenter nc=NotificationCenter.getInstance(account);
        nc.addObserver(this,NotificationCenter.messageReceivedByServer);
        nc.addObserver(this,NotificationCenter.fileUploadProgressChanged);
        nc.addObserver(this,NotificationCenter.fileUploadFailed);
    }

    private void stopObservers(){
        NotificationCenter nc=NotificationCenter.getInstance(account);
        nc.removeObserver(this,NotificationCenter.messageReceivedByServer);
        nc.removeObserver(this,NotificationCenter.fileUploadProgressChanged);
        nc.removeObserver(this,NotificationCenter.fileUploadFailed);
    }

    @Override public void didReceivedNotification(int id, int accountId, Object... args) {
        if (!active()) { cancel(); return; }
        if(pending==null || accountId!=account) return;
        try {
            if(id==NotificationCenter.fileUploadProgressChanged){
                String location=args.length>0&&args[0] instanceof String?(String)args[0]:null;
                long uploaded=args.length>1&&args[1] instanceof Long?(Long)args[1]:0;
                long total=args.length>2&&args[2] instanceof Long?(Long)args[2]:pending.node.size;
                if(!matchesPendingUpload(location,total))return;
                pending.lastLocation=location;
                if(pending.multipart!=null) transferManager.updateProgress(Math.min(pending.multipart.doneBytes+uploaded,pending.node.size),pending.node.size);
                else transferManager.updateProgress(uploaded,total);
                return;
            }
            if(id==NotificationCenter.fileUploadFailed){
                String location=args.length>0&&args[0] instanceof String?(String)args[0]:null;
                if(!matchesPendingUpload(location,pending.node.size))return;
                transferManager.waitingNetwork("Соединение прервано — докачка продолжится автоматически");
                scheduleRetry();
                return;
            }
            if(id!=NotificationCenter.messageReceivedByServer) return;
            Object obj=args.length>2?args[2]:null;
            if(!(obj instanceof TLRPC.Message)) return;
            TLRPC.Message message=(TLRPC.Message)obj;
            if(message.peer_id==null) return;
            long did = message.peer_id.channel_id!=0 ? -message.peer_id.channel_id :
                    message.peer_id.chat_id!=0 ? -message.peer_id.chat_id : message.peer_id.user_id;
            if(did!=pending.dialogId) return;
            if(message.media==null || message.media.document==null) return;
            if(pending.encryptedPayload!=null && !pending.encryptedCaption.equals(message.message))return;
            if(pending.multipart!=null){onMultipartPartSent(message);return;}
            pending.node.telegramMessageId=message.id;
            pending.node.telegramDocumentId=message.media.document.id;
            Pending done=pending; pending=null;
            stopObservers();
            if(done.encryptedPayload!=null){
                try{
                    TelePremiumVaultBackupStore.save(ApplicationLoader.applicationContext,done.node.id,done.encryptedPayload);
                    done.node.localBackupId=done.node.id;
                }catch(Exception backupError){FileLog.e(backupError);}
                finally{done.encryptedPayload.delete();}
            }
            if(done.encryptedPayload!=null) {
                transferManager.markIndexing(done.node.id,done.node.telegramMessageId);
                try {
                    // Telegram accepted the ciphertext. Preserve its authenticated
                    // metadata locally BEFORE committing a cloud-index generation.
                    TelePremiumVaultPendingIndexStore.save(account,done.node);
                } catch(Exception persistError) {
                    FileLog.e(persistError);
                    transferManager.failRetryable("Файл в канале, но защищённая запись не сохранена");
                    done.callback.onComplete(null,"Файл загружен в канал, но не удалось сохранить запись для восстановления");
                    return;
                }
            } else {
                transferManager.complete();
            }
            done.callback.onComplete(done.node,null);
        } catch(Exception e) {
            FileLog.e(e);
        }
    }

    private boolean matchesPendingUpload(String location,long total){
        if(pending==null)return false;
        if(pending.encryptedPayload!=null){
            if(location!=null && location.contains(pending.encryptedFilename))return true;
            return total>0 && total==pending.encryptedPayload.length();
        }
        if(location!=null && pending.node.name!=null && location.contains(pending.node.name))return true;
        if(total>0 && pending.node.size>0)return total==pending.node.size;
        return pending.lastLocation==null || location==null || location.equals(pending.lastLocation);
    }

    private void scheduleRetry(){
        if(retryScheduled || pending==null)return;
        retryScheduled=true;
        AndroidUtilities.runOnUIThread(() -> {
            retryScheduled=false;
            if(pending==null)return;
            if(ConnectionsManager.getInstance(account).getConnectionState()!=ConnectionsManager.ConnectionStateConnected){
                scheduleRetry();
                return;
            }
            if(pending.retryCount>=5){
                transferManager.failRetryable("Не удалось продолжить передачу автоматически");
                Pending done=pending; pending=null; stopObservers();
                if(done.encryptedPayload!=null)done.encryptedPayload.delete();
                done.callback.onComplete(null,"Не удалось продолжить передачу автоматически");
                return;
            }
            pending.retryCount++;
            transferManager.retrying();
            sendToCloudPeer();
        },2500);
    }

    // ------------------------------------------------------------------ multipart upload

    /** Number of parts for {@code size} bytes with a per-document {@code limit}. */
    public static int planPartCount(long size,long limit){
        return (int)Math.max(1,(size+limit-1)/limit);
    }

    /**
     * Balanced part size: the file is split into equally sized parts (rounded up to 1 MiB)
     * instead of N full parts plus a small tail, so every part streams and retries alike.
     */
    public static long planPartSize(long size,int count){
        long mib=1024L*1024L;
        long raw=(size+count-1)/count;
        return ((raw+mib-1)/mib)*mib;
    }

    private android.content.SharedPreferences multipartPrefs(){
        return ApplicationLoader.applicationContext.getSharedPreferences("tp_cloud_multipart_"+account,android.content.Context.MODE_PRIVATE);
    }

    private void saveMultipartState(Pending p){
        if (!active()) return;
        try{
            multipartPrefs().edit().putString("uri",p.uri.toString()).putLong("size",p.node.size)
                    .putBoolean("vault",p.multipart.vault).putString("node",p.node.toJson().toString()).apply();
        }catch(Exception e){FileLog.e(e);}
    }

    private CloudNode restoreMultipartState(Uri uri,long size,boolean vault){
        android.content.SharedPreferences prefs=multipartPrefs();
        if(!uri.toString().equals(prefs.getString("uri",null)) || prefs.getLong("size",-1)!=size || prefs.getBoolean("vault",false)!=vault)return null;
        try{
            CloudNode node=CloudNode.fromJson(new org.json.JSONObject(prefs.getString("node","{}")));
            return node.isMultipart()?node:null;
        }catch(Exception e){FileLog.e(e);return null;}
    }

    private void clearMultipartState(){if (!active()) return;multipartPrefs().edit().clear().apply();}

    /** Runs on the global queue. */
    private void importMultipart(Uri uri,String parentId,String safe,String mime,long size,long limit,ImportCallback callback) throws Exception {
        boolean vault=true;
        CloudNode node=restoreMultipartState(uri,size,vault);
        if(node==null){
            int count=planPartCount(size,limit);
            long partSize=planPartSize(size,count);
            node=CloudNode.file(parentId,safe,mime,size,null);
            node.allocateParts(count);
            long left=size;
            for(int i=0;i<count;i++){node.partSizes[i]=Math.min(partSize,left);left-=node.partSizes[i];}
            if(vault)node.encryptionVersion=1;
        }
        Multipart mp=new Multipart();
        mp.count=node.partMessageIds.length;
        mp.vault=vault;
        mp.digest=java.security.MessageDigest.getInstance("SHA-256");
        while(mp.index<mp.count && node.partMessageIds[mp.index]!=0){mp.doneBytes+=node.partSizes[mp.index];mp.index++;}
        transferManager.beginUpload(uri.toString(),parentId,safe,mime,size);
        transferManager.updateProgress(mp.doneBytes,size);
        // Resume: the whole-file hash still has to cover the parts already in the cloud.
        if(mp.doneBytes>0)hashRange(uri,0,mp.doneBytes,mp.digest);
        CloudNode target=node;
        AndroidUtilities.runOnUIThread(()->peerManager.ensure((dialogId,error)->{
            if (!active()) { callback.onComplete(null, TelePremiumCloudResetState.MESSAGE); return; }
            if(error!=null || dialogId==0){
                transferManager.waitingNetwork(error==null?"Cloud service peer unavailable":error);
                callback.onComplete(null,error==null?"Cloud service peer unavailable":error);
                return;
            }
            if(pending!=null){callback.onComplete(null,"Уже выполняется загрузка");return;}
            pending=new Pending(target,callback,dialogId,uri,parentId,safe,mime);
            pending.multipart=mp;
            saveMultipartState(pending);
            startObservers();
            transferManager.markUploading();
            stageNextPart();
        }));
    }

    private void stageNextPart(){
        if (!active()) return;
        Pending p=pending;
        if(p==null || p.multipart==null)return;
        Multipart mp=p.multipart;
        if(mp.index>=mp.count){finishMultipart();return;}
        Utilities.globalQueue.postRunnable(()->{
            File staging=null;
            try{
                if (!active()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
                int index=mp.index;
                long offset=0;
                for(int i=0;i<index;i++)offset+=p.node.partSizes[i];
                long length=p.node.partSizes[index];
                staging=createPartTarget(p.node,index,mp.count,mp.vault,length);
                java.security.MessageDigest partDigest=java.security.MessageDigest.getInstance("SHA-256");
                String partSha;
                try(android.os.ParcelFileDescriptor pfd=ApplicationLoader.applicationContext.getContentResolver().openFileDescriptor(p.uri,"r");
                    java.io.FileInputStream raw=new java.io.FileInputStream(pfd.getFileDescriptor())){
                    raw.getChannel().position(offset);
                    PartInputStream in=new PartInputStream(raw,length,mp.digest,partDigest);
                    byte[] master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
                    try {
                        TelePremiumVaultKeyManager.markKeyUsed(ApplicationLoader.applicationContext);
                        VaultFileOps.UploadResult result=VaultFileOps.encryptToFile(in,staging,master);
                        partSha=result.originalSha256;
                    } finally {Arrays.fill(master,(byte)0);}
                    if(in.count!=length)throw new java.io.IOException("Файл изменился во время загрузки");
                }
                p.node.partSha256[index]=partSha;
                File ready=staging;
                AndroidUtilities.runOnUIThread(()->{
                    if(!active() || pending!=p){ready.delete();return;}
                    p.encryptedPayload=ready;
                    p.encryptedFilename=ready.getName();
                    p.encryptedCaption="#TPPART:"+p.node.id.substring(0,8)+":"+(index+1)+"/"+mp.count;
                    sendToCloudPeer();
                });
            }catch(Exception e){
                FileLog.e(e);
                if(staging!=null)staging.delete();
                String message=e.getMessage()==null?"Не удалось подготовить часть файла":e.getMessage();
                AndroidUtilities.runOnUIThread(()->{
                    if(pending!=p)return;
                    pending=null;stopObservers();
                    transferManager.failRetryable(message);
                    p.callback.onComplete(null,message);
                });
            }
        });
    }

    private void onMultipartPartSent(TLRPC.Message message){
        if (!active()) return;
        Pending p=pending;
        Multipart mp=p.multipart;
        int index=mp.index;
        p.node.partMessageIds[index]=message.id;
        p.node.partDocumentIds[index]=message.media.document.id;
        if(index==0){p.node.telegramMessageId=message.id;p.node.telegramDocumentId=message.media.document.id;}
        if(p.encryptedPayload!=null)p.encryptedPayload.delete();
        p.encryptedPayload=null;p.encryptedFilename=null;p.encryptedCaption=null;p.lastLocation=null;
        p.retryCount=0;
        mp.doneBytes+=p.node.partSizes[index];
        mp.index++;
        saveMultipartState(p);
        transferManager.updateProgress(mp.doneBytes,p.node.size);
        stageNextPart();
    }

    private void finishMultipart(){
        if (!active()) return;
        Pending done=pending;pending=null;
        stopObservers();
        done.node.sha256=hex(done.multipart.digest.digest());
        clearMultipartState();
        if(done.multipart.vault){
            transferManager.markIndexing(done.node.id,done.node.telegramMessageId);
            try{
                TelePremiumVaultPendingIndexStore.save(account,done.node);
            }catch(Exception persistError){
                FileLog.e(persistError);
                transferManager.failRetryable("Файл в канале, но защищённая запись не сохранена");
                done.callback.onComplete(null,"Файл загружен в канал, но не удалось сохранить запись для восстановления");
                return;
            }
        }else{
            transferManager.complete();
        }
        done.callback.onComplete(done.node,null);
    }

    private File createPartTarget(CloudNode node,int index,int count,boolean vault,long length) throws java.io.IOException {
        File target=VaultTransportStaging.createCiphertextTarget(ApplicationLoader.applicationContext,"tpp_");
        File dir=target.getParentFile();
        long free=dir==null?Long.MAX_VALUE:dir.getUsableSpace();
        long need=length+64L*1024L*1024L;
        if(free<need)throw new java.io.IOException("Недостаточно места: для подготовки части нужно "+(need/(1024L*1024L))+" МБ свободных");
        return target;
    }

    private static void hashRange(Uri uri,long offset,long length,java.security.MessageDigest digest) throws java.io.IOException {
        try(android.os.ParcelFileDescriptor pfd=ApplicationLoader.applicationContext.getContentResolver().openFileDescriptor(uri,"r");
            java.io.FileInputStream raw=new java.io.FileInputStream(pfd.getFileDescriptor())){
            raw.getChannel().position(offset);
            PartInputStream in=new PartInputStream(raw,length,digest,null);
            byte[] b=new byte[1024*1024];
            while(in.read(b)>0){}
            if(in.count!=length)throw new java.io.IOException("Файл изменился — загрузку нужно начать заново");
        }
    }

    private static String hex(byte[] bytes){
        StringBuilder b=new StringBuilder(bytes.length*2);
        for(byte x:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",x));
        return b.toString();
    }

    /** Reads at most {@code limit} bytes and feeds them to the whole-file and part digests. */
    private static final class PartInputStream extends java.io.FilterInputStream {
        private long remaining;
        long count;
        private final java.security.MessageDigest whole, part;

        PartInputStream(InputStream in,long limit,java.security.MessageDigest whole,java.security.MessageDigest part){
            super(in);this.remaining=limit;this.whole=whole;this.part=part;
        }

        @Override public int read() throws java.io.IOException {
            byte[] one=new byte[1];
            int n=read(one,0,1);
            return n<=0?-1:(one[0]&0xff);
        }

        @Override public int read(byte[] b,int off,int len) throws java.io.IOException {
            if(remaining<=0)return -1;
            int n=super.read(b,off,(int)Math.min(len,remaining));
            if(n>0){
                remaining-=n;count+=n;
                if(whole!=null)whole.update(b,off,n);
                if(part!=null)part.update(b,off,n);
            }
            return n;
        }

        @Override public long skip(long n){return 0;}
        @Override public boolean markSupported(){return false;}
    }

    public File resolveDownloadedFile(TLRPC.Document document) {
        return document==null?null:FileLoader.getInstance(account).getPathToAttach(document,true);
    }

    public void cancel() {
        clearMultipartState();
        if(pending==null){transferManager.cancel();return;}
        Pending done=pending; pending=null;
        stopObservers();
        if(done.encryptedPayload!=null)done.encryptedPayload.delete();
        transferManager.cancel();
        done.callback.onComplete(null,"Отменено");
    }
}
