package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.telepremium.TPStrings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YouTube in the TelePremium media library, built on NewPipe Extractor (no Google API key, no
 * account): a channel / playlist link is parsed into a list of videos with their metadata,
 * a video is resolved into its separate video and audio formats for playback (MergingMediaSource)
 * and for download with a chosen quality and audio track.
 */
public final class TelePremiumYouTube {
    public static final String MIME = "video/youtube";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static volatile boolean initialized;

    private TelePremiumYouTube() {}

    public static boolean isYouTube(TelePremiumMediaItem item) {
        return item != null && MIME.equals(item.mimeType);
    }

    // ------------------------------------------------------------------ models

    /** A list of videos with a handle for the next page. */
    public static final class Feed {
        public String title;
        public String url;
        public final ArrayList<TelePremiumMediaItem> items = new ArrayList<>();
        Page nextPage;
        ListLinkHandler tab;
        int kind; // 0 channel tab, 1 playlist

        public boolean hasMore() { return Page.isValid(nextPage); }
    }

    public static final class VideoOption {
        public String label, url, format, codec;
        public int height, fps;
        public boolean videoOnly, webm;
        public long size;
    }

    public static final class AudioOption {
        public String label, url, format, language;
        public int bitrate;
        public boolean webm, original;
        public long size;
    }

    public static final class Formats {
        public String title, uploader, hlsUrl;
        public boolean live;
        public long durationMs;
        public final ArrayList<VideoOption> videos = new ArrayList<>();
        public final ArrayList<AudioOption> audios = new ArrayList<>();
    }

    public interface Callback<T> { void onResult(T result, String error); }

    // ------------------------------------------------------------------ setup

    private static synchronized void ensureInit() {
        if (initialized) return;
        String lang = TPStrings.isRussian() ? "ru" : Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();
        if (TextUtils.isEmpty(country)) country = "RU";
        NewPipe.init(new HttpDownloader(), new Localization(lang, country), new ContentCountry(country));
        initialized = true;
    }

    private static StreamingService yt() { return ServiceList.YouTube; }

    private static <T> void async(java.util.concurrent.Callable<T> work, Callback<T> callback) {
        EXECUTOR.execute(() -> {
            T result = null;
            String error = null;
            try {
                ensureInit();
                result = work.call();
            } catch (ReCaptchaException e) {
                error = "YouTube требует проверку (reCAPTCHA). Попробуйте позже или через другой VPN-сервер";
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            T r = result;
            String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(r, err));
        });
    }

    // ------------------------------------------------------------------ channel / playlist

    /** Channel (videos tab), playlist or a single video link. */
    public static void loadFeed(String input, Callback<Feed> callback) {
        async(() -> {
            String url = normalize(input);
            StreamingService service = yt();
            StreamingService.LinkType type = service.getLinkTypeByUrl(url);
            Feed feed = new Feed();
            feed.url = url;
            if (type == StreamingService.LinkType.CHANNEL) {
                ChannelInfo channel = ChannelInfo.getInfo(service, url);
                feed.title = channel.getName();
                ListLinkHandler videos = null;
                for (ListLinkHandler tab : channel.getTabs()) {
                    if (tab.getContentFilters().contains(ChannelTabs.VIDEOS)) { videos = tab; break; }
                }
                if (videos == null && !channel.getTabs().isEmpty()) videos = channel.getTabs().get(0);
                if (videos == null) throw new IllegalStateException("На канале нет видео");
                ChannelTabInfo info = ChannelTabInfo.getInfo(service, videos);
                feed.tab = videos;
                feed.kind = 0;
                addItems(feed, info.getRelatedItems(), channel.getName());
                feed.nextPage = info.getNextPage();
            } else if (type == StreamingService.LinkType.PLAYLIST) {
                PlaylistInfo playlist = PlaylistInfo.getInfo(service, url);
                feed.title = playlist.getName();
                feed.kind = 1;
                addItems(feed, new ArrayList<InfoItem>(playlist.getRelatedItems()), playlist.getUploaderName());
                feed.nextPage = playlist.getNextPage();
            } else if (type == StreamingService.LinkType.STREAM) {
                StreamInfo info = StreamInfo.getInfo(service, url);
                feed.title = info.getUploaderName();
                feed.items.add(itemFromStream(info));
            } else {
                throw new IllegalArgumentException("Это не ссылка на канал, плейлист или видео YouTube");
            }
            if (feed.items.isEmpty()) throw new IllegalStateException("Видео не найдены");
            return feed;
        }, callback);
    }

