package org.telegram.messenger.telepremium.cloud;
// Legacy V1 transport used TL_messages_getMessages; V2 resolves payload with TL_channels_getMessages.

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.VaultFileOps;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultBackupStore;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Arrays;

public final class CloudDownloadManager implements NotificationCenter.NotificationCenterDelegate {
    public static final int CLOUD_ONLY=0, DOWNLOADING=1, OFFLINE_AVAILABLE=2, ERROR=3;
    private final long ownerUserId;
    private final long epoch = TelePremiumCloudResetState.epoch();
    private static final java.util.Set<CloudDownloadManager> LIVE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static void cancelAllForReset() {
        synchronized (LIVE) { for (CloudDownloadManager operation : new java.util.ArrayList<>(LIVE)) operation.cancel(); }
    }
    private volatile boolean cancelled;
    private final boolean preview;
    private boolean active() { return !cancelled && TelePremiumCloudResetState.allowed(epoch) && ownerUserId == org.telegram.messenger.UserConfig.getInstance(account).getClientUserId(); }
    private final int account;
    private final TelePremiumCloudPeerManager peerManager;
    private final CloudTransferManager transferManager;
    private CloudNode activeNode;
    private TLRPC.Document activeDocument;
    private TLRPC.Message activeMessage;
    private TLRPC.Document lastResolvedDocument;
    private Callback activeCallback;
    private boolean retryScheduled;
    private int retryCount;
    // Multipart download state (a file stored as several Telegram documents).
    private CloudNode multiNode;
    private TLRPC.Document[] multiDocs;
    private TLRPC.Message[] multiMessages;
    private int multiIndex;
    private long multiDoneBytes;
    private long multiTotalBytes;

    public interface Callback { void onState(int state, File file, String error); }

    public CloudDownloadManager(int account){this(account,false);}

    /** Preview fetches never create, update or cancel user-visible transfer notifications. */
    public CloudDownloadManager(int account,boolean preview){
        this.preview=preview;
        this.account=account;
        this.ownerUserId=org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        synchronized (LIVE) { LIVE.add(this); }
        this.peerManager=TelePremiumVaultKeyManager.isEnabled(ApplicationLoader.applicationContext)
                ? TelePremiumCloudPeerManager.getVaultInstance(account)
                : TelePremiumCloudPeerManager.getInstance(account);
        this.transferManager=preview?null:CloudTransferManager.getInstance(account);
    }

    public void download(CloudNode node, Callback callback) {
        if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
        if(node==null || node.telegramMessageId==0){callback.onState(ERROR,null,"У файла нет Telegram payload");return;}
        if(preview && !node.isMultipart() && tryLocalVaultReplica(node,callback))return;
        if(node.isMultipart()){downloadMultipart(node,callback);return;}
        peerManager.ensure((dialogId,error)->{
            if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
            if(error!=null || peerManager.getInputChannel()==null){
                if(!tryLocalVaultReplica(node,callback))callback.onState(ERROR,null,error==null?"Cloud service peer unavailable":error);
                return;
            }
            TLRPC.TL_channels_getMessages req=new TLRPC.TL_channels_getMessages();
            req.channel=peerManager.getInputChannel();
            req.id.add((int)node.telegramMessageId);
            ConnectionsManager.getInstance(account).sendRequest(req,(response,requestError)->AndroidUtilities.runOnUIThread(()->{
                if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
                if(requestError!=null){
                    if(!tryLocalVaultReplica(node,callback))callback.onState(ERROR,null,requestError.text);
                    return;
                }
                if(!(response instanceof TLRPC.messages_Messages) || ((TLRPC.messages_Messages)response).messages.isEmpty()){
                    if(!tryLocalVaultReplica(node,callback))callback.onState(ERROR,null,"Файл не найден в облаке");
                    return;
                }
                TLRPC.Message m=((TLRPC.messages_Messages)response).messages.get(0);
                if(m.media==null || m.media.document==null){
                    if(!tryLocalVaultReplica(node,callback))callback.onState(ERROR,null,"Telegram document отсутствует");
                    return;
                }
                TLRPC.Document d=m.media.document;
                lastResolvedDocument=d;
                File existing=resolveFile(d);
                if(existing!=null && existing.exists()){
                    node.offlineState=OFFLINE_AVAILABLE;
                    if(transferManager!=null)transferManager.beginDownload(node.id,node.name,node.size,node.telegramMessageId);
                    if(transferManager!=null)transferManager.complete();
                    deliverAvailable(node,existing,callback);
                    return;
                }
                activeNode=node;activeDocument=d;activeMessage=m;activeCallback=callback;node.offlineState=DOWNLOADING;
                if(transferManager!=null)transferManager.beginDownload(node.id,node.name,node.size>0?node.size:d.size,node.telegramMessageId);
                startObservers();
                callback.onState(DOWNLOADING,null,null);
                FileLoader.getInstance(account).loadFile(d,m,FileLoader.PRIORITY_NORMAL,0);
            }));
        });
    }

