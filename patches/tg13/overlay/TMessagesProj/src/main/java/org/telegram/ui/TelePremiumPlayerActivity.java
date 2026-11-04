package org.telegram.ui;

import org.telegram.messenger.telepremium.media.TelePremiumPlayerMath;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.media.TelePremiumMediaItem;
import org.telegram.messenger.telepremium.media.TelePremiumArtworkLoader;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.media.TelePremiumQueueManager;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * TelePremium Media Center player.
 *
 * Portrait inherits the active TelePremium shell theme. Landscape keeps video
 * pixels neutral while its transient chrome derives accent/surface values from
 * the same shell. Seeking is gesture-driven: double tap the left/right half to
 * jump -/+10 seconds; transport controls live only in the bottom bar.
 */
public class TelePremiumPlayerActivity extends Activity implements TelePremiumPlaybackController.Listener {
    public static final String EXTRA_PATH = "telepremium_player_path";
    public static final String EXTRA_NAME = "telepremium_player_name";
    public static final String EXTRA_MIME = "telepremium_player_mime";
    public static final String EXTRA_CLOUD_ACCOUNT = "telepremium_player_cloud_account";
    public static final String EXTRA_CLOUD_MESSAGE_ID = "telepremium_player_cloud_message_id";
    public static final String EXTRA_CLOUD_DOCUMENT_ID = "telepremium_player_cloud_document_id";
    public static final String EXTRA_CLOUD_SIZE = "telepremium_player_cloud_size";
    public static final String EXTRA_CLOUD_NODE_ID = "telepremium_player_cloud_node_id";
    public static final String EXTRA_CLOUD_ENCRYPTION_VERSION = "telepremium_player_cloud_encryption_version";
    public static final String EXTRA_CLOUD_SHA256 = "telepremium_player_cloud_sha256";
    public static final String EXTRA_CLOUD_BACKUP_ID = "telepremium_player_cloud_backup_id";
    public static final String EXTRA_OPEN_CURRENT = "telepremium_player_open_current";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
    private TelePremiumMediaItem launchItem;
    private TelePremiumMediaItem displayItem;
    private TelePremiumMediaTheme mediaTheme;