    /** Appends the next page of the feed; the callback gets only the new items. */
    public static void loadMore(Feed feed, Callback<List<TelePremiumMediaItem>> callback) {
        async(() -> {
            if (!feed.hasMore()) return Collections.<TelePremiumMediaItem>emptyList();
            Feed page = new Feed();
            if (feed.kind == 0) {
                ListExtractor.InfoItemsPage<InfoItem> more = ChannelTabInfo.getMoreItems(yt(), feed.tab, feed.nextPage);
                addItems(page, more.getItems(), feed.title);
                feed.nextPage = more.getNextPage();
            } else {
                ListExtractor.InfoItemsPage<StreamInfoItem> more = PlaylistInfo.getMoreItems(yt(), feed.url, feed.nextPage);
                addItems(page, new ArrayList<InfoItem>(more.getItems()), feed.title);
                feed.nextPage = more.getNextPage();
            }
            feed.items.addAll(page.items);
            return page.items;
        }, callback);
    }

    private static void addItems(Feed feed, List<? extends InfoItem> items, String uploader) {
        for (InfoItem info : items) {
            if (!(info instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) info;
            TelePremiumMediaItem item = TelePremiumMediaItem.network(s.getUrl(), s.getName(), MIME, isLive(s.getStreamType()));
            item.mediaType = TelePremiumMediaItem.TYPE_VIDEO;
            item.mimeType = MIME;
            item.duration = Math.max(0, s.getDuration()) * 1000L;
            item.artworkUri = bestImage(s.getThumbnails());
            item.artist = TextUtils.isEmpty(s.getUploaderName()) ? uploader : s.getUploaderName();
            StringBuilder meta = new StringBuilder();
            if (!TextUtils.isEmpty(s.getTextualUploadDate())) meta.append(s.getTextualUploadDate());
            if (s.getViewCount() > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(compactViews(s.getViewCount()));
            }
            item.album = meta.length() == 0 ? "YouTube" : meta.toString();
            feed.items.add(item);
        }
    }

    private static TelePremiumMediaItem itemFromStream(StreamInfo info) {
        TelePremiumMediaItem item = TelePremiumMediaItem.network(info.getUrl(), info.getName(), MIME, isLive(info.getStreamType()));
        item.mediaType = TelePremiumMediaItem.TYPE_VIDEO;
        item.mimeType = MIME;
        item.duration = Math.max(0, info.getDuration()) * 1000L;
        item.artworkUri = bestImage(info.getThumbnails());
        item.artist = info.getUploaderName();
        item.album = TextUtils.isEmpty(info.getTextualUploadDate()) ? "YouTube" : info.getTextualUploadDate();
        return item;
    }

    private static boolean isLive(StreamType type) {
        return type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
    }

    private static String bestImage(List<Image> images) {
        if (images == null || images.isEmpty()) return null;
        Image best = null;
        for (Image image : images) {
            int w = image.getWidth();
            if (best == null || (w > 0 && w <= 720 && w > best.getWidth()) || best.getWidth() <= 0) best = image;
        }
        return best == null ? null : best.getUrl();
    }

    private static String compactViews(long views) {
        boolean ru = TPStrings.isRussian();
        if (views >= 1_000_000) return String.format(Locale.ROOT, "%.1f%s", views / 1_000_000f, ru ? " млн просм." : "M views");
        if (views >= 1_000) return String.format(Locale.ROOT, "%.0f%s", views / 1_000f, ru ? " тыс. просм." : "K views");
        return views + (ru ? " просм." : " views");
    }

    private static String normalize(String input) {
        String url = input == null ? "" : input.trim();
        if (url.startsWith("@")) url = "https://www.youtube.com/" + url;
        if (!url.startsWith("http")) url = "https://" + url;
        return url;
    }

    // ------------------------------------------------------------------ formats

    public static void loadFormats(String videoUrl, Callback<Formats> callback) {
        async(() -> formats(videoUrl), callback);
    }

    private static Formats formats(String videoUrl) throws Exception {
        StreamInfo info = StreamInfo.getInfo(yt(), videoUrl);
        Formats f = new Formats();
        f.title = info.getName();
        f.uploader = info.getUploaderName();
        f.durationMs = Math.max(0, info.getDuration()) * 1000L;
        f.live = isLive(info.getStreamType());
        f.hlsUrl = TextUtils.isEmpty(info.getHlsUrl()) ? null : info.getHlsUrl();
        for (VideoStream v : info.getVideoOnlyStreams()) addVideo(f, v, true);
        for (VideoStream v : info.getVideoStreams()) addVideo(f, v, false);
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl() || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            MediaFormat format = a.getFormat();
            if (format == null) continue;
            AudioOption o = new AudioOption();
            o.url = a.getContent();
            o.format = format.getSuffix();
            o.webm = format == MediaFormat.WEBMA || format == MediaFormat.OPUS || format == MediaFormat.WEBMA_OPUS;
            o.bitrate = a.getAverageBitrate() > 0 ? a.getAverageBitrate() : a.getBitrate() / 1000;
            o.original = a.getAudioTrackType() == null || a.getAudioTrackType() == AudioTrackType.ORIGINAL;
            Locale locale = a.getAudioLocale();
            String track = !TextUtils.isEmpty(a.getAudioTrackName()) ? a.getAudioTrackName()
                    : locale != null ? locale.getDisplayLanguage(Locale.getDefault()) : null;
            o.language = track;
            o.size = a.getItagItem() == null ? 0 : a.getItagItem().getContentLength();
            o.label = (track == null ? (TPStrings.isRussian() ? "Звук" : "Audio") : track)
                    + (a.getAudioTrackType() == AudioTrackType.DUBBED ? (TPStrings.isRussian() ? " (дубляж)" : " (dubbed)") : "")
                    + (a.getAudioTrackType() == AudioTrackType.DESCRIPTIVE ? (TPStrings.isRussian() ? " (тифлокомментарий)" : " (described)") : "")
                    + " · " + (o.bitrate > 0 ? o.bitrate + " kbps " : "") + format.getName()
                    + (o.size > 0 ? " · " + size(o.size) : "");
            f.audios.add(o);
        }
        // Best first: originals, then bitrate
        Collections.sort(f.audios, (a, b) -> a.original != b.original ? (a.original ? -1 : 1) : Integer.compare(b.bitrate, a.bitrate));
        Collections.sort(f.videos, (a, b) -> a.height != b.height ? Integer.compare(b.height, a.height) : Integer.compare(b.fps, a.fps));
        return f;
    }

