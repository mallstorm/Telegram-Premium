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
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.media.TelePremiumAudioEffects;
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
public class TelePremiumPlayerActivity extends Activity implements TelePremiumPlaybackController.Listener, TelePremiumQueueManager.Listener {
    public static final String EXTRA_START_POSITION = "telepremium_start_position_ms";
    private long pendingStartPosition=-1;
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
    public static final String EXTRA_CLOUD_PART_MESSAGES = "telepremium_player_cloud_part_messages";
    public static final String EXTRA_CLOUD_PART_DOCUMENTS = "telepremium_player_cloud_part_documents";
    public static final String EXTRA_CLOUD_PART_SIZES = "telepremium_player_cloud_part_sizes";
    public static final String EXTRA_CLOUD_PART_HASHES = "telepremium_player_cloud_part_hashes";
    public static final String EXTRA_OPEN_CURRENT = "telepremium_player_open_current";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
    private TelePremiumMediaItem launchItem;
    private TelePremiumMediaItem displayItem;
    private TelePremiumMediaTheme mediaTheme;

    private static final String PREFS = "telepremium_player_ui";
    private TelePremiumPlayerKit kit;
    private TelePremiumPlayerKit.Palette pal;
    private FrameLayout root;
    private ImageView artworkImage, playButton, fitButton, subtitlesButton, shuffleButton, repeatButton, sourceIcon, lockButton;
    private LinearLayout fallbackCover, sourcePill;
    private View nextCard, leftSeek, rightSeek;
    private TextView fallbackArtist, fallbackTitle, headerTitleView, titleView, artistView, stateView, positionView, durationView;
    private TextView speedButton, speedLabel, sourceLabel, hwButton, nextSubtitle;
    private TextView recLabel;
    private ImageView recIcon, recTool;
    private final org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.Listener recListener = (recording, elapsed, saved, error) -> {
        updateRecViews();
        if (saved != null) Toast.makeText(this, tf("TP_REC_SAVED", R.string.TP_REC_SAVED, saved), Toast.LENGTH_LONG).show();
        else if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    };
    private boolean portraitAudioLayout;
    private TelePremiumAspectSurfaceView surface;
    private SeekBar seekBar;
    private View topChrome, bottomChrome;
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
            if (item.isMultipart()) {
                cloudIntent.putExtra(EXTRA_CLOUD_PART_MESSAGES, item.partMessageIds);
                cloudIntent.putExtra(EXTRA_CLOUD_PART_DOCUMENTS, item.partDocumentIds);
                cloudIntent.putExtra(EXTRA_CLOUD_PART_SIZES, item.partSizes);
                cloudIntent.putExtra(EXTRA_CLOUD_PART_HASHES, item.partSha256);
            }
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
        pendingStartPosition=getIntent().getLongExtra(EXTRA_START_POSITION,-1);
        launchItem = readLaunchItem(getIntent());
        displayItem = launchItem != null ? launchItem : controller.getCurrent();
        controller.addListener(this);
        org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.addListener(recListener);
        controller.getQueue().addListener(this);
        applyLockscreenMode();
        buildUi();
        if (launchItem != null) controller.play(launchItem);
        else if (controller.getCurrent() == null) {
            Toast.makeText(this, t("TP_L10N_409", R.string.TP_L10N_409), Toast.LENGTH_SHORT).show();
            finish();
        } else {
            bindCurrent(controller.getCurrent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingStartPosition=intent.getLongExtra(EXTRA_START_POSITION,-1);
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
            long[] parts = intent.getLongArrayExtra(EXTRA_CLOUD_PART_MESSAGES);
            if (parts != null && parts.length > 1) {
                cloudItem.partMessageIds = parts;
                long[] docs = intent.getLongArrayExtra(EXTRA_CLOUD_PART_DOCUMENTS);
                long[] sizes = intent.getLongArrayExtra(EXTRA_CLOUD_PART_SIZES);
                String[] hashes = intent.getStringArrayExtra(EXTRA_CLOUD_PART_HASHES);
                cloudItem.partDocumentIds = docs != null && docs.length == parts.length ? docs : new long[parts.length];
                cloudItem.partSizes = sizes != null && sizes.length == parts.length ? sizes : new long[parts.length];
                cloudItem.partSha256 = hashes != null && hashes.length == parts.length ? hashes : new String[parts.length];
            }
            return cloudItem;
        }
        String path = intent.getStringExtra(EXTRA_PATH);
        Uri uri = path == null ? intent.getData() : Uri.fromFile(new File(path));
        if (uri == null) return null;
        if (TextUtils.isEmpty(name)) name = resolveDisplayName(uri);
        if (TextUtils.isEmpty(name)) name = path == null ? t("TP_L10N_410", R.string.TP_L10N_410) : new File(path).getName();
        if (TextUtils.isEmpty(mime)) mime = intent.getType();
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return TelePremiumMediaItem.network(uri.toString(), name, mime, false);
        return TelePremiumMediaItem.device(uri.toString(), name, mime, 0, 0);
    }

    private void buildUi() {
        mediaTheme = TelePremiumMediaTheme.from(this);
        kit = new TelePremiumPlayerKit(this);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        chromeVisible = true;
        clearViewRefs();
        if (landscape) buildLandscape(); else buildPortrait();
        // The smooth-motion view feeds the decoder through its own Surface once GL is ready.
        if (!(surface instanceof TelePremiumSmoothVideoView)) controller.bindSurface(surface);
        bindCurrent(displayItem != null ? displayItem : controller.getCurrent());
        setupGestures();
        updatePlayButton();
        updateSpeedButton();
        refreshQueueModeButtons();
        refreshNextCard();
        if (landscape) scheduleChromeHide();
    }

    private void clearViewRefs() {
        surface = null; artworkImage = null; fallbackCover = null; fallbackArtist = null; fallbackTitle = null;
        headerTitleView = null; titleView = null; artistView = null; stateView = null; positionView = null; durationView = null;
        playButton = null; speedButton = null; speedLabel = null; fitButton = null; subtitlesButton = null; hwButton = null;
        shuffleButton = null; repeatButton = null; sourcePill = null; sourceIcon = null; sourceLabel = null;
        recLabel = null; recIcon = null; recTool = null;
        nextCard = null; nextSubtitle = null; topChrome = null; bottomChrome = null; leftSeek = null; rightSeek = null; lockButton = null;
    }

