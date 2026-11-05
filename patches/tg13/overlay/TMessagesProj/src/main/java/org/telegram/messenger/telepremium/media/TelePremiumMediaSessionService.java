package org.telegram.messenger.telepremium.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import org.telegram.messenger.R;
import org.telegram.ui.TelePremiumPlayerActivity;

/**
 * MediaSession/notification bridge for TelePremium playback.
 *
 * Important lifecycle rules:
 * - the service is NOT sticky, so Android must not resurrect an old player card;
 * - audio-focus gain never starts playback by itself;
 * - the notification is user-dismissible and its delete action fully closes playback;
 * - paused playback is detached from foreground mode, making the card freely removable;
 * - STOP from notification, MediaSession or swipe always releases the shared player.
 */
public final class TelePremiumMediaSessionService extends Service implements TelePremiumPlaybackController.Listener {
    private static final String CHANNEL = "telepremium_media";
    private static final int NOTIFICATION_ID = 7717;
    public static final String ACTION_TOGGLE = "telepremium.media.TOGGLE";
    public static final String ACTION_NEXT = "telepremium.media.NEXT";
    public static final String ACTION_PREVIOUS = "telepremium.media.PREVIOUS";
    public static final String ACTION_STOP = "telepremium.media.STOP";
    public static final String ACTION_SEEK = "telepremium.media.SEEK";
    public static final String ACTION_REC = "telepremium.media.REC";
    private static final String EXTRA_SEGMENT = "segment";

