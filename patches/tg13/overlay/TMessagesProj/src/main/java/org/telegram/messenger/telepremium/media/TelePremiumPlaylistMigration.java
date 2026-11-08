package org.telegram.messenger.telepremium.media;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

/** Preserve old document playlist contents while this device still owns its read grant. */
public final class TelePremiumPlaylistMigration {
    private static final java.util.concurrent.Executor WORKER=Executors.newSingleThreadExecutor();
    private static final Set<String> ATTEMPTED=new HashSet<>();
    public static void captureLocalFiles() {
        final long owner=TelePremiumMediaLibraryStore.owner();
        if(owner<=0)return;
        for(JSONObject row:TelePremiumMediaLibraryStore.values("iptv")) {
            final String url=row.optString("url");
            if(row.has("inlineGzip")||!TelePremiumNetworkPlaylistParser.isLocal(url)||!ATTEMPTED.add(owner+"/"+url))continue;
            WORKER.execute(()->{
                try {
                    String text=TelePremiumNetworkPlaylistParser.readPlaylistForSync(url);
                    java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                    try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                    String compressed=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
                    AndroidUtilities.runOnUIThread(()->{
                        if(owner!=TelePremiumMediaLibraryStore.owner())return;
                        for(JSONObject current:TelePremiumMediaLibraryStore.values("iptv"))if(url.equals(current.optString("url"))&&!current.has("inlineGzip")) {
                            try{current.put("inlineGzip",compressed);TelePremiumMediaLibraryStore.put("iptv",url,current,false);}catch(Exception ignored){}
                            break;
                        }
                    });
                }catch(Exception e){AndroidUtilities.runOnUIThread(()->{
                    if(owner==TelePremiumMediaLibraryStore.owner())TelePremiumMediaLibrarySync.localError("Добавьте заново локальный плейлист: доступ к старому файлу потерян");
                });}
            });
        }
    }
    private TelePremiumPlaylistMigration(){}
}
