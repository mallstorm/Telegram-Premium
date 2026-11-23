package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lightweight local playlist repository. Playlists store references, never duplicate media payloads. */
public final class TelePremiumPlaylistRepository {
    private static final String PREFS = "telepremium_media_playlists_v1";
    private static final String KEY = "playlists";
    private static final TelePremiumPlaylistRepository INSTANCE = new TelePremiumPlaylistRepository();
    public static TelePremiumPlaylistRepository getInstance() { return INSTANCE; }

    public static final class Playlist {
        public String id = UUID.randomUUID().toString();
        public String name = "Новый плейлист";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = createdAt;
        public String cover;
        public final ArrayList<TelePremiumMediaItem> items = new ArrayList<>();
    }

    private TelePremiumPlaylistRepository() {}

    public synchronized List<Playlist> getAll() {
        ArrayList<Playlist> out = new ArrayList<>();
        for (JSONObject row : TelePremiumMediaLibraryStore.values("playlist")) try { out.add(fromJson(row)); }
        catch (Exception e) { TelePremiumMediaLibrarySync.localError("Не удалось прочитать плейлист"); }
        return out;
    }

    public synchronized Playlist find(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (Playlist playlist : getAll()) if (id.equals(playlist.id)) return playlist;
        return null;
    }

    public synchronized Playlist save(String id, String name, List<TelePremiumMediaItem> items) {
        ArrayList<Playlist> all = new ArrayList<>(getAll());
        Playlist target = null;
        for (Playlist p : all) if (!TextUtils.isEmpty(id) && id.equals(p.id)) { target = p; break; }
        if (target == null) {
            if (!TextUtils.isEmpty(id)) throw new IllegalStateException("Плейлист уже удалён");
            target = new Playlist();
            if (!TextUtils.isEmpty(id)) target.id = id;
            all.add(target);
        }
        if (!TextUtils.isEmpty(name)) target.name = name;
        target.items.clear();
        if (items != null) for (TelePremiumMediaItem it : items) if (it != null && !it.ephemeral) target.items.add(it);
        target.updatedAt = System.currentTimeMillis();
        try { if (!TelePremiumMediaLibraryStore.put("playlist", target.id, toJson(target), TextUtils.isEmpty(id))) throw new IllegalStateException("Запись не сохранена"); } catch (Exception e) { throw new IllegalStateException("Не удалось сохранить плейлист", e); }
        return target;
    }

    public synchronized void delete(String id) { TelePremiumMediaLibraryStore.remove("playlist", id); }

    private static JSONObject toJson(Playlist p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", p.id).put("name", p.name).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt).put("cover", p.cover);
        JSONArray items = new JSONArray();
        for (TelePremiumMediaItem item : p.items) {
            JSONObject row=item.toJson();
            if(item.sourceType==TelePremiumMediaItem.SOURCE_CLOUD) {
                long owner=item.cloudUserId;
                if(owner==0 && item.cloudAccount>=0 && item.cloudAccount<org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT)
                    owner=org.telegram.messenger.UserConfig.getInstance(item.cloudAccount).getClientUserId();
                row.put("cloudUserId",owner).put("cloudAccount",-1).put("cloudEpoch",-1);
            } else if(item.sourceType==TelePremiumMediaItem.SOURCE_DEVICE) {
                TelePremiumMediaLibraryStore.bindDevice(item.id,item.uri);row.put("uri",JSONObject.NULL);
            }
            items.put(row);
        }
        o.put("items", items);
        return o;
    }

    private static Playlist fromJson(JSONObject o) throws Exception {
        Playlist p = new Playlist();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", p.name);
        p.createdAt = o.optLong("createdAt", p.createdAt);
        p.updatedAt = o.optLong("updatedAt", p.createdAt);
        p.cover = o.optString("cover", null);
        JSONArray items = o.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            TelePremiumMediaItem item=TelePremiumMediaItem.fromJson(items.getJSONObject(i));
            if(item.sourceType==TelePremiumMediaItem.SOURCE_CLOUD) {
                item.cloudAccount=-1;item.cloudEpoch=-1;
                for(int a=0;a<org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT;a++)if(org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()
                        && item.cloudUserId>0 && org.telegram.messenger.UserConfig.getInstance(a).getClientUserId()==item.cloudUserId)item.cloudAccount=a;
            } else if(item.sourceType==TelePremiumMediaItem.SOURCE_DEVICE) item.uri=TelePremiumMediaLibraryStore.deviceUri(item);
            p.items.add(item);
        }
        return p;
    }
}