    private TelePremiumPlaybackController controller;
    private MediaSession session;
    private AudioManager audioManager;
    private boolean noisyRegistered;
    private boolean shuttingDown;
    private long publishedDuration = -1;
    private String artworkFor;
    private android.graphics.Bitmap artwork;
    private TelePremiumMediaNotificationViews cardViews;
    private final TelePremiumStreamRecorder.Listener recorderListener = (recording, elapsed, saved, error) -> {
        if (!shuttingDown && controller != null && controller.getCurrent() != null) updateSessionAndNotification();
        if (saved != null) {
            try { android.widget.Toast.makeText(this, "REC ✓ " + saved, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
        }
    };
    private long lastCardUpdate;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (controller == null) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // Never remember an implicit resume request. A later AUDIOFOCUS_GAIN must
            // not wake TelePremium up by itself; playback resumes only by user action.
            controller.pause();
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (controller != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) controller.pause();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        controller = TelePremiumPlaybackController.getInstance();
        controller.addListener(this);
        TelePremiumStreamRecorder.addListener(recorderListener);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        createSession();
        registerNoisy();

        // startForegroundService() is only issued while a real media item is being
        // opened. If Android somehow creates us without one, do not leave a ghost card.
        if (controller.getCurrent() != null) {
            startForeground(NOTIFICATION_ID, buildNotification());
            if (!controller.isPlaying()) detachForegroundNotification();
        } else {
            stopSelf();
        }
    }

    private void createSession() {
        session = new MediaSession(this, "TelePremiumMedia");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { controller.play(); }
            @Override public void onPause() { controller.pause(); }
            @Override public void onSkipToNext() { controller.next(); }
            @Override public void onSkipToPrevious() { controller.previous(); }
            @Override public void onSeekTo(long pos) { controller.seekTo(pos); }
            @Override public void onStop() { shutdownPlayback(); }
        });
        session.setActive(true);
        updateSession();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE.equals(action)) controller.toggle();
            else if (ACTION_NEXT.equals(action)) controller.next();
            else if (ACTION_PREVIOUS.equals(action)) controller.previous();
            else if (ACTION_REC.equals(action)) {
                TelePremiumMediaItem current = controller.getCurrent();
                if (current != null) TelePremiumStreamRecorder.toggle(current.title);
            }
            else if (ACTION_SEEK.equals(action)) {
                long duration = controller.getDuration();
                int segment = intent.getIntExtra(EXTRA_SEGMENT, -1);
                if (duration > 0 && segment >= 0) {
                    float fraction = (segment + 0.5f) / TelePremiumMediaNotificationViews.SEEK_SEGMENTS;
                    controller.seekTo((long) (duration * Math.min(1f, fraction)));
                }
            }
            else if (ACTION_STOP.equals(action)) {
                shutdownPlayback();
                return START_NOT_STICKY;
            }
        }

        if (controller == null || controller.getCurrent() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        syncNotificationLifecycle();
        // Critical: never let Android recreate this service with a null Intent after
        // process pressure. That was one source of the apparently self-starting card.
        return START_NOT_STICKY;
    }

    @Override public void onMediaChanged(TelePremiumMediaItem item) {
        if (shuttingDown) return;
        if (item == null) {
            removeNotificationAndStop();
            return;
        }
        updateSessionAndNotification();
    }

    @Override public void onStateChanged(int state) {
        if (shuttingDown || controller == null) return;
        if (controller.isPlaying() && audioManager != null) {
            try { audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); } catch (Throwable ignore) {}
        }
        updateSessionAndNotification();
    }

    @Override public void onProgress(long position, long duration, long buffered, int bufferedPercent) {
        if (session == null) return;
        // The lock-screen / shade seek bar appears only once the session knows the duration.
        if (duration > 0 && duration != publishedDuration) updateSession();
        else updatePlaybackState(position);
        // The custom shade card draws its own seek bar: refresh it once a second while playing.
        TelePremiumMediaItem playing = controller == null ? null : controller.getCurrent();
        if (useCustomCard() && playing != null && (!playing.live || TelePremiumStreamRecorder.isRecording()) && controller.isPlaying() && !shuttingDown
                && android.os.SystemClock.elapsedRealtime() - lastCardUpdate >= 1000) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                try { nm.notify(NOTIFICATION_ID, buildNotification()); } catch (Throwable e) { org.telegram.messenger.FileLog.e(e); }
            }
        }
    }

    /**
     * TelePremium card (logo/note square, seek bar, centered buttons) on every Android version.
     * It is posted WITHOUT MediaStyle: with MediaStyle, Android 13+ and MIUI/HyperOS replace any
     * custom layout by their own system media player. The MediaSession stays active for
     * headset / Bluetooth / lock-screen controls. Can be switched back in TelePremium settings.
     */
    private static boolean useCustomCard() {
        return Build.VERSION.SDK_INT >= 24 && org.telegram.messenger.telepremium.TelePremiumUiPrefs.customPlayerNotification();
    }

    @Override public void onError(String message) { updateSessionAndNotification(); }

    private void updateSessionAndNotification() {
        if (session == null || controller == null || shuttingDown) return;
        if (controller.getCurrent() == null) {
            removeNotificationAndStop();
            return;
        }
        updateSession();
        syncNotificationLifecycle();
    }

    /**
     * Keep true playback foreground-safe, but immediately detach foreground mode when
     * paused/ready/error. The detached notification is a normal clearable notification.
     */
    private void syncNotificationLifecycle() {
        if (controller == null || controller.getCurrent() == null || shuttingDown) return;
        int state = controller.getState();
        boolean active = controller.isPlaying()
                || state == TelePremiumPlaybackController.STATE_RESOLVING
                || state == TelePremiumPlaybackController.STATE_BUFFERING
                || state == TelePremiumPlaybackController.STATE_SEEKING;
        Notification notification = buildNotification();
        if (active) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            detachForegroundNotification();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private void detachForegroundNotification() {
        try { stopForeground(false); } catch (Throwable ignore) {}
    }

    private void shutdownPlayback() {
        if (shuttingDown) return;
        shuttingDown = true;
        TelePremiumStreamRecorder.stop();
        try {
            if (controller != null) controller.stopAndRelease();
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
        removeNotificationAndStop();
    }

    private void removeNotificationAndStop() {
        try { stopForeground(true); } catch (Throwable ignore) {}
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void updateSession() {
        TelePremiumMediaItem item = controller == null ? null : controller.getCurrent();
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        if (item != null) {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, item.title);
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist == null ? item.sourceLabel() : item.artist);
            if (item.album != null) metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, item.album);
            long duration = controller.getDuration() > 0 ? controller.getDuration() : item.duration;
            publishedDuration = item.live ? 0 : duration;
            if (!item.live && duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            loadArtwork(item);
            if (artwork != null && item.id.equals(artworkFor)) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
            }
        }
        session.setMetadata(metadata.build());
        updatePlaybackState(controller == null ? 0 : controller.getPosition());
    }

    private void updatePlaybackState(long position) {
        if (session == null || controller == null) return;
        int state = controller.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        if (controller.getState() == TelePremiumPlaybackController.STATE_BUFFERING || controller.getState() == TelePremiumPlaybackController.STATE_RESOLVING)
            state = PlaybackState.STATE_BUFFERING;
        else if (controller.getState() == TelePremiumPlaybackController.STATE_ERROR) state = PlaybackState.STATE_ERROR;
        else if (controller.getState() == TelePremiumPlaybackController.STATE_IDLE) state = PlaybackState.STATE_STOPPED;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(state, position, controller.getSpeed());
        session.setPlaybackState(builder.build());
    }

    private void loadArtwork(TelePremiumMediaItem item) {
        if (item == null || item.id.equals(artworkFor)) return;
        artworkFor = item.id;
        artwork = null;
        final String id = item.id;
        TelePremiumArtworkLoader.load(this, item, 512, bitmap -> {
            if (shuttingDown || bitmap == null || !id.equals(artworkFor)) return;
            artwork = bitmap;
            updateSessionAndNotification();
        });
    }

    private Notification buildNotification() {
        TelePremiumMediaItem item = controller == null ? null : controller.getCurrent();
        Intent open = new Intent(this, TelePremiumPlayerActivity.class);
        open.putExtra(TelePremiumPlayerActivity.EXTRA_OPEN_CURRENT, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 100, open, pendingFlags());
        PendingIntent close = servicePending(ACTION_STOP, 4);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle(item == null ? "TelePremium" : item.title)
                .setContentText(item == null ? "Медиацентр" : (item.artist == null ? item.sourceLabel() : item.artist))
                .setContentIntent(content)
                // Do not mark the media card as Ongoing. On Android/MIUI this flag
                // blocks the user's swipe gesture even after playback is paused.
                .setOngoing(false)
                // Swiping the notification means "close player", not merely hide UI.
                .setDeleteIntent(close)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT);
        boolean custom = useCustomCard() && item != null;
        if (!custom) builder
                .addAction(new Notification.Action.Builder(R.drawable.tp_ic_prev, "Назад", servicePending(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(controller != null && controller.isPlaying() ? R.drawable.tp_ic_pause : R.drawable.tp_ic_play,
                        controller != null && controller.isPlaying() ? "Пауза" : "Играть", servicePending(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(R.drawable.tp_ic_next, "Далее", servicePending(ACTION_NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", close).build());
        if (artwork != null && item != null && item.id.equals(artworkFor)) builder.setLargeIcon(artwork);
        if (!custom && Build.VERSION.SDK_INT >= 21 && session != null) {
            builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        }
        if (custom) {
            if (Build.VERSION.SDK_INT >= 31) builder.setStyle(new Notification.DecoratedCustomViewStyle());
            try {
                if (cardViews == null) cardViews = new TelePremiumMediaNotificationViews(this);
                boolean playing = controller.isPlaying();
                android.graphics.Bitmap logo = artwork != null && item.id.equals(artworkFor) ? artwork : null;
                TelePremiumMediaNotificationViews.Actions actions = new TelePremiumMediaNotificationViews.Actions() {
                    @Override public PendingIntent toggle() { return servicePending(ACTION_TOGGLE, 2); }
                    @Override public PendingIntent previous() { return servicePending(ACTION_PREVIOUS, 1); }
                    @Override public PendingIntent next() { return servicePending(ACTION_NEXT, 3); }
                    @Override public PendingIntent close() { return close; }
                    @Override public PendingIntent record() { return servicePending(ACTION_REC, 5); }
                    @Override public PendingIntent seek(int segment) {
                        Intent intent = new Intent(TelePremiumMediaSessionService.this, TelePremiumMediaSessionService.class)
                                .setAction(ACTION_SEEK).putExtra(EXTRA_SEGMENT, segment);
                        return PendingIntent.getService(TelePremiumMediaSessionService.this, 200 + segment, intent, pendingFlags());
                    }
                };
                long duration = item.live ? 0 : controller.getDuration();
                builder.setCustomContentView(cardViews.small(item, logo, playing, actions));
                builder.setCustomBigContentView(cardViews.big(item, logo, playing, controller.getPosition(), duration,
                        controller.getBufferedPosition(), actions));
                lastCardUpdate = android.os.SystemClock.elapsedRealtime();
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }
        return builder.build();
    }

    private PendingIntent servicePending(String action, int request) {
        Intent intent = new Intent(this, TelePremiumMediaSessionService.class).setAction(action);
        return PendingIntent.getService(this, request, intent, pendingFlags());
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "TelePremium Media", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Фоновое воспроизведение TelePremium");
        nm.createNotificationChannel(channel);
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyRegistered = true;
    }

    @Override public void onDestroy() {
        if (controller != null) controller.removeListener(this);
        TelePremiumStreamRecorder.removeListener(recorderListener);
        if (audioManager != null) try { audioManager.abandonAudioFocus(focusListener); } catch (Throwable ignore) {}
        if (noisyRegistered) try { unregisterReceiver(noisyReceiver); } catch (Throwable ignore) {}
        if (session != null) { session.setActive(false); session.release(); session = null; }
        try { stopForeground(true); } catch (Throwable ignore) {}
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
