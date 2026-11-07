package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultIndexStore;
import java.util.List;

/** The cloud catalog is always an authenticated encrypted V3 document. */
public final class TelegramCloudMetadataStore {
    private final TelePremiumVaultIndexStore vaultStore;

    public interface LoadCallback { void onLoaded(CloudSnapshot snapshot, String error); }
    public interface SaveCallback { void onSaved(String error); }
    public interface HistoryCallback { void onLoaded(List<CloudSnapshot> snapshots, String error); }

    public TelegramCloudMetadataStore(int account) { vaultStore = new TelePremiumVaultIndexStore(account); }
    public void loadLatest(LoadCallback callback) { vaultStore.loadLatest(callback); }
    public void loadGenerations(HistoryCallback callback) { vaultStore.loadGenerations(callback); }
    public void save(CloudSnapshot snapshot, SaveCallback callback) {
        snapshot.updatedAt = System.currentTimeMillis();
        vaultStore.save(snapshot, callback);
    }
}
