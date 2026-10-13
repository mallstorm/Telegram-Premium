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

    private static void parseM3u(String base, String body, List<TelePremiumMediaItem> out) throws Exception {
        String title = null;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                title = comma >= 0 ? line.substring(comma + 1).trim() : null;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String url = resolve(base, line);
                // HLS entries inside a larger M3U playlist are kept as one stream item.
                String mime = guessMime(url);
                out.add(networkItem(url, TextUtils.isEmpty(title) ? titleFromUrl(url) : title, mime, isLikelyLive(url)));
                title = null;
            }
        }
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TelePremium/MediaCenter");
        c.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,audio/x-mpegurl,text/plain,*/*");
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null && b.length() < 2_000_000) b.append(line).append('\n');
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
        if (child.startsWith("http://") || child.startsWith("https://")) return child;
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
