package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.telepremium.cloud.CloudDownloadManager;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudPeerManager;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-level playback core. One player/queue is shared by portrait, landscape,
 * Media Center and MediaSession. Cloud sources use Telegram range streaming.
 */
public final class TelePremiumPlaybackController {
    public static final int STATE_IDLE = 0;
    public static final int STATE_RESOLVING = 1;
    public static final int STATE_BUFFERING = 2;
    public static final int STATE_READY = 3;
    public static final int STATE_PLAYING = 4;
    public static final int STATE_PAUSED = 5;
    public static final int STATE_SEEKING = 6;
    public static final int STATE_ENDED = 7;
    public static final int STATE_ERROR = 8;

    public interface Listener {
        default void onMediaChanged(TelePremiumMediaItem item) {}
        default void onStateChanged(int state) {}
        default void onProgress(long position, long duration, long buffered, int bufferedPercent) {}
        default void onVideoSizeChanged(int width, int height) {}
        default void onError(String message) {}
    }

    private static final TelePremiumPlaybackController INSTANCE = new TelePremiumPlaybackController();
    public static TelePremiumPlaybackController getInstance() { return INSTANCE; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final TelePremiumQueueManager queue = TelePremiumQueueManager.getInstance();
    private final SharedPreferences resumePrefs = ApplicationLoader.applicationContext.getSharedPreferences("telepremium_media_resume_v1", Context.MODE_PRIVATE);

    private VideoPlayer player;
    private SurfaceView surfaceView;
    private TelePremiumMediaItem current;
    private TLRPC.Document streamDocument;
    private TLRPC.Message streamMessage;
    private CloudDownloadManager fallbackDownload;
    private boolean cloudStreaming;
    private boolean fallbackAttempted;
    private boolean subtitlesEnabled = true;
    private float speed = 1f;
    private int state = STATE_IDLE;
    private int generation;
    private long pendingResume;
    private int reconnectAttempts;
    private long lastReconnectAt;
    private static final int MAX_RECONNECTS = 3;
    private long lastResumeWrite;
    private int videoWidth;
    private int videoHeight;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null) {
                try {
                    long position = normalize(player.getCurrentPosition());
                    long duration = normalize(player.getDuration());
                    long buffered = normalize(player.getBufferedPosition());
                    int percent = Math.max(0, Math.min(100, player.getBufferedPercentage()));
                    for (Listener listener : listeners) listener.onProgress(position, duration, buffered, percent);
                    if (current != null && current.isVideo() && duration > 60_000 && position > 10_000 && System.currentTimeMillis() - lastResumeWrite > 5000) {
                        resumePrefs.edit().putLong(current.id, position).apply();
                        lastResumeWrite = System.currentTimeMillis();
                    }
                } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
            }
            handler.postDelayed(this, 500);
        }
    };

    private TelePremiumPlaybackController() { handler.post(ticker); }

    public TelePremiumQueueManager getQueue() { return queue; }
    public TelePremiumMediaItem getCurrent() { return current; }
    public int getState() { return state; }
    public float getSpeed() { return speed; }
    public boolean isCloudStreaming() { return cloudStreaming; }
    public boolean isSubtitlesEnabled() { return subtitlesEnabled; }
    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }
    public String getDecoderLabel() { return "HW"; }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onMediaChanged(current);
        listener.onStateChanged(state);
        if (videoWidth > 0 && videoHeight > 0) listener.onVideoSizeChanged(videoWidth, videoHeight);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void bindSurface(SurfaceView view) {
        surfaceView = view;
        if (view != null) outputSurface = null;
        if (player != null) {
            try { player.setSurfaceView(view); } catch (Throwable ignore) {}
        }
    }

    private android.view.Surface outputSurface;

    /** Video goes to a plain Surface (the smooth-motion renderer's input) instead of a SurfaceView. */
    public void bindOutputSurface(android.view.Surface surface) {
        outputSurface = surface;
        if (surface != null) surfaceView = null;
        if (player != null) {
            try { player.setSurface(surface); } catch (Throwable ignore) {}
        }
    }

    public void play(TelePremiumMediaItem item) {
        if (item == null) return;
        int existing = -1;
        java.util.List<TelePremiumMediaItem> snapshot = queue.snapshot();
        for (int i = 0; i < snapshot.size(); i++) if (item.id != null && item.id.equals(snapshot.get(i).id)) { existing = i; break; }
        if (existing < 0) {
            if (!queue.append(item)) {
                // Other kind than the queue (music vs video): start a new queue of this kind.
                queue.replace(java.util.Collections.singletonList(item), 0);
                existing = 0;
            } else {
                existing = queue.size() - 1;
            }
        }
        queue.setCurrentIndex(existing);
        playInternal(item);
    }

    public void playCurrent() {
        TelePremiumMediaItem item = queue.current();
        if (item != null) playInternal(item);
    }

    public void next() {
        TelePremiumMediaItem item = queue.next();
        if (item != null) playInternal(item);
    }

    public void previous() {
        TelePremiumMediaItem item = queue.previous();
        if (item != null) playInternal(item);
    }

    public void toggle() {
        if (player == null) { playCurrent(); return; }
        try {
            boolean value = !player.getPlayWhenReady();
            player.setPlayWhenReady(value);
            setState(value ? STATE_PLAYING : STATE_PAUSED);
            if (value) ensureMediaService();
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
    }

    public void play() {
        if (player == null) { playCurrent(); return; }
        try { player.setPlayWhenReady(true); setState(STATE_PLAYING); ensureMediaService(); } catch (Throwable ignore) {}
    }

    public void pause() {
        if (player == null) return;
        try { player.pause(); setState(STATE_PAUSED); } catch (Throwable ignore) {}
    }

    public void seekTo(long position) {
        if (player == null) return;
        try {
            setState(STATE_SEEKING);
            player.seekTo(Math.max(0, position), true);
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
    }

    public void seekRelative(long delta) {
        if (player == null) return;
        try {
            long duration = normalize(player.getDuration());
            long position = normalize(player.getCurrentPosition());
            long target = Math.max(0, duration > 0 ? Math.min(duration, position + delta) : position + delta);
            seekTo(target);
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
    }

    public void setPlaybackSpeed(float value) {
        speed = Math.max(0.5f, Math.min(2.0f, value));
        if (player != null) try { player.setPlaybackSpeed(speed); } catch (Throwable ignore) {}
    }

    public void setSubtitlesEnabled(boolean enabled) {
        subtitlesEnabled = enabled;
        if (player == null || player.player == null) return;
        try {
            player.player.setTrackSelectionParameters(player.player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build());
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
    }

    public boolean isPlaying() {
        try { return player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED; }
        catch (Throwable ignore) { return false; }
    }

    public long getDuration() { try { return player == null ? 0 : normalize(player.getDuration()); } catch (Throwable ignore) { return 0; } }
    public long getPosition() { try { return player == null ? 0 : normalize(player.getCurrentPosition()); } catch (Throwable ignore) { return 0; } }
    public long getBufferedPosition() { try { return player == null ? 0 : normalize(player.getBufferedPosition()); } catch (Throwable ignore) { return 0; } }

    public void stopAndRelease() {
        generation++;
        if (fallbackDownload != null) { try { fallbackDownload.cancel(); } catch (Throwable ignore) {} fallbackDownload = null; }
        releasePlayer();
        current = null;
        streamDocument = null;
        streamMessage = null;
        setState(STATE_IDLE);
        notifyMediaChanged();
    }

    private void playInternal(TelePremiumMediaItem item) {
        generation++;
        int token = generation;
        current = item;
        fallbackAttempted = false;
        cloudStreaming = false;
        reconnectAttempts = 0;
        pendingResume = item.isVideo() ? resumePrefs.getLong(item.id, item.resumePosition) : item.resumePosition;
        videoWidth = videoHeight = 0;
        notifyMediaChanged();
        setState(STATE_RESOLVING);
        ensureMediaService();
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) resolveCloud(item, token);
        else if (TelePremiumYouTube.isYouTube(item)) resolveYouTube(item, token);
        else {
            Uri uri = item.uri == null ? null : Uri.parse(item.uri);
            if (uri == null) { fail("Источник не указан"); return; }
            startPlayer(uri, item, token);
        }
    }

    private void resolveCloud(TelePremiumMediaItem item, int token) {
        if (item.cloudAccount < 0 || item.telegramMessageId == 0) { fail("Облачный файл не указан"); return; }
        // Encrypted media cannot be played through the Telegram raw-document stream.
        if (item.encryptionVersion!=0){
            startFullDownloadFallback(item,token,"Vault требует проверенной расшифровки файла");
            return;
        }
        if (item.isMultipart()) { resolveCloudMultipart(item, token); return; }
        TelePremiumCloudPeerManager peer = TelePremiumCloudPeerManager.getInstance(item.cloudAccount);
        peer.ensure((dialogId, error) -> {
            if (token != generation) return;
            if (error != null || peer.getInputChannel() == null) { AndroidUtilities.runOnUIThread(() -> fail(error == null ? "Облако недоступно" : error)); return; }
            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = peer.getInputChannel();
            req.id.add((int) item.telegramMessageId);
            ConnectionsManager.getInstance(item.cloudAccount).sendRequest(req, (response, requestError) -> AndroidUtilities.runOnUIThread(() -> {
                if (token != generation) return;
                if (requestError != null) { fail(requestError.text); return; }
                if (!(response instanceof TLRPC.messages_Messages) || ((TLRPC.messages_Messages) response).messages.isEmpty()) { fail("Файл удалён из облака"); return; }
                TLRPC.Message message = ((TLRPC.messages_Messages) response).messages.get(0);
                if (message.media == null || message.media.document == null) { fail("Telegram document отсутствует"); return; }
                streamMessage = message;
                streamDocument = message.media.document;
                FileStreamLoadOperation.setPriorityForDocument(streamDocument, FileLoader.PRIORITY_HIGH);
                Uri uri = FileStreamLoadOperation.prepareUri(item.cloudAccount, streamDocument, streamMessage);
                if (uri == null) { startFullDownloadFallback(item, token, "Не удалось создать поток"); return; }
                cloudStreaming = !"file".equalsIgnoreCase(uri.getScheme());
                startPlayer(uri, item, token);
            }));
        });
    }

    /**
     * A Cloud file larger than the Telegram document limit is stored as several documents.
     * All parts are resolved in one request and exposed to ExoPlayer as a single seekable stream.
     */
    private void resolveCloudMultipart(TelePremiumMediaItem item, int token) {
        TelePremiumCloudPeerManager peer = TelePremiumCloudPeerManager.getInstance(item.cloudAccount);
        peer.ensure((dialogId, error) -> {
            if (token != generation) return;
            if (error != null || peer.getInputChannel() == null) { AndroidUtilities.runOnUIThread(() -> fail(error == null ? "Облако недоступно" : error)); return; }
            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = peer.getInputChannel();
            for (long id : item.partMessageIds) req.id.add((int) id);
            ConnectionsManager.getInstance(item.cloudAccount).sendRequest(req, (response, requestError) -> AndroidUtilities.runOnUIThread(() -> {
                if (token != generation) return;
                if (requestError != null) { fail(requestError.text); return; }
                if (!(response instanceof TLRPC.messages_Messages)) { fail("Файл удалён из облака"); return; }
                java.util.HashMap<Integer, TLRPC.Message> byId = new java.util.HashMap<>();
                for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) if (m != null) byId.put(m.id, m);
                int count = item.partMessageIds.length;
                Uri[] parts = new Uri[count];
                long[] sizes = new long[count];
                for (int i = 0; i < count; i++) {
                    TLRPC.Message m = byId.get((int) item.partMessageIds[i]);
                    if (m == null || m.media == null || m.media.document == null) { fail("Часть " + (i + 1) + " из " + count + " отсутствует в облаке"); return; }
                    TLRPC.Document document = m.media.document;
                    if (i == 0) { streamMessage = m; streamDocument = document; }
                    FileStreamLoadOperation.setPriorityForDocument(document, FileLoader.PRIORITY_HIGH);
                    parts[i] = FileStreamLoadOperation.prepareUri(item.cloudAccount, document, m);
                    if (parts[i] == null) { fail("Не удалось создать поток части " + (i + 1)); return; }
                    sizes[i] = document.size;
                }
                cloudStreaming = true;
                startPlayer(TelePremiumMultipartDataSource.register(parts, sizes), item, token);
            }));
        });
    }

    /** Separate audio stream (YouTube DASH) and forced HLS for the next startPlayer call. */
    private Uri pendingAudioUri;
    private boolean pendingHls;

    private void resolveYouTube(TelePremiumMediaItem item, int token) {
        TelePremiumYouTube.resolvePlayback(item.uri, (playback, error) -> {
            if (token != generation) return;
            if (playback == null) { fail(error == null ? "YouTube недоступен" : error); return; }
            pendingAudioUri = playback.audioUrl == null ? null : Uri.parse(playback.audioUrl);
            pendingHls = playback.hls;
            startPlayer(Uri.parse(playback.videoUrl), item, token);
        });
    }

    private void startPlayer(Uri uri, TelePremiumMediaItem item, int token) {
        if (token != generation) return;
        releasePlayer();
        player = new VideoPlayer(true, false);
        player.extraAudioProcessors = TelePremiumBalanceAudioProcessor.chain();
        if (outputSurface != null) player.setSurface(outputSurface);
        else if (surfaceView != null) player.setSurfaceView(surfaceView);
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (token != generation) return;
                if (playbackState == Player.STATE_BUFFERING) setState(STATE_BUFFERING);
                else if (playbackState == Player.STATE_READY) {
                    if (pendingResume > 0 && pendingResume < getDuration() - 5000) {
                        long restore = pendingResume;
                        pendingResume = 0;
                        seekTo(restore);
                    }
                    setState(playWhenReady ? STATE_PLAYING : STATE_READY);
                } else if (playbackState == Player.STATE_ENDED) {
                    setState(STATE_ENDED);
                    if (sleepAtTrackEnd) { sleepAtTrackEnd = false; return; }
                    TelePremiumMediaItem next = queue.next();
                    if (next != null) playInternal(next);
                } else if (!playWhenReady && state != STATE_RESOLVING) setState(STATE_PAUSED);
            }

            @Override public void onError(VideoPlayer videoPlayer, Exception e) {
                if (token != generation) return;
                if (tryReconnect(item, token, e)) return;
                if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD && !fallbackAttempted) startFullDownloadFallback(item, token, "Поток прерван");
                else fail("Декодер устройства не поддерживает этот формат");
            }

            @Override public void onVideoSizeChanged(int width, int height, int rotation, float ratio) {
                videoWidth = width;
                videoHeight = height;
                for (Listener listener : listeners) listener.onVideoSizeChanged(width, height);
            }
            @Override public void onRenderedFirstFrame() {}
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {}
            @Override public boolean onSurfaceDestroyed(android.graphics.SurfaceTexture surfaceTexture) { return false; }
            @Override public void onRenderedFirstFrame(AnalyticsListener.EventTime eventTime) {}
            @Override public void onSeekStarted(AnalyticsListener.EventTime eventTime) { setState(STATE_SEEKING); }
            @Override public void onSeekFinished(AnalyticsListener.EventTime eventTime) { setState(isPlaying() ? STATE_PLAYING : STATE_PAUSED); }
        });
        player.setPlayWhenReady(true);
        try {
            Uri audioUri = pendingAudioUri;
            boolean hls = pendingHls;
            pendingAudioUri = null;
            pendingHls = false;
            if (audioUri != null) player.preparePlayerMerged(uri, audioUri);
            else player.preparePlayer(uri, hls ? "hls" : typeFor(item, uri), FileLoader.PRIORITY_HIGH, 0);
            attachAudioEffects();
            player.setPlaybackSpeed(speed);
            setSubtitlesEnabled(subtitlesEnabled);
        } catch (Throwable t) {
            if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD && !fallbackAttempted) startFullDownloadFallback(item, token, "Ошибка потока");
            else fail("Не удалось открыть поток");
        }
    }

    /** Network/IO playback errors (ExoPlayer codes 2000–2999, timeouts, live-window drops). */
    private static boolean isNetworkError(Exception e) {
        if (!(e instanceof PlaybackException)) return false;
        int code = ((PlaybackException) e).errorCode;
        return (code >= 2000 && code < 3000) || code == PlaybackException.ERROR_CODE_TIMEOUT
                || code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW;
    }

    private static boolean isRemote(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) return item.encryptionVersion == 0;
        if (TelePremiumYouTube.isYouTube(item)) return true;
        String scheme = item.uri == null ? null : Uri.parse(item.uri).getScheme();
        return scheme != null && !"file".equalsIgnoreCase(scheme) && !"content".equalsIgnoreCase(scheme);
    }

    /**
     * A dropped connection is reopened at the same position (up to {@link #MAX_RECONNECTS} times
     * a minute, with growing pauses) instead of failing or downloading the whole Cloud file.
     * Cloud links and YouTube URLs are resolved again, so expired file references are renewed.
     */
    private boolean tryReconnect(TelePremiumMediaItem item, int token, Exception e) {
        if (!isNetworkError(e) || !isRemote(item) || fallbackAttempted) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastReconnectAt > 60_000L) reconnectAttempts = 0;
        if (reconnectAttempts >= MAX_RECONNECTS) return false;
        reconnectAttempts++;
        lastReconnectAt = now;
        long position = getPosition();
        if (!item.live && position > 0) pendingResume = position;
        releasePlayer();
        setState(STATE_BUFFERING);
        handler.postDelayed(() -> {
            if (token != generation) return;
            if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) resolveCloud(item, token);
            else if (TelePremiumYouTube.isYouTube(item)) resolveYouTube(item, token);
            else startPlayer(Uri.parse(item.uri), item, token);
        }, 1200L * reconnectAttempts);
        return true;
    }

    private void startFullDownloadFallback(TelePremiumMediaItem item, int token, String reason) {
        if (fallbackAttempted || item.cloudAccount < 0 || item.telegramMessageId == 0) { fail(reason); return; }
        // A multipart file is many gigabytes: never silently pre-download it after a stream hiccup.
        if (item.isMultipart() && item.encryptionVersion == 0) { fail(reason + " — повторите воспроизведение"); return; }
        fallbackAttempted = true;
        cloudStreaming = false;
        releasePlayer();
        CloudNode node = CloudNode.file("root", item.title, item.mimeType, item.size, null);
        node.telegramMessageId = item.telegramMessageId;
        node.telegramDocumentId = streamDocument == null ? item.telegramDocumentId : streamDocument.id;
        node.encryptionVersion=item.encryptionVersion;
        node.sha256=item.sha256;
        node.localBackupId=item.localBackupId;
        item.copyPartsTo(node);
        fallbackDownload = new CloudDownloadManager(item.cloudAccount);
        fallbackDownload.download(node, (downloadState, file, error) -> {
            if (token != generation) return;
            if (downloadState == CloudDownloadManager.OFFLINE_AVAILABLE && file != null && file.exists()) startPlayer(Uri.fromFile(file), item, token);
            else if (downloadState == CloudDownloadManager.ERROR) fail(error == null ? reason : error);
            else setState(STATE_BUFFERING);
        });
    }

    private String typeFor(TelePremiumMediaItem item, Uri uri) {
        String value = uri == null ? "" : uri.toString().toLowerCase(java.util.Locale.ROOT);
        String mime = item.mimeType == null ? "" : item.mimeType.toLowerCase(java.util.Locale.ROOT);
        if (value.contains(".m3u8") || mime.contains("mpegurl")) return "hls";
        if (value.contains(".mpd") || mime.contains("dash")) return "dash";
        return "other";
    }

    /** Sound is processed by TelePremium's own DSP (see TelePremiumBalanceAudioProcessor); only playback options here. */
    private void attachAudioEffects() {
        applySkipSilence();
    }

    public void applySkipSilence() {
        try {
            if (player != null && player.player != null) player.player.setSkipSilenceEnabled(TelePremiumAudioEffects.isSkipSilence());
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    // ---- sleep timer ----

    public static final long SLEEP_END_OF_TRACK = -1;
    private long sleepAt;
    private boolean sleepAtTrackEnd;
    private final Runnable sleepRunnable = () -> {
        sleepAt = 0;
        pause();
        for (Listener listener : listeners) listener.onStateChanged(state);
    };

    /** @param minutes 0 = off, {@link #SLEEP_END_OF_TRACK} = stop after the current track. */
    public void setSleepTimer(long minutes) {
        handler.removeCallbacks(sleepRunnable);
        sleepAt = 0;
        sleepAtTrackEnd = minutes == SLEEP_END_OF_TRACK;
        if (minutes > 0) {
            sleepAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L;
            handler.postDelayed(sleepRunnable, minutes * 60_000L);
        }
    }

    /** Remaining ms, {@link #SLEEP_END_OF_TRACK} for end of track, 0 when off. */
    public long getSleepTimerRemaining() {
        if (sleepAtTrackEnd) return SLEEP_END_OF_TRACK;
        return sleepAt == 0 ? 0 : Math.max(0, sleepAt - android.os.SystemClock.elapsedRealtime());
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.releasePlayer(false); } catch (Throwable ignore) {}
            player = null;
        }
    }

    private void fail(String message) {
        setState(STATE_ERROR);
        for (Listener listener : listeners) listener.onError(message == null ? "Ошибка воспроизведения" : message);
    }

    private void setState(int value) {
        if (state == value) return;
        state = value;
        for (Listener listener : listeners) listener.onStateChanged(value);
    }

    private void notifyMediaChanged() {
        for (Listener listener : listeners) listener.onMediaChanged(current);
    }

    private void ensureMediaService() {
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, TelePremiumMediaSessionService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
    }

    private static long normalize(long value) {
        return value < 0 || value > 86_400_000_000L ? 0 : value;
    }
}
