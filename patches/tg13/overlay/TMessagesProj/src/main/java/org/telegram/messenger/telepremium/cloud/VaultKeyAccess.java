package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultKeyResolver;

import java.util.Collections;
import java.util.List;

/** Android-side glue: master key from the Keystore-wrapped store, folder keys from the session. */
public final class VaultKeyAccess {
    private VaultKeyAccess() {}

    private static final VaultKeyResolver.MasterSource MASTER =
            () -> TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);

    public static VaultKeyResolver.Key forNode(CloudNode node) throws Exception {
        VaultKeyResolver.Key key = VaultKeyResolver.forNode(node, MASTER, VaultFolderSession.shared());
        if (key.keyRef == null) TelePremiumVaultKeyManager.markKeyUsed(ApplicationLoader.applicationContext);
        else VaultFolderSession.shared().touch(key.keyRef);
        return key;
    }

    public static VaultKeyResolver.Key forUpload(int account, String parentId) throws Exception {
        CloudSnapshot snapshot = TelePremiumCloudManager.getInstance(account).getSnapshot();
        List<CloudNode> nodes = snapshot == null ? Collections.<CloudNode>emptyList() : snapshot.nodes;
        VaultKeyResolver.Key key = VaultKeyResolver.forUpload(nodes, parentId, MASTER, VaultFolderSession.shared());
        if (key.keyRef == null) TelePremiumVaultKeyManager.markKeyUsed(ApplicationLoader.applicationContext);
        else VaultFolderSession.shared().touch(key.keyRef);
        return key;
    }

    public static String lockedMessage(Exception e) {
        return e instanceof VaultKeyResolver.LockedException ? "Папка закрыта: введите пароль" : "Сначала разблокируйте защищённое облако";
    }
}
