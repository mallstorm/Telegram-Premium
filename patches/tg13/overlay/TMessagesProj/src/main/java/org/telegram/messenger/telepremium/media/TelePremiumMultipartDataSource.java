package org.telegram.messenger.telepremium.media;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;

import org.telegram.messenger.FileStreamLoadOperation;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Presents a Cloud file stored as several Telegram documents (byte-range parts) as one
 * seekable stream. Each part is streamed by Telegram's own {@link FileStreamLoadOperation}
 * (or read from disk when already cached); seeks open only the part that covers the position,
 * so a 12+ GB video starts and seeks as fast as a single document.
 */
@OptIn(markerClass = UnstableApi.class)
public final class TelePremiumMultipartDataSource extends BaseDataSource {
    public static final String SCHEME = "tpmulti";

    private static final class Entry {
        final Uri[] parts;
        final long[] offsets;
        final long total;

        Entry(Uri[] parts, long[] sizes) {
            this.parts = parts;
            offsets = new long[sizes.length + 1];
            for (int i = 0; i < sizes.length; i++) offsets[i + 1] = offsets[i] + Math.max(0, sizes[i]);
            total = offsets[sizes.length];
        }

        int partAt(long position) {
            for (int i = 0; i < parts.length; i++) if (position < offsets[i + 1]) return i;
            return parts.length - 1;
        }
    }

    private static final ConcurrentHashMap<String, Entry> REGISTRY = new ConcurrentHashMap<>();

    /** Registers the parts (stream or file URIs, in order) and returns the URI to hand to the player. */
    public static Uri register(Uri[] parts, long[] sizes) {
        String key = UUID.randomUUID().toString().replace("-", "");
        if (REGISTRY.size() > 16) REGISTRY.clear(); // only the current and a few recent items matter
        REGISTRY.put(key, new Entry(parts, sizes));
        return Uri.parse(SCHEME + "://" + key);
    }

    private Uri uri;
    private Entry entry;
    private int partIndex;
    private long bytesRemaining;
    private DataSource child;
    private boolean opened;

    public TelePremiumMultipartDataSource() {
        super(true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        entry = REGISTRY.get(uri.getHost());
        if (entry == null) throw new IOException("Multipart stream expired");
        transferInitializing(dataSpec);
        long position = Math.max(0, dataSpec.position);
        if (position > entry.total) throw new IOException("Position beyond end of multipart file");
        bytesRemaining = entry.total - position;
        if (dataSpec.length != C.LENGTH_UNSET) bytesRemaining = Math.min(bytesRemaining, dataSpec.length);
        partIndex = entry.partAt(position);
        if (bytesRemaining > 0) openPart(position - entry.offsets[partIndex]);
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    private void openPart(long offsetInPart) throws IOException {
        closeChild();
        Uri part = entry.parts[partIndex];
        child = "tg".equals(part.getScheme()) ? new FileStreamLoadOperation() : new FileDataSource();
        child.open(new DataSpec.Builder().setUri(part).setPosition(offsetInPart).build());
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        while (true) {
            if (child == null) return C.RESULT_END_OF_INPUT;
            int n = child.read(buffer, offset, (int) Math.min(length, bytesRemaining));
            if (n == C.RESULT_END_OF_INPUT) {
                if (partIndex + 1 >= entry.parts.length) return C.RESULT_END_OF_INPUT;
                partIndex++;
                openPart(0);
                continue;
            }
            if (n > 0) {
                bytesRemaining -= n;
                bytesTransferred(n);
            }
            return n;
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        try {
            closeChild();
        } finally {
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    private void closeChild() throws IOException {
        if (child != null) {
            try {
                child.close();
            } finally {
                child = null;
            }
        }
    }
}
