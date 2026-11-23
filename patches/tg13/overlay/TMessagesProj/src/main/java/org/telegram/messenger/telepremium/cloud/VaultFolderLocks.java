package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.VaultAttemptLimiter;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderLock;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultSecretPolicy;

import java.util.Arrays;

/** Application-level operations on folder locks: protect, unlock, change secret, remove, settings. */
public final class VaultFolderLocks {
    private VaultFolderLocks() {}

    public interface Result { void onResult(String errorOrNull); }

    public static final long[] TIMEOUT_CHOICES_MS = {60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L};
    private static final String PREFS = "tp_folder_locks";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- settings -------------------------------------------------------------------------------
    public static long autoLockMs() { return prefs().getLong("auto_lock_ms", 5 * 60_000L); }
    public static void setAutoLockMs(long ms) {
        prefs().edit().putLong("auto_lock_ms", ms).apply();
        VaultFolderSession.shared().setMaxTtlMs(ms);
    }
    public static boolean secureScreen() { return prefs().getBoolean("secure_screen", true); }
    public static void setSecureScreen(boolean on) { prefs().edit().putBoolean("secure_screen", on).apply(); }
    public static boolean hideLockedNames() { return prefs().getBoolean("hide_names", false); }
    public static void setHideLockedNames(boolean on) { prefs().edit().putBoolean("hide_names", on).apply(); }

    /** Last used secret type for a folder; a UI hint only (the lock record does not reveal it). */
    public static VaultSecretPolicy.Kind kindHint(String lockId) {
        String v = prefs().getString("kind_" + lockId, null);
        for (VaultSecretPolicy.Kind k : VaultSecretPolicy.Kind.values()) if (k.name().equals(v)) return k;
        return VaultSecretPolicy.Kind.PASSWORD;
    }
    private static void setKindHint(String lockId, VaultSecretPolicy.Kind kind) {
        prefs().edit().putString("kind_" + lockId, kind.name()).apply();
    }

    private static final VaultAttemptLimiter LIMITER = new VaultAttemptLimiter(new VaultAttemptLimiter.Store() {
        @Override public long getLong(String key, long def) { return prefs().getLong("lim_" + key, def); }
        @Override public void putLong(String key, long value) { prefs().edit().putLong("lim_" + key, value).apply(); }
    }, System::currentTimeMillis);

    public static VaultAttemptLimiter limiter() { return LIMITER; }

    private static void ensureReady() {
        VaultSessionGuard.install();
        VaultFolderSession.shared().setMaxTtlMs(autoLockMs());
    }

    private static void post(Result r, String error) { AndroidUtilities.runOnUIThread(() -> r.onResult(error)); }

    public static boolean isOpen(CloudNode folder) { return folder != null && VaultFolderSession.shared().isUnlocked(folder.id); }

    public static void lockNow(CloudNode folder) {
        if (folder == null) return;
        VaultFolderSession.shared().lock(folder.id);
        VaultSessionGuard.wipeDecrypted();
    }

