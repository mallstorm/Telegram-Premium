package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TelePremiumCloudManager {
    private static final TelePremiumCloudManager[] INSTANCES = new TelePremiumCloudManager[UserConfig.MAX_ACCOUNT_COUNT];
    public static synchronized TelePremiumCloudManager getInstance(int account) {
        if (INSTANCES[account] == null) INSTANCES[account] = new TelePremiumCloudManager(account);
        return INSTANCES[account];
    }

    public interface Callback { void onComplete(String error); }
    private final int account;
    private final TelegramCloudMetadataStore store;
    private CloudSnapshot snapshot;

    private TelePremiumCloudManager(int account) {
        this.account=account; this.store=new TelegramCloudMetadataStore(account);
    }

    public CloudSnapshot getSnapshot() { return snapshot; }

    public void bootstrap(Callback callback) {
        store.loadLatest((loaded, error) -> {
            if (error != null) { callback.onComplete(error); return; }
            if (loaded != null) { snapshot=loaded; callback.onComplete(null); return; }
            snapshot=new CloudSnapshot();
            TLRPC.User user=UserConfig.getInstance(account).getCurrentUser();
            snapshot.ownerUserId=user == null ? 0 : user.id;
            snapshot.generation=1;
            CloudNode root=CloudNode.folder(null, "Облако");
            root.id="root";
            snapshot.nodes.add(root);
            commit(callback);
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

    public CloudNode findByHash(String sha256) {\n        if(snapshot==null || sha256==null) return null;\n        for(CloudNode n:snapshot.nodes) if(n.type==CloudNode.TYPE_FILE && sha256.equals(n.sha256) && n.state==CloudNode.STATE_ACTIVE) return n;\n        return null;\n    }\n\n    public void addFile(CloudNode node, Callback callback) {\n        if(snapshot==null) { callback.onComplete(\"Cloud is not ready\"); return; }\n        snapshot.nodes.add(node); commit(callback);\n    }\n\n    public void copy(CloudNode source, String parentId, Callback callback) {\n        CloudNode n = source.type==CloudNode.TYPE_FOLDER ? CloudNode.folder(parentId, source.name + \" (копия)\") : CloudNode.file(parentId, source.name, source.mimeType, source.size, source.sha256);\n        n.telegramMessageId=source.telegramMessageId; n.telegramDocumentId=source.telegramDocumentId; n.originalName=source.originalName;\n        snapshot.nodes.add(n); commit(callback);\n    }\n\n    public List<CloudNode> trashItems() {\n        if(snapshot==null) return Collections.emptyList();\n        ArrayList<CloudNode> out=new ArrayList<>(); for(CloudNode n:snapshot.nodes) if(n.state==CloudNode.STATE_TRASHED) out.add(n); return out;\n    }\n\n    public List<CloudNode> favorites() {\n        if(snapshot==null) return Collections.emptyList();\n        ArrayList<CloudNode> out=new ArrayList<>(); for(CloudNode n:snapshot.nodes) if(n.favorite && n.state==CloudNode.STATE_ACTIVE) out.add(n); return out;\n    }\n\n    public void createFolder(String parentId, String name, Callback callback) {
        if(snapshot==null) { callback.onComplete("Cloud is not ready"); return; }
        snapshot.nodes.add(CloudNode.folder(parentId, name));
        commit(callback);
    }

    public void rename(CloudNode node, String name, Callback callback) {
        node.name=name; node.modifiedAt=System.currentTimeMillis(); commit(callback);
    }

    public void move(CloudNode node, String parentId, Callback callback) {
        node.parentId=parentId; node.modifiedAt=System.currentTimeMillis(); commit(callback);
    }

    public void trash(CloudNode node, Callback callback) {
        node.state=CloudNode.STATE_TRASHED; node.deletedAt=System.currentTimeMillis(); commit(callback);
    }

    public void restore(CloudNode node, Callback callback) {
        node.state=CloudNode.STATE_ACTIVE; node.deletedAt=0; commit(callback);
    }

    public void toggleFavorite(CloudNode node, Callback callback) {
        node.favorite=!node.favorite; commit(callback);
    }

    private void commit(Callback callback) {
        snapshot.generation++;
        store.save(snapshot, error -> callback.onComplete(error));
    }
}
