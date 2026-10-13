#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def read(rel):
    p = SRC / rel
    if not p.exists():
        raise SystemExit(f"missing source: {rel}")
    return p, p.read_text(encoding="utf-8")


def replace_once(rel, old, new):
    p, text = read(rel)
    if new in text and old not in text:
        print(f"already patched: {rel}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {rel}")


def replace_between(text, start_marker, end_marker, replacement, label):
    start = text.find(start_marker)
    end = text.find(end_marker, start + 1)
    if start < 0 or end < 0:
        raise SystemExit(f"{label}: replacement markers not found")
    return text[:start] + replacement + text[end:]


# 1) Media Center entry in the Telegram drawer, next to Cloud.
drawer = "TMessagesProj/src/main/java/org/telegram/ui/Adapters/DrawerLayoutAdapter.java"
replace_once(
    drawer,
    '        items.add(new Item(18, "Облако", R.drawable.msg2_folder));\n        items.add(new Item(8, LocaleController.getString(R.string.Settings), settingsIcon));',
    '        items.add(new Item(18, "Облако", R.drawable.msg2_folder));\n        items.add(new Item(19, "Медиа", R.drawable.msg2_folder));\n        items.add(new Item(8, LocaleController.getString(R.string.Settings), settingsIcon));',
)

launch = "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
replace_once(
    launch,
    '''                } else if (id == 13) {\n                    Browser.openUrl(LaunchActivity.this, LocaleController.getString(R.string.TelegramFeaturesUrl));''',
    '''                } else if (id == 19) {\n                    startActivity(new Intent(LaunchActivity.this, TelePremiumMediaCenterActivity.class));\n                    drawerLayoutContainer.closeDrawer(false);\n                } else if (id == 13) {\n                    Browser.openUrl(LaunchActivity.this, LocaleController.getString(R.string.TelegramFeaturesUrl));''',
)

# 2) Register Media Center, playlists and foreground MediaSession service.
manifest = "TMessagesProj/src/main/AndroidManifest.xml"
p, text = read(manifest)
block = '''        <activity\n            android:name="org.telegram.ui.TelePremiumMediaCenterActivity"\n            android:theme="@style/Theme.TMessages"\n            android:configChanges="keyboardHidden|orientation|screenSize|uiMode"\n            android:hardwareAccelerated="true"\n            android:exported="false" />\n        <activity\n            android:name="org.telegram.ui.TelePremiumPlaylistActivity"\n            android:theme="@style/Theme.TMessages"\n            android:configChanges="keyboardHidden|orientation|screenSize|uiMode"\n            android:exported="false" />\n        <service\n            android:name="org.telegram.messenger.telepremium.media.TelePremiumMediaSessionService"\n            android:exported="false"\n            android:foregroundServiceType="mediaPlayback" />\n'''
marker = '        <activity\n            android:name="org.telegram.ui.LaunchActivity"'
if block not in text:
    if marker not in text:
        raise SystemExit(f"{manifest}: LaunchActivity marker not found")
    text = text.replace(marker, block + marker, 1)

player_manifest_old = '''            android:hardwareAccelerated="true"\n            android:resizeableActivity="true"\n            android:exported="true">'''
player_manifest_new = '''            android:hardwareAccelerated="true"\n            android:resizeableActivity="true"\n            android:supportsPictureInPicture="true"\n            android:launchMode="singleTop"\n            android:exported="true">'''
if player_manifest_new not in text:
    if player_manifest_old not in text:
        raise SystemExit(f"{manifest}: TelePremium player manifest marker not found")
    text = text.replace(player_manifest_old, player_manifest_new, 1)
p.write_text(text, encoding="utf-8")
print("registered Media Center activities/service + PiP")

# 3) Player V10: MX-like chrome/settings while keeping SurfaceView transparent.
player = "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumPlayerActivity.java"
p, player_text = read(player)

if "import android.content.pm.ActivityInfo;" not in player_text:
    player_text = player_text.replace("import android.content.Intent;\n", "import android.content.Intent;\nimport android.content.pm.ActivityInfo;\n", 1)
if "import android.widget.ScrollView;" not in player_text:
    player_text = player_text.replace("import android.widget.SeekBar;\n", "import android.widget.SeekBar;\nimport android.widget.ScrollView;\n", 1)

old_fields = '''    private View topChrome, bottomChrome;\n    private boolean userSeeking, landscape, chromeVisible = true, fitMode = true, pip;'''
new_fields = '''    private View topChrome, bottomChrome;\n    private TextView lockButton;\n    private boolean userSeeking, landscape, chromeVisible = true, pip, controlsLocked;\n    private static final int SCALE_FIT = 0;\n    private static final int SCALE_CROP = 1;\n    private static final int SCALE_STRETCH = 2;\n    private static final int SCALE_ORIGINAL = 3;\n    private int scaleMode = SCALE_FIT;\n    private int seekStepSeconds = 10;\n    private boolean gestureBrightness = true;\n    private boolean gestureVolume = true;'''
if new_fields not in player_text:
    if old_fields not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: player state anchor missing")
    player_text = player_text.replace(old_fields, new_fields, 1)