    /** A Telegram message may be gone, but the same-device encrypted replica can survive. */
    private boolean tryLocalVaultReplica(CloudNode node,Callback callback) {
        if (!active()) return false;
        if(node==null || node.encryptionVersion!=1 || node.localBackupId==null)return false;
        File replica=TelePremiumVaultBackupStore.find(ApplicationLoader.applicationContext,node.localBackupId);
        if(replica==null)return false;
        if(transferManager!=null)transferManager.beginDownload(node.id,node.name,node.size,node.telegramMessageId);
        deliverAvailable(node,replica,callback);
        return true;
    }

    /** Never hand ciphertext to media players or external viewers as plaintext. */
    private void deliverAvailable(CloudNode node,File encryptedOrPlain,Callback callback) {
        if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
        if(node.encryptionVersion==0){callback.onState(OFFLINE_AVAILABLE,encryptedOrPlain,null);return;}
        if(node.encryptionVersion!=1){callback.onState(ERROR,null,"Unsupported Vault format");return;}
        try {
            // Require a currently unlocked session; no plaintext is made available otherwise.
            byte[] master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
            callback.onState(DOWNLOADING,null,null);
            Utilities.globalQueue.postRunnable(()->{
                try {
                    if (!active()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
                    File folder=new File(ApplicationLoader.applicationContext.getCacheDir(),"tpvault_dec");
                    File file=VaultFileOps.decryptToVerifiedFile(encryptedOrPlain,folder,node.name,master,node.sha256,node.size);
                    AndroidUtilities.runOnUIThread(()->{
                        if (!active()) { file.delete(); callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
                        if(transferManager!=null)transferManager.complete();node.offlineState=OFFLINE_AVAILABLE;
                        callback.onState(OFFLINE_AVAILABLE,file,null);
                    });
                } catch(Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(()->{
                        if(transferManager!=null)transferManager.failRetryable("Vault authentication failed");node.offlineState=ERROR;
                        callback.onState(ERROR,null,"Не удалось проверить подлинность Vault-файла");
                    });
                } finally {Arrays.fill(master,(byte)0);}
            });
        } catch(Exception e) {
            callback.onState(ERROR,null,"Сначала разблокируйте защищённое облако");
        }
    }

    public boolean releaseOffline(CloudNode node) {
        if(node==null || node.telegramMessageId==0)return false;
        if(node.isMultipart()){
            boolean ok=deleteIfPresent(joinedFile(node));
            if(ok)node.offlineState=CLOUD_ONLY;
            return ok;
        }
        TLRPC.Document d=(activeNode==node?activeDocument:lastResolvedDocument);
        if(d==null || d.id!=node.telegramDocumentId)return false;
        FileLoader.getInstance(account).cancelLoadFile(d);
        File regular=FileLoader.getInstance(account).getPathToAttach(d,false);
        File cached=FileLoader.getInstance(account).getPathToAttach(d,true);
        boolean ok=deleteIfPresent(regular);
        if(regular==null || !regular.equals(cached))ok=deleteIfPresent(cached)&&ok;
        if(ok) node.offlineState=CLOUD_ONLY;
        return ok;
    }

    private void startObservers(){
        NotificationCenter nc=NotificationCenter.getInstance(account);
        nc.addObserver(this,NotificationCenter.fileLoaded);
        nc.addObserver(this,NotificationCenter.fileLoadFailed);
        nc.addObserver(this,NotificationCenter.fileLoadProgressChanged);
    }

    private void stopObservers(){
        NotificationCenter nc=NotificationCenter.getInstance(account);
        nc.removeObserver(this,NotificationCenter.fileLoaded);
        nc.removeObserver(this,NotificationCenter.fileLoadFailed);
        nc.removeObserver(this,NotificationCenter.fileLoadProgressChanged);
    }

    @Override public void didReceivedNotification(int id,int accountId,Object... args) {
        if (!active()) { cancel(); return; }
        if(activeDocument==null || activeCallback==null || accountId!=account)return;
        String expected=FileLoader.getAttachFileName(activeDocument);
        String actual=args.length>0 && args[0] instanceof String?(String)args[0]:null;
        if(actual!=null && !expected.equals(actual))return;
        if(multiNode!=null){
            if(id==NotificationCenter.fileLoadProgressChanged){
                long loaded=args.length>1 && args[1] instanceof Long?(Long)args[1]:0;
                if(transferManager!=null)transferManager.updateProgress(multiDoneBytes+loaded,multiTotalBytes);
            } else if(id==NotificationCenter.fileLoaded){
                multiDoneBytes+=activeDocument.size;
                multiIndex++;
                retryCount=0;
                if(transferManager!=null)transferManager.updateProgress(multiDoneBytes,multiTotalBytes);
                nextMultipartPart();
            } else if(id==NotificationCenter.fileLoadFailed){
                if(transferManager!=null)transferManager.waitingNetwork("Соединение прервано — скачивание продолжится автоматически");
                scheduleRetry();
            }
            return;
        }
        if(id==NotificationCenter.fileLoadProgressChanged){
            long loaded=args.length>1 && args[1] instanceof Long?(Long)args[1]:0;
            long total=args.length>2 && args[2] instanceof Long?(Long)args[2]:(activeNode.size>0?activeNode.size:activeDocument.size);
            if(transferManager!=null)transferManager.updateProgress(loaded,total);
        } else if(id==NotificationCenter.fileLoaded){
            File f=args.length>1 && args[1] instanceof File?(File)args[1]:resolveFile(activeDocument);
            if(f!=null && f.exists()){
                activeNode.offlineState=OFFLINE_AVAILABLE;
                if(activeNode.encryptionVersion==1){
                    CloudNode node=activeNode;Callback cb=activeCallback;
                    activeNode=null;activeCallback=null;activeDocument=null;activeMessage=null;
                    stopObservers();retryScheduled=false;retryCount=0;
                    deliverAvailable(node,f,cb);
                } else {if(transferManager!=null)transferManager.complete();finish(OFFLINE_AVAILABLE,f,null);}
            }else{
                activeNode.offlineState=ERROR;
                if(transferManager!=null)transferManager.failRetryable("Скачанный файл не найден в хранилище");
                finish(ERROR,null,"Скачанный файл не найден в хранилище");
            }
        } else if(id==NotificationCenter.fileLoadFailed){
            activeNode.offlineState=DOWNLOADING;
            if(transferManager!=null)transferManager.waitingNetwork("Соединение прервано — скачивание продолжится автоматически");
            scheduleRetry();
        }
    }

    private void scheduleRetry(){
        if(retryScheduled || activeDocument==null)return;
        retryScheduled=true;
        AndroidUtilities.runOnUIThread(()->{
            retryScheduled=false;
            if(activeDocument==null || activeCallback==null)return;
            if(ConnectionsManager.getInstance(account).getConnectionState()!=ConnectionsManager.ConnectionStateConnected){
                scheduleRetry();
                return;
            }
            if(retryCount>=8){
                if(transferManager!=null)transferManager.failRetryable("Автоматическая докачка приостановлена");
                finish(ERROR,null,"Автоматическая докачка приостановлена");
                return;
            }
            retryCount++;
            if(transferManager!=null)transferManager.retrying();
            FileLoader.getInstance(account).loadFile(activeDocument,activeMessage,FileLoader.PRIORITY_NORMAL,0);
        },2500);
    }

    public void cancel(){
        cancelled=true;
        stopObservers();
        // FileLoader also serves explicit downloads; dismissing a preview must not cancel those.
        if(!preview && activeDocument!=null)FileLoader.getInstance(account).cancelLoadFile(activeDocument);
        if(transferManager!=null)transferManager.cancel();
        if(activeCallback!=null)finish(ERROR,null,"Отменено");
    }

    private void finish(int state,File file,String error){
        Callback cb=activeCallback;activeCallback=null;activeNode=null;activeDocument=null;activeMessage=null;
        multiNode=null;multiDocs=null;multiMessages=null;
        retryScheduled=false;retryCount=0;
        stopObservers();
        if(cb!=null)cb.onState(state,file,error);
    }

    // ------------------------------------------------------------------ multipart

    /** Joined plaintext of a multipart file (reused while it is complete). */
    public static File joinedFile(CloudNode node) {
        if(node==null)return null;
        File base=ApplicationLoader.applicationContext.getExternalCacheDir();
        if(base==null)base=ApplicationLoader.applicationContext.getCacheDir();
        File dir=new File(base,"telepremium_cloud_joined");
        String safe=(node.name==null?"file":node.name).replaceAll("[\\\\/:*?\"<>|]","_");
        return new File(dir,(node.id==null?"x":node.id.substring(0,Math.min(8,node.id.length())))+"_"+safe);
    }

    private void downloadMultipart(CloudNode node,Callback callback){
        if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
        if(node.encryptionVersion==0){
            File joined=joinedFile(node);
            if(joined!=null && joined.isFile() && joined.length()==node.size){
                node.offlineState=OFFLINE_AVAILABLE;
                callback.onState(OFFLINE_AVAILABLE,joined,null);
                return;
            }
        }
        peerManager.ensure((dialogId,error)->{
            if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
            if(error!=null || peerManager.getInputChannel()==null){callback.onState(ERROR,null,error==null?"Cloud service peer unavailable":error);return;}
            TLRPC.TL_channels_getMessages req=new TLRPC.TL_channels_getMessages();
            req.channel=peerManager.getInputChannel();
            for(long id:node.partMessageIds)req.id.add((int)id);
            ConnectionsManager.getInstance(account).sendRequest(req,(response,requestError)->AndroidUtilities.runOnUIThread(()->{
                if (!active()) { callback.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
                if(requestError!=null){callback.onState(ERROR,null,requestError.text);return;}
                if(!(response instanceof TLRPC.messages_Messages)){callback.onState(ERROR,null,"Файл не найден в облаке");return;}
                java.util.HashMap<Integer,TLRPC.Message> byId=new java.util.HashMap<>();
                for(TLRPC.Message m:((TLRPC.messages_Messages)response).messages)if(m!=null)byId.put(m.id,m);
                int count=node.partMessageIds.length;
                TLRPC.Document[] docs=new TLRPC.Document[count];
                TLRPC.Message[] messages=new TLRPC.Message[count];
                long total=0;
                for(int i=0;i<count;i++){
                    TLRPC.Message m=byId.get((int)node.partMessageIds[i]);
                    if(m==null || m.media==null || m.media.document==null){
                        callback.onState(ERROR,null,"Часть "+(i+1)+" из "+count+" отсутствует в облаке");
                        return;
                    }
                    messages[i]=m;docs[i]=m.media.document;total+=docs[i].size;
                }
                multiNode=node;multiDocs=docs;multiMessages=messages;multiIndex=0;multiDoneBytes=0;multiTotalBytes=total;
                activeNode=node;activeCallback=callback;node.offlineState=DOWNLOADING;
                if(transferManager!=null)transferManager.beginDownload(node.id,node.name,total,node.telegramMessageId);
                startObservers();
                callback.onState(DOWNLOADING,null,null);
                nextMultipartPart();
            }));
        });
    }

    private void nextMultipartPart(){
        if (!active()) { cancel(); return; }
        while(multiIndex<multiDocs.length){
            File f=resolveFile(multiDocs[multiIndex]);
            if(f!=null && f.exists()){
                multiDoneBytes+=multiDocs[multiIndex].size;
                multiIndex++;
                if(transferManager!=null)transferManager.updateProgress(multiDoneBytes,multiTotalBytes);
                continue;
            }
            activeDocument=multiDocs[multiIndex];activeMessage=multiMessages[multiIndex];
            FileLoader.getInstance(account).loadFile(activeDocument,activeMessage,FileLoader.PRIORITY_NORMAL,0);
            return;
        }
        assembleMultipart();
    }

    private void assembleMultipart(){
        CloudNode node=multiNode;Callback cb=activeCallback;TLRPC.Document[] docs=multiDocs;
        stopObservers();
        activeNode=null;activeCallback=null;activeDocument=null;activeMessage=null;
        multiNode=null;multiDocs=null;multiMessages=null;retryScheduled=false;retryCount=0;
        File[] parts=new File[docs.length];
        for(int i=0;i<docs.length;i++)parts[i]=resolveFile(docs[i]);
        byte[] master=null;
        if(node.encryptionVersion==1){
            try{master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);}
            catch(Exception e){cb.onState(ERROR,null,"Сначала разблокируйте защищённое облако");return;}
        }
        final byte[] key=master;
        Utilities.globalQueue.postRunnable(()->{
            File out=null;
            try{
                if (!active()) throw new IllegalStateException(TelePremiumCloudResetState.MESSAGE);
                java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
                if(key!=null){
                    File folder=new File(ApplicationLoader.applicationContext.getCacheDir(),"tpvault_dec");
                    if(!folder.isDirectory())folder.mkdirs();
                    out=new File(folder,"mp_"+node.id+"_"+(node.name==null?"file":node.name.replaceAll("[^a-zA-Z0-9._-]","_")));
                } else {
                    out=joinedFile(node);
                    out.getParentFile().mkdirs();
                }
                File tmp=new File(out.getPath()+".part");
                try(java.io.FileOutputStream os=new java.io.FileOutputStream(tmp)){
                    byte[] buffer=new byte[1024*1024];
                    for(int i=0;i<parts.length;i++){
                        File source=parts[i];
                        File plain=source;
                        if(key!=null){
                            String partSha=node.partSha256==null?null:node.partSha256[i];
                            long partSize=node.partSizes==null?0:node.partSizes[i];
                            plain=VaultFileOps.decryptToVerifiedFile(source,new File(ApplicationLoader.applicationContext.getCacheDir(),"tpvault_dec_parts"),"part"+i,key,partSha,partSize);
                        }
                        try(java.io.FileInputStream in=new java.io.FileInputStream(plain)){
                            int n;
                            while((n=in.read(buffer))>0){os.write(buffer,0,n);sha.update(buffer,0,n);}
                        } finally {
                            if(key!=null)plain.delete();
                        }
                        final long done=tmp.length();
                        AndroidUtilities.runOnUIThread(()->{if(transferManager!=null)transferManager.updateProgress(Math.min(done,node.size),node.size);});
                    }
                    os.getFD().sync();
                }
                if(node.size>0 && tmp.length()!=node.size)throw new java.io.IOException("Размер собранного файла не совпадает");
                String hex=bytesToHex(sha.digest());
                if(node.sha256!=null && node.sha256.length()==64 && !node.sha256.equalsIgnoreCase(hex))throw new SecurityException("Контрольная сумма собранного файла не совпадает");
                if(out.exists())out.delete();
                if(!tmp.renameTo(out))throw new java.io.IOException("Не удалось сохранить собранный файл");
                // The joined copy replaces the parts: free the space they take in the Telegram cache.
                if(key==null)for(int i=0;i<docs.length;i++)deleteIfPresent(parts[i]);
                File result=out;
                AndroidUtilities.runOnUIThread(()->{
                    if (!active()) { result.delete(); cb.onState(ERROR, null, TelePremiumCloudResetState.MESSAGE); return; }
                    if(transferManager!=null)transferManager.complete();node.offlineState=OFFLINE_AVAILABLE;
                    cb.onState(OFFLINE_AVAILABLE,result,null);
                });
            }catch(Exception e){
                FileLog.e(e);
                if(out!=null)new File(out.getPath()+".part").delete();
                String message=e instanceof SecurityException?e.getMessage():(key!=null?"Не удалось проверить подлинность Vault-файла":"Не удалось собрать файл из частей");
                AndroidUtilities.runOnUIThread(()->{
                    if(transferManager!=null)transferManager.failRetryable(message);node.offlineState=ERROR;
                    cb.onState(ERROR,null,message);
                });
            }finally{
                if(key!=null)Arrays.fill(key,(byte)0);
            }
        });
    }

    private static String bytesToHex(byte[] bytes){
        StringBuilder b=new StringBuilder(bytes.length*2);
        for(byte x:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",x));
        return b.toString();
    }

    private File resolveFile(TLRPC.Document document){
        File regular=FileLoader.getInstance(account).getPathToAttach(document,false);
        if(regular!=null && regular.exists())return regular;
        File cached=FileLoader.getInstance(account).getPathToAttach(document,true);
        if(cached!=null && cached.exists())return cached;
        return regular!=null?regular:cached;
    }

    private boolean deleteIfPresent(File file){return file==null || !file.exists() || file.delete();}
}