    // ---- protect ----------------------------------------------------------------------------------
    public static void protect(int account, CloudNode folder, VaultSecretPolicy.Kind kind, String raw, Result done) {
        ensureReady();
        if (folder == null || folder.type != CloudNode.TYPE_FOLDER || "root".equals(folder.id)) { done.onResult("Эту папку нельзя защитить"); return; }
        if (folder.lock != null) { done.onResult("Папка уже защищена"); return; }
        if (!VaultSecretPolicy.isAcceptable(VaultSecretPolicy.check(kind, raw))) { done.onResult("Секрет слишком простой"); return; }
        final TelePremiumCloudManager manager = TelePremiumCloudManager.getInstance(account);
        Utilities.globalQueue.postRunnable(() -> {
            byte[] master = null, folderKey = null;
            char[] secret = null;
            try {
                master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
                folderKey = VaultFolderLock.newFolderKey();
                secret = VaultSecretPolicy.toSecret(kind, raw);
                final String record = VaultFolderLock.seal(secret, folder.id, master, folderKey).encode();
                final byte[] fk = folderKey.clone();
                AndroidUtilities.runOnUIThread(() -> manager.setFolderLock(folder.id, record, error -> {
                    if (error == null) {
                        setKindHint(folder.id, kind);
                        VaultFolderSession.shared().put(folder.id, fk, autoLockMs());
                        VaultRekeyJob.kick(account, null); // existing files move under the folder key
                    }
                    Arrays.fill(fk, (byte) 0);
                    done.onResult(error);
                }));
            } catch (Exception e) {
                post(done, "Не удалось включить защиту: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                if (master != null) Arrays.fill(master, (byte) 0);
                if (folderKey != null) Arrays.fill(folderKey, (byte) 0);
                if (secret != null) Arrays.fill(secret, '\0');
            }
        });
    }

    // ---- unlock -----------------------------------------------------------------------------------
    /** Digits-only input is tried as PIN first, then as password (a long digit-only password is legal). */
    public static void unlock(int account, CloudNode folder, VaultSecretPolicy.Kind kind, String raw, Result done) {
        ensureReady();
        if (folder == null || folder.lock == null) { done.onResult("Папка не защищена"); return; }
        long wait = LIMITER.remainingMs(folder.id);
        if (wait > 0) { done.onResult("Слишком много попыток. Подождите " + Math.max(1, (wait + 999) / 1000) + " с"); return; }
        Utilities.globalQueue.postRunnable(() -> {
            byte[] master = null, key = null;
            try {
                master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
                VaultFolderLock.Record record = VaultFolderLock.Record.parse(folder.lock);
                VaultSecretPolicy.Kind[] order = kind == VaultSecretPolicy.Kind.PASSWORD && raw.matches("\\d+")
                        ? new VaultSecretPolicy.Kind[]{VaultSecretPolicy.Kind.PIN, VaultSecretPolicy.Kind.PASSWORD}
                        : new VaultSecretPolicy.Kind[]{kind};
                VaultFolderLock.WrongSecretException wrong = null;
                for (VaultSecretPolicy.Kind k : order) {
                    char[] secret = VaultSecretPolicy.toSecret(k, raw);
                    try { key = VaultFolderLock.open(record, secret, folder.id, master); setKindHint(folder.id, k); break; }
                    catch (VaultFolderLock.WrongSecretException e) { wrong = e; }
                    finally { Arrays.fill(secret, '\0'); }
                }
                if (key == null) {
                    LIMITER.recordFailure(folder.id);
                    int left = LIMITER.triesLeftBeforeDelay(folder.id);
                    long delay = LIMITER.remainingMs(folder.id);
                    post(done, delay > 0 ? "Неверно. Следующая попытка через " + Math.max(1, (delay + 999) / 1000) + " с"
                            : "Неверно. Осталось попыток без паузы: " + left);
                    return;
                }
                LIMITER.recordSuccess(folder.id);
                final byte[] fk = key.clone();
                AndroidUtilities.runOnUIThread(() -> {
                    VaultFolderSession.shared().put(folder.id, fk, autoLockMs());
                    Arrays.fill(fk, (byte) 0);
                    VaultRekeyJob.kick(account, null);
                    done.onResult(null);
                });
            } catch (Exception e) {
                post(done, "Не удалось открыть: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                if (master != null) Arrays.fill(master, (byte) 0);
                if (key != null) Arrays.fill(key, (byte) 0);
            }
        });
    }

    /** Opens the folder with a key obtained from the biometric Keystore unwrap; the key id is verified. */
    public static void unlockWithKey(int account, CloudNode folder, byte[] folderKey, Result done) {
        ensureReady();
        try {
            VaultFolderLock.Record record = VaultFolderLock.Record.parse(folder.lock);
            if (!record.keyId.equals(VaultFolderLock.keyId(folderKey))) { done.onResult("Ключ биометрии не подходит"); return; }
            VaultFolderSession.shared().put(folder.id, folderKey, autoLockMs());
            VaultRekeyJob.kick(account, null);
            done.onResult(null);
        } catch (Exception e) { done.onResult("Не удалось открыть"); }
        finally { Arrays.fill(folderKey, (byte) 0); }
    }

    // ---- change secret / remove ---------------------------------------------------------------------
    /** The folder must be open: the key in the session is re-sealed under the new secret. */
    public static void changeSecret(int account, CloudNode folder, VaultSecretPolicy.Kind kind, String raw, Result done) {
        ensureReady();
        byte[] fk = VaultFolderSession.shared().get(folder.id);
        if (fk == null) { done.onResult("Сначала откройте папку"); return; }
        if (!VaultSecretPolicy.isAcceptable(VaultSecretPolicy.check(kind, raw))) { Arrays.fill(fk, (byte) 0); done.onResult("Секрет слишком простой"); return; }
        final TelePremiumCloudManager manager = TelePremiumCloudManager.getInstance(account);
        Utilities.globalQueue.postRunnable(() -> {
            byte[] master = null; char[] secret = null;
            try {
                master = TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(ApplicationLoader.applicationContext);
                secret = VaultSecretPolicy.toSecret(kind, raw);
                final String record = VaultFolderLock.seal(secret, folder.id, master, fk).encode();
                AndroidUtilities.runOnUIThread(() -> manager.setFolderLock(folder.id, record, error -> {
                    if (error == null) setKindHint(folder.id, kind);
                    done.onResult(error);
                }));
            } catch (Exception e) {
                post(done, "Не удалось сменить секрет");
            } finally {
                Arrays.fill(fk, (byte) 0);
                if (master != null) Arrays.fill(master, (byte) 0);
                if (secret != null) Arrays.fill(secret, '\0');
            }
        });
    }

    public static void removeProtection(int account, CloudNode folder, Context context, Result done) {
        ensureReady();
        if (!isOpen(folder)) { done.onResult("Сначала откройте папку"); return; }
        TelePremiumCloudManager.getInstance(account).beginRemoveFolderLock(folder.id, error -> {
            if (error == null) {
                org.telegram.messenger.telepremium.cloud.vault.VaultFolderBiometric.forget(context, folder.id);
                VaultRekeyJob.kick(account, null);
            }
            done.onResult(error);
        });
    }
}
