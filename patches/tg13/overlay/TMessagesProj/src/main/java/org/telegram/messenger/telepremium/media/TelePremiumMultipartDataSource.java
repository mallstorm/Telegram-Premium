package org.telegram.messenger.telepremium.media;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultCrypto;
import org.telegram.tgnet.TLRPC;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plays a Cloud file — one Telegram document or several byte-range parts, plain or Vault —
 * as one seekable stream.
 *
 * <ul>
 * <li><b>Sliding window.</b> Only the part being played and the next one stay on the device.
 *     A part the player has left is cancelled and its Telegram cache is deleted; when playback
 *     stops, everything streamed for the item is removed. Parts that were fully downloaded
 *     before playback (offline copies) are never touched.</li>
 * <li><b>Prefetch.</b> The next part starts downloading 64 MB before the boundary.</li>
 * <li><b>Vault.</b> Envelopes are made of independent 1 MiB AES-GCM chunks, so a position maps
 *     to one chunk: only that chunk is downloaded, authenticated and decrypted. Nothing
 *     unauthenticated reaches the player, and the plaintext never touches the disk.</li>
 * </ul>
 */
@OptIn(markerClass = UnstableApi.class)
public final class TelePremiumMultipartDataSource extends BaseDataSource {
    public static final String SCHEME = "tpmulti";

    private static final long PREFETCH_BYTES = 64L * 1024L * 1024L;
    private static final int HEADER = TelePremiumVaultCrypto.STREAM_HEADER_BYTES;
    private static final int CHUNK = TelePremiumVaultCrypto.STREAM_CHUNK_BYTES;
    private static final int TAG = TelePremiumVaultCrypto.STREAM_TAG_BYTES;
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tp-cloud-prefetch");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ registry

    private static final class Entry {
        final int account;
        final Uri[] parts;
        final TLRPC.Document[] documents;
        final long[] offsets;
        final long total;
        /** Complete on disk before playback started: an offline copy that must be kept. */
        final boolean[] keep;
        final boolean[] touched;
        final int[] readers;
        final byte[][] headers;
        volatile byte[] master;
        int focus;
        boolean closed;

        Entry(int account, Uri[] parts, TLRPC.Document[] documents, long[] sizes, byte[] master) {
            this.account = account;
            this.parts = parts;
            this.documents = documents;
            this.master = master;
            int n = parts.length;
            offsets = new long[n + 1];
            for (int i = 0; i < n; i++) offsets[i + 1] = offsets[i] + Math.max(0, sizes[i]);
            total = offsets[n];
            keep = new boolean[n];
            touched = new boolean[n];
            readers = new int[n];
            headers = new byte[n][];
            for (int i = 0; i < n; i++) keep[i] = !"tg".equals(parts[i].getScheme()) || isComplete(account, documents == null ? null : documents[i]);
        }

        boolean vault() { return master != null; }

        long plainSize(int i) { return offsets[i + 1] - offsets[i]; }

        int partAt(long position) {
            for (int i = 0; i < parts.length; i++) if (position < offsets[i + 1]) return i;
            return parts.length - 1;
        }

        synchronized void acquire(int i) {
            readers[i]++;
            touched[i] = true;
        }

        synchronized void release(int i) {
            if (readers[i] > 0) readers[i]--;
            evict();
        }

        synchronized void focus(int i) {
            focus = i;
            evict();
        }

        synchronized void close() {
            closed = true;
            byte[] key = master;
            master = null;
            if (key != null) Arrays.fill(key, (byte) 0);
            evict();
        }

        private void evict() {
            for (int j = 0; j < parts.length; j++) {
                if (keep[j] || !touched[j] || readers[j] > 0) continue;
                if (!closed && (j == focus || j == focus + 1)) continue;
                touched[j] = false;
                drop(account, documents == null ? null : documents[j]);
            }
        }
    }

