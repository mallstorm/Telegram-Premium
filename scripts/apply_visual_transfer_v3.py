#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def replace_once(rel, old, new):
    path = SRC / rel
    data = path.read_text(encoding="utf-8")
    if new in data and old not in data:
        print(f"already patched: {rel}")
        return
    count = data.count(old)
    if count < 1:
        raise SystemExit(f"{rel}: expected at least one match, got {count}: {old[:90]!r}")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {rel}")


# Drawer: eliminate the fixed blue top header. The full drawer still uses Telegram
# theme keys, while the profile header gets the per-preset lightweight artwork.
drawer = "TMessagesProj/src/main/java/org/telegram/ui/Cells/DrawerProfileCell.java"
replace_once(
    drawer,
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;\n",
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeArtwork;\n",
)
replace_once(
    drawer,
    """    public Integer applyBackground(boolean force) {
        Integer currentTag = (Integer) getTag();
        int backgroundKey = Theme.hasThemeKey(Theme.key_chats_menuTopBackground) && Theme.getColor(Theme.key_chats_menuTopBackground) != 0 ? Theme.key_chats_menuTopBackground : Theme.key_chats_menuTopBackgroundCats;
        if (force || currentTag == null || backgroundKey != currentTag) {
            setBackgroundColor(Theme.getColor(backgroundKey));
            setTag(backgroundKey);
        }
        return backgroundKey;
    }
""",
    """    public Integer applyBackground(boolean force) {
        Integer currentTag = (Integer) getTag();
        int backgroundKey = Theme.hasThemeKey(Theme.key_chats_menuTopBackground) && Theme.getColor(Theme.key_chats_menuTopBackground) != 0 ? Theme.key_chats_menuTopBackground : Theme.key_chats_menuTopBackgroundCats;
        if (force || currentTag == null || backgroundKey != currentTag) {
            setBackground(TelePremiumThemeArtwork.drawerHeader(getContext()));
            setTag(backgroundKey);
        }
        return backgroundKey;
    }
""",
)

