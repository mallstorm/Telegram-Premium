package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.media.TelePremiumMediaItem;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.media.TelePremiumQueueManager;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** TelePremium Media Center player base. V10 MX-like behavior is applied by apply_media_center_v7.py. */
public class TelePremiumPlayerActivity extends Activity implements TelePremiumPlaybackController.Listener {
    public static final String EXTRA_PATH = "telepremium_player_path";
    public static final String EXTRA_NAME = "telepremium_player_name";
    public static final String EXTRA_MIME = "telepremium_player_mime";
    public static final String EXTRA_CLOUD_ACCOUNT = "telepremium_player_cloud_account";
    public static final String EXTRA_CLOUD_MESSAGE_ID = "telepremium_player_cloud_message_id";
    public static final String EXTRA_CLOUD_DOCUMENT_ID = "telepremium_player_cloud_document_id";
    public static final String EXTRA_CLOUD_SIZE = "telepremium_player_cloud_size";
    public static final String EXTRA_OPEN_CURRENT = "telepremium_player_open_current";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
    private TelePremiumMediaItem launchItem;
    private TelePremiumMediaTheme mediaTheme;

    private FrameLayout root;
    private AspectSurfaceView surface;
    private TextView audioArtwork, titleView, artistView, sourceView, stateView, positionView, durationView;
    private TextView playButton, speedButton, fitButton, subtitlesButton, hwButton, queueButton;
    private SeekBar seekBar;
    private View topChrome, bottomChrome;
    private boolean userSeeking, landscape, chromeVisible = true, fitMode = true, pip;
    private final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private int speedIndex = 2;
    private GestureDetector gestures;
    private float touchDownX, touchDownY;
    private long gestureStartPosition;
    private float gestureStartBrightness;
    private int gestureStartVolume;
    private int gestureMode;