    private static boolean isComplete(int account, TLRPC.Document document) {
        if (document == null) return false;
        try {
            FileLoader loader = FileLoader.getInstance(account);
            File a = loader.getPathToAttach(document, false);
            File b = loader.getPathToAttach(document, true);
            return (a != null && a.exists() && a.length() >= document.size) || (b != null && b.exists() && b.length() >= document.size);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Stops the Telegram download of a part and removes everything it cached. */
    private static void drop(int account, TLRPC.Document document) {
        if (document == null) return;
        try {
            FileLoader loader = FileLoader.getInstance(account);
            loader.cancelLoadFile(document, true);
            File a = loader.getPathToAttach(document, false);
            File b = loader.getPathToAttach(document, true);
            if (a != null && a.exists()) a.delete();
            if (b != null && !b.equals(a) && b.exists()) b.delete();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static final ConcurrentHashMap<String, Entry> REGISTRY = new ConcurrentHashMap<>();

    /** Plain multipart (kept for callers without document objects). */
    public static Uri register(Uri[] parts, long[] sizes) {
        return register(-1, parts, null, sizes, null);
    }

    /**
     * @param documents Telegram documents of the parts (for cache eviction), may be null
     * @param sizes     plaintext size of every part
     * @param master    Vault master key (copied) or null for plain files
     */
    public static Uri register(int account, Uri[] parts, @Nullable TLRPC.Document[] documents, long[] sizes, @Nullable byte[] master) {
        if (REGISTRY.size() > 8) {
            for (Entry stale : REGISTRY.values()) stale.close();
            REGISTRY.clear();
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        REGISTRY.put(key, new Entry(account, parts, documents, sizes, master == null ? null : master.clone()));
        return Uri.parse(SCHEME + "://" + key);
    }

    /** Playback of this stream is over: drop its cached parts and wipe the key. */
    public static void unregister(@Nullable Uri uri) {
        if (uri == null || !SCHEME.equals(uri.getScheme())) return;
        Entry entry = REGISTRY.remove(uri.getHost());
        if (entry != null) entry.close();
    }

    // ------------------------------------------------------------------ reader

    private Uri uri;
    private Entry entry;
    private int partIndex;
    private long position;
    private long bytesRemaining;
    private DataSource child;
    private int childIndex = -1;
    private boolean opened;

    private Future<DataSource> prefetch;
    private int prefetchIndex = -1;

    // Vault state of the current part
    private TelePremiumVaultCrypto.ChunkDecryptor decryptor;
    private long nextChunk;
    private byte[] plain;
    private int plainPos;

    public TelePremiumMultipartDataSource() {
        super(true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        entry = REGISTRY.get(uri.getHost());
        if (entry == null || entry.closed) throw new IOException("Cloud stream expired");
        transferInitializing(dataSpec);
        position = Math.max(0, dataSpec.position);
        if (position > entry.total) throw new IOException("Position beyond end of Cloud file");
        bytesRemaining = entry.total - position;
        if (dataSpec.length != C.LENGTH_UNSET) bytesRemaining = Math.min(bytesRemaining, dataSpec.length);
        partIndex = entry.partAt(position);
        entry.focus(partIndex);
        if (bytesRemaining > 0) openPart(position - entry.offsets[partIndex]);
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    private void openPart(long offsetInPart) throws IOException {
        closeChild();
        if (entry.vault()) {
            openVaultPart(offsetInPart);
            return;
        }
        DataSource ready = offsetInPart == 0 ? takePrefetch(partIndex) : null;
        if (ready != null) {
            child = ready;
            childIndex = partIndex;
        } else {
            discardPrefetch();
            openChild(partIndex, offsetInPart);
        }
    }

    private void openChild(int index, long offset) throws IOException {
        child = newRaw(entry.parts[index]);
        childIndex = index;
        entry.acquire(index);
        child.open(new DataSpec.Builder().setUri(entry.parts[index]).setPosition(offset).build());
    }

    private static DataSource newRaw(Uri part) {
        return "tg".equals(part.getScheme()) ? new FileStreamLoadOperation() : new FileDataSource();
    }

    // ------------------------------------------------------------------ Vault

    private void openVaultPart(long offsetInPart) throws IOException {
        long chunk = offsetInPart / CHUNK;
        int skip = (int) (offsetInPart % CHUNK);
        byte[] header = entry.headers[partIndex];
        boolean atZero;
        DataSource ready = chunk == 0 ? takePrefetch(partIndex) : null;
        if (ready != null) {
            child = ready;
            childIndex = partIndex;
            atZero = true;
        } else {
            discardPrefetch();
            atZero = header == null || chunk == 0;
            openChild(partIndex, atZero ? 0 : TelePremiumVaultCrypto.chunkOffset(chunk));
        }
        if (atZero) {
            byte[] read = new byte[HEADER];
            readFully(child, read, HEADER);
            if (header == null) entry.headers[partIndex] = header = read;
            else if (!Arrays.equals(header, read)) throw new IOException("Vault header changed");
            if (chunk > 0) {
                closeChild();
                openChild(partIndex, TelePremiumVaultCrypto.chunkOffset(chunk));
            }
        }
        byte[] key = entry.master;
        if (key == null) throw new IOException("Vault is locked");
        try {
            decryptor = TelePremiumVaultCrypto.ChunkDecryptor.open(header, key, TelePremiumVaultCrypto.Purpose.FILE);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Vault key error", e);
        }
        nextChunk = chunk;
        plain = null;
        plainPos = 0;
        if (!loadChunk()) throw new IOException("Vault chunk out of range");
        plainPos = skip;
    }

    /** Reads, authenticates and decrypts the next chunk of the current part. */
    private boolean loadChunk() throws IOException {
        long expected = Math.min(CHUNK, entry.plainSize(partIndex) - nextChunk * CHUNK);
        if (expected <= 0) return false;
        byte[] len = new byte[4];
        readFully(child, len, 4);
        int size = (len[0] & 0xff) << 24 | (len[1] & 0xff) << 16 | (len[2] & 0xff) << 8 | (len[3] & 0xff);
        if (size != expected + TAG) throw new IOException("Vault chunk has an unexpected length");
        byte[] cipherText = new byte[size];
        readFully(child, cipherText, size);
        if (plain != null) Arrays.fill(plain, (byte) 0);
        try {
            plain = decryptor.decryptChunk(nextChunk, cipherText, size);
            if (plain.length != expected) throw new IOException("Vault chunk has an unexpected length");
            nextChunk++;
            if (nextChunk * CHUNK >= entry.plainSize(partIndex)) {
                // The last data chunk must be followed by the authenticated terminator.
                readFully(child, len, 4);
                int t = (len[0] & 0xff) << 24 | (len[1] & 0xff) << 16 | (len[2] & 0xff) << 8 | (len[3] & 0xff);
                if (t != TAG) throw new IOException("Vault terminator missing");
                byte[] terminator = new byte[TAG];
                readFully(child, terminator, TAG);
                decryptor.verifyTerminator(nextChunk, terminator, TAG);
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Vault chunk authentication failed", e);
        }
        plainPos = 0;
        return true;
    }

    private static void readFully(DataSource source, byte[] buffer, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = source.read(buffer, done, length - done);
            if (n == C.RESULT_END_OF_INPUT) throw new EOFException("Cloud part ended early");
            done += n;
        }
    }

    // ------------------------------------------------------------------ reading

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        while (true) {
            if (child == null) return C.RESULT_END_OF_INPUT;
            int want = (int) Math.min(length, bytesRemaining);
            int n;
            if (entry.vault()) {
                if (plain == null || plainPos >= plain.length) {
                    if (!loadChunk()) n = C.RESULT_END_OF_INPUT;
                    else continue;
                } else {
                    n = Math.min(want, plain.length - plainPos);
                    System.arraycopy(plain, plainPos, buffer, offset, n);
                    plainPos += n;
                }
            } else {
                n = child.read(buffer, offset, want);
            }
            if (n == C.RESULT_END_OF_INPUT) {
                if (partIndex + 1 >= entry.parts.length) return C.RESULT_END_OF_INPUT;
                partIndex++;
                position = entry.offsets[partIndex];
                entry.focus(partIndex); // the part just finished leaves the window and is deleted
                openPart(0);
                continue;
            }
            if (n > 0) {
                bytesRemaining -= n;
                position += n;
                bytesTransferred(n);
                maybePrefetch();
            }
            return n;
        }
    }

    /**
     * Opens the next Telegram part in the background shortly before the current one ends, so its
     * download is already running when the player crosses the boundary (no rebuffer at the joint).
     */
    private void maybePrefetch() {
        int next = partIndex + 1;
        if (prefetch != null || next >= entry.parts.length) return;
        if (entry.offsets[next] - position > PREFETCH_BYTES) return;
        Uri part = entry.parts[next];
        if (!"tg".equals(part.getScheme())) return; // already on disk: nothing to warm up
        Entry e = entry;
        e.acquire(next);
        prefetchIndex = next;
        prefetch = PREFETCH.submit(() -> {
            DataSource source = new FileStreamLoadOperation();
            source.open(new DataSpec.Builder().setUri(part).setPosition(0).build());
            return source;
        });
    }

    @Nullable
    private DataSource takePrefetch(int index) {
        if (prefetch == null || prefetchIndex != index) return null;
        Future<DataSource> ready = prefetch;
        prefetch = null;
        prefetchIndex = -1;
        try {
            return ready.get(); // its reader slot (acquired in maybePrefetch) passes to the child
        } catch (Exception e) {
            entry.release(index);
            return null;
        }
    }

    private void discardPrefetch() {
        Future<DataSource> stale = prefetch;
        int index = prefetchIndex;
        Entry e = entry;
        prefetch = null;
        prefetchIndex = -1;
        if (stale == null) return;
        PREFETCH.execute(() -> {
            try {
                stale.get().close();
            } catch (Exception ignore) {
            }
            if (e != null) e.release(index);
        });
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        try {
            discardPrefetch();
            closeChild();
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
                plain = null;
            }
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    private void closeChild() throws IOException {
        if (decryptor != null) {
            decryptor.close();
            decryptor = null;
        }
        if (child != null) {
            int index = childIndex;
            try {
                child.close();
            } finally {
                child = null;
                childIndex = -1;
                if (index >= 0 && entry != null) entry.release(index);
            }
        }
    }
}