    // V3.6 cinematic player UI: scoped palette (does not change the chat/cloud theme).
    private static final int PLAYER_BG = 0xff0b0913;
    private static final int PLAYER_PANEL = 0xff171221;
    private static final int PLAYER_VIOLET = 0xffa477ff;
    private static final int PLAYER_WHITE = 0xfff7f2ff;
    private static final int PLAYER_MUTED = 0xffb6adc8;
    private static final int PLAYER_STROKE = 0xff52356f;
    private FrameLayout root;
    private ImageView artworkImage;
    private TextView headerTitleView, shuffleButton, repeatButton;
    private boolean portraitAudioLayout;
    private TelePremiumAspectSurfaceView surface;
    private TextView audioArtwork, titleView, artistView, sourceView, stateView, positionView, durationView;
    private TextView playButton, speedButton, fitButton, subtitlesButton, hwButton, queueButton;
    private SeekBar seekBar;
    private View topChrome, bottomChrome;
    private TextView lockButton;
    private boolean userSeeking, landscape, chromeVisible = true, pip, controlsLocked;
    private static final int SCALE_FIT = TelePremiumPlayerMath.SCALE_FIT;
    private static final int SCALE_CROP = TelePremiumPlayerMath.SCALE_CROP;
    private static final int SCALE_STRETCH = TelePremiumPlayerMath.SCALE_STRETCH;
    private static final int SCALE_ORIGINAL = TelePremiumPlayerMath.SCALE_ORIGINAL;
    private int scaleMode = SCALE_FIT;
    private int seekStepSeconds = 10;
    private boolean gestureBrightness = true;
    private boolean gestureVolume = true;
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
            // Preserve security metadata: losing encryptionVersion here causes
            // encrypted .tpv bytes to be treated as ordinary Telegram media.
            Intent cloudIntent = new Intent(context, TelePremiumPlayerActivity.class);
            cloudIntent.putExtra(EXTRA_CLOUD_ACCOUNT, item.cloudAccount);
            cloudIntent.putExtra(EXTRA_CLOUD_MESSAGE_ID, item.telegramMessageId);
            cloudIntent.putExtra(EXTRA_CLOUD_DOCUMENT_ID, item.telegramDocumentId);
            cloudIntent.putExtra(EXTRA_CLOUD_SIZE, item.size);
            cloudIntent.putExtra(EXTRA_NAME, item.title);
            cloudIntent.putExtra(EXTRA_MIME, item.mimeType);
            cloudIntent.putExtra(EXTRA_CLOUD_NODE_ID, item.cloudNodeId);
            cloudIntent.putExtra(EXTRA_CLOUD_ENCRYPTION_VERSION, item.encryptionVersion);
            cloudIntent.putExtra(EXTRA_CLOUD_SHA256, item.sha256);
            cloudIntent.putExtra(EXTRA_CLOUD_BACKUP_ID, item.localBackupId);
            start(context, cloudIntent);
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
        displayItem = launchItem != null ? launchItem : controller.getCurrent();
        controller.addListener(this);
        buildUi();
        if (launchItem != null) controller.play(launchItem);
        else if (controller.getCurrent() == null) {
            Toast.makeText(this, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_409), Toast.LENGTH_SHORT).show();
            finish();
        } else {
            bindCurrent(controller.getCurrent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        TelePremiumMediaItem item = readLaunchItem(intent);
        if (item != null) { displayItem = item; controller.play(item); } else bindCurrent(controller.getCurrent());
    }

    private TelePremiumMediaItem readLaunchItem(Intent intent) {
        if (intent == null || intent.getBooleanExtra(EXTRA_OPEN_CURRENT, false)) return null;
        String name = intent.getStringExtra(EXTRA_NAME);
        String mime = intent.getStringExtra(EXTRA_MIME);
        int account = intent.getIntExtra(EXTRA_CLOUD_ACCOUNT, -1);
        long messageId = intent.getLongExtra(EXTRA_CLOUD_MESSAGE_ID, 0);
        long docId = intent.getLongExtra(EXTRA_CLOUD_DOCUMENT_ID, 0);
        long size = intent.getLongExtra(EXTRA_CLOUD_SIZE, 0);
        if (messageId != 0 && account >= 0) {
            TelePremiumMediaItem cloudItem = TelePremiumMediaItem.cloud(
                    account, intent.getStringExtra(EXTRA_CLOUD_NODE_ID), messageId, docId, name, mime, 0, size);
            cloudItem.encryptionVersion = intent.getIntExtra(EXTRA_CLOUD_ENCRYPTION_VERSION, 0);
            cloudItem.sha256 = intent.getStringExtra(EXTRA_CLOUD_SHA256);
            cloudItem.localBackupId = intent.getStringExtra(EXTRA_CLOUD_BACKUP_ID);
            return cloudItem;
        }
        String path = intent.getStringExtra(EXTRA_PATH);
        Uri uri = path == null ? intent.getData() : Uri.fromFile(new File(path));
        if (uri == null) return null;
        if (TextUtils.isEmpty(name)) name = resolveDisplayName(uri);
        if (TextUtils.isEmpty(name)) name = path == null ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_410) : new File(path).getName();
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
        bindCurrent(displayItem != null ? displayItem : controller.getCurrent());
        setupGestures();
        updatePlayButton();
        updateSpeedButton();
        if (landscape) scheduleChromeHide();
    }

    private void buildPortrait() {
        resetPortraitWindowMode();
        Window window = getWindow();
        window.setStatusBarColor(PLAYER_BG);
        window.setNavigationBarColor(PLAYER_BG);
        if (Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }

        TelePremiumMediaItem current = displayItem != null ? displayItem : controller.getCurrent();
        portraitAudioLayout = current == null || !current.isVideo();
        root = new FrameLayout(this);
        root.setBackgroundColor(PLAYER_BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(PLAYER_BG);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

        topChrome = createTopBar();
        column.addView(topChrome, new LinearLayout.LayoutParams(-1, dp(76)));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int stagePx = portraitAudioLayout
                ? Math.min(screenWidth - dp(56), dp(375))
                : Math.max(dp(190), screenWidth * 9 / 16);
        FrameLayout stage = createStage();
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, stagePx + (portraitAudioLayout ? dp(16) : 0));
        if (portraitAudioLayout) stageLp.setMargins(dp(22), dp(2), dp(22), 0);
        column.addView(stage, stageLp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_HORIZONTAL);
        meta.setPadding(dp(24), dp(17), dp(24), dp(4));
        titleView = text(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_410), portraitAudioLayout ? 25 : 19, PLAYER_WHITE, true);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        artistView = text("", 15, PLAYER_MUTED, false);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        sourceView = pill(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_411));
        sourceView.setTextColor(PLAYER_MUTED);
        sourceView.setGravity(Gravity.CENTER);
        sourceView.setBackground(roundBg(PLAYER_PANEL, PLAYER_STROKE, 21));
        meta.addView(titleView, new LinearLayout.LayoutParams(-1, dp(39)));
        meta.addView(artistView, new LinearLayout.LayoutParams(-1, dp(26)));
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-2, dp(36));
        sourceLp.topMargin = dp(8);
        meta.addView(sourceView, sourceLp);
        column.addView(meta, new LinearLayout.LayoutParams(-1, dp(123)));

        bottomChrome = createBottomControls(false);
        column.addView(bottomChrome, new LinearLayout.LayoutParams(-1, dp(portraitAudioLayout ? 193 : 156)));

        queueButton = text(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_412), 14, PLAYER_WHITE, false);
        queueButton.setGravity(Gravity.CENTER_VERTICAL);
        queueButton.setPadding(dp(20), 0, dp(16), 0);
        queueButton.setBackground(roundBg(PLAYER_PANEL, PLAYER_STROKE, 23));
        queueButton.setOnClickListener(v -> showQueue());
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, dp(60));
        qp.setMargins(dp(20), dp(3), dp(20), dp(20));
        column.addView(queueButton, qp);
        setContentView(root);
    }

    private void buildLandscape() {
        applyLandscapeWindowMode();
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout stage = createStage();
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        topChrome = createTopBar();
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, dp(70), Gravity.TOP);
        root.addView(topChrome, top);

        // Deliberately no center transport controls. Video remains unobstructed;
        // left/right double tap performs -/+10s and play/pause stays in bottom bar.
        bottomChrome = createBottomControls(true);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM);
        root.addView(bottomChrome, bottom);

        lockButton = pill("🔒");
        lockButton.setGravity(Gravity.CENTER);
        lockButton.setVisibility(controlsLocked ? View.VISIBLE : View.GONE);
        lockButton.setOnClickListener(v -> toggleControlsLock());
        FrameLayout.LayoutParams lockParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lockParams.setMargins(0, 0, dp(18), 0);
        root.addView(lockButton, lockParams);
        setContentView(root);
    }

    private void applyLandscapeWindowMode() {
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
            window.getInsetsController().hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            window.getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void resetPortraitWindowMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
            window.getInsetsController().show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attributes);
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private View createTopBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(14), dp(4), dp(12), 0);
        wrap.setBackground(landscape ? chromeGradient(false) : roundBg(PLAYER_BG, PLAYER_BG, 0));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = transparentButton("‹", 36);
        back.setTextColor(PLAYER_WHITE);
        back.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_413));
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), -1));

        headerTitleView = text(landscape ? "TelePremium Media" : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_414), landscape ? 17 : 20, PLAYER_WHITE, true);
        headerTitleView.setSingleLine(true);
        headerTitleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(headerTitleView, new LinearLayout.LayoutParams(0, -1, 1f));

        hwButton = transparentButton("HW", 12);
        hwButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_415));
        hwButton.setTextColor(PLAYER_MUTED);
        hwButton.setOnClickListener(v -> showPlayerSettings());
        row.addView(hwButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        subtitlesButton = transparentButton("▤", 23);
        subtitlesButton.setTextColor(PLAYER_WHITE);
        subtitlesButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_416));
        subtitlesButton.setOnClickListener(v -> {
            controller.setSubtitlesEnabled(!controller.isSubtitlesEnabled());
            subtitlesButton.setAlpha(controller.isSubtitlesEnabled() ? 1f : .35f);
        });
        row.addView(subtitlesButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        if (landscape) {
            TextView videoQueue = transparentButton("☷", 23);
            videoQueue.setTextColor(PLAYER_WHITE);
            videoQueue.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_417));
            videoQueue.setOnClickListener(v -> showQueue());
            row.addView(videoQueue, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        TextView more = transparentButton("⋮", 27);
        more.setTextColor(PLAYER_WHITE);
        more.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_418));
        more.setOnClickListener(v -> showMoreMenu());
        row.addView(more, new LinearLayout.LayoutParams(dp(40), dp(42)));
        wrap.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));

        stateView = text("TelePremium Media", 11, PLAYER_MUTED, false);
        stateView.setPadding(dp(49), 0, dp(8), 0);
        stateView.setSingleLine(true);
        stateView.setEllipsize(TextUtils.TruncateAt.END);
        wrap.addView(stateView, new LinearLayout.LayoutParams(-1, dp(20)));
        return wrap;
    }

    private GradientDrawable chromeGradient(boolean bottom) {
        int deep = 0xe9000000;
        int clear = 0x00000000;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                bottom ? new int[]{clear, 0xe9000000, deep} : new int[]{deep, 0xc4000000, clear});
    }

    private FrameLayout createStage() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(landscape ? Color.BLACK : PLAYER_BG);
        surface = new TelePremiumAspectSurfaceView(this);
        surface.setScaleMode(scaleMode);
        stage.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        artworkImage = new ImageView(this);
        artworkImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkImage.setBackground(roundBg(0xff1f1732, PLAYER_STROKE, 26));
        artworkImage.setClipToOutline(Build.VERSION.SDK_INT >= 21);
        FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        imageLp.setMargins(dp(8), dp(8), dp(8), dp(8));
        stage.addView(artworkImage, imageLp);
        audioArtwork = text("♫", 108, PLAYER_VIOLET, true);
        audioArtwork.setGravity(Gravity.CENTER);
        GradientDrawable fallbackCover = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff1b1230, 0xff31205b, 0xff0c0916});
        fallbackCover.setCornerRadius(dp(26));
        fallbackCover.setStroke(dp(1), PLAYER_STROKE);
        audioArtwork.setBackground(fallbackCover);
        FrameLayout.LayoutParams artworkParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        artworkParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        stage.addView(audioArtwork, artworkParams);
        stage.setOnTouchListener((v, event) -> handleStageTouch(event));
        return stage;
    }

    private View createBottomControls(boolean overlay) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(4), dp(15), dp(6));
        box.setBackground(overlay ? chromeGradient(true) : roundBg(PLAYER_BG, PLAYER_BG, 0));
        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        positionView = text("0:00", 12, PLAYER_WHITE, false);
        durationView = text("0:00", 12, PLAYER_WHITE, false);
        positionView.setGravity(Gravity.CENTER);
        durationView.setGravity(Gravity.CENTER);
        seekBar = new SeekBar(this);
        seekBar.setMax(10000);
        tintSeekBar(seekBar);
        timeline.addView(positionView, new LinearLayout.LayoutParams(dp(54), -1));
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, -1, 1f));
        timeline.addView(durationView, new LinearLayout.LayoutParams(dp(54), -1));
        box.addView(timeline, new LinearLayout.LayoutParams(-1, dp(48)));
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

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER);
        TextView previous = transparentButton("|◀", 24);
        previous.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_419));
        previous.setOnClickListener(v -> controller.previous());
        TextView next = transparentButton("▶|", 24);
        next.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_420));
        next.setOnClickListener(v -> controller.next());
        speedButton = transparentButton("1×", 15);
        speedButton.setOnClickListener(v -> cycleSpeed());
        speedButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_421));
        fitButton = transparentButton(scaleModeIcon(), 21);
        fitButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_422));
        fitButton.setOnClickListener(v -> toggleFit());
        TextView pipButton = transparentButton("▣", 22);
        pipButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_423));
        pipButton.setOnClickListener(v -> enterPip());
        playButton = roundButton(controller.isPlaying() ? "Ⅱ" : "▶", 30);
        playButton.setOnClickListener(v -> { controller.toggle(); scheduleChromeHide(); });
        playButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_424));
        FrameLayout playSlot = new FrameLayout(this);
        playSlot.addView(playButton, new FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER));
        if (overlay) {
            transport.addView(speedButton, controlWeight());
            transport.addView(previous, controlWeight());
            transport.addView(playSlot, new LinearLayout.LayoutParams(dp(78), -1));
            transport.addView(next, controlWeight());
            transport.addView(fitButton, controlWeight());
            transport.addView(pipButton, controlWeight());
        } else {
            if (portraitAudioLayout) {
                shuffleButton = transparentButton("⤨", 24);
                shuffleButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_425));
                shuffleButton.setOnClickListener(v -> toggleShuffle());
                repeatButton = transparentButton("↻", 26);
                repeatButton.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_426));
                repeatButton.setOnClickListener(v -> toggleRepeat());
                transport.addView(shuffleButton, controlWeight());
            } else {
                transport.addView(fitButton, controlWeight());
            }
            transport.addView(previous, controlWeight());
            transport.addView(playSlot, new LinearLayout.LayoutParams(dp(86), -1));
            transport.addView(next, controlWeight());
            if (portraitAudioLayout) {
                transport.addView(repeatButton, controlWeight());
                refreshQueueModeButtons();
            } else {
                transport.addView(pipButton, controlWeight());
            }
        }
        box.addView(transport, new LinearLayout.LayoutParams(-1, dp(76)));
        if (!overlay) {
            LinearLayout extras = new LinearLayout(this);
            extras.setGravity(Gravity.CENTER);
            speedButton.setTextColor(PLAYER_MUTED);
            extras.addView(speedButton, new LinearLayout.LayoutParams(0, dp(51), 1f));
            TextView output = transparentButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_427), 13);
            output.setContentDescription(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_428));
            output.setTextColor(PLAYER_MUTED);
            output.setOnClickListener(v -> showPlayerSettings());
            extras.addView(output, new LinearLayout.LayoutParams(0, dp(51), 1.5f));
            TextView queue = transparentButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_429), 13);
            queue.setTextColor(PLAYER_MUTED);
            queue.setOnClickListener(v -> showQueue());
            extras.addView(queue, new LinearLayout.LayoutParams(0, dp(51), 1.1f));
            box.addView(extras, new LinearLayout.LayoutParams(-1, dp(51)));
        }
        return box;
    }

    private void toggleShuffle() {
        TelePremiumQueueManager queue = controller.getQueue();
        queue.setShuffle(!queue.isShuffle());
        refreshQueueModeButtons();
    }

    private void toggleRepeat() {
        TelePremiumQueueManager queue = controller.getQueue();
        queue.setRepeatMode(queue.getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF
                ? TelePremiumQueueManager.REPEAT_ALL : TelePremiumQueueManager.REPEAT_OFF);
        refreshQueueModeButtons();
    }

    private void refreshQueueModeButtons() {
        if (shuffleButton != null) shuffleButton.setTextColor(controller.getQueue().isShuffle() ? PLAYER_VIOLET : PLAYER_MUTED);
        if (repeatButton != null) repeatButton.setTextColor(controller.getQueue().getRepeatMode() != TelePremiumQueueManager.REPEAT_OFF ? PLAYER_VIOLET : PLAYER_MUTED);
    }

    private void bindCurrent(TelePremiumMediaItem item) {
        if (item == null) return;
        if (!landscape && portraitAudioLayout == item.isVideo()) {
            // Queue can switch between audio and video without restarting the Activity.
            displayItem = item;
            buildUi();
            return;
        }
        displayItem = item;
        if (titleView != null) titleView.setText(item.isVideo() ? item.title : stripAudioExtension(item.title));
        if (artistView != null) artistView.setText(TextUtils.isEmpty(item.artist)
                ? (item.isVideo() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_098) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_343)) : item.artist);
        if (sourceView != null) sourceView.setText(sourceText(item));
        if (headerTitleView != null) headerTitleView.setText(landscape
                ? (TextUtils.isEmpty(item.title) ? "TelePremium Media" : item.title)
                : item.isVideo() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_098) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_414));

        boolean video = item.isVideo();
        if (surface != null) {
            surface.setVisibility(video ? View.VISIBLE : View.GONE);
            surface.setScaleMode(scaleMode);
        }
        if (audioArtwork != null) {
            audioArtwork.setVisibility(video ? View.GONE : View.VISIBLE);
            audioArtwork.setText("♫");
        }
        if (artworkImage != null) {
            artworkImage.setVisibility(video ? View.GONE : View.VISIBLE);
            artworkImage.setImageDrawable(null);
            if (!video) {
                final String requestedId = item.id;
                TelePremiumArtworkLoader.load(this, item, dp(420), bitmap -> {
                    TelePremiumMediaItem current = controller.getCurrent();
                    if (artworkImage == null || current == null || !requestedId.equals(current.id)) return;
                    if (bitmap != null && !bitmap.isRecycled()) {
                        artworkImage.setImageBitmap(bitmap);
                        audioArtwork.setVisibility(View.GONE);
                    } else {
                        artworkImage.setVisibility(View.GONE);
                        audioArtwork.setVisibility(View.VISIBLE);
                    }
                });
            }
        }
        if (hwButton != null) hwButton.setVisibility(video ? View.VISIBLE : View.GONE);
        if (subtitlesButton != null) subtitlesButton.setVisibility(video ? View.VISIBLE : View.GONE);
    }

    private String sourceText(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) return item.encryptionVersion >= 3
                ? "☁  TelePremium Cloud · Vault" : "☁  TelePremium Cloud";
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK) return "◉  " + (item.live ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_430) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_431));
        return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_432);
    }

    @Override public void onMediaChanged(TelePremiumMediaItem item) { displayItem = item; bindCurrent(item); }

    @Override public void onStateChanged(int state) {
        updatePlayButton();
        if (stateView == null) return;
        TelePremiumMediaItem item = controller.getCurrent();
        if (state == TelePremiumPlaybackController.STATE_RESOLVING) stateView.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_433));
        else if (state == TelePremiumPlaybackController.STATE_BUFFERING) stateView.setText(controller.isCloudStreaming() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_434) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_435));
        else if (state == TelePremiumPlaybackController.STATE_SEEKING) stateView.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_436));
        else if (state == TelePremiumPlaybackController.STATE_ERROR) stateView.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_437));
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
        scaleMode = (scaleMode + 1) % 4;
        if (surface != null) surface.setScaleMode(scaleMode);
        if (fitButton != null) fitButton.setText(scaleModeIcon());
        Toast.makeText(this, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_438) + scaleModeLabel(), Toast.LENGTH_SHORT).show();
    }

    private String scaleModeIcon() {
        if (scaleMode == SCALE_CROP) return "⌗";
        if (scaleMode == SCALE_STRETCH) return "⇔";
        if (scaleMode == SCALE_ORIGINAL) return "1:1";
        return "↔";
    }

    private String scaleModeLabel() {
        if (scaleMode == SCALE_CROP) return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_439);
        if (scaleMode == SCALE_STRETCH) return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_440);
        if (scaleMode == SCALE_ORIGINAL) return "100%";
        return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_441);
    }

    private void showMoreMenu() {
        showPlayerSettings();
    }

    private void showPlayerSettings() {
        Dialog dialog = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(18));
        content.setBackground(roundBg(mediaTheme.surfaceElevated, mediaTheme.cardStroke, 22));

        TextView heading = text(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_428), 20, mediaTheme.textPrimary, true);
        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(50)));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_442), "Telegram ExoPlayer", controller.getDecoderLabel(), v ->
                Toast.makeText(this, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_443), Toast.LENGTH_LONG).show()));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_422), "Fit / Crop / Stretch / 100%", scaleModeLabel(), v -> { dialog.dismiss(); showScaleDialog(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_444), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_421), formatSpeed(controller.getSpeed()), v -> { dialog.dismiss(); showSpeedDialog(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_445), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_446), seekStepSeconds + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_447), v -> { dialog.dismiss(); showSeekStepDialog(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_448), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_449), gestureBrightness ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_450) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_451), v -> { gestureBrightness = !gestureBrightness; dialog.dismiss(); showPlayerSettings(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_452), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_453), gestureVolume ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_450) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_451), v -> { gestureVolume = !gestureVolume; dialog.dismiss(); showPlayerSettings(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_416), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_454), controller.isSubtitlesEnabled() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_450) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_451), v -> { controller.setSubtitlesEnabled(!controller.isSubtitlesEnabled()); dialog.dismiss(); showPlayerSettings(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_455), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_426), controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_451) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_450), v -> {
            controller.getQueue().setRepeatMode(controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? TelePremiumQueueManager.REPEAT_ALL : TelePremiumQueueManager.REPEAT_OFF);
            dialog.dismiss(); showPlayerSettings();
        }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_456), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_457), controller.getQueue().isShuffle() ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_450) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_451), v -> {
            controller.getQueue().setShuffle(!controller.getQueue().isShuffle());
            dialog.dismiss(); showPlayerSettings();
        }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_458), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_459), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_460), v -> { dialog.dismiss(); showOrientationDialog(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_461), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_462), controlsLocked ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_450) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_451), v -> { toggleControlsLock(); dialog.dismiss(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_463), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_464), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_117), v -> { dialog.dismiss(); showSleepTimer(); }));
        content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_465), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_466), controller.getQueue().size() + "", v -> { dialog.dismiss(); showQueue(); }));
        TelePremiumMediaItem item = controller.getCurrent();
        if (item != null && item.isVideo() && Build.VERSION.SDK_INT >= 26) {
            content.addView(settingsRow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_423), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_467), "PiP", v -> { dialog.dismiss(); enterPip(); }));
        }
        TextView library = pill(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_468));
        library.setGravity(Gravity.CENTER);
        library.setOnClickListener(v -> { dialog.dismiss(); startActivity(new Intent(this, TelePremiumMediaCenterActivity.class)); });
        LinearLayout.LayoutParams libraryParams = new LinearLayout.LayoutParams(-1, dp(52));
        libraryParams.setMargins(0, dp(10), 0, 0);
        content.addView(library, libraryParams);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        dialog.setContentView(scroll);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(-1, landscape ? -1 : -2);
                window.setGravity(Gravity.BOTTOM);
            }
        });
        dialog.show();
    }

    private View settingsRow(String title, String subtitle, String value, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(5), dp(10), dp(5));
        row.setBackground(roundBg(mediaTheme.cardBackground, mediaTheme.cardStroke, 14));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView primary = text(title, 14, mediaTheme.textPrimary, true);
        TextView secondary = text(subtitle, 11, mediaTheme.textSecondary, false);
        labels.addView(primary, new LinearLayout.LayoutParams(-1, dp(23)));
        labels.addView(secondary, new LinearLayout.LayoutParams(-1, dp(19)));
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView right = text(value, 12, mediaTheme.accent, false);
        right.setGravity(Gravity.CENTER | Gravity.RIGHT);
        row.addView(right, new LinearLayout.LayoutParams(dp(112), dp(46)));
        row.setOnClickListener(listener);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(0, dp(2), 0, dp(2));
        holder.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        return holder;
    }

    private void showScaleDialog() {
        String[] labels = {org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_441), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_439), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_440), "100%"};
        new AlertDialog.Builder(this).setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_422)).setSingleChoiceItems(labels, scaleMode, (d, which) -> {
            scaleMode = which;
            if (surface != null) surface.setScaleMode(scaleMode);
            if (fitButton != null) fitButton.setText(scaleModeIcon());
            d.dismiss();
        }).show();
    }

    private void showSpeedDialog() {
        String[] labels = new String[speeds.length];
        int checked = 0;
        float current = controller.getSpeed();
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = formatSpeed(speeds[i]);
            if (Math.abs(speeds[i] - current) < Math.abs(speeds[checked] - current)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_444)).setSingleChoiceItems(labels, checked, (d, which) -> {
            controller.setPlaybackSpeed(speeds[which]);
            updateSpeedButton();
            d.dismiss();
        }).show();
    }

    private void showSeekStepDialog() {
        final int[] values = {5, 10, 15, 30};
        String[] labels = {org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_469), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_470), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_471), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_273)};
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == seekStepSeconds) checked = i;
        new AlertDialog.Builder(this).setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_445)).setSingleChoiceItems(labels, checked, (d, which) -> {
            seekStepSeconds = values[which];
            d.dismiss();
        }).show();
    }

    private void showOrientationDialog() {
        String[] labels = {org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_472), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_473), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_474)};
        new AlertDialog.Builder(this).setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_458)).setItems(labels, (d, which) -> {
            if (which == 1) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            else if (which == 2) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }).show();
    }

    private void toggleControlsLock() {
        controlsLocked = !controlsLocked;
        if (lockButton != null) lockButton.setVisibility(controlsLocked ? View.VISIBLE : View.GONE);
        if (controlsLocked) hideChrome(); else { showChrome(); scheduleChromeHide(); }
        Toast.makeText(this, controlsLocked ? org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_475) : org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_476), Toast.LENGTH_SHORT).show();
    }

    private void showSleepTimer() {
        String[] labels = {org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_477), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_478), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_479), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_480), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_481)};
        new AlertDialog.Builder(this).setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_463)).setItems(labels, (d, which) -> {
            if (which < 4) {
                long delay = new long[]{15, 30, 45, 60}[which] * 60_000L;
                handler.postDelayed(controller::pause, delay);
                Toast.makeText(this, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_482) + labels[which], Toast.LENGTH_SHORT).show();
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
        TextView heading = text(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_483), 19, mediaTheme.textPrimary, true);
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

        TextView mediaCenter = pill(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_484));
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
                if (landscape && !controlsLocked) toggleChrome();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!landscape || root == null || controlsLocked) return true;
                long delta = seekStepSeconds * 1000L;
                if (e.getX() < root.getWidth() / 2f) controller.seekRelative(-delta);
                else controller.seekRelative(delta);
                showChrome();
                scheduleChromeHide();
                return true;
            }
        });
    }

    private boolean handleStageTouch(MotionEvent event) {
        if (gestures != null) gestures.onTouchEvent(event);
        if (!landscape || controlsLocked) return true;
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
            } else if (gestureMode == 2 && gestureBrightness) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(.02f, Math.min(1f, gestureStartBrightness - dy / root.getHeight()));
                getWindow().setAttributes(lp);
            } else if (gestureMode == 3 && gestureVolume) {
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
        if (controlsLocked) return;
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
        } catch (Throwable tpError) { org.telegram.messenger.FileLog.e(tpError); }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && landscape && !pip) applyLandscapeWindowMode();
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
            bar.setProgressTintList(ColorStateList.valueOf(PLAYER_VIOLET));
            bar.setThumbTintList(ColorStateList.valueOf(PLAYER_VIOLET));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff42384f));
            bar.setSecondaryProgressTintList(ColorStateList.valueOf(0xff674e89));
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
        int color = PLAYER_WHITE;
        TextView v = text(value, sp, color, false);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    private TextView roundButton(String value, int sp) {
        TextView v = text(value, sp, PLAYER_WHITE, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(PLAYER_PANEL);
        bg.setStroke(dp(2), PLAYER_VIOLET);
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

    private static String stripAudioExtension(String title) { return TelePremiumPlayerMath.stripAudioExtension(title, org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TP_L10N_485)); }

    private static String formatTime(long ms) { return TelePremiumPlayerMath.formatTime(ms); }

    private static String formatSpeed(float speed) { return TelePremiumPlayerMath.formatSpeed(speed); }
}
