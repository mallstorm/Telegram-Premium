package org.telegram.messenger.telepremium.cloud;
// Legacy V1 transport used TL_messages_deleteMessages; V2 deletes payload with TL_channels_deleteMessages.

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class TelePremiumCloudManager {
    private static final TelePremiumCloudManager[] INSTANCES = new TelePremiumCloudManager[UserConfig.MAX_ACCOUNT_COUNT];
    public static synchronized TelePremiumCloudManager getInstance(int account) {
        if (INSTANCES[account] == null ||
                INSTANCES[account].epoch != TelePremiumCloudResetState.epoch() ||
                INSTANCES[account].ownerUserId != UserConfig.getInstance(account).getClientUserId())
            INSTANCES[account] = new TelePremiumCloudManager(account);
        return INSTANCES[account];
    }

    public interface Callback { void onComplete(String error); }
    private final long epoch = TelePremiumCloudResetState.epoch();
    private boolean active() { return TelePremiumCloudResetState.allowed(epoch) && ownerUserId == UserConfig.getInstance(account).getClientUserId(); }
    private final int account;
    private final long ownerUserId;
    private final TelegramCloudMetadataStore store;
    private final TelePremiumCloudPeerManager peerManager;
    private CloudSnapshot snapshot;
    private final String clientId=UUID.randomUUID().toString();
    private final CloudConflictResolver conflictResolver=new CloudConflictResolver();
    // Deduplicate overlapping bootstraps from rapid account-switch / resume.
    private boolean bootstrapInFlight;
    private volatile boolean remoteReady;
    private boolean publishingFirstIndex;

    /** The encrypted local catalog is readable offline, but not writable. */
    public boolean isRemoteReady() { return active() && remoteReady; }

    private boolean requireWritable(Callback callback) {
        if (!active()) { callback.onComplete(TelePremiumCloudResetState.MESSAGE); return false; }
        if (!remoteReady) {
            callback.onComplete("Vault offline: wait for sync before changing files");
            return false;
        }
        return true;
    }
    private final ArrayList<Callback> bootstrapWaiters = new ArrayList<>();

    private TelePremiumCloudManager(int account) {
        this.account=account;
        this.ownerUserId=UserConfig.getInstance(account).getClientUserId();
        this.store=new TelegramCloudMetadataStore(account);
        this.peerManager=TelePremiumCloudPeerManager.getVaultInstance(account);
    }

    public CloudSnapshot getSnapshot() { return snapshot; }

    /** Lock-aware view of the catalog; every UI-facing query below goes through it. */
    public org.telegram.messenger.telepremium.cloud.vault.VaultVisibility.View view() {
        return org.telegram.messenger.telepremium.cloud.vault.VaultVisibility.view(snapshot == null ? java.util.Collections.<CloudNode>emptyList() : snapshot.nodes);
    }
    public boolean isVisible(CloudNode node) { return view().visible(node); }
    public boolean isProtected(CloudNode node) { return view().isProtected(node); }
    public CloudNode findNodeById(String id) { return findById(id); }

    public void bootstrap(Callback callback) {
        if (!active()) { if (callback != null) callback.onComplete(TelePremiumCloudResetState.MESSAGE); return; }
        synchronized (this) {
            if (callback != null) bootstrapWaiters.add(callback);
            if (bootstrapInFlight) return;
            bootstrapInFlight = true;
        }
        Callback finish = this::completeBootstrap;
        // V3 is the sole backend. Never inspect or migrate V1/V2 channels here.
        bootstrapVault(finish);
    }

    private static final String MAINTENANCE_PREFS = "telepremium_cloud_maintenance";
    private static final String KEY_TRASH_DAYS = "trash_retention_days";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    public static final int[] TRASH_RETENTION_CHOICES = {0, 7, 30, 90};

    private static android.content.SharedPreferences maintenancePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(MAINTENANCE_PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** Days a trashed item stays recoverable before it is removed from the catalog; 0 disables auto-clean. */
    public static int getTrashRetentionDays() {
        return maintenancePrefs().getInt(KEY_TRASH_DAYS, 30);
    }

    public static void setTrashRetentionDays(int days) {
        maintenancePrefs().edit().putInt(KEY_TRASH_DAYS, Math.max(0, days)).apply();
    }

    /** Trash entries whose retention period has expired (top-level items only). */
    public List<CloudNode> expiredTrash(long now, int retentionDays) {
        if (snapshot == null || retentionDays <= 0) return Collections.emptyList();
        ArrayList<CloudNode> out = new ArrayList<>();
        long limit = retentionDays * DAY_MS;
        for (CloudNode n : trashItems()) {
            if (n.deletedAt > 0 && now - n.deletedAt >= limit) out.add(n);
        }
        return out;
    }

    public interface PurgeCallback { void onComplete(int removed, String error); }

    /**
     * Removes expired trash entries from the catalog in one commit. Payload attachments are kept
     * (same anti-delete policy as {@link #deleteMetadataPermanently}).
     */
    public void purgeExpiredTrash(PurgeCallback callback) {
        int days = getTrashRetentionDays();
        List<CloudNode> expired = expiredTrash(System.currentTimeMillis(), days);
        if (expired.isEmpty()) { callback.onComplete(0, null); return; }
        if (!active() || !remoteReady) { callback.onComplete(0, "Vault offline"); return; }
        long now = System.currentTimeMillis();
        HashSet<Integer> payloadMessages = new HashSet<>();
        for (CloudNode node : expired) markDeleted(node, now, payloadMessages);
        commit(error -> callback.onComplete(error == null ? expired.size() : 0, error));
    }

    private void maybeAutoPurgeTrash() {
        if (snapshot == null || getTrashRetentionDays() <= 0 || (!remoteReady)) return;
        String key = "last_purge_" + account + "_v3";
        long now = System.currentTimeMillis();
        if (now - maintenancePrefs().getLong(key, 0L) < DAY_MS) return;
        maintenancePrefs().edit().putLong(key, now).apply();
        purgeExpiredTrash((removed, error) -> {
            if (error != null) org.telegram.messenger.FileLog.e("TelePremium trash auto-clean: " + error);
            else if (removed > 0) org.telegram.messenger.FileLog.d("TelePremium trash auto-clean removed " + removed + " entries");
        });
    }

    private void completeBootstrap(String error) {
        if(error==null&&remoteReady&&snapshot!=null&&CloudImageNames.compactGeneric(snapshot.nodes,System.currentTimeMillis())>0){
            commit(this::completeBootstrapListeners);return;
        }
        completeBootstrapListeners(error);
    }

    private void completeBootstrapListeners(String error) {
        if (error == null) {
            try { maybeAutoPurgeTrash(); } catch (Throwable e) { org.telegram.messenger.FileLog.e(e); }
        }
        final ArrayList<Callback> listeners;
        synchronized (this) {
            listeners = new ArrayList<>(bootstrapWaiters);
            bootstrapWaiters.clear();
            bootstrapInFlight = false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Callback listener : listeners) listener.onComplete(active() ? error : TelePremiumCloudResetState.MESSAGE);
        });
    }

    private void bootstrapVault(Callback callback) {
        store.loadGenerations((generations, error) -> {
            if (!active()) { callback.onComplete(TelePremiumCloudResetState.MESSAGE); return; }
            CloudSnapshot loaded=new CloudRecoveryEngine().recover(generations);
            if (loaded != null) {
                snapshot=loaded;
                remoteReady = error == null;
                // A validated local encrypted journal can be displayed offline,
                // but the warning must not be reported as successful sync.
                callback.onComplete(error);
                return;
            }
            if (error != null) { remoteReady=false; callback.onComplete(error); return; }
            snapshot=new CloudSnapshot();
            snapshot.clientId=clientId;
            TLRPC.User user=UserConfig.getInstance(account).getCurrentUser();
            snapshot.ownerUserId=user == null ? 0 : user.id;
            snapshot.generation=1;
            CloudNode root=CloudNode.folder(null, "Облако");
            root.id="root";
            snapshot.nodes.add(root);
            publishingFirstIndex = true;
            commit(commitError -> {
                publishingFirstIndex = false;
                remoteReady = commitError == null;
                callback.onComplete(commitError);
            });
        });
    }

    public List<CloudNode> children(String parentId, boolean trash) {
        if(snapshot==null) return Collections.emptyList();
        ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes) {
            boolean parent = parentId == null ? n.parentId == null : parentId.equals(n.parentId);
            if(parent && !"root".equals(n.id) && (trash ? n.state==CloudNode.STATE_TRASHED : n.state==CloudNode.STATE_ACTIVE)) out.add(n);
        }
        out=new ArrayList<>(view().filter(out));
        Collections.sort(out, Comparator.comparingInt((CloudNode n)->n.type).thenComparing(n->n.name.toLowerCase()));
        return out;
    }

    public CloudNode findByHash(String sha256) {
        if(snapshot==null || sha256==null) return null;
        for(CloudNode n:snapshot.nodes) if(n.type==CloudNode.TYPE_FILE && sha256.equals(n.sha256) && n.state==CloudNode.STATE_ACTIVE) return n;
        return null;
    }

    public void addFile(CloudNode node, Callback callback) {
        if(snapshot==null) { callback.onComplete("Cloud is not ready"); return; }
        if(!requireWritable(callback))return;
        // Idempotent retries: re-commit the same node, never duplicate its ID.
        for(CloudNode existing:snapshot.nodes) {
            if(node.id.equals(existing.id)) {
                if(existing.telegramMessageId!=node.telegramMessageId ||
                   existing.telegramDocumentId!=node.telegramDocumentId) {
                    callback.onComplete("Конфликт идентификатора файла Vault");return;
                }
                commit(callback);return;
            }
        }
        CloudImageNames.assignNew(node,snapshot.nodes);
        snapshot.nodes.add(node); commit(callback);
    }

    public void copy(CloudNode source, String parentId, Callback callback) {
        if(!requireWritable(callback))return;
        CloudNode n = source.type==CloudNode.TYPE_FOLDER ? CloudNode.folder(parentId, source.name + " (копия)") : CloudNode.file(parentId, source.name, source.mimeType, source.size, source.sha256);
        n.telegramMessageId=source.telegramMessageId; n.telegramDocumentId=source.telegramDocumentId; n.originalName=source.originalName;
        n.encryptionVersion=source.encryptionVersion;n.backupMessageId=source.backupMessageId;n.backupDocumentId=source.backupDocumentId;
        n.localBackupId=source.localBackupId;
        n.copyPartsFrom(source);
        snapshot.nodes.add(n); commit(callback);
    }

    public List<CloudNode> trashItems() {
        if(snapshot==null) return Collections.emptyList();
        ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes) {
            if(n.state!=CloudNode.STATE_TRASHED) continue;
            CloudNode parent=findById(n.parentId);
            if(parent==null || parent.state!=CloudNode.STATE_TRASHED) out.add(n);
        }
        return new ArrayList<>(view().filter(out));
    }

    public List<CloudNode> favorites() {
        if(snapshot==null) return Collections.emptyList();
        ArrayList<CloudNode> out=new ArrayList<>(); for(CloudNode n:snapshot.nodes) if(n.favorite && n.state==CloudNode.STATE_ACTIVE) out.add(n); return new ArrayList<>(view().filter(out));
    }

    public List<CloudNode> allFiles() {
        if(snapshot==null) return Collections.emptyList();
        ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes) if(n.type==CloudNode.TYPE_FILE && n.state==CloudNode.STATE_ACTIVE) out.add(n);
        return new ArrayList<>(view().filter(out));
    }

    public int countFiles(String folderId) {
        if(snapshot==null || folderId==null) return 0;
        if(view().contentsHidden(folderId))return 0;
        return countFiles(folderId,new HashSet<>());
    }

    private int countFiles(String folderId,HashSet<String> visited) {
        if(!visited.add(folderId))return 0;
        int count=0;
        for(CloudNode n:snapshot.nodes){
            if(n.state!=CloudNode.STATE_ACTIVE || !folderId.equals(n.parentId))continue;
            if(n.type==CloudNode.TYPE_FILE){if(n.keyRef==null||org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession.shared().isUnlocked(n.keyRef))count++;}
            else if(!(n.lock!=null&&!org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession.shared().isUnlocked(n.id)))count+=countFiles(n.id,visited);
        }
        return count;
    }

    public List<CloudNode> search(String query) {
        if(snapshot==null || query==null) return Collections.emptyList();
        String q=query.trim().toLowerCase(); ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes) if(n.state==CloudNode.STATE_ACTIVE && !"root".equals(n.id) && n.name!=null && n.name.toLowerCase().contains(q)) out.add(n);
        return new ArrayList<>(view().filter(out));
    }

    public List<CloudNode> category(String kind) {
        if(snapshot==null)return Collections.emptyList(); ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes){if(n.type!=CloudNode.TYPE_FILE||n.state!=CloudNode.STATE_ACTIVE)continue; String x=(n.name==null?"":n.name).toLowerCase(); String m=n.mimeType==null?"":n.mimeType;
            boolean hit=("images".equals(kind)&&CloudImageNames.isImage(n)) || ("video".equals(kind)&&m.startsWith("video/")) || ("audio".equals(kind)&&m.startsWith("audio/")) || ("apk".equals(kind)&&x.endsWith(".apk")) || ("archives".equals(kind)&&(x.endsWith(".zip")||x.endsWith(".rar")||x.endsWith(".7z")||x.endsWith(".tar")||x.endsWith(".gz")||x.endsWith(".tgz")||x.endsWith(".bz2")||x.endsWith(".xz"))) || ("documents".equals(kind)&&(m.startsWith("text/")||x.endsWith(".pdf")||x.endsWith(".doc")||x.endsWith(".docx")||x.endsWith(".xls")||x.endsWith(".xlsx")||x.endsWith(".ppt")||x.endsWith(".pptx")||x.endsWith(".epub")||x.endsWith(".rtf")));
            if(hit)out.add(n);}
        return new ArrayList<>(view().filter(out));
    }

    public int payloadReferenceCount(CloudNode node) {
        if(snapshot==null||node==null||node.telegramDocumentId==0)return 0; int count=0;
        for(CloudNode n:snapshot.nodes)if(n.telegramDocumentId==node.telegramDocumentId && n.state!=CloudNode.STATE_DELETED)count++; return count;
    }

    public void deleteMetadataPermanently(CloudNode node, Callback callback) {
        if(snapshot==null||node==null){callback.onComplete("Cloud is not ready");return;}
        if(!requireWritable(callback))return;
        long now=System.currentTimeMillis();
        HashSet<Integer> payloadMessages=new HashSet<>();
        markDeleted(node,now,payloadMessages);
        commit(error->{
            if(error!=null){callback.onComplete(error);return;}
            // Anti-delete default: tombstones remove entries from the UI, but the
            // encrypted (or legacy) Telegram attachment is retained for recovery.
            // Physical deletion must be an independent explicit purge workflow.
            callback.onComplete(null);
        });
    }

    public CloudIntegrityChecker.Result checkIntegrity(){return new CloudIntegrityChecker().check(snapshot);}

    public void createFolder(String parentId, String name, Callback callback) {
        if(snapshot==null) { callback.onComplete("Cloud is not ready"); return; }
        if(!requireWritable(callback))return;
        snapshot.nodes.add(CloudNode.folder(parentId, name));
        commit(callback);
    }

    public void rename(CloudNode node, String name, Callback callback) {
        if(!requireWritable(callback))return;
        node.name=name; node.modifiedAt=System.currentTimeMillis(); commit(callback);
    }

    public void move(CloudNode node, String parentId, Callback callback) {
        if(!requireWritable(callback))return;
        node.parentId=parentId; node.modifiedAt=System.currentTimeMillis(); commit(callback);
    }

    public void trash(CloudNode node, Callback callback) {
        if(!requireWritable(callback))return;
        long now=System.currentTimeMillis();
        setTreeState(node,CloudNode.STATE_TRASHED,now); commit(callback);
    }

    public void restore(CloudNode node, Callback callback) {
        if(!requireWritable(callback))return;
        long now=System.currentTimeMillis();
        CloudNode parent=findById(node.parentId);
        if(parent==null || parent.state!=CloudNode.STATE_ACTIVE) node.parentId="root";
        setTreeState(node,CloudNode.STATE_ACTIVE,now); commit(callback);
    }

    public void toggleFavorite(CloudNode node, Callback callback) {
        if(!requireWritable(callback))return;
        node.favorite=!node.favorite; node.modifiedAt=System.currentTimeMillis(); commit(callback);
    }

    // ---- Folder locks (FL1) -------------------------------------------------------------

    /** Sets or replaces the lock record of a folder (protect / change secret). */
    public void setFolderLock(String folderId, String record, Callback callback) {
        if(!requireWritable(callback))return;
        CloudNode f=findById(folderId);
        if(f==null||f.type!=CloudNode.TYPE_FOLDER||"root".equals(f.id)||record==null){callback.onComplete("Папку нельзя защитить");return;}
        String oldLock=f.lock; boolean oldOff=f.lockOff;
        f.lock=record; f.lockOff=false; f.modifiedAt=System.currentTimeMillis();
        commit(error->{ if(error!=null){f.lock=oldLock;f.lockOff=oldOff;} callback.onComplete(error); });
    }

    /** Starts removing protection: the record stays until every payload is back under the outer key. */
    public void beginRemoveFolderLock(String folderId, Callback callback) {
        if(!requireWritable(callback))return;
        CloudNode f=findById(folderId);
        if(f==null||f.lock==null){callback.onComplete("Защита не включена");return;}
        f.lockOff=true; f.modifiedAt=System.currentTimeMillis();
        commit(error->{ if(error!=null)f.lockOff=false; callback.onComplete(error); });
    }

    /** Drops lock records that no longer protect any payload. */
    public void clearFinishedLocks(Callback callback) {
        if(snapshot==null||!remoteReady){callback.onComplete(null);return;}
        java.util.List<CloudNode> done=org.telegram.messenger.telepremium.cloud.vault.VaultLockTree.finishedUnlocks(snapshot.nodes);
        if(done.isEmpty()){callback.onComplete(null);return;}
        for(CloudNode f:done){f.lock=null;f.lockOff=false;f.modifiedAt=System.currentTimeMillis();}
        commit(callback);
    }

    /**
     * Points {@code node} at a freshly uploaded payload (re-encrypted under another key) and, once the
     * catalog commit is acknowledged, physically deletes the old Telegram messages and local replica.
     */
    public void swapPayload(CloudNode node, CloudNode fresh, Callback callback) {
        if(!requireWritable(callback))return;
        if(node==null||fresh==null||fresh.telegramMessageId==0){callback.onComplete("Нет нового файла");return;}
        if(node.sha256!=null&&fresh.sha256!=null&&!node.sha256.equalsIgnoreCase(fresh.sha256)){callback.onComplete("Контрольная сумма не совпала");return;}
        final long oMsg=node.telegramMessageId,oDoc=node.telegramDocumentId; final String oBackup=node.localBackupId,oRef=node.keyRef;
        final long[] oPm=node.partMessageIds,oPd=node.partDocumentIds,oPs=node.partSizes; final String[] oPh=node.partSha256;
        HashSet<Integer> old=new HashSet<>();
        if(oMsg!=0)old.add((int)oMsg);
        if(oPm!=null)for(long m:oPm)if(m!=0)old.add((int)m);
        node.telegramMessageId=fresh.telegramMessageId; node.telegramDocumentId=fresh.telegramDocumentId;
        node.localBackupId=fresh.localBackupId; node.keyRef=fresh.keyRef; node.encryptionVersion=1;
        node.copyPartsFrom(fresh); node.modifiedAt=System.currentTimeMillis();
        commit(error->{
            if(error!=null){
                node.telegramMessageId=oMsg;node.telegramDocumentId=oDoc;node.localBackupId=oBackup;node.keyRef=oRef;
                node.partMessageIds=oPm;node.partDocumentIds=oPd;node.partSizes=oPs;node.partSha256=oPh;
                callback.onComplete(error);return;
            }
            if(oBackup!=null&&!oBackup.equals(node.localBackupId)){
                try{java.io.File replica=org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultBackupStore.find(ApplicationLoader.applicationContext,oBackup);if(replica!=null)replica.delete();}catch(Throwable t){org.telegram.messenger.FileLog.e(t);}
            }
            ArrayList<Integer> dead=new ArrayList<>();
            for(int id:old)if(!hasLiveReference(id))dead.add(id);
            // Old ciphertext must really disappear: it is encrypted under the previous key.
            deletePayloadMessages(dead,e->callback.onComplete(null));
        });
    }

    private CloudNode findById(String id) {
        if(snapshot==null||id==null)return null;
        for(CloudNode n:snapshot.nodes)if(id.equals(n.id))return n;
        return null;
    }

    private void setTreeState(CloudNode root,int state,long now) {
        root.state=state; root.modifiedAt=now; root.deletedAt=state==CloudNode.STATE_ACTIVE?0:now;
        if(root.type!=CloudNode.TYPE_FOLDER)return;
        for(CloudNode child:snapshot.nodes)if(root.id.equals(child.parentId))setTreeState(child,state,now);
    }

    private void markDeleted(CloudNode root,long now,HashSet<Integer> payloadMessages) {
        root.state=CloudNode.STATE_DELETED; root.favorite=false; root.modifiedAt=now; root.deletedAt=now;
        if(root.telegramMessageId!=0)payloadMessages.add((int)root.telegramMessageId);
        if(root.partMessageIds!=null)for(long part:root.partMessageIds)if(part!=0)payloadMessages.add((int)part);
        if(root.type!=CloudNode.TYPE_FOLDER)return;
        for(CloudNode child:snapshot.nodes)if(root.id.equals(child.parentId))markDeleted(child,now,payloadMessages);
    }

    private boolean hasLiveReference(int messageId) {
        for(CloudNode n:snapshot.nodes){
            if(n.state==CloudNode.STATE_DELETED)continue;
            if(n.telegramMessageId==messageId)return true;
            if(n.partMessageIds!=null)for(long part:n.partMessageIds)if(part==messageId)return true;
        }
        return false;
    }

    private void deletePayloadMessages(ArrayList<Integer> messageIds,Callback callback) {
        if(messageIds.isEmpty()){callback.onComplete(null);return;}
        peerManager.ensure((dialogId,error)->{
            if(error!=null || peerManager.getInputChannel()==null){callback.onComplete(error==null?"Cloud service peer unavailable":error);return;}
            TLRPC.TL_channels_deleteMessages req=new TLRPC.TL_channels_deleteMessages();
            req.channel=peerManager.getInputChannel();
            req.id.addAll(messageIds);
            ConnectionsManager.getInstance(account).sendRequest(req,(response,requestError)->
                    AndroidUtilities.runOnUIThread(()->callback.onComplete(requestError==null?null:requestError.text)));
        });
    }

    /** Persist settings inside the same authenticated V3 index, merged with file operations. */
    public void updateMediaLibrary(org.json.JSONArray records, Callback callback) {
        if (!requireWritable(callback)) return;
        if (snapshot == null) { callback.onComplete("Vault catalog is not ready"); return; }
        snapshot.mediaLibrary = CloudSnapshot.mergeMediaLibrary(snapshot.mediaLibrary, records);
        commit(callback);
    }

    private void commit(Callback callback) {
        if (!active()) { callback.onComplete(TelePremiumCloudResetState.MESSAGE); return; }
        if (!remoteReady && !publishingFirstIndex) {
            callback.onComplete("Vault offline: encrypted catalog is read-only");
            return;
        }
        commitMerged(callback, 0);
    }

    private void commitMerged(Callback callback, int attempt) {
        store.loadLatest((remote,error)->{
            if (!active()) { callback.onComplete(TelePremiumCloudResetState.MESSAGE); return; }
            if(error!=null){callback.onComplete(error);return;}
            if(remote!=null) snapshot=conflictResolver.merge(snapshot,remote);
            snapshot.generation=Math.max(snapshot.generation,remote==null?0:remote.generation)+1;
            snapshot.journalGeneration++; snapshot.clientId=clientId;
            store.save(snapshot, saveError -> {
                if (saveError != null && attempt < 2) commitMerged(callback, attempt + 1);
                else {
                    remoteReady=saveError==null;
                    callback.onComplete(saveError);
                }
            });
        });
    }
}
