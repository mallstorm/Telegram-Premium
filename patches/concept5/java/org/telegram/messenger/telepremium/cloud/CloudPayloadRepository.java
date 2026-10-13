package org.telegram.messenger.telepremium.cloud;

import android.net.Uri;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class CloudPayloadRepository implements NotificationCenter.NotificationCenterDelegate {
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
        CloudNode node; ImportCallback callback; long dialogId; Uri uri; String parentId; String displayName; String mime;
        String lastLocation; int retryCount;
        Pending(CloudNode node, ImportCallback callback, long dialogId, Uri uri, String parentId, String displayName, String mime){
            this.node=node; this.callback=callback; this.dialogId=dialogId; this.uri=uri; this.parentId=parentId; this.displayName=displayName; this.mime=mime;
        }
    }

    public CloudPayloadRepository(int account){
        this.account=account;
        this.peerManager=TelePremiumCloudPeerManager.getInstance(account);
        this.transferManager=CloudTransferManager.getInstance(account);
    }

    public void importUri(Uri uri, String parentId, String displayName, String mime, ImportCallback callback) {
        if(pending!=null){callback.onComplete(null,"Уже выполняется загрузка");return;}
        Utilities.globalQueue.postRunnable(() -> {
            File temp=null;
            try {
                String safe=(displayName==null||displayName.trim().isEmpty())?"cloud-file":displayName.replaceAll("[\\\\/:*?\"<>|]","_");
                temp=new File(ApplicationLoader.applicationContext.getCacheDir(),"tpcloud_"+System.nanoTime()+"_"+safe);
                try(InputStream in=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(temp)) {
                    if(in==null) throw new IllegalStateException("Не удалось открыть выбранный файл");
                    byte[] b=new byte[1024*1024]; int n;
                    while((n=in.read(b))>0) out.write(b,0,n);
                }
                CloudNode node=CloudNode.file(parentId,safe,mime,temp.length(),CloudFileHasher.sha256(temp));
                transferManager.beginUpload(uri.toString(), parentId, safe, mime, node.size);
                transferManager.updateProgress(0, node.size);
                CloudNode existing=dedupLookup==null?null:dedupLookup.find(node.sha256);
                if(existing!=null && existing.telegramDocumentId!=0){
                    node.telegramDocumentId=existing.telegramDocumentId;
                    node.telegramMessageId=existing.telegramMessageId;
                    temp.delete();
                    transferManager.complete();
                    AndroidUtilities.runOnUIThread(() -> callback.onComplete(node,null));
                    return;
                }
                temp.delete();
                AndroidUtilities.runOnUIThread(() -> peerManager.ensure((dialogId,error) -> {
                    if(error!=null || dialogId==0){
                        transferManager.waitingNetwork(error==null?"Cloud service peer unavailable":error);
                        callback.onComplete(null,error==null?"Cloud service peer unavailable":error);
                        return;
                    }
                    pending=new Pending(node,callback,dialogId,uri,parentId,safe,mime);
                    startObservers(); transferManager.markUploading(); sendToCloudPeer();
                }));
            } catch(Exception e) {
                if(temp!=null) temp.delete();
                FileLog.e(e);
                transferManager.failRetryable(e.getMessage());
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(null,e.getMessage()));
            }
        });
    }

    public void resumePending(ImportCallback callback) {
        CloudTransferManager.Task task=transferManager.getTask();
        if(task==null || task.type!=CloudTransferManager.TYPE_UPLOAD) return;
        if(task.state!=CloudTransferManager.WAITING_NETWORK && task.state!=CloudTransferManager.FAILED_RETRYABLE) return;
        if(task.uri==null || task.uri.trim().isEmpty()) return;
        try { importUri(Uri.parse(task.uri), task.parentId==null?"root":task.parentId, task.fileName, task.mime, callback); }
        catch(Throwable e) { transferManager.failRetryable(e.getMessage()); }
    }

    private void sendToCloudPeer() {
        Pending p=pending; if(p==null)return;
        SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), null, null, p.uri, p.node.name, p.node.mimeType,
                p.dialogId, null, null, null, null, null, false, 0, null, null, 0, false);
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
        if(pending==null || accountId!=account) return;
        try {
            if(id==NotificationCenter.fileUploadProgressChanged){
                String location=args.length>0&&args[0] instanceof String?(String)args[0]:null;
                long uploaded=args.length>1&&args[1] instanceof Long?(Long)args[1]:0;
                long total=args.length>2&&args[2] instanceof Long?(Long)args[2]:pending.node.size;
                if(!matchesPendingUpload(location,total))return;
                pending.lastLocation=location; transferManager.updateProgress(uploaded,total); return;
            }
            if(id==NotificationCenter.fileUploadFailed){
                String location=args.length>0&&args[0] instanceof String?(String)args[0]:null;
                if(!matchesPendingUpload(location,pending.node.size))return;
                transferManager.waitingNetwork("Соединение прервано — докачка продолжится автоматически");
                scheduleRetry(); return;
            }
            if(id!=NotificationCenter.messageReceivedByServer) return;
            Object obj=args.length>2?args[2]:null;
            if(!(obj instanceof TLRPC.Message)) return;
            TLRPC.Message message=(TLRPC.Message)obj;
            if(message.peer_id==null) return;
            long did = message.peer_id.channel_id!=0 ? -message.peer_id.channel_id : message.peer_id.chat_id!=0 ? -message.peer_id.chat_id : message.peer_id.user_id;
            if(did!=pending.dialogId) return;
            if(message.media==null || message.media.document==null) return;
            pending.node.telegramMessageId=message.id;
            pending.node.telegramDocumentId=message.media.document.id;
            Pending done=pending; pending=null; stopObservers(); transferManager.complete(); done.callback.onComplete(done.node,null);
        } catch(Exception e) { FileLog.e(e); }
    }

    private boolean matchesPendingUpload(String location,long total){
        if(pending==null)return false;
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
            if(ConnectionsManager.getInstance(account).getConnectionState()!=ConnectionsManager.ConnectionStateConnected){ scheduleRetry(); return; }
            if(pending.retryCount>=5){
                transferManager.failRetryable("Не удалось продолжить передачу автоматически");
                Pending done=pending; pending=null; stopObservers(); done.callback.onComplete(null,"Не удалось продолжить передачу автоматически"); return;
            }
            pending.retryCount++; transferManager.retrying(); sendToCloudPeer();
        },2500);
    }

    public File resolveDownloadedFile(TLRPC.Document document) { return document==null?null:FileLoader.getInstance(account).getPathToAttach(document,true); }
    public void cancel() {
        if(pending==null){transferManager.cancel();return;}
        Pending done=pending; pending=null; stopObservers(); transferManager.cancel(); done.callback.onComplete(null,"Отменено");
    }
}