    public static boolean canHandle(String name, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("audio/") || m.startsWith("video/") || m.contains("mpegurl") || m.contains("dash")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".amr")
                || n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mkv") || n.endsWith(".webm")
                || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".3gp") || n.endsWith(".ts")
                || n.endsWith(".m2ts") || n.endsWith(".m3u8") || n.endsWith(".mpd");
    }

    public static void open(Context context, File file, String name, String mime) {
        if (context == null || file == null) return;
        Intent intent = new Intent(context, TelePremiumPlayerActivity.class);
        intent.putExtra(EXTRA_PATH, file.getAbsolutePath());
        intent.putExtra(EXTRA_NAME, TextUtils.isEmpty(name) ? file.getName() : name);
        intent.putExtra(EXTRA_MIME, mime);
        start(context, intent);
    }

    public static void openCloud(Context context, int account, long telegramMessageId, String name, String mime, long size) {
        if (context == null || account < 0 || telegramMessageId == 0) return;
        Intent intent = new Intent(context, TelePremiumPlayerActivity.class);
        intent.putExtra(EXTRA_CLOUD_ACCOUNT, account);
        intent.putExtra(EXTRA_CLOUD_MESSAGE_ID, telegramMessageId);
        intent.putExtra(EXTRA_CLOUD_SIZE, size);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_MIME, mime);
        start(context, intent);
    }

    public static void openMedia(Context context, TelePremiumMediaItem item) {
        if (context == null || item == null) return;
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) {
            openCloud(context, item.cloudAccount, item.telegramMessageId, item.title, item.mimeType, item.size);
            return;
        }
        Intent intent = new Intent(context, TelePremiumPlayerActivity.class);
        intent.setData(item.uri == null ? null : Uri.parse(item.uri));
        intent.putExtra(EXTRA_NAME, item.title);
        intent.putExtra(EXTRA_MIME, item.mimeType);
        start(context, intent);
    }

    public static void openCurrent(Context context) {
        Intent intent = new Intent(context, TelePremiumPlayerActivity.class);
        intent.putExtra(EXTRA_OPEN_CURRENT, true);
        start(context, intent);
    }

    private static void start(Context context, Intent intent) {
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        mediaTheme = TelePremiumMediaTheme.from(this);
        mediaTheme.applySystemBars(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        launchItem = readLaunchItem(getIntent());
        controller.addListener(this);
        buildUi();
        if (launchItem != null) controller.play(launchItem);
        else if (controller.getCurrent() == null) {
            Toast.makeText(this, "Медиа не выбрано", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            bindCurrent(controller.getCurrent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        TelePremiumMediaItem item = readLaunchItem(intent);
        if (item != null) controller.play(item); else bindCurrent(controller.getCurrent());
    }

    private TelePremiumMediaItem readLaunchItem(Intent intent) {
        if (intent == null || intent.getBooleanExtra(EXTRA_OPEN_CURRENT, false)) return null;
        String name = intent.getStringExtra(EXTRA_NAME);
        String mime = intent.getStringExtra(EXTRA_MIME);
        int account = intent.getIntExtra(EXTRA_CLOUD_ACCOUNT, -1);
        long messageId = intent.getLongExtra(EXTRA_CLOUD_MESSAGE_ID, 0);
        long docId = intent.getLongExtra(EXTRA_CLOUD_DOCUMENT_ID, 0);
        long size = intent.getLongExtra(EXTRA_CLOUD_SIZE, 0);
        if (messageId != 0 && account >= 0) return TelePremiumMediaItem.cloud(account, null, messageId, docId, name, mime, 0, size);
        String path = intent.getStringExtra(EXTRA_PATH);
        Uri uri = path == null ? intent.getData() : Uri.fromFile(new File(path));
        if (uri == null) return null;
        if (TextUtils.isEmpty(name)) name = resolveDisplayName(uri);
        if (TextUtils.isEmpty(name)) name = path == null ? "Медиа" : new File(path).getName();
        if (TextUtils.isEmpty(mime)) mime = intent.getType();
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return TelePremiumMediaItem.network(uri.toString(), name, mime, false);
        return TelePremiumMediaItem.device(uri.toString(), name, mime, 0, 0);
    }

    private void buildUi() {
        mediaTheme = TelePremiumMediaTheme.from(this);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        chromeVisible = true;
        if (landscape) buildLandscape(); else buildPortrait();
        controller.bindSurface(surface);
        bindCurrent(controller.getCurrent());
        setupGestures();
        updatePlayButton();
        updateSpeedButton();
        if (landscape) scheduleChromeHide();
    }

    private void buildPortrait() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        mediaTheme.applySystemBars(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(mediaTheme.background);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        topChrome = createTopBar();
        column.addView(topChrome, new LinearLayout.LayoutParams(-1, dp(78)));

        FrameLayout stage = createStage();
        column.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(18), dp(8), dp(18), 0);
        titleView = text("Медиа", 21, mediaTheme.textPrimary, true);
        artistView = text("", 13, mediaTheme.textSecondary, false);
        sourceView = pill("Устройство");
        meta.addView(titleView, new LinearLayout.LayoutParams(-1, dp(31)));
        meta.addView(artistView, new LinearLayout.LayoutParams(-1, dp(22)));
        meta.addView(sourceView, new LinearLayout.LayoutParams(-2, dp(31)));
        column.addView(meta, new LinearLayout.LayoutParams(-1, dp(94)));

        bottomChrome = createBottomControls(false);
        column.addView(bottomChrome, new LinearLayout.LayoutParams(-1, dp(164)));

        queueButton = pill("☷  Далее в плейлисте");
        queueButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        queueButton.setPadding(dp(18), 0, dp(18), 0);
        queueButton.setOnClickListener(v -> showQueue());
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, dp(56));
        qp.setMargins(dp(16), 0, dp(16), dp(10));
        column.addView(queueButton, qp);
        setContentView(root);
    }

    private void buildLandscape() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout stage = createStage();
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        topChrome = createTopBar();
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP);
        root.addView(topChrome, top);

        bottomChrome = createBottomControls(true);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(122), Gravity.BOTTOM);
        root.addView(bottomChrome, bottom);
        setContentView(root);
    }

    private View createTopBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(10), dp(5), dp(10), dp(3));
        wrap.setBackgroundColor(landscape ? mediaTheme.playerOverlay : mediaTheme.background);

        int chromeText = landscape ? Color.WHITE : mediaTheme.textPrimary;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = transparentButton("‹", 34);
        back.setTextColor(chromeText);
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(50), -1));

        titleView = text("TelePremium", 16, chromeText, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1f));

        hwButton = transparentButton("HW", 13);
        hwButton.setTextColor(chromeText);
        row.addView(hwButton, new LinearLayout.LayoutParams(dp(48), dp(42)));

        subtitlesButton = transparentButton("▤", 24);
        subtitlesButton.setTextColor(chromeText);
        subtitlesButton.setOnClickListener(v -> {
            controller.setSubtitlesEnabled(!controller.isSubtitlesEnabled());
            subtitlesButton.setAlpha(controller.isSubtitlesEnabled() ? 1f : .35f);
        });
        row.addView(subtitlesButton, new LinearLayout.LayoutParams(dp(50), dp(42)));

        TextView more = transparentButton("⋮", 27);
        more.setTextColor(chromeText);
        more.setOnClickListener(v -> showMoreMenu());
        row.addView(more, new LinearLayout.LayoutParams(dp(42), dp(42)));
        wrap.addView(row, new LinearLayout.LayoutParams(-1, dp(45)));

        stateView = text("TelePremium Media", 11, landscape ? 0xffddd8e8 : mediaTheme.textSecondary, false);
        stateView.setPadding(dp(52), 0, dp(8), 0);
        stateView.setSingleLine(true);
        stateView.setEllipsize(TextUtils.TruncateAt.END);
        wrap.addView(stateView, new LinearLayout.LayoutParams(-1, dp(23)));
        return wrap;
    }

    private FrameLayout createStage() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(landscape ? Color.BLACK : mediaTheme.background);

        surface = new AspectSurfaceView(this);
        surface.setFill(!fitMode);
        stage.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        audioArtwork = text("♫", 60, mediaTheme.accent, true);
        audioArtwork.setGravity(Gravity.CENTER);
        audioArtwork.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        audioArtwork.setBackground(roundBg(mediaTheme.artworkBackground, mediaTheme.cardStroke, 26));
        FrameLayout.LayoutParams artworkParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        artworkParams.setMargins(dp(22), dp(12), dp(22), dp(12));
        stage.addView(audioArtwork, artworkParams);
        stage.setOnTouchListener((v, event) -> handleStageTouch(event));
        return stage;
    }

    private View createBottomControls(boolean overlay) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(7));
        box.setBackgroundColor(overlay ? mediaTheme.playerOverlayStrong : mediaTheme.background);

        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        int timelineText = overlay ? Color.WHITE : mediaTheme.textPrimary;
        positionView = text("0:00", 12, timelineText, false);
        durationView = text("0:00", 12, timelineText, false);
        seekBar = new SeekBar(this);
        seekBar.setMax(10000);
        tintSeekBar(seekBar);
        timeline.addView(positionView, new LinearLayout.LayoutParams(dp(56), -1));
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, -1, 1f));
        timeline.addView(durationView, new LinearLayout.LayoutParams(dp(58), -1));
        box.addView(timeline, new LinearLayout.LayoutParams(-1, dp(44)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) positionView.setText(formatTime(progressToMs(progress)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                controller.seekTo(progressToMs(seekBar.getProgress()));
                scheduleChromeHide();
            }
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        speedButton = transparentButton("1×", 14);
        speedButton.setOnClickListener(v -> cycleSpeed());
        TextView previous = transparentButton("|◀", 21);
        previous.setOnClickListener(v -> controller.previous());
        TextView next = transparentButton("▶|", 21);
        next.setOnClickListener(v -> controller.next());
        fitButton = transparentButton("↔", 21);
        fitButton.setOnClickListener(v -> toggleFit());
        TextView pipButton = transparentButton("▣", 22);
        pipButton.setContentDescription("Картинка в картинке");
        pipButton.setOnClickListener(v -> enterPip());
        queueButton = transparentButton("☷", 22);
        queueButton.setContentDescription("Очередь воспроизведения");
        queueButton.setOnClickListener(v -> showQueue());

        controls.addView(speedButton, controlWeight());
        controls.addView(previous, controlWeight());

        FrameLayout playSlot = new FrameLayout(this);
        playButton = roundButton(controller.isPlaying() ? "Ⅱ" : "▶", 24);
        playButton.setOnClickListener(v -> { controller.toggle(); scheduleChromeHide(); });
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
        playSlot.addView(playButton, playLp);
        controls.addView(playSlot, controlWeight());

        controls.addView(next, controlWeight());
        controls.addView(fitButton, controlWeight());
        controls.addView(pipButton, controlWeight());
        controls.addView(queueButton, controlWeight());
        box.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1f));
        return box;
    }

    private void bindCurrent(TelePremiumMediaItem item) {
        if (item == null) return;
        if (titleView != null) titleView.setText(item.title == null ? "Медиа" : item.title);
        if (artistView != null) artistView.setText(TextUtils.isEmpty(item.artist) ? (item.isVideo() ? "Видео" : "Неизвестный исполнитель") : item.artist);
        if (sourceView != null) sourceView.setText(sourceText(item));

        boolean video = item.isVideo();
        if (surface != null) surface.setVisibility(video ? View.VISIBLE : View.GONE);
        if (audioArtwork != null) {
            audioArtwork.setVisibility(video ? View.GONE : View.VISIBLE);
            if (!video) {
                String artist = TextUtils.isEmpty(item.artist) ? "TELEPREMIUM" : item.artist.toUpperCase(Locale.ROOT);
                String title = TextUtils.isEmpty(item.title) ? "Музыка" : stripAudioExtension(item.title);
                audioArtwork.setText("♫\n\n" + artist + "\n" + title);
            }
        }
        if (hwButton != null) hwButton.setVisibility(video ? View.VISIBLE : View.INVISIBLE);
        if (subtitlesButton != null) subtitlesButton.setVisibility(video ? View.VISIBLE : View.INVISIBLE);
        if (queueButton != null) queueButton.setContentDescription("Очередь воспроизведения");
    }

    private String sourceText(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) return "☁  Потоковое воспроизведение · TelePremium Cloud";
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK) return "◉  " + (item.live ? "Онлайн-поток" : "Сеть");
        return "▣  Устройство";
    }

    @Override public void onMediaChanged(TelePremiumMediaItem item) { bindCurrent(item); }

    @Override public void onStateChanged(int state) {
        updatePlayButton();
        if (stateView == null) return;
        TelePremiumMediaItem item = controller.getCurrent();
        if (state == TelePremiumPlaybackController.STATE_RESOLVING) stateView.setText("Подключение к источнику…");
        else if (state == TelePremiumPlaybackController.STATE_BUFFERING) stateView.setText(controller.isCloudStreaming() ? "Онлайн · буферизация…" : "Буферизация…");
        else if (state == TelePremiumPlaybackController.STATE_SEEKING) stateView.setText("Переход к фрагменту…");
        else if (state == TelePremiumPlaybackController.STATE_ERROR) stateView.setText("Ошибка воспроизведения");
        else if (item != null) stateView.setText(sourceText(item));
    }

    @Override public void onProgress(long position, long duration, long buffered, int bufferedPercent) {
        if (seekBar == null || positionView == null || durationView == null) return;
        if (duration > 0) {
            int p = (int) Math.max(0, Math.min(10000, position * 10000L / duration));
            int b = (int) Math.max(p, Math.min(10000, buffered * 10000L / duration));
            if (!userSeeking) seekBar.setProgress(p);
            seekBar.setSecondaryProgress(b);
        }
        if (!userSeeking) positionView.setText(formatTime(position));
        durationView.setText(duration > 0 ? formatTime(duration) : "LIVE");
    }

    @Override public void onVideoSizeChanged(int width, int height) {
        if (surface != null) surface.setVideoSize(width, height);
    }

    @Override public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void updatePlayButton() {
        if (playButton != null) playButton.setText(controller.isPlaying() ? "Ⅱ" : "▶");
    }

    private void updateSpeedButton() {
        if (speedButton != null) speedButton.setText(formatSpeed(controller.getSpeed()));
    }

    private long progressToMs(int progress) {
        long duration = controller.getDuration();
        return duration <= 0 ? controller.getPosition() : duration * Math.max(0, Math.min(10000, progress)) / 10000L;
    }

    private void cycleSpeed() {
        float current = controller.getSpeed();
        int best = 0;
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - current) < Math.abs(speeds[best] - current)) best = i;
        }
        speedIndex = (best + 1) % speeds.length;
        controller.setPlaybackSpeed(speeds[speedIndex]);
        updateSpeedButton();
    }

    private void toggleFit() {
        fitMode = !fitMode;
        if (surface != null) surface.setFill(!fitMode);
        if (fitButton != null) fitButton.setText(fitMode ? "↔" : "↕");
    }

    private void showMoreMenu() {
        String repeat = controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? "Повтор: выкл" : "Повтор: вкл";
        new AlertDialog.Builder(this).setItems(new CharSequence[]{"Очередь", repeat, "Перемешать", "Скорость", "Таймер сна"}, (d, which) -> {
            if (which == 0) showQueue();
            else if (which == 1) controller.getQueue().setRepeatMode(controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? TelePremiumQueueManager.REPEAT_ALL : TelePremiumQueueManager.REPEAT_OFF);
            else if (which == 2) {
                controller.getQueue().setShuffle(!controller.getQueue().isShuffle());
                Toast.makeText(this, controller.getQueue().isShuffle() ? "Перемешивание включено" : "Перемешивание выключено", Toast.LENGTH_SHORT).show();
            } else if (which == 3) cycleSpeed();
            else if (which == 4) showSleepTimer();
        }).show();
    }

    private void showSleepTimer() {
        String[] labels = {"15 минут", "30 минут", "45 минут", "60 минут", "После текущего трека"};
        new AlertDialog.Builder(this).setTitle("Таймер сна").setItems(labels, (d, which) -> {
            if (which < 4) {
                long delay = new long[]{15, 30, 45, 60}[which] * 60_000L;
                handler.postDelayed(controller::pause, delay);
                Toast.makeText(this, "Таймер сна: " + labels[which], Toast.LENGTH_SHORT).show();
            } else {
                long remaining = Math.max(0, controller.getDuration() - controller.getPosition());
                if (remaining > 0) handler.postDelayed(controller::pause, remaining);
            }
        }).show();
    }

    private void showQueue() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(20));
        content.setBackgroundColor(mediaTheme.surfaceElevated);
        TextView heading = text("Далее в очереди", 19, mediaTheme.textPrimary, true);
        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));

        List<TelePremiumMediaItem> items = controller.getQueue().snapshot();
        int currentIndex = controller.getQueue().getCurrentIndex();
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            TelePremiumMediaItem item = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(4), dp(4));
            TextView label = text((i == currentIndex ? "▶  " : (i + 1) + ".  ") + item.title + "\n" + item.sourceLabel(),
                    13, i == currentIndex ? mediaTheme.accent : mediaTheme.textPrimary, false);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(56), 1f));
            TextView up = transparentButton("↑", 20);
            up.setOnClickListener(v -> { if (index > 0) controller.getQueue().move(index, index - 1); dialog.dismiss(); showQueue(); });
            row.addView(up, new LinearLayout.LayoutParams(dp(42), dp(50)));
            TextView down = transparentButton("↓", 20);
            down.setOnClickListener(v -> { if (index + 1 < controller.getQueue().size()) controller.getQueue().move(index, index + 1); dialog.dismiss(); showQueue(); });
            row.addView(down, new LinearLayout.LayoutParams(dp(42), dp(50)));
            TextView remove = transparentButton("×", 22);
            remove.setOnClickListener(v -> { controller.getQueue().remove(index); dialog.dismiss(); showQueue(); });
            row.addView(remove, new LinearLayout.LayoutParams(dp(42), dp(50)));
            label.setOnClickListener(v -> { controller.getQueue().setCurrentIndex(index); controller.playCurrent(); dialog.dismiss(); });
            content.addView(row, new LinearLayout.LayoutParams(-1, dp(62)));
        }

        TextView mediaCenter = pill("＋  Открыть медиатеку");
        mediaCenter.setGravity(Gravity.CENTER);
        mediaCenter.setOnClickListener(v -> { startActivity(new Intent(this, TelePremiumMediaCenterActivity.class)); dialog.dismiss(); });
        content.addView(mediaCenter, new LinearLayout.LayoutParams(-1, dp(54)));
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(-1, -2);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) { w.setLayout(-1, -2); w.setGravity(Gravity.BOTTOM); }
        });
        dialog.show();
    }

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (landscape) toggleChrome();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!landscape || root == null) return true;
                if (e.getX() < root.getWidth() / 2f) controller.seekRelative(-10_000);
                else controller.seekRelative(10_000);
                showChrome();
                scheduleChromeHide();
                return true;
            }
        });
    }

    private boolean handleStageTouch(MotionEvent event) {
        if (gestures != null) gestures.onTouchEvent(event);
        if (!landscape) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            gestureMode = 0;
            gestureStartPosition = controller.getPosition();
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            gestureStartBrightness = lp.screenBrightness < 0 ? .5f : lp.screenBrightness;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            gestureStartVolume = am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - touchDownX, dy = event.getY() - touchDownY;
            if (gestureMode == 0 && Math.max(Math.abs(dx), Math.abs(dy)) > dp(18)) {
                gestureMode = Math.abs(dx) > Math.abs(dy) ? 1 : (touchDownX < root.getWidth() / 2f ? 2 : 3);
            }
            if (gestureMode == 1) {
                long duration = controller.getDuration();
                if (duration > 0) controller.seekTo(Math.max(0, Math.min(duration,
                        gestureStartPosition + (long) (dx / root.getWidth() * Math.min(duration, 180_000)))));
            } else if (gestureMode == 2) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(.02f, Math.min(1f, gestureStartBrightness - dy / root.getHeight()));
                getWindow().setAttributes(lp);
            } else if (gestureMode == 3) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int value = Math.max(0, Math.min(max, gestureStartVolume + Math.round((-dy / root.getHeight()) * max)));
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                }
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            gestureMode = 0;
            scheduleChromeHide();
        }
        return true;
    }

    private void toggleChrome() { if (chromeVisible) hideChrome(); else showChrome(); }

    private void showChrome() {
        chromeVisible = true;
        if (topChrome != null) topChrome.setVisibility(View.VISIBLE);
        if (bottomChrome != null) bottomChrome.setVisibility(View.VISIBLE);
    }

    private void hideChrome() {
        if (!landscape) return;
        chromeVisible = false;
        if (topChrome != null) topChrome.setVisibility(View.GONE);
        if (bottomChrome != null) bottomChrome.setVisibility(View.GONE);
    }

    private void scheduleChromeHide() {
        if (!landscape) return;
        handler.removeCallbacks(hideChromeRunnable);
        handler.postDelayed(hideChromeRunnable, 2800);
    }

    private final Runnable hideChromeRunnable = this::hideChrome;

    private void enterPip() {
        TelePremiumMediaItem item = controller.getCurrent();
        if (Build.VERSION.SDK_INT < 26 || item == null || !item.isVideo()) return;
        try {
            int w = Math.max(1, controller.getVideoWidth()), h = Math.max(1, controller.getVideoHeight());
            Rational ratio = new Rational(w, h);
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio).build());
        } catch (Throwable ignore) {}
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        TelePremiumMediaItem item = controller.getCurrent();
        if (item != null && item.isVideo() && controller.isPlaying()) enterPip();
    }

    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        pip = isInPictureInPictureMode;
        if (pip) {
            if (topChrome != null) topChrome.setVisibility(View.GONE);
            if (bottomChrome != null) bottomChrome.setVisibility(View.GONE);
        } else if (landscape) {
            showChrome();
            scheduleChromeHide();
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        handler.removeCallbacks(hideChromeRunnable);
        buildUi();
    }

    @Override protected void onStop() {
        super.onStop();
        TelePremiumMediaItem item = controller.getCurrent();
        if (item != null && item.isVideo() && !pip && !(Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode())) controller.pause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        controller.removeListener(this);
        controller.bindSurface(null);
        super.onDestroy();
    }

    private void tintSeekBar(SeekBar bar) {
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setProgressTintList(ColorStateList.valueOf(mediaTheme.accent));
            bar.setThumbTintList(ColorStateList.valueOf(mediaTheme.accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(mediaTheme.timelineTrack));
            bar.setSecondaryProgressTintList(ColorStateList.valueOf(mediaTheme.withAlpha(mediaTheme.accent, 0x68)));
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView pill(String value) {
        TextView v = text(value, 12, mediaTheme.accent, false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mediaTheme.chipSelectedBackground);
        bg.setStroke(dp(1), mediaTheme.cardStroke);
        bg.setCornerRadius(dp(18));
        v.setBackground(bg);
        v.setPadding(dp(14), 0, dp(14), 0);
        return v;
    }

    private TextView transparentButton(String value, int sp) {
        int color = landscape ? Color.WHITE : mediaTheme.textPrimary;
        TextView v = text(value, sp, color, false);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    private TextView roundButton(String value, int sp) {
        TextView v = text(value, sp, mediaTheme.onAccent, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(mediaTheme.accent);
        v.setBackground(bg);
        return v;
    }

    private GradientDrawable roundBg(int color, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams controlWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(float v) { return AndroidUtilities.dp(v); }

    private String resolveDisplayName(Uri uri) {
        if (uri == null) return null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return uri.getLastPathSegment();
    }

    private static String stripAudioExtension(String title) {
        if (TextUtils.isEmpty(title)) return "Музыка";
        String lower = title.toLowerCase(Locale.ROOT);
        String[] extensions = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".amr"};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) return title.substring(0, title.length() - extension.length());
        }
        return title;
    }

    private static String formatTime(long ms) {
        long total = Math.max(0, ms / 1000), h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private static String formatSpeed(float speed) {
        return speed == Math.round(speed) ? String.format(Locale.ROOT, "%d×", Math.round(speed)) : String.format(Locale.ROOT, "%.2g×", speed);
    }

    private static final class AspectSurfaceView extends SurfaceView {
        private int videoWidth, videoHeight;
        private boolean fill;

        AspectSurfaceView(Context context) { super(context); }

        void setVideoSize(int width, int height) {
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }

        void setFill(boolean value) {
            fill = value;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            if (fill || videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
                setMeasuredDimension(width, height);
                return;
            }
            float vr = videoWidth / (float) videoHeight, br = width / (float) height;
            if (vr > br) height = Math.max(1, Math.round(width / vr));
            else width = Math.max(1, Math.round(height * vr));
            setMeasuredDimension(width, height);
        }
    }
}
