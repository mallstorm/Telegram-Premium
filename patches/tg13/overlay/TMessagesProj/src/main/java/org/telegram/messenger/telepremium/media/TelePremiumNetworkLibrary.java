package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Saved IPTV playlists, radio favorites and the chosen radio country. */
public final class TelePremiumNetworkLibrary {
    private static final String PREFS = "telepremium_network_library";

    public static final class IptvPlaylist {
        public final String name, url;
        IptvPlaylist(String name, String url) { this.name = name; this.url = url; }
    }

    private TelePremiumNetworkLibrary() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<IptvPlaylist> getIptvPlaylists() {
        ArrayList<IptvPlaylist> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("iptv", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new IptvPlaylist(o.optString("name"), o.optString("url")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    public static void addIptvPlaylist(String name, String url) {
        List<IptvPlaylist> list = getIptvPlaylists();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).url.equals(url)) list.remove(i);
        list.add(0, new IptvPlaylist(TextUtils.isEmpty(name) ? url : name, url));
        saveIptv(list);
    }

    public static void removeIptvPlaylist(String url) {
        List<IptvPlaylist> list = getIptvPlaylists();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).url.equals(url)) list.remove(i);
        saveIptv(list);
    }

    private static void saveIptv(List<IptvPlaylist> list) {
        JSONArray arr = new JSONArray();
        try {
            for (IptvPlaylist p : list) arr.put(new JSONObject().put("name", p.name).put("url", p.url));
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString("iptv", arr.toString()).apply();
    }

    public static List<TelePremiumMediaItem> getRadioFavorites() {
        ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("radio_fav", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                TelePremiumMediaItem item = TelePremiumMediaItem.fromJson(arr.getJSONObject(i));
                item.mediaType = TelePremiumMediaItem.TYPE_STREAM;
                item.live = true;
                out.add(item);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    public static boolean isRadioFavorite(TelePremiumMediaItem item) {
        for (TelePremiumMediaItem f : getRadioFavorites()) if (f.uri != null && f.uri.equals(item.uri)) return true;
        return false;
    }

    public static void toggleRadioFavorite(TelePremiumMediaItem item) {
        List<TelePremiumMediaItem> list = getRadioFavorites();
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).uri != null && list.get(i).uri.equals(item.uri)) { list.remove(i); removed = true; }
        }
        if (!removed) list.add(0, item);
        JSONArray arr = new JSONArray();
        try {
            for (TelePremiumMediaItem f : list) {
                JSONObject o = f.toJson();
                o.put("artworkUri", f.artworkUri);
                arr.put(o);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        prefs().edit().putString("radio_fav", arr.toString()).apply();
    }

    /** Defaults to the device country, then RU. */
    public static String getRadioCountry() {
        String fallback = Locale.getDefault().getCountry();
        return prefs().getString("radio_country", TextUtils.isEmpty(fallback) ? "RU" : fallback.toUpperCase(Locale.ROOT));
    }

    public static String getRadioCountryName() { return prefs().getString("radio_country_name", null); }

    public static void setRadioCountry(String code, String name) {
        prefs().edit().putString("radio_country", code).putString("radio_country_name", name).apply();
    }
}
