"""Shared test doubles for the folder-lock glue that production Cloud classes reference.

Pure classes (VaultFolderSession, VaultFolderLock, VaultVisibility, VaultKeyResolver, VaultLockTree) are
compiled for real from the overlay; only the Android-bound glue is replaced here.
"""
PKG = "org/telegram/messenger/telepremium/cloud/"
VAULT_PURE = ["VaultFolderLock", "VaultFolderSession", "VaultLockTree", "VaultVisibility", "VaultKeyResolver"]

STUBS = {
    PKG + "VaultSessionGuard.java":
        'package org.telegram.messenger.telepremium.cloud;'
        'public class VaultSessionGuard{public static final String LOCKED_CACHE_DIR="tpvault_dec_locked";'
        'public static void install(){}public static void lockEverything(){}public static void wipeDecrypted(){}}',
}

KEY_STUBS = {
    PKG + "VaultKeyAccess.java":
        'package org.telegram.messenger.telepremium.cloud;'
        'import org.telegram.messenger.telepremium.cloud.vault.*;'
        'public class VaultKeyAccess{'
        'public static VaultKeyResolver.Key forNode(CloudNode n) throws Exception{return VaultKeyResolver.forNode(n,'
        '()->TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(org.telegram.messenger.ApplicationLoader.applicationContext),VaultFolderSession.shared());}'
        'public static VaultKeyResolver.Key forUpload(int a,String parent) throws Exception{return VaultKeyResolver.forPayload(null,'
        '()->TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(org.telegram.messenger.ApplicationLoader.applicationContext),VaultFolderSession.shared());}'
        'public static String lockedMessage(Exception e){return "locked";}}',
}

def pure_sources(prod, names=VAULT_PURE):
    return [prod / ("org/telegram/messenger/telepremium/cloud/vault/" + n + ".java") for n in names]