old_portrait = '''    private void buildPortrait() {\n        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);\n        mediaTheme.applySystemBars(this);'''
new_portrait = '''    private void buildPortrait() {\n        resetPortraitWindowMode();\n        mediaTheme.applySystemBars(this);'''
if new_portrait not in player_text:
    if old_portrait not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: portrait window anchor missing")
    player_text = player_text.replace(old_portrait, new_portrait, 1)
player_text = player_text.replace(
    '''        FrameLayout stage = createStage();\n        column.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));''',
    '''        FrameLayout stage = createStage();\n        column.addView(stage, new LinearLayout.LayoutParams(-1, dp(232)));''',
    1,
)

old_landscape = '''    private void buildLandscape() {\n        getWindow().getDecorView().setSystemUiVisibility(\n                View.SYSTEM_UI_FLAG_FULLSCREEN\n                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION\n                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);\n        root = new FrameLayout(this);'''
new_landscape = '''    private void buildLandscape() {\n        applyLandscapeWindowMode();\n        root = new FrameLayout(this);'''
if new_landscape not in player_text:
    if old_landscape not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: landscape window anchor missing")
    player_text = player_text.replace(old_landscape, new_landscape, 1)

old_stage = '''        surface = new AspectSurfaceView(this);\n        surface.setFill(!fitMode);\n        stage.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));'''
new_stage = '''        surface = new AspectSurfaceView(this);\n        surface.setScaleMode(scaleMode);\n        stage.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));'''
if new_stage not in player_text:
    if old_stage not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: SurfaceView anchor missing")
    player_text = player_text.replace(old_stage, new_stage, 1)

landscape_tail = '''        bottomChrome = createBottomControls(true);\n        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(122), Gravity.BOTTOM);\n        root.addView(bottomChrome, bottom);\n        setContentView(root);'''
landscape_tail_new = '''        bottomChrome = createBottomControls(true);\n        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(122), Gravity.BOTTOM);\n        root.addView(bottomChrome, bottom);\n\n        lockButton = pill("🔒");\n        lockButton.setGravity(Gravity.CENTER);\n        lockButton.setVisibility(controlsLocked ? View.VISIBLE : View.GONE);\n        lockButton.setOnClickListener(v -> toggleControlsLock());\n        FrameLayout.LayoutParams lockParams = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.RIGHT | Gravity.CENTER_VERTICAL);\n        lockParams.setMargins(0, 0, dp(18), 0);\n        root.addView(lockButton, lockParams);\n        setContentView(root);'''
if landscape_tail_new not in player_text:
    if landscape_tail not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: landscape chrome tail missing")
    player_text = player_text.replace(landscape_tail, landscape_tail_new, 1)

helper_anchor = '''    private View createTopBar() {'''
helper_block = '''    private void applyLandscapeWindowMode() {\n        Window window = getWindow();\n        window.setStatusBarColor(Color.BLACK);\n        window.setNavigationBarColor(Color.BLACK);\n        window.getDecorView().setBackgroundColor(Color.BLACK);\n        if (Build.VERSION.SDK_INT >= 28) {\n            WindowManager.LayoutParams attributes = window.getAttributes();\n            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;\n            window.setAttributes(attributes);\n        }\n        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {\n            window.getInsetsController().hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());\n            window.getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);\n        }\n        window.getDecorView().setSystemUiVisibility(\n                View.SYSTEM_UI_FLAG_LAYOUT_STABLE\n                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN\n                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION\n                        | View.SYSTEM_UI_FLAG_FULLSCREEN\n                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION\n                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);\n    }\n\n    private void resetPortraitWindowMode() {\n        Window window = getWindow();\n        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {\n            window.getInsetsController().show(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());\n        }\n        if (Build.VERSION.SDK_INT >= 28) {\n            WindowManager.LayoutParams attributes = window.getAttributes();\n            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;\n            window.setAttributes(attributes);\n        }\n        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);\n    }\n\n'''
if "private void applyLandscapeWindowMode()" not in player_text:
    if helper_anchor not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: helper anchor missing")
    player_text = player_text.replace(helper_anchor, helper_block + helper_anchor, 1)

player_text = player_text.replace(
    '''        hwButton = transparentButton("HW", 13);\n        hwButton.setTextColor(chromeText);\n        row.addView(hwButton, new LinearLayout.LayoutParams(dp(48), dp(42)));''',
    '''        hwButton = transparentButton("HW", 13);\n        hwButton.setTextColor(chromeText);\n        hwButton.setOnClickListener(v -> showPlayerSettings());\n        row.addView(hwButton, new LinearLayout.LayoutParams(dp(48), dp(42)));''',
    1,
)

