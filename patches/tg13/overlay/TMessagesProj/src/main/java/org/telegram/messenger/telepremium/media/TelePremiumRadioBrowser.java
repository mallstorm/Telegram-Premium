package org.telegram.messenger.telepremium.media;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Client for the Radio Browser community catalog (radio-browser.info, also used by RadioDroid
 * and Transistor). Servers are discovered via DNS (all.api.radio-browser.info) as the project
 * recommends; broken stations are hidden (hidebroken=true, i.e. only stations whose last check
 * succeeded), so the lists stay current.
 */
public final class TelePremiumRadioBrowser {
    private static final String[] FALLBACK_SERVERS = {"de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info"};
    private static final String USER_AGENT = "TelePremium/13.1.1";
    private static volatile List<String> servers;

    public static final class Country {
        public final String code, name;
        public final int stations;
        Country(String code, String name, int stations) { this.code = code; this.name = name; this.stations = stations; }
    }

    public interface StationsCallback { void onLoaded(List<TelePremiumMediaItem> stations, String error); }
    public interface CountriesCallback { void onLoaded(List<Country> countries, String error); }

    private TelePremiumRadioBrowser() {}

    /** Most listened live stations of a country (ISO 3166-1 alpha-2, e.g. "RU"). */
    public static void byCountry(String countryCode, int limit, StationsCallback callback) {
        stations("/json/stations/search?countrycode=" + Uri.encode(countryCode.toUpperCase(Locale.ROOT))
                + "&hidebroken=true&order=clickcount&reverse=true&limit=" + limit, callback);
    }

    /** Free-text search over station names and tags, optionally inside one country. */
    public static void search(String query, String countryCode, int limit, StationsCallback callback) {
        String q = Uri.encode(query.trim());
        String country = TextUtils.isEmpty(countryCode) ? "" : "&countrycode=" + Uri.encode(countryCode);
        // Two passes (name, tag) merged: "rock" should find "Rock FM" and stations tagged rock.
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
            String error = null;
            try {
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (String field : new String[]{"name", "tag"}) {
                    for (TelePremiumMediaItem item : parseStations(get("/json/stations/search?" + field + "=" + q + country
                            + "&hidebroken=true&order=clickcount&reverse=true&limit=" + limit))) {
                        if (seen.add(item.uri)) out.add(item);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                error = message(e);
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(out, err));
        });
    }

    public static void countries(CountriesCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Country> out = new ArrayList<>();
            String error = null;
            try {
                JSONArray arr = new JSONArray(get("/json/countries?hidebroken=true&order=stationcount&reverse=true"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String code = o.optString("iso_3166_1", o.optString("countrycode", ""));
                    int count = o.optInt("stationcount");
                    if (code.length() == 2 && count > 0) out.add(new Country(code.toUpperCase(Locale.ROOT), o.optString("name", code), count));
                }
            } catch (Exception e) {
                FileLog.e(e);
                error = message(e);
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(out, err));
        });
    }

    /** Registers a play with the catalog (keeps its popularity ranking honest). Fire and forget. */
    public static void countClick(TelePremiumMediaItem station) {
        if (station == null || TextUtils.isEmpty(station.cloudNodeId) || !station.cloudNodeId.startsWith("radio:")) return;
        String uuid = station.cloudNodeId.substring(6);
        Utilities.globalQueue.postRunnable(() -> {
            try { get("/json/url/" + Uri.encode(uuid)); } catch (Exception ignored) { /* best effort */ }
        });
    }

    private static void stations(String path, StationsCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            List<TelePremiumMediaItem> out = Collections.emptyList();
            String error = null;
            try {
                out = parseStations(get(path));
            } catch (Exception e) {
                FileLog.e(e);
                error = message(e);
            }
            final List<TelePremiumMediaItem> result = out;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result, err));
        });
    }

    static List<TelePremiumMediaItem> parseStations(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        ArrayList<TelePremiumMediaItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String url = o.optString("url_resolved", "");
            if (TextUtils.isEmpty(url)) url = o.optString("url", "");
            if (!url.startsWith("http")) continue;
            if (o.has("lastcheckok") && o.optInt("lastcheckok", 1) == 0) continue;
            String codec = o.optString("codec", "").toUpperCase(Locale.ROOT);
            boolean hls = o.optInt("hls", 0) == 1 || url.toLowerCase(Locale.ROOT).contains(".m3u8");
            String mime = hls ? "application/x-mpegURL" : "AAC".equals(codec) || "AAC+".equals(codec) ? "audio/aac"
                    : "OGG".equals(codec) || "OPUS".equals(codec) ? "audio/ogg" : "FLAC".equals(codec) ? "audio/flac" : "audio/mpeg";
            TelePremiumMediaItem item = TelePremiumMediaItem.network(url, o.optString("name", url).trim(), mime, true);
            item.mediaType = TelePremiumMediaItem.TYPE_STREAM;
            String favicon = o.optString("favicon", "");
            item.artworkUri = favicon.startsWith("http") ? favicon : null;
            if (item.artworkUri == null) {
                // No logo in the catalog: use the station site's icon.
                String host = hostOf(o.optString("homepage", ""));
                if (host != null) item.artworkUri = TelePremiumArtworkLoader.siteIconUrl(host);
            }
            String tags = o.optString("tags", "").replace(",", ", ");
            item.artist = TextUtils.isEmpty(tags) ? o.optString("country", null) : tags;
            int bitrate = o.optInt("bitrate", 0);
            String country = o.optString("countrycode", "");
            StringBuilder album = new StringBuilder("Radio");
            if (!TextUtils.isEmpty(country)) album.append(" · ").append(country.toUpperCase(Locale.ROOT));
            if (!TextUtils.isEmpty(codec) && !"UNKNOWN".equals(codec)) album.append(" · ").append(codec);
            if (bitrate > 0) album.append(' ').append(bitrate).append(" kbps");
            item.album = album.toString();
            String uuid = o.optString("stationuuid", "");
            if (!TextUtils.isEmpty(uuid)) item.cloudNodeId = "radio:" + uuid;
            out.add(item);
        }
        return out;
    }

    private static List<String> servers() {
        List<String> list = servers;
        if (list != null) return list;
        LinkedHashSet<String> found = new LinkedHashSet<>();
        try {
            for (InetAddress address : InetAddress.getAllByName("all.api.radio-browser.info")) {
                String host = address.getCanonicalHostName();
                if (host != null && host.endsWith(".api.radio-browser.info")) found.add(host);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (found.isEmpty()) Collections.addAll(found, FALLBACK_SERVERS);
        list = new ArrayList<>(found);
        Collections.shuffle(list);
        servers = list;
        return list;
    }

    private static String get(String path) throws Exception {
        Exception last = null;
        for (String host : servers()) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://" + host + path).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toString("UTF-8");
                }
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("Radio Browser unavailable") : last;
    }

    private static String message(Exception e) {
        return TextUtils.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String hostOf(String url) {
        try {
            if (url == null || !url.startsWith("http")) return null;
            String host = android.net.Uri.parse(url).getHost();
            return host == null || host.isEmpty() ? null : host;
        } catch (Throwable e) {
            return null;
        }
    }
}
