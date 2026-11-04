package org.telegram.messenger.telepremium.cloud;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.telegram.messenger.FileLog;

import java.util.List;

/** Lists the files of a Storage Access Framework tree for batch upload (sub-folders flattened, depth-limited). */
public final class TelePremiumCloudFolderScanner {
    private static final int MAX_DEPTH = 4;

    private TelePremiumCloudFolderScanner() {}

    /** Must run off the UI thread. */
    public static void collect(ContentResolver resolver, Uri tree, int limit, List<Uri> out) {
        collect(resolver, tree, DocumentsContract.getTreeDocumentId(tree), 0, limit, out);
    }

    private static void collect(ContentResolver resolver, Uri tree, String documentId, int depth, int limit, List<Uri> out) {
        if (depth > MAX_DEPTH || out.size() >= limit) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && out.size() < limit) {
                String id = c.getString(0);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1))) {
                    collect(resolver, tree, id, depth + 1, limit, out);
                } else {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
