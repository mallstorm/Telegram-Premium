package org.telegram.messenger.telepremium.media;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct URL + M3U/M3U8/PLS/XSPF resolver used by the Network source. */
public final class TelePremiumNetworkPlaylistParser {
    public interface Callback { void onLoaded(List<TelePremiumMediaItem> items, String error); }

    public static void resolveAsync(String input, Callback callback) {
        new Thread(() -> {
            ArrayList<TelePremiumMediaItem> result = new ArrayList<>();
            String error = null;
            try {
                String url = input == null ? "" : input.trim();
                if (isLocal(url)) {
                    // A playlist file picked on the device: detect the format by its content.
                    String body = downloadText(url, 16_000_000);
                    String head = body.trim().toLowerCase(Locale.ROOT);
                    if (head.startsWith("[playlist]")) parsePls(url, body, result);
                    else if (head.startsWith("<?xml") || head.startsWith("<playlist")) parseXspf(url, body, result);
                    else parseM3u(url, body, result, head.contains("tvg-") || head.contains("group-title"));
                    if (result.isEmpty()) throw new IllegalArgumentException("Плейлист не содержит поддерживаемых медиаадресов");
                    ArrayList<TelePremiumMediaItem> copy = result;
                    AndroidUtilities.runOnUIThread(() -> callback.onLoaded(copy, null));
                    return;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("Поддерживаются HTTP/HTTPS адреса");
                String lower = pathLower(url);
                if (lower.endsWith(".m3u8")) {
                    // .m3u8 is ambiguous: it can be a real HLS manifest or a user playlist.
                    // Inspect the manifest body instead of guessing from words in the URL.
                    String body = downloadText(url);
                    if (looksLikeHlsManifest(body)) {
                        result.add(networkItem(url, titleFromUrl(url), "application/x-mpegURL", true));
                    } else {
                        parseM3u(url, body, result);
                    }
                } else if (lower.endsWith(".m3u") || lower.endsWith(".pls") || lower.endsWith(".xspf")) {
                    String body = downloadText(url);
                    if (lower.endsWith(".pls")) parsePls(url, body, result);
                    else if (lower.endsWith(".xspf")) parseXspf(url, body, result);
                    else parseM3u(url, body, result);
                } else {
                    result.add(networkItem(url, titleFromUrl(url), guessMime(url), isLikelyLive(url)));
                }
                if (result.isEmpty()) throw new IllegalArgumentException("Плейлист не содержит поддерживаемых медиаадресов");
            } catch (Throwable t) {
                error = TextUtils.isEmpty(t.getMessage()) ? "Не удалось открыть сетевой источник" : t.getMessage();
            }
            ArrayList<TelePremiumMediaItem> copy = result;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(copy, failure));
        }, "TelePremiumNetworkParser").start();
    }

    private static final Pattern M3U_ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    private static void parseM3u(String base, String body, List<TelePremiumMediaItem> out) throws Exception {
        parseM3u(base, body, out, false);
    }