    // ---------------------------------------------------------------- portrait (music + video)

    private void buildPortrait() {
        resetPortraitWindowMode();
        pal = TelePremiumPlayerKit.Palette.surface(mediaTheme);
        Window window = getWindow();
        window.setStatusBarColor(pal.background);
        window.setNavigationBarColor(pal.background);
        mediaTheme.applySystemBars(this);

        TelePremiumMediaItem current = displayItem != null ? displayItem : controller.getCurrent();
        portraitAudioLayout = current == null || !current.isVideo();
        root = new FrameLayout(this);
        root.setBackgroundColor(pal.background);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(18));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

        // Top bar: back · title · (video: HW, subtitles) · more
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(6), 0);
        top.addView(kit.iconButton(R.drawable.tp_ic_back, pal.text, 26, t("TP_L10N_413", R.string.TP_L10N_413), v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        headerTitleView = kit.singleLine(kit.text(t("TP_L10N_414", R.string.TP_L10N_414), 21, pal.text, true));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(10);
        top.addView(headerTitleView, titleLp);
        if (!portraitAudioLayout) addVideoHeaderButtons(top);
        top.addView(kit.iconButton(R.drawable.tp_ic_more, pal.text, 24, t("TP_L10N_418", R.string.TP_L10N_418), v -> showPlayerSettings()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        topChrome = top;
        column.addView(top, new LinearLayout.LayoutParams(-1, dp(60)));

        // Stage: square artwork card for music, 16:9 surface for video
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        FrameLayout stage = createStage(false);
        LinearLayout.LayoutParams stageLp;
        if (portraitAudioLayout) {
            int side = Math.min(screenWidth - dp(56), dp(380));
            stageLp = new LinearLayout.LayoutParams(side, side);
            stageLp.gravity = Gravity.CENTER_HORIZONTAL;
            stageLp.topMargin = dp(6);
        } else {
            stageLp = new LinearLayout.LayoutParams(-1, screenWidth * 9 / 16);
        }
        column.addView(stage, stageLp);

        // Title / artist / source chip — wrap_content so nothing gets clipped
        titleView = kit.singleLine(kit.text("", portraitAudioLayout ? 26 : 20, pal.text, true));
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(-1, -2);
        titleRowLp.setMargins(dp(24), dp(22), dp(24), 0);
        column.addView(titleView, titleRowLp);
        artistView = kit.singleLine(kit.text("", 16, pal.muted, false));
        artistView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
        artistLp.setMargins(dp(24), dp(8), dp(24), 0);
        column.addView(artistView, artistLp);
        sourcePill = kit.pill(R.drawable.tp_ic_device, "", pal, false);
        sourceIcon = (ImageView) sourcePill.getChildAt(0);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        pillLp.topMargin = dp(14);
        column.addView(sourcePill, pillLp);
        stateView = kit.singleLine(kit.text("", 12, pal.muted, false));
        stateView.setGravity(Gravity.CENTER);
        stateView.setVisibility(View.GONE);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
        stateLp.topMargin = dp(6);
        column.addView(stateView, stateLp);

        column.addView(createTimeline(), timelineParams());
        column.addView(createPortraitTransport(), new LinearLayout.LayoutParams(-1, dp(92)));

        View line = kit.divider(TelePremiumPlayerKit.withAlpha(pal.muted, 0x33), false);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, dp(1));
        lineLp.setMargins(dp(24), dp(4), dp(24), dp(4));
        column.addView(line, lineLp);
        column.addView(createExtrasRow(), new LinearLayout.LayoutParams(-1, dp(78)));

        nextCard = createNextCard();
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(-1, dp(66));
        nextLp.setMargins(dp(20), dp(12), dp(20), 0);
        column.addView(nextCard, nextLp);
        setContentView(root);
    }

    private void addVideoHeaderButtons(LinearLayout top) {
        hwButton = kit.text("HW", 13, pal.muted, true);
        hwButton.setGravity(Gravity.CENTER);
        hwButton.setContentDescription(t("TP_L10N_415", R.string.TP_L10N_415));
        hwButton.setOnClickListener(v -> showPlayerSettings());
        top.addView(hwButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        subtitlesButton = kit.iconButton(R.drawable.tp_ic_subtitles, pal.text, 22, t("TP_L10N_416", R.string.TP_L10N_416), v -> {
            controller.setSubtitlesEnabled(!controller.isSubtitlesEnabled());
            subtitlesButton.setAlpha(controller.isSubtitlesEnabled() ? 1f : .4f);
        });
        subtitlesButton.setAlpha(controller.isSubtitlesEnabled() ? 1f : .4f);
        top.addView(subtitlesButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    private LinearLayout.LayoutParams timelineParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.setMargins(dp(14), dp(18), dp(14), 0);
        return lp;
    }

    private View createTimeline() {
        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        positionView = kit.text("0:00", 13, pal.text, false);
        durationView = kit.text("0:00", 13, pal.text, false);
        positionView.setGravity(Gravity.CENTER);
        durationView.setGravity(Gravity.CENTER);
        seekBar = new SeekBar(this);
        seekBar.setMax(10000);
        kit.tintSeekBar(seekBar, pal);
        timeline.addView(positionView, new LinearLayout.LayoutParams(dp(58), -1));
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, -1, 1f));
        timeline.addView(durationView, new LinearLayout.LayoutParams(dp(58), -1));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) positionView.setText(formatTime(progressToMs(progress)));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; handler.removeCallbacks(hideChromeRunnable); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                controller.seekTo(progressToMs(bar.getProgress()));
                scheduleChromeHide();
            }
        });
        return timeline;
    }

    /** Five equal slots: the play button sits in the middle one, i.e. exactly at the center. */
    private View createPortraitTransport() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        View left, right;
        if (portraitAudioLayout) {
            shuffleButton = kit.iconButton(R.drawable.tp_ic_shuffle, pal.text, 24, t("TP_L10N_425", R.string.TP_L10N_425), v -> toggleShuffle());
            repeatButton = kit.iconButton(R.drawable.tp_ic_repeat, pal.text, 24, t("TP_L10N_426", R.string.TP_L10N_426), v -> toggleRepeat());
            left = shuffleButton;
            right = repeatButton;
        } else {
            fitButton = kit.iconButton(R.drawable.tp_ic_aspect, pal.text, 24, t("TP_L10N_422", R.string.TP_L10N_422), v -> toggleFit());
            left = fitButton;
            right = kit.iconButton(R.drawable.tp_ic_pip, pal.text, 24, t("TP_L10N_423", R.string.TP_L10N_423), v -> enterPip());
        }
        ImageView prev = kit.iconButton(R.drawable.tp_ic_prev, pal.text, 30, t("TP_L10N_419", R.string.TP_L10N_419), v -> controller.previous());
        ImageView next = kit.iconButton(R.drawable.tp_ic_next, pal.text, 30, t("TP_L10N_420", R.string.TP_L10N_420), v -> controller.next());
        playButton = kit.playButton(pal, 80, v -> { controller.toggle(); scheduleChromeHide(); });
        playButton.setContentDescription(t("TP_L10N_424", R.string.TP_L10N_424));
        row.addView(kit.slot(left, 52, 52), new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(kit.slot(prev, 56, 56), new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(kit.slot(playButton, 80, 80), new LinearLayout.LayoutParams(0, -1, 1.2f));
        row.addView(kit.slot(next, 56, 56), new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(kit.slot(right, 52, 52), new LinearLayout.LayoutParams(0, -1, 1f));
        return row;
    }

    /** "1× Скорость" | "source Устройство" | "list Очередь" with thin vertical dividers. */
    private View createExtrasRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        int dividerColor = TelePremiumPlayerKit.withAlpha(pal.muted, 0x33);

        LinearLayout speed = extraColumn(null, t("TP_P_SPEED", R.string.TP_P_SPEED), v -> showSpeedDialog());
        speedButton = kit.text("1×", 22, pal.text, true);
        speedButton.setGravity(Gravity.CENTER);
        speed.addView(speedButton, 0, new LinearLayout.LayoutParams(-2, dp(28)));
        speedLabel = (TextView) speed.getChildAt(1);

        LinearLayout source = extraColumn(R.drawable.tp_ic_device, t("TP_L10N_411", R.string.TP_L10N_411), v -> showPlayerSettings());
        sourceLabel = (TextView) source.getChildAt(1);
        LinearLayout queue = extraColumn(R.drawable.tp_ic_list, t("TP_P_QUEUE", R.string.TP_P_QUEUE), v -> showQueue());
        LinearLayout rec = extraColumn(R.drawable.tp_ic_rec, t("TP_REC", R.string.TP_REC), v -> toggleRecording());
        recIcon = (ImageView) rec.getChildAt(0);
        recLabel = (TextView) rec.getChildAt(1);

        row.addView(speed, new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(kit.divider(dividerColor, true));
        row.addView(source, new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(kit.divider(dividerColor, true));
        row.addView(queue, new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(kit.divider(dividerColor, true));
        row.addView(rec, new LinearLayout.LayoutParams(0, -1, 1f));
        updateRecViews();
        return row;
    }

    // ---------------------------------------------------------------- recording

    private void toggleRecording() {
        TelePremiumMediaItem item = controller.getCurrent();
        if (item == null) return;
        org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.toggle(item.title);
        updateRecViews();
        if (org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.isRecording())
            Toast.makeText(this, t("TP_REC_STARTED", R.string.TP_REC_STARTED), Toast.LENGTH_SHORT).show();
    }

    private void updateRecViews() {
        boolean on = org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.isRecording();
        int red = 0xffe53935;
        if (recIcon != null) kit.setIcon(recIcon, on ? R.drawable.tp_ic_rec : R.drawable.tp_ic_rec, on ? red : TelePremiumPlayerKit.withAlpha(red, 0xb0));
        if (recLabel != null) {
            recLabel.setText(on ? "● " + formatTime(org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.elapsedMs()) : t("TP_REC", R.string.TP_REC));
            recLabel.setTextColor(on ? red : pal.muted);
        }
        if (recTool != null) kit.setIcon(recTool, R.drawable.tp_ic_rec, on ? red : TelePremiumPlayerKit.withAlpha(red, 0xb0));
    }

    private LinearLayout extraColumn(Integer iconRes, String label, View.OnClickListener listener) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(Color.TRANSPARENT, Color.TRANSPARENT, 14, 0), pal.accent));
        if (iconRes != null) col.addView(kit.icon(iconRes, pal.text), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView text = kit.singleLine(kit.text(label, 13, pal.muted, false));
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        col.addView(text, lp);
        col.setOnClickListener(listener);
        return col;
    }

    private View createNextCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), 0, dp(12), 0);
        card.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(pal.panel, pal.stroke, 22, 1), pal.accent));
        card.addView(kit.icon(R.drawable.tp_ic_list, pal.accent), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = kit.singleLine(kit.text(t("TP_P_UP_NEXT", R.string.TP_P_UP_NEXT), 16, pal.text, true));
        nextSubtitle = kit.singleLine(kit.text("", 12, pal.muted, false));
        texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(3);
        texts.addView(nextSubtitle, subLp);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textsLp.leftMargin = dp(16);
        card.addView(texts, textsLp);
        card.addView(kit.icon(R.drawable.tp_ic_chevron_right, pal.muted), new LinearLayout.LayoutParams(dp(24), dp(24)));
        card.setOnClickListener(v -> showQueue());
        return card;
    }

    // ---------------------------------------------------------------- landscape video

    private void buildLandscape() {
        applyLandscapeWindowMode();
        pal = TelePremiumPlayerKit.Palette.video(mediaTheme);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(createStage(true), new FrameLayout.LayoutParams(-1, -1));

        // Top chrome: back · title · speed pill · HW · queue · more, then the source chip
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), dp(10), dp(18), dp(26));
        top.setBackground(chromeGradient(false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(kit.iconButton(R.drawable.tp_ic_back, pal.text, 28, t("TP_L10N_413", R.string.TP_L10N_413), v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        headerTitleView = kit.singleLine(kit.text("", 20, pal.text, true));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(12);
        row.addView(headerTitleView, titleLp);
        TextView topSpeed = kit.text("1×", 15, pal.accent, true);
        topSpeed.setGravity(Gravity.CENTER);
        topSpeed.setPadding(dp(16), 0, dp(16), 0);
        topSpeed.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(pal.panel, pal.accent, 18, 1.5f), pal.accent));
        topSpeed.setOnClickListener(v -> cycleSpeed());
        topSpeed.setTag("speed");
        LinearLayout.LayoutParams speedLp = new LinearLayout.LayoutParams(-2, dp(36));
        speedLp.rightMargin = dp(10);
        row.addView(topSpeed, speedLp);
        hwButton = kit.text("HW", 15, pal.text, true);
        hwButton.setGravity(Gravity.CENTER);
        hwButton.setOnClickListener(v -> showPlayerSettings());
        row.addView(hwButton, new LinearLayout.LayoutParams(dp(52), dp(44)));
        row.addView(kit.iconButton(R.drawable.tp_ic_list, pal.text, 26, t("TP_L10N_417", R.string.TP_L10N_417), v -> showQueue()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(kit.iconButton(R.drawable.tp_ic_more, pal.text, 26, t("TP_L10N_418", R.string.TP_L10N_418), v -> showPlayerSettings()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(row, new LinearLayout.LayoutParams(-1, dp(52)));
        sourcePill = kit.pill(R.drawable.tp_ic_cloud, "", pal, false);
        sourcePill.setBackground(TelePremiumPlayerKit.round(TelePremiumPlayerKit.withAlpha(Color.BLACK, 0x66), TelePremiumPlayerKit.withAlpha(pal.text, 0x40), 20, 1));
        sourceIcon = (ImageView) sourcePill.getChildAt(0);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.setMargins(dp(4), dp(6), 0, 0);
        top.addView(sourcePill, pillLp);
        stateView = null;
        topChrome = top;
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Edge seek buttons («  ») on the left and right center
        leftSeek = edgeSeekButton(true);
        rightSeek = edgeSeekButton(false);
        root.addView(leftSeek, new FrameLayout.LayoutParams(dp(64), dp(132), Gravity.LEFT | Gravity.CENTER_VERTICAL));
        root.addView(rightSeek, new FrameLayout.LayoutParams(dp(64), dp(132), Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        // Bottom chrome: timeline, then [speed] — [prev ▶ next] — [aspect pip queue]
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(22), dp(28), dp(22), dp(14));
        bottom.setBackground(chromeGradient(true));
        bottom.addView(createTimeline(), new LinearLayout.LayoutParams(-1, dp(44)));
        FrameLayout controls = new FrameLayout(this);
        speedButton = kit.text("1×", 18, pal.accent, true);
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(pal.panel, pal.stroke, 26, 1), pal.accent));
        speedButton.setOnClickListener(v -> cycleSpeed());
        controls.addView(speedButton, new FrameLayout.LayoutParams(dp(92), dp(52), Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER_VERTICAL);
        center.addView(kit.iconButton(R.drawable.tp_ic_prev, pal.text, 30, t("TP_L10N_419", R.string.TP_L10N_419), v -> { controller.previous(); scheduleChromeHide(); }), new LinearLayout.LayoutParams(dp(60), dp(60)));
        playButton = kit.playButton(pal, 74, v -> { controller.toggle(); scheduleChromeHide(); });
        playButton.setContentDescription(t("TP_L10N_424", R.string.TP_L10N_424));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(74), dp(74));
        playLp.setMargins(dp(26), 0, dp(26), 0);
        center.addView(playButton, playLp);
        center.addView(kit.iconButton(R.drawable.tp_ic_next, pal.text, 30, t("TP_L10N_420", R.string.TP_L10N_420), v -> { controller.next(); scheduleChromeHide(); }), new LinearLayout.LayoutParams(dp(60), dp(60)));
        controls.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        fitButton = kit.toolButton(R.drawable.tp_ic_expand, pal, t("TP_L10N_422", R.string.TP_L10N_422), v -> { toggleFit(); scheduleChromeHide(); });
        tools.addView(fitButton, toolLp());
        recTool = kit.toolButton(R.drawable.tp_ic_rec, pal, t("TP_REC", R.string.TP_REC), v -> { toggleRecording(); scheduleChromeHide(); });
        tools.addView(recTool, toolLp());
        tools.addView(kit.toolButton(R.drawable.tp_ic_pip, pal, t("TP_L10N_423", R.string.TP_L10N_423), v -> enterPip()), toolLp());
        tools.addView(kit.toolButton(R.drawable.tp_ic_list, pal, t("TP_L10N_417", R.string.TP_L10N_417), v -> showQueue()), toolLp());
        controls.addView(tools, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        bottom.addView(controls, new LinearLayout.LayoutParams(-1, dp(80)));
        bottomChrome = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        lockButton = kit.toolButton(R.drawable.tp_ic_lock, pal, t("TP_L10N_461", R.string.TP_L10N_461), v -> toggleControlsLock());
        lockButton.setVisibility(controlsLocked ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lockParams = new FrameLayout.LayoutParams(dp(56), dp(52), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lockParams.setMargins(0, 0, dp(84), 0);
        root.addView(lockButton, lockParams);
        setContentView(root);
    }

    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(52));
        lp.leftMargin = dp(10);
        return lp;
    }

    private View edgeSeekButton(boolean back) {
        FrameLayout button = new FrameLayout(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(TelePremiumPlayerKit.withAlpha(Color.BLACK, 0x55));
        shape.setStroke(dp(1), TelePremiumPlayerKit.withAlpha(pal.accent, 0x66));
        float r = dp(66);
        shape.setCornerRadii(back ? new float[]{0, 0, r, r, r, r, 0, 0} : new float[]{r, r, 0, 0, 0, 0, r, r});
        button.setBackground(TelePremiumPlayerKit.ripple(shape, pal.accent));
        ImageView arrow = kit.icon(back ? R.drawable.tp_ic_rewind : R.drawable.tp_ic_forward, pal.accent);
        button.addView(arrow, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        button.setContentDescription(back ? tf("TP_P_SEEK_BACK_FMT", R.string.TP_P_SEEK_BACK_FMT, seekStepSeconds) : tf("TP_P_SEEK_FWD_FMT", R.string.TP_P_SEEK_FWD_FMT, seekStepSeconds));
        button.setOnClickListener(v -> {
            controller.seekRelative((back ? -1 : 1) * seekStepSeconds * 1000L);
            scheduleChromeHide();
        });
        return button;
    }

    private GradientDrawable chromeGradient(boolean bottom) {
        int deep = 0xd9000000, mid = 0x80000000, clear = 0x00000000;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                bottom ? new int[]{clear, mid, deep} : new int[]{deep, mid, clear});
    }

    // ---------------------------------------------------------------- stage

    private FrameLayout createStage(boolean fullscreen) {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(fullscreen ? Color.BLACK : (portraitAudioLayout ? Color.TRANSPARENT : Color.BLACK));
        surface = isSmoothMotionEnabled() && TelePremiumSmoothVideoView.isSupported(this)
                ? new TelePremiumSmoothVideoView(this) : new TelePremiumAspectSurfaceView(this);
        surface.setScaleMode(scaleMode);
        stage.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        if (!fullscreen && portraitAudioLayout) {
            // Fallback cover in theme colors: note, ARTIST spaced, Title in accent
            fallbackCover = new LinearLayout(this);
            fallbackCover.setOrientation(LinearLayout.VERTICAL);
            fallbackCover.setGravity(Gravity.CENTER);
            GradientDrawable cover = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                    TelePremiumPlayerKit.withAlpha(pal.accent, 0x2e), pal.panel, TelePremiumPlayerKit.withAlpha(pal.accent, 0x18)});
            cover.setCornerRadius(dp(28));
            cover.setStroke(dp(1), pal.stroke);
            fallbackCover.setBackground(cover);
            fallbackCover.addView(kit.icon(R.drawable.tp_ic_music, pal.accent), new LinearLayout.LayoutParams(dp(112), dp(112)));
            fallbackArtist = kit.singleLine(kit.text("", 15, pal.muted, false));
            fallbackArtist.setGravity(Gravity.CENTER);
            fallbackArtist.setLetterSpacing(0.35f);
            LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
            artistLp.setMargins(dp(20), dp(18), dp(20), 0);
            fallbackCover.addView(fallbackArtist, artistLp);
            fallbackTitle = kit.singleLine(kit.text("", 19, pal.accent, true));
            fallbackTitle.setGravity(Gravity.CENTER);
            fallbackTitle.setLetterSpacing(0.22f);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
            titleLp.setMargins(dp(20), dp(8), dp(20), 0);
            fallbackCover.addView(fallbackTitle, titleLp);
            stage.addView(fallbackCover, new FrameLayout.LayoutParams(-1, -1));

            artworkImage = new ImageView(this);
            artworkImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artworkImage.setBackground(TelePremiumPlayerKit.round(pal.panel, pal.stroke, 28, 1));
            artworkImage.setClipToOutline(true);
            artworkImage.setVisibility(View.GONE);
            stage.addView(artworkImage, new FrameLayout.LayoutParams(-1, -1));
        }
        stage.setOnTouchListener((v, event) -> handleStageTouch(event));
        return stage;
    }

    // ---------------------------------------------------------------- binding

    private void toggleShuffle() {
        TelePremiumQueueManager queue = controller.getQueue();
        queue.setShuffle(!queue.isShuffle());
        refreshQueueModeButtons();
    }

    private void toggleRepeat() {
        TelePremiumQueueManager queue = controller.getQueue();
        int mode = queue.getRepeatMode();
        queue.setRepeatMode(mode == TelePremiumQueueManager.REPEAT_OFF ? TelePremiumQueueManager.REPEAT_ALL
                : mode == TelePremiumQueueManager.REPEAT_ALL ? TelePremiumQueueManager.REPEAT_ONE : TelePremiumQueueManager.REPEAT_OFF);
        refreshQueueModeButtons();
        refreshNextCard();
    }

    private void refreshQueueModeButtons() {
        if (kit == null || pal == null) return;
        TelePremiumQueueManager queue = controller.getQueue();
        if (shuffleButton != null) kit.setIcon(shuffleButton, R.drawable.tp_ic_shuffle, queue.isShuffle() ? pal.accent : pal.text);
        if (repeatButton != null) {
            int mode = queue.getRepeatMode();
            kit.setIcon(repeatButton, mode == TelePremiumQueueManager.REPEAT_ONE ? R.drawable.tp_ic_repeat_one : R.drawable.tp_ic_repeat,
                    mode == TelePremiumQueueManager.REPEAT_OFF ? pal.text : pal.accent);
        }
    }

    private void refreshNextCard() {
        if (nextSubtitle == null) return;
        TelePremiumMediaItem next = controller.getQueue().peekNext();
        nextSubtitle.setText(next == null ? t("TP_P_END_OF_QUEUE", R.string.TP_P_END_OF_QUEUE)
                : tf("TP_P_NEXT_FMT", R.string.TP_P_NEXT_FMT, next.isVideo() ? next.title : stripAudioExtension(next.title)));
    }

    @Override public void onQueueChanged(List<TelePremiumMediaItem> items, int currentIndex) {
        handler.post(() -> { refreshNextCard(); refreshQueueModeButtons(); });
    }

    private void bindCurrent(TelePremiumMediaItem item) {
        if (item == null) return;
        if (!landscape && portraitAudioLayout == item.isVideo()) {
            // The queue switched between music and video: rebuild the matching layout.
            displayItem = item;
            buildUi();
            return;
        }
        displayItem = item;
        boolean video = item.isVideo();
        String cleanTitle = video ? item.title : stripAudioExtension(item.title);
        String artist = TextUtils.isEmpty(item.artist)
                ? (video ? t("TP_L10N_098", R.string.TP_L10N_098) : t("TP_L10N_343", R.string.TP_L10N_343)) : item.artist;
        if (titleView != null) titleView.setText(cleanTitle);
        if (artistView != null) artistView.setText(artist);
        if (headerTitleView != null) headerTitleView.setText(landscape
                ? (TextUtils.isEmpty(item.title) ? "TelePremium Media" : item.title)
                : video ? t("TP_L10N_098", R.string.TP_L10N_098) : t("TP_L10N_414", R.string.TP_L10N_414));
        TelePremiumPlayerKit.setPillText(sourcePill, landscape ? landscapeSourceText(item) : sourceText(item));
        if (sourceIcon != null) kit.setIcon(sourceIcon, sourceIconRes(item), landscape ? pal.accent : pal.muted);
        if (sourceLabel != null) sourceLabel.setText(sourceShortText(item));
        if (fallbackArtist != null) fallbackArtist.setText(TextUtils.isEmpty(item.artist) ? "" : item.artist.toUpperCase(Locale.getDefault()));
        if (fallbackTitle != null) fallbackTitle.setText(cleanTitle == null ? "" : cleanTitle.toUpperCase(Locale.getDefault()));

        if (surface != null) {
            surface.setVisibility(video ? View.VISIBLE : View.GONE);
            surface.setScaleMode(scaleMode);
        }
        if (fallbackCover != null) fallbackCover.setVisibility(video ? View.GONE : View.VISIBLE);
        if (artworkImage != null) {
            artworkImage.setVisibility(View.GONE);
            artworkImage.setImageDrawable(null);
            if (!video) {
                final String requestedId = item.id;
                TelePremiumArtworkLoader.load(this, item, dp(420), bitmap -> {
                    TelePremiumMediaItem current = controller.getCurrent();
                    if (artworkImage == null || current == null || !requestedId.equals(current.id)) return;
                    if (bitmap != null && !bitmap.isRecycled()) {
                        artworkImage.setImageBitmap(bitmap);
                        artworkImage.setVisibility(View.VISIBLE);
                        if (fallbackCover != null) fallbackCover.setVisibility(View.GONE);
                    }
                });
            }
        }
        if (hwButton != null) hwButton.setVisibility(video ? View.VISIBLE : View.GONE);
        if (subtitlesButton != null) subtitlesButton.setVisibility(video ? View.VISIBLE : View.GONE);
        refreshNextCard();
    }

    private int sourceIconRes(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) return R.drawable.tp_ic_cloud;
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK) return item.isVideo() ? R.drawable.tp_ic_tv : (item.live ? R.drawable.tp_ic_radio : R.drawable.tp_ic_wifi);
        return R.drawable.tp_ic_device;
    }

    private String sourceShortText(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) return t("TP_L10N_069", R.string.TP_L10N_069);
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK) return t("TP_L10N_431", R.string.TP_L10N_431);
        return t("TP_L10N_411", R.string.TP_L10N_411);
    }

    private String sourceText(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD) return item.encryptionVersion > 0 ? "TelePremium Cloud · Vault" : "TelePremium Cloud";
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK) {
            if (!TextUtils.isEmpty(item.album)) return item.album;
            return item.live ? t("TP_L10N_430", R.string.TP_L10N_430) : t("TP_L10N_431", R.string.TP_L10N_431);
        }
        return t("TP_L10N_411", R.string.TP_L10N_411);
    }

    private String landscapeSourceText(TelePremiumMediaItem item) {
        boolean streaming = item.sourceType != TelePremiumMediaItem.SOURCE_DEVICE;
        return streaming ? t("TP_P_STREAMING", R.string.TP_P_STREAMING) + " · " + sourceText(item) : sourceText(item);
    }

    /** The window has no insets controller until its decor view exists (it does not yet inside onCreate). */
    private static android.view.WindowInsetsController safeInsets(Window window) {
        if (Build.VERSION.SDK_INT < 30 || window == null || window.peekDecorView() == null) return null;
        try {
            return window.getInsetsController();
        } catch (RuntimeException e) {
            return null;
        }
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
        android.view.WindowInsetsController insets = safeInsets(window);
        if (insets != null) {
            insets.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
        android.view.WindowInsetsController insets = safeInsets(window);
        if (insets != null) {
            insets.show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attributes);
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override public void onMediaChanged(TelePremiumMediaItem item) { displayItem = item; bindCurrent(item); }

    @Override public void onStateChanged(int state) {
        if(pendingStartPosition>=0&&(state==TelePremiumPlaybackController.STATE_READY||state==TelePremiumPlaybackController.STATE_PLAYING)){long seek=pendingStartPosition;pendingStartPosition=-1;controller.seekTo(seek);}
        updatePlayButton();
        if (stateView == null) return;
        TelePremiumMediaItem item = controller.getCurrent();
        boolean transient_ = state == TelePremiumPlaybackController.STATE_RESOLVING || state == TelePremiumPlaybackController.STATE_BUFFERING
                || state == TelePremiumPlaybackController.STATE_SEEKING || state == TelePremiumPlaybackController.STATE_ERROR;
        stateView.setVisibility(transient_ ? View.VISIBLE : View.GONE);
        if (state == TelePremiumPlaybackController.STATE_RESOLVING) stateView.setText(t("TP_L10N_433", R.string.TP_L10N_433));
        else if (state == TelePremiumPlaybackController.STATE_BUFFERING) stateView.setText(controller.isCloudStreaming() ? t("TP_L10N_434", R.string.TP_L10N_434) : t("TP_L10N_435", R.string.TP_L10N_435));
        else if (state == TelePremiumPlaybackController.STATE_SEEKING) stateView.setText(t("TP_L10N_436", R.string.TP_L10N_436));
        else if (state == TelePremiumPlaybackController.STATE_ERROR) stateView.setText(t("TP_L10N_437", R.string.TP_L10N_437));
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
        if (org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.isRecording()) updateRecViews();
        durationView.setText(duration > 0 ? formatTime(duration) : "LIVE");
    }

    @Override public void onVideoSizeChanged(int width, int height) {
        if (surface != null) surface.setVideoSize(width, height);
    }

    @Override public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void updatePlayButton() {
        if (playButton != null && kit != null) kit.setIcon(playButton, controller.isPlaying() ? R.drawable.tp_ic_pause : R.drawable.tp_ic_play, pal.text);
    }

    private void updateSpeedButton() {
        String value = formatSpeed(controller.getSpeed());
        if (speedButton != null) speedButton.setText(value);
        if (topChrome != null) {
            View top = topChrome.findViewWithTag("speed");
            if (top instanceof TextView) ((TextView) top).setText(value);
        }
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
        Toast.makeText(this, t("TP_L10N_438", R.string.TP_L10N_438) + scaleModeLabel(), Toast.LENGTH_SHORT).show();
    }

    private String scaleModeIcon() {
        if (scaleMode == SCALE_CROP) return "⌗";
        if (scaleMode == SCALE_STRETCH) return "⇔";
        if (scaleMode == SCALE_ORIGINAL) return "1:1";
        return "↔";
    }

    private String scaleModeLabel() {
        if (scaleMode == SCALE_CROP) return t("TP_L10N_439", R.string.TP_L10N_439);
        if (scaleMode == SCALE_STRETCH) return t("TP_L10N_440", R.string.TP_L10N_440);
        if (scaleMode == SCALE_ORIGINAL) return "100%";
        return t("TP_L10N_441", R.string.TP_L10N_441);
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

        TextView heading = text(t("TP_L10N_428", R.string.TP_L10N_428), 20, mediaTheme.textPrimary, true);
        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(50)));
        content.addView(settingsRow(t("TP_FX_TITLE", R.string.TP_FX_TITLE), t("TP_FX_SUBTITLE", R.string.TP_FX_SUBTITLE), TelePremiumAudioEffects.isActive() ? t("TP_L10N_450", R.string.TP_L10N_450) : "›", v -> { dialog.dismiss(); TelePremiumAudioEffectsDialog.show(this, mediaTheme); }));
        content.addView(settingsRow(t("TP_P_SMOOTH", R.string.TP_P_SMOOTH), t("TP_P_SMOOTH_INFO", R.string.TP_P_SMOOTH_INFO), isSmoothMotionEnabled() ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> {
            getSharedPreferences("telepremium_player_ui", MODE_PRIVATE).edit().putBoolean("smooth_motion", !isSmoothMotionEnabled()).apply();
            dialog.dismiss();
            buildUi();
            Toast.makeText(this, isSmoothMotionEnabled() ? t("TP_P_SMOOTH_ON", R.string.TP_P_SMOOTH_ON) : t("TP_P_SMOOTH_OFF", R.string.TP_P_SMOOTH_OFF), Toast.LENGTH_SHORT).show();
        }));
        content.addView(settingsRow(t("TP_P_LOCKSCREEN", R.string.TP_P_LOCKSCREEN), t("TP_P_LOCKSCREEN_INFO", R.string.TP_P_LOCKSCREEN_INFO), isLockscreenEnabled() ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("lockscreen", !isLockscreenEnabled()).apply();
            applyLockscreenMode();
            dialog.dismiss(); showPlayerSettings();
        }));
        content.addView(settingsRow(t("TP_L10N_442", R.string.TP_L10N_442), "Telegram ExoPlayer", controller.getDecoderLabel(), v ->
                Toast.makeText(this, t("TP_L10N_443", R.string.TP_L10N_443), Toast.LENGTH_LONG).show()));
        content.addView(settingsRow(t("TP_L10N_422", R.string.TP_L10N_422), "Fit / Crop / Stretch / 100%", scaleModeLabel(), v -> { dialog.dismiss(); showScaleDialog(); }));
        content.addView(settingsRow(t("TP_L10N_444", R.string.TP_L10N_444), t("TP_L10N_421", R.string.TP_L10N_421), formatSpeed(controller.getSpeed()), v -> { dialog.dismiss(); showSpeedDialog(); }));
        content.addView(settingsRow(t("TP_L10N_445", R.string.TP_L10N_445), t("TP_L10N_446", R.string.TP_L10N_446), seekStepSeconds + t("TP_L10N_447", R.string.TP_L10N_447), v -> { dialog.dismiss(); showSeekStepDialog(); }));
        content.addView(settingsRow(t("TP_L10N_448", R.string.TP_L10N_448), t("TP_L10N_449", R.string.TP_L10N_449), gestureBrightness ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> { gestureBrightness = !gestureBrightness; dialog.dismiss(); showPlayerSettings(); }));
        content.addView(settingsRow(t("TP_L10N_452", R.string.TP_L10N_452), t("TP_L10N_453", R.string.TP_L10N_453), gestureVolume ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> { gestureVolume = !gestureVolume; dialog.dismiss(); showPlayerSettings(); }));
        content.addView(settingsRow(t("TP_L10N_416", R.string.TP_L10N_416), t("TP_L10N_454", R.string.TP_L10N_454), controller.isSubtitlesEnabled() ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> { controller.setSubtitlesEnabled(!controller.isSubtitlesEnabled()); dialog.dismiss(); showPlayerSettings(); }));
        content.addView(settingsRow(t("TP_L10N_455", R.string.TP_L10N_455), t("TP_L10N_426", R.string.TP_L10N_426), controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? t("TP_L10N_451", R.string.TP_L10N_451) : t("TP_L10N_450", R.string.TP_L10N_450), v -> {
            controller.getQueue().setRepeatMode(controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? TelePremiumQueueManager.REPEAT_ALL : TelePremiumQueueManager.REPEAT_OFF);
            dialog.dismiss(); showPlayerSettings();
        }));
        content.addView(settingsRow(t("TP_L10N_456", R.string.TP_L10N_456), t("TP_L10N_457", R.string.TP_L10N_457), controller.getQueue().isShuffle() ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> {
            controller.getQueue().setShuffle(!controller.getQueue().isShuffle());
            dialog.dismiss(); showPlayerSettings();
        }));
        content.addView(settingsRow(t("TP_L10N_458", R.string.TP_L10N_458), t("TP_L10N_459", R.string.TP_L10N_459), t("TP_L10N_460", R.string.TP_L10N_460), v -> { dialog.dismiss(); showOrientationDialog(); }));
        content.addView(settingsRow(t("TP_L10N_461", R.string.TP_L10N_461), t("TP_L10N_462", R.string.TP_L10N_462), controlsLocked ? t("TP_L10N_450", R.string.TP_L10N_450) : t("TP_L10N_451", R.string.TP_L10N_451), v -> { toggleControlsLock(); dialog.dismiss(); }));
        content.addView(settingsRow(t("TP_L10N_463", R.string.TP_L10N_463), t("TP_L10N_464", R.string.TP_L10N_464), t("TP_L10N_117", R.string.TP_L10N_117), v -> { dialog.dismiss(); showSleepTimer(); }));
        content.addView(settingsRow(t("TP_L10N_465", R.string.TP_L10N_465), t("TP_L10N_466", R.string.TP_L10N_466), controller.getQueue().size() + "", v -> { dialog.dismiss(); showQueue(); }));
        TelePremiumMediaItem item = controller.getCurrent();
        if (item != null && item.isVideo() && Build.VERSION.SDK_INT >= 26) {
            content.addView(settingsRow(t("TP_L10N_423", R.string.TP_L10N_423), t("TP_L10N_467", R.string.TP_L10N_467), "PiP", v -> { dialog.dismiss(); enterPip(); }));
        }
        TextView library = pill(t("TP_L10N_468", R.string.TP_L10N_468));
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
        String[] labels = {t("TP_L10N_441", R.string.TP_L10N_441), t("TP_L10N_439", R.string.TP_L10N_439), t("TP_L10N_440", R.string.TP_L10N_440), "100%"};
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_422", R.string.TP_L10N_422)).setSingleChoiceItems(labels, scaleMode, (d, which) -> {
            scaleMode = which;
            if (surface != null) surface.setScaleMode(scaleMode);
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
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_444", R.string.TP_L10N_444)).setSingleChoiceItems(labels, checked, (d, which) -> {
            controller.setPlaybackSpeed(speeds[which]);
            updateSpeedButton();
            d.dismiss();
        }).show();
    }

    private void showSeekStepDialog() {
        final int[] values = {5, 10, 15, 30};
        String[] labels = {t("TP_L10N_469", R.string.TP_L10N_469), t("TP_L10N_470", R.string.TP_L10N_470), t("TP_L10N_471", R.string.TP_L10N_471), t("TP_L10N_273", R.string.TP_L10N_273)};
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == seekStepSeconds) checked = i;
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_445", R.string.TP_L10N_445)).setSingleChoiceItems(labels, checked, (d, which) -> {
            seekStepSeconds = values[which];
            d.dismiss();
        }).show();
    }

    private void showOrientationDialog() {
        String[] labels = {t("TP_L10N_472", R.string.TP_L10N_472), t("TP_L10N_473", R.string.TP_L10N_473), t("TP_L10N_474", R.string.TP_L10N_474)};
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_458", R.string.TP_L10N_458)).setItems(labels, (d, which) -> {
            if (which == 1) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            else if (which == 2) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }).show();
    }

    private void toggleControlsLock() {
        controlsLocked = !controlsLocked;
        if (lockButton != null) lockButton.setVisibility(controlsLocked ? View.VISIBLE : View.GONE);
        if (controlsLocked) hideChrome(); else { showChrome(); scheduleChromeHide(); }
        Toast.makeText(this, controlsLocked ? t("TP_L10N_475", R.string.TP_L10N_475) : t("TP_L10N_476", R.string.TP_L10N_476), Toast.LENGTH_SHORT).show();
    }

    private void showSleepTimer() {
        String[] labels = {t("TP_L10N_477", R.string.TP_L10N_477), t("TP_L10N_478", R.string.TP_L10N_478), t("TP_L10N_479", R.string.TP_L10N_479), t("TP_L10N_480", R.string.TP_L10N_480), t("TP_L10N_481", R.string.TP_L10N_481)};
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_463", R.string.TP_L10N_463)).setItems(labels, (d, which) -> {
            if (which < 4) {
                long delay = new long[]{15, 30, 45, 60}[which] * 60_000L;
                handler.postDelayed(controller::pause, delay);
                Toast.makeText(this, t("TP_L10N_482", R.string.TP_L10N_482) + labels[which], Toast.LENGTH_SHORT).show();
            } else {
                long remaining = Math.max(0, controller.getDuration() - controller.getPosition());
                if (remaining > 0) handler.postDelayed(controller::pause, remaining);
            }
        }).show();
    }

    private void showQueue() {
        TelePremiumQueueSheet.show(this, mediaTheme);
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
        if (leftSeek != null) leftSeek.setVisibility(View.VISIBLE);
        if (rightSeek != null) rightSeek.setVisibility(View.VISIBLE);
    }

    private void hideChrome() {
        if (!landscape) return;
        chromeVisible = false;
        if (topChrome != null) topChrome.setVisibility(View.GONE);
        if (bottomChrome != null) bottomChrome.setVisibility(View.GONE);
        if (leftSeek != null) leftSeek.setVisibility(View.GONE);
        if (rightSeek != null) rightSeek.setVisibility(View.GONE);
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
            if (leftSeek != null) leftSeek.setVisibility(View.GONE);
            if (rightSeek != null) rightSeek.setVisibility(View.GONE);
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
        org.telegram.messenger.telepremium.media.TelePremiumStreamRecorder.removeListener(recListener);
        controller.getQueue().removeListener(this);
        controller.bindSurface(null);
        // Viewer-only playback from a locked folder ends with the player window.
        TelePremiumMediaItem leaving = controller.getCurrent();
        if (isFinishing() && leaving != null && leaving.ephemeral) controller.stopAndRelease();
        super.onDestroy();
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
        TextView v = text(value, 13, mediaTheme.accent, false);
        v.setBackground(TelePremiumPlayerKit.round(mediaTheme.chipSelectedBackground, mediaTheme.cardStroke, 18, 1));
        v.setPadding(dp(14), 0, dp(14), 0);
        return v;
    }

    private TextView transparentButton(String value, int sp) {
        TextView v = text(value, sp, mediaTheme.textPrimary, false);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    private GradientDrawable roundBg(int color, int stroke, float radius) {
        return TelePremiumPlayerKit.round(color, stroke, radius, 1);
    }

    private static String t(String key, int res) { return TPStrings.get(key, res); }

    private static String tf(String key, int res, Object... args) { return TPStrings.format(key, res, args); }

    private boolean isSmoothMotionEnabled() {
        return getSharedPreferences("telepremium_player_ui", MODE_PRIVATE).getBoolean("smooth_motion", false);
    }

    private boolean isLockscreenEnabled() { return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("lockscreen", true); }

    /** Full player window over the lock screen (music keeps playing; no unlock needed to skip or seek). */
    private void applyLockscreenMode() {
        boolean enabled = isLockscreenEnabled();
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(enabled);
        } else if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
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

    private static String stripAudioExtension(String title) { return TelePremiumPlayerMath.stripAudioExtension(title, t("TP_L10N_485", R.string.TP_L10N_485)); }

    private static String formatTime(long ms) { return TelePremiumPlayerMath.formatTime(ms); }

    private static String formatSpeed(float speed) { return TelePremiumPlayerMath.formatSpeed(speed); }
}