    private static void addVideo(Formats f, VideoStream v, boolean videoOnly) {
        if (!v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) return;
        MediaFormat format = v.getFormat();
        if (format == null || (format != MediaFormat.MPEG_4 && format != MediaFormat.WEBM)) return;
        VideoOption o = new VideoOption();
        o.url = v.getContent();
        o.height = v.getHeight();
        o.fps = v.getFps();
        o.videoOnly = videoOnly;
        o.webm = format == MediaFormat.WEBM;
        o.format = format.getSuffix();
        o.codec = v.getCodec() == null ? "" : v.getCodec();
        o.size = v.getItagItem() == null ? 0 : v.getItagItem().getContentLength();
        String res = v.getResolution() != null ? v.getResolution() : o.height + "p";
        String codec = o.codec.startsWith("avc") ? "H.264" : o.codec.startsWith("vp9") || o.codec.startsWith("vp09") ? "VP9"
                : o.codec.startsWith("av01") ? "AV1" : o.codec.toUpperCase(Locale.ROOT);
        o.label = res + " · " + format.getName() + (codec.isEmpty() ? "" : " " + codec)
                + (videoOnly ? "" : (TPStrings.isRussian() ? " · со звуком" : " · with audio"))
                + (o.size > 0 ? " · " + size(o.size) : "");
        f.videos.add(o);
    }