old_toggle_fit = '''    private void toggleFit() {\n        fitMode = !fitMode;\n        if (surface != null) surface.setFill(!fitMode);\n        if (fitButton != null) fitButton.setText(fitMode ? "↔" : "↕");\n    }'''
new_toggle_fit = '''    private void toggleFit() {\n        scaleMode = (scaleMode + 1) % 4;\n        if (surface != null) surface.setScaleMode(scaleMode);\n        if (fitButton != null) fitButton.setText(scaleModeIcon());\n        Toast.makeText(this, "Масштаб: " + scaleModeLabel(), Toast.LENGTH_SHORT).show();\n    }\n\n    private String scaleModeIcon() {\n        if (scaleMode == SCALE_CROP) return "⌗";\n        if (scaleMode == SCALE_STRETCH) return "⇔";\n        if (scaleMode == SCALE_ORIGINAL) return "1:1";\n        return "↔";\n    }\n\n    private String scaleModeLabel() {\n        if (scaleMode == SCALE_CROP) return "Заполнить с обрезкой";\n        if (scaleMode == SCALE_STRETCH) return "Растянуть";\n        if (scaleMode == SCALE_ORIGINAL) return "100%";\n        return "Вписать";\n    }'''
if new_toggle_fit not in player_text:
    if old_toggle_fit not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: scale toggle anchor missing")
    player_text = player_text.replace(old_toggle_fit, new_toggle_fit, 1)

start = player_text.find("    private void showMoreMenu() {")
end = player_text.find("    private void showSleepTimer() {", start)
if start < 0 or end < 0:
    raise SystemExit("TelePremiumPlayerActivity: settings method anchors missing")