# Cloud: complete the visual theme and add a persistent transfer strip with
# percentage, size, speed and reconnect/resume state.
cloud = "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCloudActivity.java"
replace_once(
    cloud,
    "import android.widget.LinearLayout;\nimport android.widget.ScrollView;\n",
    "import android.widget.LinearLayout;\nimport android.widget.ProgressBar;\nimport android.widget.ScrollView;\n",
)
replace_once(
    cloud,
    "import org.telegram.messenger.telepremium.cloud.CloudPayloadRepository;\n",
    "import org.telegram.messenger.telepremium.cloud.CloudPayloadRepository;\n"
    "import org.telegram.messenger.telepremium.cloud.CloudTransferManager;\n",
)
replace_once(
    cloud,
    "import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;\n",
    "import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeArtwork;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;\n"
    "import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;\n",
)
replace_once(
    cloud,
    "    private TextView syncState;\n    private EditText search;\n",
    "    private TextView syncState;\n"
    "    private LinearLayout transferStrip;\n"
    "    private TextView transferTitle;\n"
    "    private TextView transferDetails;\n"
    "    private TextView transferPercent;\n"
    "    private ProgressBar transferProgress;\n"
    "    private CloudTransferManager transferManager;\n"
    "    private TelePremiumThemePreset visualTheme;\n"
    "    private final CloudTransferManager.Listener transferListener = this::updateTransferStrip;\n"
    "    private EditText search;\n",
)
replace_once(
    cloud,
    """        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        LinearLayout page = new LinearLayout(context);
""",
    """        visualTheme = TelePremiumThemeController.getCurrent(context);
        FrameLayout root = new FrameLayout(context);
        root.setBackground(TelePremiumThemeArtwork.cloudBackground(context));
        LinearLayout page = new LinearLayout(context);
""",
)
replace_once(
    cloud,
    "        syncState.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));\n        page.addView(syncState, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(56)));\n\n        search = new EditText(context);\n",
    "        syncState.setBackgroundColor(Color.TRANSPARENT);\n        page.addView(syncState, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(52)));\n        createTransferStrip(page);\n\n        search = new EditText(context);\n",
)
replace_once(
    cloud,
    "        search.setBackground(roundRect(0xfff1f3f5, 14));\n",
    "        search.setTextColor(visualTheme.textPrimary);\n        search.setHintTextColor(visualTheme.textSecondary);\n        search.setBackground(roundRect(visualTheme.searchBackground, 14));\n",
)
replace_once(
    cloud,
    "        accounts.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));\n",
    "        accounts.setBackgroundColor(Color.TRANSPARENT);\n",
)
replace_once(
    cloud,
    "        add.setBackground(roundRect(getThemedColor(Theme.key_windowBackgroundWhiteBlueButton), 32));\n",
    "        add.setBackground(roundRect(visualTheme.fab, 32));\n",
)
replace_once(
    cloud,
    "    private void createRecentSheet(FrameLayout root) {\n",
    """    private void createTransferStrip(LinearLayout page) {
        transferStrip = new LinearLayout(context);
        transferStrip.setOrientation(LinearLayout.VERTICAL);
        transferStrip.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        GradientDrawable bg = roundRect(visualTheme.surfaceElevated, 13);
        bg.setStroke(AndroidUtilities.dp(1), visualTheme.divider);
        transferStrip.setBackground(bg);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        transferTitle = label("Подготовка файла", 14, true);
        top.addView(transferTitle, new LinearLayout.LayoutParams(0, AndroidUtilities.dp(24), 1f));
        transferPercent = label("0%", 14, true);
        transferPercent.setTextColor(visualTheme.accent);
        transferPercent.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(transferPercent, new LinearLayout.LayoutParams(AndroidUtilities.dp(56), AndroidUtilities.dp(24)));
        transferStrip.addView(top, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(24)));

        transferDetails = label("", 12, false);
        transferDetails.setTextColor(visualTheme.textSecondary);
        transferDetails.setSingleLine(true);
        transferStrip.addView(transferDetails, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(20)));

        transferProgress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        transferProgress.setMax(1000);
        transferProgress.getProgressDrawable().setColorFilter(visualTheme.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(5));
        pp.topMargin = AndroidUtilities.dp(4);
        transferStrip.addView(transferProgress, pp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(66));
        lp.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(2), AndroidUtilities.dp(16), AndroidUtilities.dp(5));
        page.addView(transferStrip, lp);
        transferStrip.setVisibility(View.GONE);
    }

    private void attachTransferManager(int account) {
        if (transferManager != null) transferManager.removeListener(transferListener);
        transferManager = CloudTransferManager.getInstance(account);
        transferManager.addListener(transferListener);
    }

    private void updateTransferStrip(CloudTransferManager.Task task) {
        if (transferStrip == null) return;
        if (task == null || task.state == CloudTransferManager.CANCELLED) {
            transferStrip.setVisibility(View.GONE);
            return;
        }
        transferStrip.setVisibility(View.VISIBLE);
        int percent = task.percent();
        transferTitle.setText(CloudTransferManager.stateLabel(task));
        transferPercent.setText(percent + "%");
        transferProgress.setProgress(percent * 10);
        StringBuilder details = new StringBuilder(task.fileName == null ? "Файл" : task.fileName);
        if (task.total > 0) {
            details.append("  ·  ").append(humanSize(task.transferred)).append(" / ").append(humanSize(task.total));
        }
        if (task.speedBytesPerSec > 1) {
            details.append("  ·  ").append(humanSize((long) task.speedBytesPerSec)).append("/с");
        } else if (task.error != null && !task.error.isEmpty()) {
            details.append("  ·  ").append(task.error);
        }
        transferDetails.setText(details.toString());
        if (task.state == CloudTransferManager.COMPLETED) {
            transferProgress.setProgress(1000);
            transferPercent.setText("100%");
            transferStrip.postDelayed(() -> {
                CloudTransferManager.Task current = transferManager == null ? null : transferManager.getTask();
                if (current != null && current.state == CloudTransferManager.COMPLETED) {
                    transferStrip.setVisibility(View.GONE);
                    transferManager.clear();
                }
            }, 1500);
        }
    }

    private void resumePendingTransfer(int account) {
        if (transferManager == null) return;
        CloudTransferManager.Task task = transferManager.getTask();
        if (task == null) return;
        if (task.type == CloudTransferManager.TYPE_UPLOAD &&
                (task.state == CloudTransferManager.WAITING_NETWORK || task.state == CloudTransferManager.FAILED_RETRYABLE)) {
            payloads.resumePending((node, error) -> {
                if (node != null) {
                    cloud.addFile(node, e -> {
                        setSyncText(e == null ? "✓  Загрузка восстановлена" : "Не удалось синхронизировать", e == null);
                        renderBody();
                    });
                } else if (error != null) {
                    setSyncText("Ожидание повторной загрузки: " + error, false);
                }
            });
        } else if (task.type == CloudTransferManager.TYPE_DOWNLOAD && task.messageId != 0 &&
                (task.state == CloudTransferManager.WAITING_NETWORK || task.state == CloudTransferManager.FAILED_RETRYABLE)) {
            CloudNode node = new CloudNode();
            node.id = task.nodeId;
            node.name = task.fileName;
            node.size = task.total;
            node.telegramMessageId = task.messageId;
            new CloudDownloadManager(account).download(node, (state, file, error) -> {
                if (error != null) setSyncText("Докачка приостановлена: " + error, false);
                else if (state == CloudDownloadManager.OFFLINE_AVAILABLE) setSyncText("✓  Докачка завершена", true);
            });
        }
    }

    @Override public void onFragmentDestroy() {
        if (transferManager != null) transferManager.removeListener(transferListener);
        super.onFragmentDestroy();
    }

    private void createRecentSheet(FrameLayout root) {
""",
)
replace_once(
    cloud,
    "        recentSheet.setBackground(roundRect(getThemedColor(Theme.key_windowBackgroundWhite), 22));\n",
    "        recentSheet.setBackground(roundRect(visualTheme.surfaceElevated, 22));\n",
)
replace_once(
    cloud,
    "        icon.setBackground(roundRect(0xffe5f3ff, 10));\n",
    "        icon.setBackground(roundRect(visualTheme.searchBackground, 10));\n",
)
replace_once(
    cloud,
    """    private void configureAccount(int account) {
        cloud = TelePremiumCloudManager.getInstance(account);
        payloads = new CloudPayloadRepository(account);
        payloads.setDedupLookup(sha256 -> cloud.findByHash(sha256));
    }
""",
    """    private void configureAccount(int account) {
        cloud = TelePremiumCloudManager.getInstance(account);
        payloads = new CloudPayloadRepository(account);
        payloads.setDedupLookup(sha256 -> cloud.findByHash(sha256));
        attachTransferManager(account);
        resumePendingTransfer(account);
    }
""",
)
replace_once(
    cloud,
    "        frame.setBackground(roundRect(active ? 0xffd7ebfa : 0xfff3f5f7, 13));\n",
    "        frame.setBackground(roundRect(active ? visualTheme.listSelector : visualTheme.surfaceElevated, 13));\n",
)
replace_once(
    cloud,
    "        frame.setBackground(roundRect(active ? 0xffd7ebfa : 0xfff3f5f7, 13));\n",
    "        frame.setBackground(roundRect(active ? visualTheme.listSelector : visualTheme.surfaceElevated, 13));\n",
)
replace_once(
    cloud,
    "        icon.setBackground(roundRect(0xffdceeff, 12));\n",
    "        icon.setBackground(roundRect(visualTheme.searchBackground, 12));\n",
)
replace_once(
    cloud,
    "        LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(AndroidUtilities.dp(4),AndroidUtilities.dp(7),AndroidUtilities.dp(4),AndroidUtilities.dp(7)); row.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));\n",
    "        LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(AndroidUtilities.dp(4),AndroidUtilities.dp(7),AndroidUtilities.dp(4),AndroidUtilities.dp(7)); row.setBackgroundColor(visualTheme.surfaceElevated);\n",
)
replace_once(
    cloud,
    "icon.setBackground(roundRect(node.type==CloudNode.TYPE_FOLDER?0xffdceeff:0xfff0f2f4,10));",
    "icon.setBackground(roundRect(visualTheme.searchBackground,10));",
)
replace_once(
    cloud,
    "    private void openNode(AccountNode item) {\n        if(item.node.type==CloudNode.TYPE_FOLDER)",
    "    private void openNode(AccountNode item) {\n        attachTransferManager(item.account);\n        if(item.node.type==CloudNode.TYPE_FOLDER)",
)
replace_once(
    cloud,
    "    private void saveToDevice(AccountNode item){\n        new CloudDownloadManager",
    "    private void saveToDevice(AccountNode item){\n        attachTransferManager(item.account);\n        new CloudDownloadManager",
)
replace_once(
    cloud,
    "    private GradientDrawable outlinedCard(){GradientDrawable d=roundRect(getThemedColor(Theme.key_windowBackgroundWhite),12);d.setStroke(AndroidUtilities.dp(1),0xffdfe3e8);return d;}\n",
    "    private GradientDrawable outlinedCard(){GradientDrawable d=roundRect(visualTheme.surfaceElevated,12);d.setStroke(AndroidUtilities.dp(1),visualTheme.divider);return d;}\n",
)

print("TelePremium visual theme completion + resilient transfer UI applied")
