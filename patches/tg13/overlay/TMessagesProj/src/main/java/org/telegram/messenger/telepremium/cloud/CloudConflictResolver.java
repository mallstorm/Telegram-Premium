package org.telegram.messenger.telepremium.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CloudConflictResolver {
    public CloudSnapshot choose(CloudSnapshot local, CloudSnapshot remote) {
        if(local==null)return remote;if(remote==null)return local;
        if(!local.cloudId.equals(remote.cloudId))return remote;
        if(remote.generation>local.generation)return remote;
        if(local.generation>remote.generation)return local;
        return remote.updatedAt>=local.updatedAt?remote:local;
    }

    /** Merge independent edits instead of discarding the operation made on this device. */
    public CloudSnapshot merge(CloudSnapshot local, CloudSnapshot remote) {
        if (local == null) return remote;
        if (remote == null) return local;
        if (!local.cloudId.equals(remote.cloudId)) return remote;
        CloudSnapshot merged = new CloudSnapshot();
        merged.cloudId = remote.cloudId;
        merged.ownerUserId = remote.ownerUserId != 0 ? remote.ownerUserId : local.ownerUserId;
        merged.generation = Math.max(local.generation, remote.generation);
        merged.journalGeneration = Math.max(local.journalGeneration, remote.journalGeneration);
        merged.updatedAt = Math.max(local.updatedAt, remote.updatedAt);
        Map<String, CloudNode> byId = new LinkedHashMap<>();
        for (CloudNode node : remote.nodes) byId.put(node.id, node);
        for (CloudNode node : local.nodes) {
            CloudNode other = byId.get(node.id);
            if (other == null || changeTime(node) >= changeTime(other)) byId.put(node.id, node);
        }
        merged.nodes.addAll(byId.values());
        merged.mediaLibrary = CloudSnapshot.mergeMediaLibrary(local.mediaLibrary, remote.mediaLibrary);
        return merged;
    }

    private long changeTime(CloudNode node) {
        return Math.max(node.modifiedAt, node.deletedAt);
    }
    public boolean isConflict(CloudSnapshot local,CloudSnapshot remote){
        return local!=null&&remote!=null&&local.cloudId.equals(remote.cloudId)&&local.generation==remote.generation&&local.updatedAt!=remote.updatedAt;
    }
}