settings_block = '''    private void showMoreMenu() {\n        showPlayerSettings();\n    }\n\n    private void showPlayerSettings() {\n        Dialog dialog = new Dialog(this);\n        ScrollView scroll = new ScrollView(this);\n        LinearLayout content = new LinearLayout(this);\n        content.setOrientation(LinearLayout.VERTICAL);\n        content.setPadding(dp(16), dp(12), dp(16), dp(18));\n        content.setBackground(roundBg(mediaTheme.surfaceElevated, mediaTheme.cardStroke, 22));\n\n        TextView heading = text("Настройки плеера", 20, mediaTheme.textPrimary, true);\n        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(50)));\n        content.addView(settingsRow("Декодер", "Telegram ExoPlayer", controller.getDecoderLabel(), v ->\n                Toast.makeText(this, "HW используется автоматически, если кодек поддерживается устройством", Toast.LENGTH_LONG).show()));\n        content.addView(settingsRow("Масштаб видео", "Fit / Crop / Stretch / 100%", scaleModeLabel(), v -> { dialog.dismiss(); showScaleDialog(); }));\n        content.addView(settingsRow("Скорость", "Скорость воспроизведения", formatSpeed(controller.getSpeed()), v -> { dialog.dismiss(); showSpeedDialog(); }));\n        content.addView(settingsRow("Шаг двойного тапа", "Левая/правая половина видео", seekStepSeconds + " сек", v -> { dialog.dismiss(); showSeekStepDialog(); }));\n        content.addView(settingsRow("Жест яркости", "Вертикально по левой половине", gestureBrightness ? "Вкл" : "Выкл", v -> { gestureBrightness = !gestureBrightness; dialog.dismiss(); showPlayerSettings(); }));\n        content.addView(settingsRow("Жест громкости", "Вертикально по правой половине", gestureVolume ? "Вкл" : "Выкл", v -> { gestureVolume = !gestureVolume; dialog.dismiss(); showPlayerSettings(); }));\n        content.addView(settingsRow("Субтитры", "Включение текстовой дорожки", controller.isSubtitlesEnabled() ? "Вкл" : "Выкл", v -> { controller.setSubtitlesEnabled(!controller.isSubtitlesEnabled()); dialog.dismiss(); showPlayerSettings(); }));\n        content.addView(settingsRow("Повтор", "Повтор очереди", controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? "Выкл" : "Вкл", v -> {\n            controller.getQueue().setRepeatMode(controller.getQueue().getRepeatMode() == TelePremiumQueueManager.REPEAT_OFF ? TelePremiumQueueManager.REPEAT_ALL : TelePremiumQueueManager.REPEAT_OFF);\n            dialog.dismiss(); showPlayerSettings();\n        }));\n        content.addView(settingsRow("Перемешивание", "Shuffle очереди", controller.getQueue().isShuffle() ? "Вкл" : "Выкл", v -> {\n            controller.getQueue().setShuffle(!controller.getQueue().isShuffle());\n            dialog.dismiss(); showPlayerSettings();\n        }));\n        content.addView(settingsRow("Ориентация", "Авто / портрет / альбомная", "Изменить", v -> { dialog.dismiss(); showOrientationDialog(); }));\n        content.addView(settingsRow("Блокировка", "Отключить жесты и скрыть chrome", controlsLocked ? "Вкл" : "Выкл", v -> { toggleControlsLock(); dialog.dismiss(); }));\n        content.addView(settingsRow("Таймер сна", "Остановить воспроизведение позже", "Открыть", v -> { dialog.dismiss(); showSleepTimer(); }));\n        content.addView(settingsRow("Очередь", "Текущий плейлист", controller.getQueue().size() + "", v -> { dialog.dismiss(); showQueue(); }));\n        TelePremiumMediaItem item = controller.getCurrent();\n        if (item != null && item.isVideo() && Build.VERSION.SDK_INT >= 26) {\n            content.addView(settingsRow("Картинка в картинке", "Продолжить поверх других приложений", "PiP", v -> { dialog.dismiss(); enterPip(); }));\n        }\n        TextView library = pill("Открыть медиатеку");\n        library.setGravity(Gravity.CENTER);\n        library.setOnClickListener(v -> { dialog.dismiss(); startActivity(new Intent(this, TelePremiumMediaCenterActivity.class)); });\n        LinearLayout.LayoutParams libraryParams = new LinearLayout.LayoutParams(-1, dp(52));\n        libraryParams.setMargins(0, dp(10), 0, 0);\n        content.addView(library, libraryParams);\n        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));\n        dialog.setContentView(scroll);\n        dialog.setOnShowListener(d -> {\n            Window window = dialog.getWindow();\n            if (window != null) {\n                window.setBackgroundDrawableResource(android.R.color.transparent);\n                window.setLayout(-1, landscape ? -1 : -2);\n                window.setGravity(Gravity.BOTTOM);\n            }\n        });\n        dialog.show();\n    }\n\n    private View settingsRow(String title, String subtitle, String value, View.OnClickListener listener) {\n        LinearLayout row = new LinearLayout(this);\n        row.setGravity(Gravity.CENTER_VERTICAL);\n        row.setPadding(dp(12), dp(5), dp(10), dp(5));\n        row.setBackground(roundBg(mediaTheme.cardBackground, mediaTheme.cardStroke, 14));\n        LinearLayout labels = new LinearLayout(this);\n        labels.setOrientation(LinearLayout.VERTICAL);\n        TextView primary = text(title, 14, mediaTheme.textPrimary, true);\n        TextView secondary = text(subtitle, 11, mediaTheme.textSecondary, false);\n        labels.addView(primary, new LinearLayout.LayoutParams(-1, dp(23)));\n        labels.addView(secondary, new LinearLayout.LayoutParams(-1, dp(19)));\n        row.addView(labels, new LinearLayout.LayoutParams(0, dp(46), 1f));\n        TextView right = text(value, 12, mediaTheme.accent, false);\n        right.setGravity(Gravity.CENTER | Gravity.RIGHT);\n        row.addView(right, new LinearLayout.LayoutParams(dp(112), dp(46)));\n        row.setOnClickListener(listener);\n        LinearLayout holder = new LinearLayout(this);\n        holder.setPadding(0, dp(2), 0, dp(2));\n        holder.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));\n        return holder;\n    }\n\n    private void showScaleDialog() {\n        String[] labels = {"Вписать", "Заполнить с обрезкой", "Растянуть", "100%"};\n        new AlertDialog.Builder(this).setTitle("Масштаб видео").setSingleChoiceItems(labels, scaleMode, (d, which) -> {\n            scaleMode = which;\n            if (surface != null) surface.setScaleMode(scaleMode);\n            if (fitButton != null) fitButton.setText(scaleModeIcon());\n            d.dismiss();\n        }).show();\n    }\n\n    private void showSpeedDialog() {\n        String[] labels = new String[speeds.length];\n        int checked = 0;\n        float current = controller.getSpeed();\n        for (int i = 0; i < speeds.length; i++) {\n            labels[i] = formatSpeed(speeds[i]);\n            if (Math.abs(speeds[i] - current) < Math.abs(speeds[checked] - current)) checked = i;\n        }\n        new AlertDialog.Builder(this).setTitle("Скорость").setSingleChoiceItems(labels, checked, (d, which) -> {\n            controller.setPlaybackSpeed(speeds[which]);\n            updateSpeedButton();\n            d.dismiss();\n        }).show();\n    }\n\n    private void showSeekStepDialog() {\n        final int[] values = {5, 10, 15, 30};\n        String[] labels = {"5 секунд", "10 секунд", "15 секунд", "30 секунд"};\n        int checked = 1;\n        for (int i = 0; i < values.length; i++) if (values[i] == seekStepSeconds) checked = i;\n        new AlertDialog.Builder(this).setTitle("Шаг двойного тапа").setSingleChoiceItems(labels, checked, (d, which) -> {\n            seekStepSeconds = values[which];\n            d.dismiss();\n        }).show();\n    }\n\n    private void showOrientationDialog() {\n        String[] labels = {"Автоматически", "Портрет", "Альбомная"};\n        new AlertDialog.Builder(this).setTitle("Ориентация").setItems(labels, (d, which) -> {\n            if (which == 1) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);\n            else if (which == 2) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);\n            else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);\n        }).show();\n    }\n\n    private void toggleControlsLock() {\n        controlsLocked = !controlsLocked;\n        if (lockButton != null) lockButton.setVisibility(controlsLocked ? View.VISIBLE : View.GONE);\n        if (controlsLocked) hideChrome(); else { showChrome(); scheduleChromeHide(); }\n        Toast.makeText(this, controlsLocked ? "Управление заблокировано" : "Управление разблокировано", Toast.LENGTH_SHORT).show();\n    }\n\n'''
player_text = player_text[:start] + settings_block + player_text[end:]

