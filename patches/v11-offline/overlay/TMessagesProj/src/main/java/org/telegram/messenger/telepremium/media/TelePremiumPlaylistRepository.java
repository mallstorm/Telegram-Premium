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

    private final SharedPreferences prefs;

    private TelePremiumPlaylistRepository() {
        Context context = ApplicationLoader.applicationContext;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Playlist> getAll() {
        ArrayList<Playlist> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) out.add(fromJson(array.getJSONObject(i)));
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
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
            target = new Playlist();
            if (!TextUtils.isEmpty(id)) target.id = id;
            all.add(target);
        }
        if (!TextUtils.isEmpty(name)) target.name = name;
        target.items.clear();
        if (items != null) target.items.addAll(items);
        target.updatedAt = System.currentTimeMillis();
        persist(all);
        return target;
    }

    public synchronized void delete(String id) {
        ArrayList<Playlist> all = new ArrayList<>(getAll());
        for (int i = all.size() - 1; i >= 0; i--) if (id != null && id.equals(all.get(i).id)) all.remove(i);
        persist(all);
    }

    private void persist(List<Playlist> all) {
        JSONArray array = new JSONArray();
        try {
            for (Playlist p : all) array.put(toJson(p));
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    private static JSONObject toJson(Playlist p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", p.id).put("name", p.name).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt).put("cover", p.cover);
        JSONArray items = new JSONArray();
        for (TelePremiumMediaItem item : p.items) items.put(item.toJson());
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
        if (items != null) for (int i = 0; i < items.length(); i++) p.items.add(TelePremiumMediaItem.fromJson(items.getJSONObject(i)));
        return p;
    }
}
