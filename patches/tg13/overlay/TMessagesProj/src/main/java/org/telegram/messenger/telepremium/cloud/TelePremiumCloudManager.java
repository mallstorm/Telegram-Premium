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
                INSTANCES[account].ownerUserId != UserConfig.getInstance(account).getClientUserId())
            INSTANCES[account] = new TelePremiumCloudManager(account);
        return INSTANCES[account];
    }

    public interface Callback { void onComplete(String error); }
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
    public boolean isRemoteReady() { return remoteReady; }

    private boolean requireWritable(Callback callback) {
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

    public void bootstrap(Callback callback) {
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
        if (!remoteReady) { callback.onComplete(0, "Vault offline"); return; }
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
            for (Callback listener : listeners) listener.onComplete(error);
        });
    }

    private void bootstrapVault(Callback callback) {
        store.loadGenerations((generations, error) -> {
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
        return out;
    }

    public List<CloudNode> favorites() {
        if(snapshot==null) return Collections.emptyList();
        ArrayList<CloudNode> out=new ArrayList<>(); for(CloudNode n:snapshot.nodes) if(n.favorite && n.state==CloudNode.STATE_ACTIVE) out.add(n); return out;
    }

    public List<CloudNode> allFiles() {
        if(snapshot==null) return Collections.emptyList();
        ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes) if(n.type==CloudNode.TYPE_FILE && n.state==CloudNode.STATE_ACTIVE) out.add(n);
        return out;
    }

    public int countFiles(String folderId) {
        if(snapshot==null || folderId==null) return 0;
        return countFiles(folderId,new HashSet<>());
    }

    private int countFiles(String folderId,HashSet<String> visited) {
        if(!visited.add(folderId))return 0;
        int count=0;
        for(CloudNode n:snapshot.nodes){
            if(n.state!=CloudNode.STATE_ACTIVE || !folderId.equals(n.parentId))continue;
            if(n.type==CloudNode.TYPE_FILE)count++;else count+=countFiles(n.id,visited);
        }
        return count;
    }

    public List<CloudNode> search(String query) {
        if(snapshot==null || query==null) return Collections.emptyList();
        String q=query.trim().toLowerCase(); ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes) if(n.state==CloudNode.STATE_ACTIVE && !"root".equals(n.id) && n.name!=null && n.name.toLowerCase().contains(q)) out.add(n);
        return out;
    }

    public List<CloudNode> category(String kind) {
        if(snapshot==null)return Collections.emptyList(); ArrayList<CloudNode> out=new ArrayList<>();
        for(CloudNode n:snapshot.nodes){if(n.type!=CloudNode.TYPE_FILE||n.state!=CloudNode.STATE_ACTIVE)continue; String x=(n.name==null?"":n.name).toLowerCase(); String m=n.mimeType==null?"":n.mimeType;
            boolean hit=("images".equals(kind)&&m.startsWith("image/")) || ("video".equals(kind)&&m.startsWith("video/")) || ("audio".equals(kind)&&m.startsWith("audio/")) || ("apk".equals(kind)&&x.endsWith(".apk")) || ("archives".equals(kind)&&(x.endsWith(".zip")||x.endsWith(".rar")||x.endsWith(".7z")||x.endsWith(".tar")||x.endsWith(".gz")||x.endsWith(".tgz")||x.endsWith(".bz2")||x.endsWith(".xz"))) || ("documents".equals(kind)&&(m.startsWith("text/")||x.endsWith(".pdf")||x.endsWith(".doc")||x.endsWith(".docx")||x.endsWith(".xls")||x.endsWith(".xlsx")||x.endsWith(".ppt")||x.endsWith(".pptx")||x.endsWith(".epub")||x.endsWith(".rtf")));
            if(hit)out.add(n);}
        return out;
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

    private void commit(Callback callback) {
        if (!remoteReady && !publishingFirstIndex) {
            callback.onComplete("Vault offline: encrypted catalog is read-only");
            return;
        }
        commitMerged(callback, 0);
    }

    private void commitMerged(Callback callback, int attempt) {
        store.loadLatest((remote,error)->{
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