player_text = player_text.replace(
    '''            @Override public boolean onSingleTapConfirmed(MotionEvent e) {\n                if (landscape) toggleChrome();\n                return true;\n            }\n\n            @Override public boolean onDoubleTap(MotionEvent e) {\n                if (!landscape || root == null) return true;\n                if (e.getX() < root.getWidth() / 2f) controller.seekRelative(-10_000);\n                else controller.seekRelative(10_000);\n                showChrome();\n                scheduleChromeHide();\n                return true;\n            }''',
    '''            @Override public boolean onSingleTapConfirmed(MotionEvent e) {\n                if (landscape && !controlsLocked) toggleChrome();\n                return true;\n            }\n\n            @Override public boolean onDoubleTap(MotionEvent e) {\n                if (!landscape || root == null || controlsLocked) return true;\n                long delta = seekStepSeconds * 1000L;\n                if (e.getX() < root.getWidth() / 2f) controller.seekRelative(-delta);\n                else controller.seekRelative(delta);\n                showChrome();\n                scheduleChromeHide();\n                return true;\n            }''',
    1,
)
player_text = player_text.replace(
    '''    private boolean handleStageTouch(MotionEvent event) {\n        if (gestures != null) gestures.onTouchEvent(event);\n        if (!landscape) return true;''',
    '''    private boolean handleStageTouch(MotionEvent event) {\n        if (gestures != null) gestures.onTouchEvent(event);\n        if (!landscape || controlsLocked) return true;''',
    1,
)
player_text = player_text.replace(
    '''            } else if (gestureMode == 2) {\n                WindowManager.LayoutParams lp = getWindow().getAttributes();''',
    '''            } else if (gestureMode == 2 && gestureBrightness) {\n                WindowManager.LayoutParams lp = getWindow().getAttributes();''',
    1,
)
player_text = player_text.replace(
    '''            } else if (gestureMode == 3) {\n                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);''',
    '''            } else if (gestureMode == 3 && gestureVolume) {\n                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);''',
    1,
)
player_text = player_text.replace(
    '''    private void showChrome() {\n        chromeVisible = true;''',
    '''    private void showChrome() {\n        if (controlsLocked) return;\n        chromeVisible = true;''',
    1,
)

focus_anchor = '''    @Override public void onUserLeaveHint() {'''
focus_block = '''    @Override public void onWindowFocusChanged(boolean hasFocus) {\n        super.onWindowFocusChanged(hasFocus);\n        if (hasFocus && landscape && !pip) applyLandscapeWindowMode();\n    }\n\n'''
if "onWindowFocusChanged(boolean hasFocus)" not in player_text:
    if focus_anchor not in player_text:
        raise SystemExit("TelePremiumPlayerActivity: focus anchor missing")
    player_text = player_text.replace(focus_anchor, focus_block + focus_anchor, 1)

aspect_start = player_text.find("    private static final class AspectSurfaceView extends SurfaceView {")
if aspect_start < 0:
    raise SystemExit("TelePremiumPlayerActivity: AspectSurfaceView start missing")
aspect_end = player_text.rfind("\n    }\n}")
if aspect_end < aspect_start:
    raise SystemExit("TelePremiumPlayerActivity: AspectSurfaceView end missing")
