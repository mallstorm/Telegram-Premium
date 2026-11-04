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

    public interface Callback { void onState(int state, File file, String error); }

    public CloudDownloadManager(int account){
        this.account=account;
        this.peerManager=TelePremiumVaultKeyManager.isEnabled(ApplicationLoader.applicationContext)
                ? TelePremiumCloudPeerManager.getVaultInstance(account)
                : TelePremiumCloudPeerManager.getInstance(account);
        this.transferManager=CloudTransferManager.getInstance(account);
    }

    public void download(CloudNode node, Callback callback) {
        if(node==null || node.telegramMessageId==0){callback.onState(ERROR,null,"У файла нет Telegram payload");return;}
        peerManager.ensure((dialogId,error)->{
            if(error!=null || peerManager.getInputChannel()==null){
                if(!tryLocalVaultReplica(node,callback))callback.onState(ERROR,null,error==null?"Cloud service peer unavailable":error);
                return;
            }
            TLRPC.TL_channels_getMessages req=new TLRPC.TL_channels_getMessages();
            req.channel=peerManager.getInputChannel();
            req.id.add((int)node.telegramMessageId);
            ConnectionsManager.getInstance(account).sendRequest(req,(response,requestError)->AndroidUtilities.runOnUIThread(()->{
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
                    transferManager.beginDownload(node.id,node.name,node.size,node.telegramMessageId);
                    transferManager.complete();
                    deliverAvailable(node,existing,callback);
                    return;
                }
                activeNode=node;activeDocument=d;activeMessage=m;activeCallback=callback;node.offlineState=DOWNLOADING;
                transferManager.beginDownload(node.id,node.name,node.size>0?node.size:d.size,node.telegramMessageId);
                startObservers();
                FileLoader.getInstance(account).loadFile(d,m,FileLoader.PRIORITY_NORMAL,0);
                callback.onState(DOWNLOADING,null,null);
            }));
        });
    }

    /** A Telegram message may be gone, but the same-device encrypted replica can survive. */
    private boolean tryLocalVaultReplica(CloudNode node,Callback callback) {
        if(node==null || node.encryptionVersion!=1 || node.localBackupId==null)return false;
        File replica=TelePremiumVaultBackupStore.find(ApplicationLoader.applicationContext,node.localBackupId);
        if(replica==null)return false;
        transferManager.beginDownload(node.id,node.name,node.size,node.telegramMessageId);
        deliverAvailable(node,replica,callback);
        return true;
    }

    /** Never hand ciphertext to media players or external viewers as plaintext. */
    private void deliverAvailable(CloudNode node,File encryptedOrPlain,Callback callback) {
        if(node.encryptionVersion==0){callback.onState(OFFLINE_AVAILABLE,encryptedOrPlain,null);return;}
        if(node.encryptionVersion!=1){callback.onState(ERROR,null,"Unsupported Vault format");return;}
        try {
            // Require a currently unlocked session; no plaintext is made available otherwise.
            byte[] master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
            callback.onState(DOWNLOADING,null,null);
            Utilities.globalQueue.postRunnable(()->{
                try {
                    File folder=new File(ApplicationLoader.applicationContext.getCacheDir(),"tpvault_dec");
                    File file=VaultFileOps.decryptToVerifiedFile(encryptedOrPlain,folder,node.name,master,node.sha256,node.size);
                    AndroidUtilities.runOnUIThread(()->{
                        transferManager.complete();node.offlineState=OFFLINE_AVAILABLE;
                        callback.onState(OFFLINE_AVAILABLE,file,null);
                    });
                } catch(Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(()->{
                        transferManager.failRetryable("Vault authentication failed");node.offlineState=ERROR;
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
        if(activeDocument==null || activeCallback==null || accountId!=account)return;
        String expected=FileLoader.getAttachFileName(activeDocument);
        String actual=args.length>0 && args[0] instanceof String?(String)args[0]:null;
        if(actual!=null && !expected.equals(actual))return;
        if(id==NotificationCenter.fileLoadProgressChanged){
            long loaded=args.length>1 && args[1] instanceof Long?(Long)args[1]:0;
            long total=args.length>2 && args[2] instanceof Long?(Long)args[2]:(activeNode.size>0?activeNode.size:activeDocument.size);
            transferManager.updateProgress(loaded,total);
        } else if(id==NotificationCenter.fileLoaded){
            File f=args.length>1 && args[1] instanceof File?(File)args[1]:resolveFile(activeDocument);
            if(f!=null && f.exists()){
                activeNode.offlineState=OFFLINE_AVAILABLE;
                if(activeNode.encryptionVersion==1){
                    CloudNode node=activeNode;Callback cb=activeCallback;
                    activeNode=null;activeCallback=null;activeDocument=null;activeMessage=null;
                    stopObservers();retryScheduled=false;retryCount=0;
                    deliverAvailable(node,f,cb);
                } else {transferManager.complete();finish(OFFLINE_AVAILABLE,f,null);}
            }else{
                activeNode.offlineState=ERROR;
                transferManager.failRetryable("Скачанный файл не найден в хранилище");
                finish(ERROR,null,"Скачанный файл не найден в хранилище");
            }
        } else if(id==NotificationCenter.fileLoadFailed){
            activeNode.offlineState=DOWNLOADING;
            transferManager.waitingNetwork("Соединение прервано — скачивание продолжится автоматически");
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
                transferManager.failRetryable("Автоматическая докачка приостановлена");
                finish(ERROR,null,"Автоматическая докачка приостановлена");
                return;
            }
            retryCount++;
            transferManager.retrying();
            FileLoader.getInstance(account).loadFile(activeDocument,activeMessage,FileLoader.PRIORITY_NORMAL,0);
        },2500);
    }

    public void cancel(){
        if(activeDocument!=null)FileLoader.getInstance(account).cancelLoadFile(activeDocument);
        transferManager.cancel();
        if(activeCallback!=null)finish(ERROR,null,"Отменено");
    }

    private void finish(int state,File file,String error){
        Callback cb=activeCallback;activeCallback=null;activeNode=null;activeDocument=null;activeMessage=null;
        retryScheduled=false;retryCount=0;
        stopObservers();
        if(cb!=null)cb.onState(state,file,error);
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