    static String size(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.2f GB", bytes / (1024f * 1024f * 1024f));
        return String.format(Locale.ROOT, "%.0f MB", bytes / (1024f * 1024f));
    }

    // ------------------------------------------------------------------ playback

    public static final class Playback {
        public String videoUrl, audioUrl;
        public boolean hls;
    }

    private static final int[] QUALITIES = {360, 480, 720, 1080, 1440, 2160};

    public static int[] qualities() { return QUALITIES.clone(); }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_youtube", Context.MODE_PRIVATE);
    }

    public static int getMaxQuality() { return prefs().getInt("max_quality", 1080); }

    public static void setMaxQuality(int height) { prefs().edit().putInt("max_quality", height).apply(); }

    /**
     * Picks what the player streams: HLS for live; otherwise the best video-only format up to the
     * preferred height (H.264 MP4 first: hardware decoding everywhere) plus the best original audio.
     */
    public static void resolvePlayback(String videoUrl, Callback<Playback> callback) {
        async(() -> {
            Formats f = formats(videoUrl);
            Playback p = new Playback();
            if (f.live && f.hlsUrl != null) {
                p.videoUrl = f.hlsUrl;
                p.hls = true;
                return p;
            }
            int max = getMaxQuality();
            VideoOption best = null;
            for (VideoOption v : f.videos) {
                if (!v.videoOnly || v.height > max) continue;
                if (best == null || v.height > best.height || (v.height == best.height && !v.webm && best.webm)) best = v;
            }
            AudioOption audio = null;
            for (AudioOption a : f.audios) { if (!a.webm) { audio = a; break; } }
            if (audio == null && !f.audios.isEmpty()) audio = f.audios.get(0);
            if (best == null || audio == null) {
                // fall back to a muxed (video + audio) format
                VideoOption muxed = null;
                for (VideoOption v : f.videos) if (!v.videoOnly && (muxed == null || v.height > muxed.height)) muxed = v;
                if (muxed == null) {
                    if (f.hlsUrl != null) { p.videoUrl = f.hlsUrl; p.hls = true; return p; }
                    throw new IllegalStateException("Нет доступных форматов для воспроизведения");
                }
                p.videoUrl = muxed.url;
                return p;
            }
            p.videoUrl = best.url;
            p.audioUrl = audio.url;
            return p;
        }, callback);
    }

    // ------------------------------------------------------------------ HTTP for the extractor

    private static final class HttpDownloader extends Downloader {
        private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

        @Override
        public Response execute(Request request) throws java.io.IOException, ReCaptchaException {
            HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(request.httpMethod());
            boolean hasAgent = false;
            if (request.headers() != null) {
                for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                    if ("User-Agent".equalsIgnoreCase(h.getKey())) hasAgent = true;
                    for (String value : h.getValue()) c.addRequestProperty(h.getKey(), value);
                }
            }
            if (!hasAgent) c.setRequestProperty("User-Agent", USER_AGENT);
            byte[] body = request.dataToSend();
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
            }
            int code = c.getResponseCode();
            if (code == 429) {
                c.disconnect();
                throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int n;
                    while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
                    text = out.toString(StandardCharsets.UTF_8.name());
                }
            }
            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() != null) headers.put(h.getKey(), h.getValue());
            }
            String latest = c.getURL().toString();
            String message = c.getResponseMessage();
            c.disconnect();
            return new Response(code, message, headers, text, latest);
        }
    }
}