new_aspect = '''    private static final class AspectSurfaceView extends SurfaceView {\n        private int videoWidth, videoHeight;\n        private int scaleMode = SCALE_FIT;\n\n        AspectSurfaceView(Context context) { super(context); }\n\n        void setVideoSize(int width, int height) {\n            videoWidth = width;\n            videoHeight = height;\n            requestLayout();\n        }\n\n        void setScaleMode(int mode) {\n            scaleMode = mode;\n            requestLayout();\n        }\n\n        @Override protected void onMeasure(int widthSpec, int heightSpec) {\n            int boxWidth = MeasureSpec.getSize(widthSpec);\n            int boxHeight = MeasureSpec.getSize(heightSpec);\n            if (videoWidth <= 0 || videoHeight <= 0 || boxWidth <= 0 || boxHeight <= 0 || scaleMode == SCALE_STRETCH) {\n                setMeasuredDimension(boxWidth, boxHeight);\n                return;\n            }\n            float vr = videoWidth / (float) videoHeight;\n            float br = boxWidth / (float) boxHeight;\n            int width = boxWidth, height = boxHeight;\n            if (scaleMode == SCALE_CROP) {\n                if (vr > br) width = Math.max(1, Math.round(boxHeight * vr));\n                else height = Math.max(1, Math.round(boxWidth / vr));\n            } else if (scaleMode == SCALE_ORIGINAL) {\n                float scale = Math.min(1f, Math.min(boxWidth / (float) videoWidth, boxHeight / (float) videoHeight));\n                width = Math.max(1, Math.round(videoWidth * scale));\n                height = Math.max(1, Math.round(videoHeight * scale));\n            } else {\n                if (vr > br) height = Math.max(1, Math.round(boxWidth / vr));\n                else width = Math.max(1, Math.round(boxHeight * vr));\n            }\n            setMeasuredDimension(width, height);\n        }\n    }'''
player_text = player_text[:aspect_start] + new_aspect + player_text[aspect_end + len("\n    }"):]

player_text = player_text.replace(
    '''        fitButton = transparentButton("↔", 21);''',
    '''        fitButton = transparentButton(scaleModeIcon(), 18);''',
    1,
)
player_text = player_text.replace(
    '''        boolean video = item.isVideo();\n        if (surface != null) surface.setVisibility(video ? View.VISIBLE : View.GONE);''',
    '''        boolean video = item.isVideo();\n        if (surface != null) {\n            surface.setVisibility(video ? View.VISIBLE : View.GONE);\n            surface.setScaleMode(scaleMode);\n        }\n        if (!landscape && surface != null && surface.getParent() instanceof View) {\n            View stageView = (View) surface.getParent();\n            ViewGroup.LayoutParams lp = stageView.getLayoutParams();\n            if (lp instanceof LinearLayout.LayoutParams) {\n                lp.height = dp(video ? 232 : 340);\n                stageView.setLayoutParams(lp);\n            }\n            stageView.setBackgroundColor(video ? Color.BLACK : mediaTheme.background);\n        }''',
    1,
)

if "surface.setBackgroundColor" in player_text:
    raise SystemExit("TelePremiumPlayerActivity: opaque SurfaceView background regression detected")
if "setDecorFitsSystemWindows(false)" in player_text:
    raise SystemExit("TelePremiumPlayerActivity: unsafe decor-fit fullscreen regression detected")

p.write_text(player_text, encoding="utf-8")
print("patched TelePremium Player V10 MX-like controls/settings and restored visible SurfaceView")

# 4) Media Center: full-width uniform rows, denser vertical rhythm, real thumbnails.
center = "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumMediaCenterActivity.java"
p, center_text = read(center)
if "import android.widget.FrameLayout;" not in center_text:
    center_text = center_text.replace("import android.widget.EditText;\n", "import android.widget.EditText;\nimport android.widget.FrameLayout;\nimport android.widget.ImageView;\n", 1)
if "import org.telegram.messenger.telepremium.media.TelePremiumArtworkLoader;" not in center_text:
    center_text = center_text.replace(
        "import org.telegram.messenger.telepremium.media.TelePremiumCloudMediaRepository;\n",
        "import org.telegram.messenger.telepremium.media.TelePremiumArtworkLoader;\nimport org.telegram.messenger.telepremium.media.TelePremiumCloudMediaRepository;\n",
        1,
    )

center_text = center_text.replace("sources.addView(sourceDevice, weight(dp(62)));", "sources.addView(sourceDevice, weight(dp(56)));", 1)
center_text = center_text.replace("sources.addView(sourceCloud, weight(dp(62)));", "sources.addView(sourceCloud, weight(dp(56)));", 1)
center_text = center_text.replace("sources.addView(sourceNetwork, weight(dp(62)));", "sources.addView(sourceNetwork, weight(dp(56)));", 1)
center_text = center_text.replace("sources.addView(sourcePlaylists, weight(dp(62)));", "sources.addView(sourcePlaylists, weight(dp(56)));", 1)
center_text = center_text.replace("root.addView(sources, new LinearLayout.LayoutParams(-1, dp(69)));", "root.addView(sources, new LinearLayout.LayoutParams(-1, dp(63)));", 1)
center_text = center_text.replace("root.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));", "root.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));", 1)
center_text = center_text.replace("root.addView(filters, new LinearLayout.LayoutParams(-1, dp(52)));", "root.addView(filters, new LinearLayout.LayoutParams(-1, dp(46)));", 1)
center_text = center_text.replace("root.addView(createMiniPlayer(), new LinearLayout.LayoutParams(-1, dp(72)));", "root.addView(createMiniPlayer(), new LinearLayout.LayoutParams(-1, dp(68)));", 1)
center_text = center_text.replace("new LinearLayout.LayoutParams(w, dp(38))", "new LinearLayout.LayoutParams(w, dp(34))", 1)

