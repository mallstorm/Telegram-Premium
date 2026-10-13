package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;

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
        this.peerManager=TelePremiumCloudPeerManager.getInstance(account);
        this.transferManager=CloudTransferManager.getInstance(account);
    }

    public void download(CloudNode node, Callback callback) {
        if(node==null || node.telegramMessageId==0){callback.onState(ERROR,null,"У файла нет Telegram payload");return;}
        peerManager.ensure((dialogId,error)->{
            if(error!=null || peerManager.getInputChannel()==null){callback.onState(ERROR,null,error==null?"Cloud service peer unavailable":error);return;}
            TLRPC.TL_channels_getMessages req=new TLRPC.TL_channels_getMessages();
            req.channel=peerManager.getInputChannel();
            req.id.add((int)node.telegramMessageId);
            ConnectionsManager.getInstance(account).sendRequest(req,(response,requestError)->AndroidUtilities.runOnUIThread(()->{
                if(requestError!=null){callback.onState(ERROR,null,requestError.text);return;}
                if(!(response instanceof TLRPC.messages_Messages) || ((TLRPC.messages_Messages)response).messages.isEmpty()){
                    callback.onState(ERROR,null,"Файл не найден в облаке");return;
                }
                TLRPC.Message m=((TLRPC.messages_Messages)response).messages.get(0);
                if(m.media==null || m.media.document==null){callback.onState(ERROR,null,"Telegram document отсутствует");return;}
                TLRPC.Document d=m.media.document;
                lastResolvedDocument=d;
                File existing=resolveFile(d);
                if(existing!=null && existing.exists()){
                    node.offlineState=OFFLINE_AVAILABLE;
                    transferManager.beginDownload(node.id,node.name,node.size,node.telegramMessageId);
                    transferManager.complete();
                    callback.onState(OFFLINE_AVAILABLE,existing,null);
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
                transferManager.complete();
                finish(OFFLINE_AVAILABLE,f,null);
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
