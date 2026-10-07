package org.telegram.messenger.telepremium.cloud;

import android.net.Uri;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class CloudPayloadRepository {
    private final int account;
    public interface ImportCallback { void onComplete(CloudNode node, String error); }

    public CloudPayloadRepository(int account){this.account=account;}

    public void importUri(Uri uri, String parentId, String displayName, String mime, ImportCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            File temp=null;
            try {
                String safe=(displayName==null||displayName.trim().isEmpty())?"cloud-file":displayName.replaceAll("[\\\\/:*?\"<>|]","_");
                temp=new File(ApplicationLoader.applicationContext.getCacheDir(),"tpcloud_"+System.nanoTime()+"_"+safe);
                try(InputStream in=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                    FileOutputStream out=new FileOutputStream(temp)) {
                    if(in==null) throw new IllegalStateException("Cannot open selected file");
                    byte[] b=new byte[1024*1024]; int n; while((n=in.read(b))>0) out.write(b,0,n);
                }
                CloudNode node=CloudNode.file(parentId,safe,mime,temp.length(),CloudFileHasher.sha256(temp));
                File finalTemp=temp;
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(node,null));
            } catch(Exception e) {
                if(temp!=null) temp.delete();
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onComplete(null,e.getMessage()));
            }
        });
    }

    public File resolveDownloadedFile(TLRPC.Document document) {
        return document == null ? null : FileLoader.getInstance(account).getPathToAttach(document, true);
    }
}