adapter_start = center_text.find("    private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder> {")
adapter_end = center_text.find("    private String mimeLabel", adapter_start)
if adapter_start < 0 or adapter_end < 0:
    raise SystemExit("TelePremiumMediaCenterActivity: adapter markers missing")
new_adapter = '''    private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder> {\n        @Override public MediaHolder onCreateViewHolder(ViewGroup parent, int viewType) {\n            LinearLayout row = new LinearLayout(TelePremiumMediaCenterActivity.this);\n            row.setOrientation(LinearLayout.HORIZONTAL);\n            row.setGravity(Gravity.CENTER_VERTICAL);\n            row.setPadding(dp(6), dp(4), dp(5), dp(4));\n            row.setBackground(roundBg(mediaTheme.cardBackground, mediaTheme.cardStroke, 15));\n            RecyclerView.LayoutParams rowParams = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));\n            rowParams.setMargins(0, dp(2), 0, dp(2));\n            row.setLayoutParams(rowParams);\n\n            FrameLayout artwork = new FrameLayout(TelePremiumMediaCenterActivity.this);\n            artwork.setBackground(roundBg(mediaTheme.artworkBackground, mediaTheme.cardStroke, 12));\n            ImageView thumbnail = new ImageView(TelePremiumMediaCenterActivity.this);\n            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);\n            thumbnail.setVisibility(View.GONE);\n            artwork.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));\n            TextView icon = text("♫", 23, mediaTheme.accent, true);\n            icon.setGravity(Gravity.CENTER);\n            artwork.addView(icon, new FrameLayout.LayoutParams(-1, -1));\n            TextView durationBadge = text("", 9, 0xffffffff, true);\n            durationBadge.setGravity(Gravity.CENTER);\n            durationBadge.setPadding(dp(4), 0, dp(4), 0);\n            durationBadge.setBackground(roundBg(0xb0000000, 0x22000000, 7));\n            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, dp(18), Gravity.RIGHT | Gravity.BOTTOM);\n            badgeParams.setMargins(0, 0, dp(3), dp(3));\n            artwork.addView(durationBadge, badgeParams);\n            row.addView(artwork, new LinearLayout.LayoutParams(dp(58), dp(58)));\n\n            LinearLayout labels = new LinearLayout(TelePremiumMediaCenterActivity.this);\n            labels.setOrientation(LinearLayout.VERTICAL);\n            labels.setPadding(dp(10), 0, dp(2), 0);\n            TextView title = text("", 14, mediaTheme.textPrimary, true);\n            title.setSingleLine();\n            title.setEllipsize(TextUtils.TruncateAt.END);\n            TextView sub = text("", 11, mediaTheme.textSecondary, false);\n            sub.setSingleLine();\n            sub.setEllipsize(TextUtils.TruncateAt.END);\n            TextView source = text("", 10, mediaTheme.accent, false);\n            source.setSingleLine();\n            source.setEllipsize(TextUtils.TruncateAt.END);\n            labels.addView(title, new LinearLayout.LayoutParams(-1, dp(22)));\n            labels.addView(sub, new LinearLayout.LayoutParams(-1, dp(18)));\n            labels.addView(source, new LinearLayout.LayoutParams(-1, dp(17)));\n            row.addView(labels, new LinearLayout.LayoutParams(0, dp(58), 1f));\n\n            TextView add = button("＋", 21, false);\n            add.setTextColor(mediaTheme.accent);\n            add.setContentDescription("Добавить в очередь");\n            row.addView(add, new LinearLayout.LayoutParams(dp(38), dp(52)));\n            TextView more = button("⋮", 22, false);\n            more.setTextColor(mediaTheme.textSecondary);\n            more.setContentDescription("Действия с файлом");\n            row.addView(more, new LinearLayout.LayoutParams(dp(30), dp(52)));\n            return new MediaHolder(row, thumbnail, icon, durationBadge, title, sub, source, add, more);\n        }\n\n        @Override public void onBindViewHolder(MediaHolder h, int position) {\n            TelePremiumMediaItem item = visibleItems.get(position);\n            h.boundId = item.id;\n            h.thumbnail.setImageDrawable(null);\n            h.thumbnail.setVisibility(View.GONE);\n            h.icon.setVisibility(View.VISIBLE);\n            h.icon.setText(item.isVideo() ? "▣" : (item.live ? "◉" : "♫"));\n            h.durationBadge.setText(item.duration > 0 ? formatDuration(item.duration) : "");\n            h.durationBadge.setVisibility(item.duration > 0 ? View.VISIBLE : View.GONE);\n            TelePremiumArtworkLoader.load(TelePremiumMediaCenterActivity.this, item, dp(112), bitmap -> {\n                if (!item.id.equals(h.boundId)) return;\n                if (bitmap != null) {\n                    h.thumbnail.setImageBitmap(bitmap);\n                    h.thumbnail.setVisibility(View.VISIBLE);\n                    h.icon.setVisibility(View.GONE);\n                }\n            });\n            h.title.setText(item.title);\n            String secondary;\n            if (!TextUtils.isEmpty(item.artist)) {\n                secondary = item.artist + (TextUtils.isEmpty(item.album) ? "" : " · " + item.album);\n            } else {\n                secondary = item.isVideo() ? mimeLabel(item) : "Неизвестный исполнитель";\n            }\n            h.sub.setText(secondary);\n            h.source.setText((item.duration > 0 ? formatDuration(item.duration) + " · " : "") + mimeLabel(item) + " · " + item.sourceLabel());\n            h.itemView.setOnClickListener(v -> {\n                int p = h.getAdapterPosition();\n                if (p >= 0) playItem(p);\n            });\n            h.add.setOnClickListener(v -> {\n                controller.getQueue().append(item);\n                Toast.makeText(TelePremiumMediaCenterActivity.this, "Добавлено в очередь", Toast.LENGTH_SHORT).show();\n            });\n            h.more.setOnClickListener(v -> showItemMenu(item));\n        }\n\n        @Override public int getItemCount() { return visibleItems.size(); }\n    }\n\n    private static final class MediaHolder extends RecyclerView.ViewHolder {\n        final ImageView thumbnail;\n        final TextView icon, durationBadge, title, sub, source, add, more;\n        String boundId;\n        MediaHolder(View itemView, ImageView thumbnail, TextView icon, TextView durationBadge, TextView title, TextView sub, TextView source, TextView add, TextView more) {\n            super(itemView);\n            this.thumbnail = thumbnail;\n            this.icon = icon;\n            this.durationBadge = durationBadge;\n            this.title = title;\n            this.sub = sub;\n            this.source = source;\n            this.add = add;\n            this.more = more;\n        }\n    }\n\n    private void showItemMenu(TelePremiumMediaItem item) {\n        new AlertDialog.Builder(this).setTitle(item.title).setItems(new CharSequence[]{\n                "Воспроизвести", "Воспроизвести следующим", "Добавить в очередь"\n        }, (d, which) -> {\n            if (which == 0) controller.play(item);\n            else if (which == 1) controller.getQueue().insertNext(item);\n            else controller.getQueue().append(item);\n            if (which == 0) TelePremiumPlayerActivity.openCurrent(this);\n            else Toast.makeText(this, which == 1 ? "Добавлено следующим" : "Добавлено в очередь", Toast.LENGTH_SHORT).show();\n        }).show();\n    }\n\n'''
center_text = center_text[:adapter_start] + new_adapter + center_text[adapter_end:]

