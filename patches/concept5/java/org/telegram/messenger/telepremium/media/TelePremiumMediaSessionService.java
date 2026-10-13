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

/** Foreground media session for background audio, headset events and lock-screen controls. */
public final class TelePremiumMediaSessionService extends Service implements TelePremiumPlaybackController.Listener {
    private static final String CHANNEL = "telepremium_media";
    private static final int NOTIFICATION_ID = 7717;
    public static final String ACTION_TOGGLE = "telepremium.media.TOGGLE";
    public static final String ACTION_NEXT = "telepremium.media.NEXT";
    public static final String ACTION_PREVIOUS = "telepremium.media.PREVIOUS";
    public static final String ACTION_STOP = "telepremium.media.STOP";

    private TelePremiumPlaybackController controller;
    private MediaSession session;
    private AudioManager audioManager;
    private boolean noisyRegistered;
    private boolean foreground;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) controller.pause();
        // Never auto-resume on AUDIOFOCUS_GAIN. Playback may only restart after
        // an explicit user action; otherwise old notification/session state can
        // resurrect playback after calls, alarms or Bluetooth focus changes.
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) controller.pause();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        controller = TelePremiumPlaybackController.getInstance();
        controller.addListener(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        createSession();
        registerNoisy();
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
            @Override public void onStop() { stopPlaybackAndService(); }
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
            else if (ACTION_STOP.equals(action)) {
                stopPlaybackAndService();
                return START_NOT_STICKY;
            }
        }
        if (controller.getCurrent() == null) {
            // A sticky/OS recreation without an active item must not leave a
            // phantom media card in the notification shade.
            stopSelf();
            return START_NOT_STICKY;
        }
        updateForegroundState();
        return START_NOT_STICKY;
    }

    @Override public void onMediaChanged(TelePremiumMediaItem item) {
        if (item == null) {
            stopPlaybackAndService();
            return;
        }
        updateSessionAndNotification();
    }

    @Override public void onStateChanged(int state) {
        if (controller.isPlaying() && audioManager != null) {
            try { audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); } catch (Throwable ignore) {}
        }
        updateSessionAndNotification();
    }
    @Override public void onProgress(long position, long duration, long buffered, int bufferedPercent) {
        if (session != null) updatePlaybackState(position);
    }
    @Override public void onError(String message) { updateSessionAndNotification(); }

    private void updateSessionAndNotification() {
        if (session == null || controller == null || controller.getCurrent() == null) return;
        updateSession();
        updateForegroundState();
    }

    private void updateForegroundState() {
        if (controller == null || controller.getCurrent() == null) return;
        Notification notification = buildNotification();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (controller.isPlaying()) {
            startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } else {
            if (foreground) {
                // Detach the paused notification from foreground mode. It then
                // behaves like a normal dismissible media card and can be swiped.
                stopForeground(false);
                foreground = false;
            }
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private void updateSession() {
        TelePremiumMediaItem item = controller == null ? null : controller.getCurrent();
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        if (item != null) {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, item.title);
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist == null ? item.sourceLabel() : item.artist);
            if (item.duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, item.duration);
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
        else if (controller.getCurrent() == null) state = PlaybackState.STATE_STOPPED;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(state, position, controller.getSpeed());
        session.setPlaybackState(builder.build());
    }

    private Notification buildNotification() {
        TelePremiumMediaItem item = controller == null ? null : controller.getCurrent();
        Intent open = new Intent(this, TelePremiumPlayerActivity.class);
        open.putExtra(TelePremiumPlayerActivity.EXTRA_OPEN_CURRENT, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 100, open, pendingFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle(item == null ? "TelePremium" : item.title)
                .setContentText(item == null ? "Медиацентр" : (item.artist == null ? item.sourceLabel() : item.artist))
                .setContentIntent(content)
                .setDeleteIntent(servicePending(ACTION_STOP, 4))
                .setOngoing(false)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Назад", servicePending(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(controller != null && controller.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        controller != null && controller.isPlaying() ? "Пауза" : "Играть", servicePending(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Далее", servicePending(ACTION_NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", servicePending(ACTION_STOP, 4)).build());
        if (Build.VERSION.SDK_INT >= 21 && session != null) {
            builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private void stopPlaybackAndService() {
        if (controller != null && controller.getCurrent() != null) controller.stopAndRelease();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        stopSelf();
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
        if (audioManager != null) try { audioManager.abandonAudioFocus(focusListener); } catch (Throwable ignore) {}
        if (noisyRegistered) try { unregisterReceiver(noisyReceiver); } catch (Throwable ignore) {}
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (foreground) stopForeground(true); else stopForeground(false);
        foreground = false;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && (controller == null || controller.getCurrent() == null)) nm.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
