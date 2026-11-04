package org.telegram.messenger.telepremium.cloud.vault;

import android.content.Context;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Transport-only staging for already encrypted TelePremium Vault envelopes.
 *
 * Telegram's standard SendMessagesHelper intentionally refuses paths inside
 * /data/data/<package>. Vault journal/backup files belong there, but the
 * temporary upload copy must be placed in the app-specific EXTERNAL cache.
 * No plaintext is staged in this location, and it is never a durable backup.
 */
public final class VaultTransportStaging {
    private static final byte[] MAGIC = new byte[] {'T','P','V','A','U','L','T','1'};
    private VaultTransportStaging() { }

    public static File createCiphertextTarget(Context context, String prefix) throws IOException {
        File external = context.getExternalCacheDir();
        if (external == null) throw new IOException("Vault upload cache unavailable (external app cache)");
        File outbox = new File(external, "telepremium_vault_outbox");
        if (!outbox.isDirectory() && !outbox.mkdirs()) {
            throw new IOException("Cannot create Vault upload directory");
        }
        File target = new File(outbox, VaultFileOps.randomFileName(prefix));
        if (AndroidUtilities.isInternalUri(Uri.fromFile(target))) {
            throw new IOException("Telegram cannot send from this Vault cache directory");
        }
        return target;
    }

    /** Copy ciphertext (never plaintext) from protected persistent storage. */
    public static File stageEncryptedIndex(Context context, File journal) throws IOException {
        if (journal == null || !journal.isFile() || journal.length() < 64) {
            throw new IOException("Vault encrypted index is missing");
        }
        File temporary = createCiphertextTarget(context, "idx_");
        try (FileInputStream in = new FileInputStream(journal);
             FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] header = new byte[MAGIC.length];
            new DataInputStream(in).readFully(header);
            if (!Arrays.equals(header, MAGIC)) {
                throw new IOException("Refusing to stage a non-Vault index");
            }
            out.write(header);
            byte[] bytes = new byte[64 * 1024];
            int n;
            while ((n = in.read(bytes)) != -1) {
                if (n != 0) out.write(bytes, 0, n);
            }
            out.getFD().sync();
            if (temporary.length() != journal.length()) throw new IOException("Vault staging copy truncated");
            return temporary;
        } catch (Exception error) {
            temporary.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Vault staging failed", error);
        }
    }
}