p.write_text(center_text, encoding="utf-8")
print("patched Media Center V10 uniform full-width grid + thumbnails")

# 5) Final invariants.
_, player_text = read(player)
for forbidden in (
    "centerChrome",
    'roundButton("↶\\n10 сек.",',
    'roundButton("↷\\n10 сек.",',
    'roundButton("▶", 36)',
    "surface.setBackgroundColor",
    "setDecorFitsSystemWindows(false)",
):
    if forbidden in player_text:
        raise SystemExit(f"TelePremiumPlayerActivity: forbidden V10 regression: {forbidden}")
for required in (
    "showPlayerSettings()",
    "SCALE_CROP",
    "SCALE_STRETCH",
    "SCALE_ORIGINAL",
    "setScaleMode(scaleMode)",
    "seekStepSeconds",
    "controlsLocked",
    "SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION",
    "LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES",
    "onWindowFocusChanged(boolean hasFocus)",
):
    if required not in player_text:
        raise SystemExit(f"TelePremiumPlayerActivity: V10 invariant missing: {required}")

_, center_text = read(center)
for required in (
    "READ_MEDIA_AUDIO",
    "READ_MEDIA_VIDEO",
    "TelePremiumArtworkLoader.load",
    "RecyclerView.LayoutParams",
    "ViewGroup.LayoutParams.MATCH_PARENT",
    "showItemMenu",
    "getAdapterPosition()",
):
    if required not in center_text:
        raise SystemExit(f"TelePremiumMediaCenterActivity: V10 invariant missing: {required}")
if "getBindingAdapterPosition()" in center_text:
    raise SystemExit("Media Center uses RecyclerView API unavailable in pinned Telegram")

print("TelePremium Media Center / Player V10 MX prototype applied")
