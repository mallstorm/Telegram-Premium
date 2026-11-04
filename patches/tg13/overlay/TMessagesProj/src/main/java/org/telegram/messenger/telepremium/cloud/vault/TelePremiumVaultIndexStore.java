package org.telegram.messenger.telepremium.cloud.vault;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudPeerManager;
import org.telegram.messenger.telepremium.cloud.TelegramCloudMetadataStore;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import android.content.SharedPreferences;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Binary encrypted catalog transport for new V3 vault service channels.
 * No plaintext filenames, paths, thumbnails or directory JSON are written to
 * Telegram messages or captions. Captions only contain the fixed '#TPV3I'.
 * An encrypted local journal retains snapshots for accidental-deletion recovery.
 * This module does NOT prevent account owners from deleting channel messages.
 */
public final class TelePremiumVaultIndexStore {
    public static final String CAPTION="#TPV3I";
    private final int account;
    private final TelePremiumCloudPeerManager peer;
    private boolean saving;

    public TelePremiumVaultIndexStore(int account) {
        this.account=account;
        this.peer=TelePremiumCloudPeerManager.getVaultInstance(account);
    }

    private SharedPreferences checkpoint() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_index_checkpoint_"+account,0);
    }

    public void loadLatest(TelegramCloudMetadataStore.LoadCallback callback) {
        loadGenerations((list,error)->{
            if(error!=null){callback.onLoaded(null,error);return;}
            CloudSnapshot best=null;
            for(CloudSnapshot s:list){
                if(best==null || s.generation>best.generation ||
                        (s.generation==best.generation && s.updatedAt>best.updatedAt))best=s;
            }
            callback.onLoaded(best,null);
        });
    }

    public void loadGenerations(TelegramCloudMetadataStore.HistoryCallback callback) {
        byte[] master;
        try{master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);}
        catch(Exception e){callback.onLoaded(new ArrayList<>(),"Сначала разблокируйте защищённое облако");return;}
        peer.ensure((dialogId,peerError)->{
            if(peerError!=null || peer.getInputPeer()==null){
                loadLocalFallback(master,peerError==null?"Vault peer unavailable":peerError,callback);
                return;
            }
            TLRPC.TL_messages_getHistory req=new TLRPC.TL_messages_getHistory();
            req.peer=peer.getInputPeer();req.limit=100;
            ConnectionsManager.getInstance(account).sendRequest(req,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(error!=null || !(response instanceof TLRPC.messages_Messages)) {
                    loadLocalFallback(master,error==null?"История Vault недоступна":error.text,callback);
                    return;
                }
                ArrayList<TLRPC.Message> indexes=new ArrayList<>();
                for(TLRPC.Message m:((TLRPC.messages_Messages)response).messages) {
                    if(m!=null && m.media!=null && m.media.document!=null && m.message!=null && m.message.startsWith(CAPTION))
                        indexes.add(m);
                }
                if(indexes.isEmpty()){
                    Arrays.fill(master,(byte)0);
                    if(checkpoint().getLong("generation",0)>0) {
                        // History was wiped or rolled back; never initialize a new
                        // empty index here, show verified local data in read-only mode.
                        loadLocalFallback(null,"Индекс Vault отсутствует в канале — требуется восстановление",callback);
                    } else {
                        loadLocalFallback(null,"В канале ещё нет подтверждённого каталога Vault",(local,warning)->{
                            if(local.isEmpty())callback.onLoaded(new ArrayList<>(),null);
                            else callback.onLoaded(local,warning);
                        });
                    }
                    return;
                }
                indexes.sort((a,b)->Integer.compare(b.date,a.date));
                // Keep startup bounded; older encrypted journals remain on Telegram.
                if(indexes.size()>8)indexes.subList(8,indexes.size()).clear();
                loadNext(indexes,0,new ArrayList<>(),master,callback);
            }));
        });
    }

    private void loadNext(List<TLRPC.Message> messages,int index,ArrayList<CloudSnapshot> decoded,
                          byte[] master,TelegramCloudMetadataStore.HistoryCallback callback) {
        if(index>=messages.size()){
            Arrays.fill(master,(byte)0);
            long remote=0;for(CloudSnapshot s:decoded)remote=Math.max(remote,s.generation);
            if(checkpoint().getLong("generation",0)>remote){
                // Local journal may contain newer data. Do not report a clean sync.
                loadLocalFallback(null,"Возможен откат или удаление индекса Vault",(local,error)->{
                    if(local.isEmpty()) callback.onLoaded(decoded,error);
                    else { local.addAll(decoded); callback.onLoaded(local,error); }
                });
            } else {
                final long serverGeneration=remote;
                // A failed upload may have left a NEWER authenticated local journal.
                // Do not hide it behind a stale remote index or mark sync green.
                loadLocalFallback(null,"Локальный индекс новее серверного — требуется восстановление",(local,warning)->{
                    long localGen=0;
                    for(CloudSnapshot s:local)localGen=Math.max(localGen,s.generation);
                    if(localGen>serverGeneration) {
                        local.addAll(decoded);
                        callback.onLoaded(local,warning);
                    } else callback.onLoaded(decoded,null);
                });
            }
            return;
        }
        TLRPC.Message message=messages.get(index);
        fetchDocument(message,(file,error)->{
            if(error!=null){
                Arrays.fill(master,(byte)0);
                loadLocalFallback(null,error,(local,reason)->{
                    if(local.isEmpty()) callback.onLoaded(decoded,reason);
                    else { local.addAll(decoded); callback.onLoaded(local,reason); }
                });
                return;
            }
            Utilities.globalQueue.postRunnable(()->{
                try(FileInputStream encrypted=new FileInputStream(file);
                    ByteArrayOutputStream plain=new ByteArrayOutputStream()) {
                    TelePremiumVaultCrypto.decrypt(encrypted,plain,master,TelePremiumVaultCrypto.Purpose.INDEX);
                    CloudSnapshot snapshot=CloudSnapshot.decode(new String(plain.toByteArray(),StandardCharsets.UTF_8));
                    AndroidUtilities.runOnUIThread(()->{
                        decoded.add(snapshot);
                        loadNext(messages,index+1,decoded,master,callback);
                    });
                }catch(Exception e){
                    FileLog.e(e);Arrays.fill(master,(byte)0);
                    AndroidUtilities.runOnUIThread(()->loadLocalFallback(null,"Повреждён или не расшифровывается индекс Vault",(local,reason)->{
                        if(local.isEmpty()) callback.onLoaded(decoded,reason);
                        else { local.addAll(decoded); callback.onLoaded(local,reason); }
                    }));
                }
            });
        });
    }

    /**
     * Read a locally backed-up encrypted catalog ONLY after full GCM authentication.
     * Missing network is not a successful sync, and no new remote index is written.
     */
    private void loadLocalFallback(byte[] incomingMaster,String warning,
                                   TelegramCloudMetadataStore.HistoryCallback callback) {
        final byte[] master;
        if (incomingMaster != null) master = incomingMaster;
        else try {
            master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
        } catch (Exception e) {
            callback.onLoaded(new ArrayList<>(),warning); return;
        }
        Utilities.globalQueue.postRunnable(()->{
            ArrayList<CloudSnapshot> snapshots=new ArrayList<>();
            File dir=new File(ApplicationLoader.applicationContext.getFilesDir(),"tpvault_journal/a"+account);
            File[] journals=dir.listFiles((d,name)->name.startsWith("g") && name.endsWith(".tpv"));
            if(journals!=null) {
                Arrays.sort(journals,Comparator.comparingLong(File::lastModified).reversed());
                // Try older intact journals if the newest was partially written.
                for(int i=0;i<Math.min(journals.length,10);i++) {
                    try(FileInputStream in=new FileInputStream(journals[i]);
                        ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                        TelePremiumVaultCrypto.decrypt(in,out,master,TelePremiumVaultCrypto.Purpose.INDEX);
                        CloudSnapshot snapshot=CloudSnapshot.decode(new String(out.toByteArray(),StandardCharsets.UTF_8));
                        if(snapshot.ownerUserId==0 || snapshot.ownerUserId==org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()) {
                            snapshots.add(snapshot);
                            break;
                        }
                    }catch(Exception e){FileLog.e(e);}
                }
            }
            Arrays.fill(master,(byte)0);
            AndroidUtilities.runOnUIThread(()->callback.onLoaded(snapshots,warning));
        });
    }

    private interface FileCallback {void onReady(File file,String error);}
    private void fetchDocument(TLRPC.Message message,FileCallback callback) {
        TLRPC.Document d=message.media.document;
        File cached=FileLoader.getInstance(account).getPathToAttach(d,true);
        File regular=FileLoader.getInstance(account).getPathToAttach(d,false);
        if(cached!=null && cached.isFile()){callback.onReady(cached,null);return;}
        if(regular!=null && regular.isFile()){callback.onReady(regular,null);return;}
        final NotificationCenter nc=NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate listener=new NotificationCenter.NotificationCenterDelegate(){
            private boolean finished;
            private void finish(File file,String err){
                if(finished)return;finished=true;
                nc.removeObserver(this,NotificationCenter.fileLoaded);
                nc.removeObserver(this,NotificationCenter.fileLoadFailed);
                callback.onReady(file,err);
            }
            @Override public void didReceivedNotification(int id,int accountId,Object...args){
                if(accountId!=account)return;
                if(args.length==0 || !FileLoader.getAttachFileName(d).equals(args[0]))return;
                if(id==NotificationCenter.fileLoaded){
                    File ready=args.length>1 && args[1] instanceof File?(File)args[1]:FileLoader.getInstance(account).getPathToAttach(d,true);
                    finish(ready,ready!=null && ready.isFile()?null:"Encrypted Vault index missing");
                } else if(id==NotificationCenter.fileLoadFailed)finish(null,"Ошибка загрузки зашифрованного индекса Vault");
            }
        };
        nc.addObserver(listener,NotificationCenter.fileLoaded);
        nc.addObserver(listener,NotificationCenter.fileLoadFailed);
        FileLoader.getInstance(account).loadFile(d,message,FileLoader.PRIORITY_NORMAL,0);
        AndroidUtilities.runOnUIThread(()->{
            // A missing download must complete the callback with an error.
            // Trigger the same failure path as a Telegram file-load failure.
            listener.didReceivedNotification(NotificationCenter.fileLoadFailed,account,FileLoader.getAttachFileName(d));
        },120_000);
    }

    public void save(CloudSnapshot snapshot,TelegramCloudMetadataStore.SaveCallback callback) {
        if(saving){callback.onSaved("Vault index upload is already in progress");return;}
        final byte[] master;
        try{master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);}
        catch(Exception e){callback.onSaved("Сначала разблокируйте защищённое облако");return;}
        saving=true;
        Utilities.globalQueue.postRunnable(()->{
            try {
                // Prepare an encrypted journal record on private storage. Never
                // write plaintext snapshot JSON to a temporary file.
                byte[] raw=snapshot.encode().getBytes(StandardCharsets.UTF_8);
                File journal=journalFile(snapshot.generation);
                try(FileOutputStream out=new FileOutputStream(journal)){
                    TelePremiumVaultCrypto.encrypt(new ByteArrayInputStream(raw),out,master,TelePremiumVaultCrypto.Purpose.INDEX);
                    out.getFD().sync();
                } finally {Arrays.fill(raw,(byte)0);Arrays.fill(master,(byte)0);}
                AndroidUtilities.runOnUIThread(()->peer.ensure((dialogId,error)->{
                    if(error!=null || dialogId==0){saving=false;callback.onSaved(error==null?"Vault peer unavailable":error);return;}
                    sendEncryptedIndex(journal,snapshot.generation,dialogId,callback);
                }));
            } catch(Exception e) {
                FileLog.e(e);Arrays.fill(master,(byte)0);
                AndroidUtilities.runOnUIThread(()->{saving=false;callback.onSaved("Не удалось зашифровать каталог Vault");});
            }
        });
    }

    private File journalFile(long generation) throws Exception {
        File journalDir=new File(ApplicationLoader.applicationContext.getFilesDir(),"tpvault_journal/a"+account);
        if(!journalDir.isDirectory()&&!journalDir.mkdirs())throw new IllegalStateException("Cannot create private Vault journal");
        return new File(journalDir,"g"+generation+"_"+VaultFileOps.randomFileName("idx_"));
    }

    private static String captionFor(File file) {
        String filename=file.getName();
        int start=filename.indexOf("idx_");
        if(start<0 || start+16>filename.length()) throw new IllegalArgumentException("Invalid Vault index filename");
        return CAPTION+":"+filename.substring(start+4,start+16);
    }

    private void sendEncryptedIndex(File encrypted,long generation,long dialogId,
                                   TelegramCloudMetadataStore.SaveCallback callback) {
        final String expectedCaption=captionFor(encrypted);
        // Durable journal remains under filesDir; only already encrypted bytes are
        // copied to external app cache, which SendMessagesHelper permits uploading.
        final File uploadFile;
        try {
            uploadFile=VaultTransportStaging.stageEncryptedIndex(
                    ApplicationLoader.applicationContext, encrypted);
        } catch (Exception e) {
            FileLog.e(e);
            saving=false;
            callback.onSaved("Не удалось подготовить зашифрованный индекс для отправки");
            return;
        }
        NotificationCenter nc=NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate listener=new NotificationCenter.NotificationCenterDelegate(){
            private boolean done;
            private void finish(String error){
                if(done)return;done=true;saving=false;
                nc.removeObserver(this,NotificationCenter.messageReceivedByServer);
                uploadFile.delete();
                if(error==null){
                    checkpoint().edit().putLong("generation",generation).commit();
                    pruneJournal();
                }
                callback.onSaved(error);
            }
            @Override public void didReceivedNotification(int id,int accountId,Object...args){
                if(done || accountId!=account || id!=NotificationCenter.messageReceivedByServer)return;
                if(args.length<=2 || !(args[2] instanceof TLRPC.Message))return;
                TLRPC.Message m=(TLRPC.Message)args[2];
                if(m.media==null || m.media.document==null || m.peer_id==null || m.message==null)return;
                long did=m.peer_id.channel_id!=0?-m.peer_id.channel_id:
                        m.peer_id.chat_id!=0?-m.peer_id.chat_id:m.peer_id.user_id;
                if(did==dialogId && expectedCaption.equals(m.message))finish(null);
            }
        };
        nc.addObserver(listener,NotificationCenter.messageReceivedByServer);
        try {
            SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account),
                    uploadFile.getAbsolutePath(),uploadFile.getAbsolutePath(),null,
                    expectedCaption,"application/octet-stream",dialogId,null,null,null,null,null,false,0,null,null,false);
        } catch (RuntimeException e) {
            FileLog.e(e);
            nc.removeObserver(listener, NotificationCenter.messageReceivedByServer);
            uploadFile.delete();
            saving=false;
            callback.onSaved("Не удалось отправить зашифрованный индекс Vault");
            return;
        }
        // Never mark a successful index save before Telegram acknowledges it.
        AndroidUtilities.runOnUIThread(()->{
            if(saving) {
                nc.removeObserver(listener,NotificationCenter.messageReceivedByServer);
                uploadFile.delete();
                saving=false;callback.onSaved("Подтверждение загрузки индекса Vault не получено");
            }
        },120_000);
    }

    /**
     * Manual recovery action: re-publish the newest locally encrypted index
     * after verifying its AES-GCM tag and JSON. No plaintext is sent to Telegram.
     * It cannot recover payload bytes unless encrypted replicas also exist.
     */
    public void restoreLatestLocalIndex(TelegramCloudMetadataStore.SaveCallback callback) {
        if(saving){callback.onSaved("Синхронизация Vault выполняется");return;}
        final byte[] master;
        try{master=TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);}
        catch(Exception e){callback.onSaved("Сначала разблокируй Vault");return;}
        File dir=new File(ApplicationLoader.applicationContext.getFilesDir(),"tpvault_journal/a"+account);
        File[] files=dir.listFiles((d,n)->n.startsWith("g")&&n.endsWith(".tpv"));
        if(files==null || files.length==0){Arrays.fill(master,(byte)0);callback.onSaved("Локальный журнал Vault не найден");return;}
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        saving=true;
        Utilities.globalQueue.postRunnable(()->{
            try(FileInputStream in=new FileInputStream(files[0]);ByteArrayOutputStream raw=new ByteArrayOutputStream()){
                TelePremiumVaultCrypto.decrypt(in,raw,master,TelePremiumVaultCrypto.Purpose.INDEX);
                CloudSnapshot snapshot=CloudSnapshot.decode(new String(raw.toByteArray(),StandardCharsets.UTF_8));
                AndroidUtilities.runOnUIThread(()->peer.ensure((dialogId,error)->{
                    if(error!=null || dialogId==0){saving=false;callback.onSaved(error==null?"Vault channel unavailable":error);return;}
                    sendEncryptedIndex(files[0],snapshot.generation,dialogId,callback);
                }));
            }catch(Exception e){
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(()->{saving=false;callback.onSaved("Локальный индекс Vault повреждён");});
            }finally{Arrays.fill(master,(byte)0);}
        });
    }

    private void pruneJournal() {
        File dir=new File(ApplicationLoader.applicationContext.getFilesDir(),"tpvault_journal/a"+account);
        File[] files=dir.listFiles((d,n)->n.startsWith("g") && n.endsWith(".tpv"));
        if(files==null || files.length<=10)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        for(int i=10;i<files.length;i++)files[i].delete();
    }
}