    /**
     * Extended M3U: #EXTINF attributes tvg-logo (channel logo), group-title / #EXTGRP (group),
     * tvg-name. In IPTV mode entries are live video channels unless they are clearly audio.
     */
    static void parseM3u(String base, String body, List<TelePremiumMediaItem> out, boolean iptv) throws Exception {
        String title = null, logo = null, group = null, extGroup = null;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = lastTopLevelComma(line);
                title = comma >= 0 ? line.substring(comma + 1).trim() : null;
                String attrs = comma >= 0 ? line.substring(0, comma) : line;
                logo = null;
                group = null;
                Matcher m = M3U_ATTR.matcher(attrs);
                while (m.find()) {
                    String key = m.group(1).toLowerCase(Locale.ROOT);
                    String value = m.group(2).trim();
                    if (value.isEmpty()) continue;
                    if ("tvg-logo".equals(key)) logo = value;
                    else if ("group-title".equals(key)) group = value;
                    else if ("tvg-name".equals(key) && TextUtils.isEmpty(title)) title = value;
                }
            } else if (line.startsWith("#EXTGRP:")) {
                extGroup = line.substring(8).trim();
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String url = resolve(base, line);
                String mime = guessMime(url);
                TelePremiumMediaItem item = networkItem(url, TextUtils.isEmpty(title) ? titleFromUrl(url) : title, mime, iptv || isLikelyLive(url));
                if (!TextUtils.isEmpty(logo) && logo.startsWith("http")) item.artworkUri = logo;
                String g = !TextUtils.isEmpty(group) ? group : extGroup;
                if (!TextUtils.isEmpty(g)) item.album = g;
                if (iptv) {
                    item.live = true;
                    String lower = pathLower(url);
                    boolean audio = lower.endsWith(".mp3") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".opus")
                            || (g != null && g.toLowerCase(Locale.ROOT).contains("radio"));
                    item.mediaType = audio ? TelePremiumMediaItem.TYPE_STREAM : TelePremiumMediaItem.TYPE_VIDEO;
                    if (!audio && (mime == null || mime.startsWith("audio/"))) item.mimeType = lower.endsWith(".ts") ? "video/mp2t" : "application/x-mpegURL";
                }
                out.add(item);
                title = null;
                logo = null;
                group = null;
            }
        }
    }

    /** Titles may contain commas; attribute values are quoted, so the separator is the first comma outside quotes. */
    private static int lastTopLevelComma(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    /** Loads an IPTV playlist (M3U/M3U8 with channels) in IPTV mode. */
    public static void resolveIptvAsync(String input, Callback callback) {
        new Thread(() -> {
            ArrayList<TelePremiumMediaItem> result = new ArrayList<>();
            String error = null;
            try {
                String url = input == null ? "" : input.trim();
                if (!isLocal(url) && !url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("Поддерживаются HTTP/HTTPS адреса и файлы плейлистов");
                // A link to a single stream (not a playlist): play it as one channel, never download it as text.
                String directMime = isLocal(url) ? null : probeMediaType(url);
                if (directMime != null) {
                    TelePremiumMediaItem item = networkItem(url, titleFromUrl(url), directMime, true);
                    item.live = true;
                    if (directMime.startsWith("video/")) item.mediaType = TelePremiumMediaItem.TYPE_VIDEO;
                    result.add(item);
                    ArrayList<TelePremiumMediaItem> copy = result;
                    AndroidUtilities.runOnUIThread(() -> callback.onLoaded(copy, null));
                    return;
                }
                String body = downloadText(url, 16_000_000);
                if (looksLikeHlsManifest(body)) result.add(networkItem(url, titleFromUrl(url), "application/x-mpegURL", true));
                else parseM3u(url, body, result, true);
                if (result.isEmpty()) throw new IllegalArgumentException("Плейлист не содержит каналов");
            } catch (Throwable t) {
                error = TextUtils.isEmpty(t.getMessage()) ? "Не удалось открыть IPTV-плейлист" : t.getMessage();
            }
            ArrayList<TelePremiumMediaItem> copy = result;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(copy, failure));
        }, "TelePremiumIptvParser").start();
    }

    private static void parsePls(String base, String body, List<TelePremiumMediaItem> out) throws Exception {
        java.util.HashMap<Integer, String> urls = new java.util.HashMap<>();
        java.util.HashMap<Integer, String> titles = new java.util.HashMap<>();
        Pattern p = Pattern.compile("(?i)^(File|Title)(\\d+)=(.*)$");
        for (String raw : body.split("\\r?\\n")) {
            Matcher m = p.matcher(raw.trim());
            if (!m.matches()) continue;
            int index = Integer.parseInt(m.group(2));
            if ("File".equalsIgnoreCase(m.group(1))) urls.put(index, m.group(3).trim());
            else titles.put(index, m.group(3).trim());
        }
        ArrayList<Integer> order = new ArrayList<>(urls.keySet());
        java.util.Collections.sort(order);
        for (Integer index : order) {
            String url = resolve(base, urls.get(index));
            out.add(networkItem(url, TextUtils.isEmpty(titles.get(index)) ? titleFromUrl(url) : titles.get(index), guessMime(url), true));
        }
    }

    private static void parseXspf(String base, String body, List<TelePremiumMediaItem> out) throws Exception {
        Pattern track = Pattern.compile("(?is)<track>(.*?)</track>");
        Pattern location = Pattern.compile("(?is)<location>(.*?)</location>");
        Pattern title = Pattern.compile("(?is)<title>(.*?)</title>");
        Matcher tm = track.matcher(body);
        while (tm.find()) {
            String block = tm.group(1);
            Matcher lm = location.matcher(block);
            if (!lm.find()) continue;
            String url = resolve(base, unescape(lm.group(1).trim()));
            Matcher nm = title.matcher(block);
            String name = nm.find() ? unescape(nm.group(1).trim()) : titleFromUrl(url);
            out.add(networkItem(url, name, guessMime(url), isLikelyLive(url)));
        }
    }

    private static String downloadText(String url) throws Exception {
        return downloadText(url, 2_000_000);
    }

    /** Returns the MIME type when the URL is an audio/video stream rather than a text playlist. */
    private static String probeMediaType(String url) {
        String lower = pathLower(url);
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".txt") || lower.endsWith(".pls") || lower.endsWith(".xspf")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "TelePremium/MediaCenter");
            c.setRequestProperty("Icy-MetaData", "0");
            String type = c.getContentType();
            if (type == null) return null;
            type = type.toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi > 0) type = type.substring(0, semi).trim();
            if (type.contains("mpegurl") || type.startsWith("text/") || type.contains("json") || type.contains("xml")) return null;
            if (type.startsWith("audio/") || type.startsWith("video/")) return type;
            // octet-stream is ambiguous (IPTV panels serve M3U that way): only a .ts path is a stream.
            if (type.contains("mp2t") || (type.contains("octet-stream") && lower.endsWith(".ts"))) return "video/mp2t";
            return null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static boolean isLocal(String url) {
        return url != null && (url.startsWith("content://") || url.startsWith("file://"));
    }

    private static String downloadText(String url, int maxChars) throws Exception {
        if (isLocal(url)) {
            StringBuilder b = new StringBuilder();
            java.io.InputStream raw = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver().openInputStream(android.net.Uri.parse(url));
            if (raw == null) throw new java.io.IOException("Не удалось открыть файл плейлиста");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(raw, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && b.length() < maxChars) b.append(line).append('\n');
            }
            String text = b.toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TelePremium/MediaCenter");
        c.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,audio/x-mpegurl,text/plain,*/*");
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && b.length() < maxChars) b.append(line).append('\n');
        } finally { c.disconnect(); }
        return b.toString();
    }

    private static boolean looksLikeHlsManifest(String body) {
        if (body == null) return false;
        String b = body.toUpperCase(Locale.ROOT);
        return b.contains("#EXT-X-TARGETDURATION") || b.contains("#EXT-X-STREAM-INF")
                || b.contains("#EXT-X-MEDIA-SEQUENCE") || b.contains("#EXT-X-PLAYLIST-TYPE")
                || b.contains("#EXT-X-MAP:") || b.contains("#EXT-X-I-FRAME-STREAM-INF");
    }

    private static TelePremiumMediaItem networkItem(String url, String title, String mime, boolean live) {
        TelePremiumMediaItem item = TelePremiumMediaItem.network(url, title, mime, live);
        String lower = pathLower(url);
        String joined = ((title == null ? "" : title) + " " + lower).toLowerCase(Locale.ROOT);
        boolean radioLike = joined.contains("radio") || joined.contains("радио") || joined.contains("audio");
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")
                || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".3gp") || lower.endsWith(".ts") || lower.endsWith(".m2ts")) {
            item.mediaType = TelePremiumMediaItem.TYPE_VIDEO;
        } else if (lower.endsWith(".m3u8") || lower.endsWith(".mpd")
                || (mime != null && (mime.toLowerCase(Locale.ROOT).contains("mpegurl") || mime.toLowerCase(Locale.ROOT).contains("dash")))) {
            item.mediaType = radioLike ? TelePremiumMediaItem.TYPE_STREAM : TelePremiumMediaItem.TYPE_VIDEO;
        }
        return item;
    }

    private static String resolve(String base, String child) throws Exception {
        if (child.contains("://")) return child; // http(s), rtmp, rtsp, udp …
        if (isLocal(base)) return child.startsWith("/") ? "file://" + child : child;
        return new URI(base).resolve(child).toString();
    }

    private static String titleFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return TextUtils.isEmpty(name) ? "Онлайн-поток" : name;
        } catch (Throwable ignore) { return "Онлайн-поток"; }
    }

    private static String guessMime(String url) {
        String u = pathLower(url);
        if (u.endsWith(".m3u8")) return "application/x-mpegURL";
        if (u.endsWith(".mpd")) return "application/dash+xml";
        if (u.endsWith(".mp4") || u.endsWith(".m4v")) return "video/mp4";
        if (u.endsWith(".webm")) return "video/webm";
        if (u.endsWith(".mkv")) return "video/x-matroska";
        if (u.endsWith(".mov")) return "video/quicktime";
        if (u.endsWith(".flac")) return "audio/flac";
        if (u.endsWith(".ogg") || u.endsWith(".opus")) return "audio/ogg";
        if (u.endsWith(".aac")) return "audio/aac";
        if (u.endsWith(".m4a")) return "audio/mp4";
        if (u.endsWith(".wav")) return "audio/wav";
        return "audio/mpeg";
    }

    private static boolean isLikelyLive(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("live") || u.contains("radio") || u.contains("stream") || u.contains(".m3u8") || u.contains(".mpd")
                || (!pathLower(url).matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav|mp4|m4v|mkv|webm|mov|avi|3gp|ts|m2ts)$"));
    }

    private static String pathLower(String url) {
        try { return new URL(url).getPath().toLowerCase(Locale.ROOT); }
        catch (Throwable ignore) { return url == null ? "" : url.toLowerCase(Locale.ROOT); }
    }

    private static String unescape(String value) {
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }
}
