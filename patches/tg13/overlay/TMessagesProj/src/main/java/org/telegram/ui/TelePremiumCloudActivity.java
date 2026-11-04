package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.cloud.CloudDownloadManager;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.CloudPayloadRepository;
import org.telegram.messenger.telepremium.cloud.CloudTransferManager;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultPendingIndexStore;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeArtwork;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TelePremiumCloudActivity extends BaseFragment {
    private static final int PICK_FILE = 7301;
    private static final int SAVE_FILE = 7302;
    private int selectedAccount;
    private int pendingUploadAccount;
    private int syncGeneration;
    private boolean disposed;
    private final String folderId;
    private final String folderName;
    private LinearLayout body;
    private LinearLayout accountBar;
    private TextView addButton;
    private TextView syncState;
    private LinearLayout transferStrip;
    private TextView transferTitle;
    private TextView transferDetails;
    private TextView transferPercent;
    private ProgressBar transferProgress;
    // Opening a cloud item should not cover the catalog with a download bar.
    // Actual uploads and explicit Save-to-Device downloads remain visible.
    private boolean quietPreviewDownload;
    private CloudTransferManager transferManager;
    private TelePremiumThemePreset visualTheme;
    private final CloudTransferManager.Listener transferListener = this::updateTransferStrip;
    private EditText search;
    private String activeCategory;
    private Context context;
    private TelePremiumCloudManager cloud;
    private CloudPayloadRepository payloads;
    private File pendingExportFile;
    private String pendingExportName;

    public TelePremiumCloudActivity(int account) { this(account, "root", "Облако"); }
    private TelePremiumCloudActivity(int account, String folderId, String folderName) {
        selectedAccount = account;
        pendingUploadAccount = account;
        this.folderId = folderId;
        this.folderName = folderName;
    }

    @Override public View createView(Context context) {
        this.context = context;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(folderName);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1 && !navigateBackWithinCloud()) finishFragment();
            }
        });

        visualTheme = TelePremiumThemeController.getCurrent(context);
        FrameLayout root = new FrameLayout(context);
        root.setBackground(TelePremiumThemeArtwork.cloudBackground(context));
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        syncState = new TextView(context);
        syncState.setTextSize(16);
        syncState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        syncState.setGravity(Gravity.CENTER_VERTICAL);
        syncState.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
        syncState.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        syncState.setBackgroundColor(Color.TRANSPARENT);
        page.addView(syncState, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(52)));
        createTransferStrip(page);

        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint("Поиск файлов и папок");
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_ab_search, 0, 0, 0);
        search.setCompoundDrawablePadding(AndroidUtilities.dp(12));
        search.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        search.setTextColor(visualTheme.textPrimary);
        search.setHintTextColor(visualTheme.textSecondary);
        search.setBackground(roundRect(visualTheme.searchBackground, 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(50));
        searchParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        page.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { renderBody(); }
            public void afterTextChanged(Editable e) {}
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(92));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        HorizontalScrollView accounts = new HorizontalScrollView(context);
        accounts.setHorizontalScrollBarEnabled(false);
        accounts.setBackgroundColor(Color.TRANSPARENT);
        accountBar = new LinearLayout(context);
        accountBar.setGravity(Gravity.CENTER_VERTICAL);
        accountBar.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(7), AndroidUtilities.dp(10), AndroidUtilities.dp(7));
        accounts.addView(accountBar, new HorizontalScrollView.LayoutParams(-2, -1));
        page.addView(accounts, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(72)));

        TextView add = new TextView(context);
        addButton = add;
        add.setText("+");
        add.setTextSize(36);
        add.setTextColor(Color.WHITE);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription("Добавить");
        add.setBackground(roundRect(visualTheme.fab, 32));
        add.setElevation(AndroidUtilities.dp(6));
        add.setOnClickListener(v -> showAddMenu());
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(64), AndroidUtilities.dp(64), Gravity.RIGHT | Gravity.BOTTOM);
        addParams.setMargins(0, 0, AndroidUtilities.dp(18), AndroidUtilities.dp(88));
        root.addView(add, addParams);

        add.bringToFront();

        fragmentView = root;
        renderAccounts();
        loadSelectedAccount();
        return fragmentView;
    }

    /** Categories and search are views inside this fragment, not fragments themselves.
     * Swallow BACK for these views; only the root Cloud view may close to chats.
     */
    private boolean navigateBackWithinCloud() {
        if (activeCategory != null) {
            activeCategory = null;
            renderBody();
            return true;
        }
        if (search != null && search.length() > 0) {
            search.setText("");
            return true;
        }
        return false;
    }

    @Override public boolean onBackPressed() {
        if (navigateBackWithinCloud()) return false;
        return super.onBackPressed();
    }

    private void createTransferStrip(LinearLayout page) {
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
        if (quietPreviewDownload && task != null && task.type == CloudTransferManager.TYPE_DOWNLOAD) {
            transferStrip.setVisibility(View.GONE);
            return;
        }
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
        // A payload receipt means the file is already in Telegram. Retry ONLY
        // encrypted metadata; never re-send the original document by mistake.
        if (TelePremiumVaultKeyManager.isEnabled(context)) {
            try {
                List<CloudNode> unindexed=TelePremiumVaultPendingIndexStore.load(account);
                if(!unindexed.isEmpty()) {
                    CloudNode node=unindexed.get(0);
                    // A previous index publish may have succeeded just before a
                    // process crash. Remove the outbox only after a clean remote sync.
                    boolean alreadyIndexed=false;
                    if(cloud!=null && cloud.getSnapshot()!=null)
                        for(CloudNode saved:cloud.getSnapshot().nodes)
                            if(node.id.equals(saved.id) && saved.telegramMessageId==node.telegramMessageId) {
                                alreadyIndexed=true;break;
                            }
                    if(alreadyIndexed) {
                        TelePremiumVaultPendingIndexStore.remove(account,node.id);
                        transferManager.complete();
                        resumePendingTransfer(account);
                        return;
                    }
                    commitUploadedVaultNode(account,node,cloud,transferManager);
                    return;
                }
            } catch(Exception recoveryError) {
                org.telegram.messenger.FileLog.e(recoveryError);
                setSyncText("Ожидается разблокировка или восстановление записи Vault",false);
                return;
            }
        }
        CloudTransferManager.Task task = transferManager.getTask();
        if (task == null) return;
        if (TelePremiumVaultKeyManager.isEnabled(context) && task.type==CloudTransferManager.TYPE_UPLOAD &&
                task.messageId>0 && task.nodeId!=null) {
            setSyncText("Файл уже в канале; требуется восстановить запись Vault",false);
            return; // Do not re-upload the same .tpv payload.
        }
        if (task.state == CloudTransferManager.INDEXING) {
            setSyncText("Файл в канале: восстановление индекса Vault требуется вручную",false);
            return;
        }
        if (task.type == CloudTransferManager.TYPE_UPLOAD &&
                (task.state == CloudTransferManager.WAITING_NETWORK || task.state == CloudTransferManager.FAILED_RETRYABLE)) {
            payloads.resumePending((node, error) -> {
                if (node != null) {
                    if(TelePremiumVaultKeyManager.isEnabled(context)) {
                        commitUploadedVaultNode(account,node,cloud,transferManager);
                    } else {
                        cloud.addFile(node,e->{
                            setSyncText(e==null?"✓  Загрузка восстановлена":"Не удалось синхронизировать: "+shortError(e),e==null);
                            renderBody();
                        });
                    }
                } else if (error != null) {
                    setSyncText("Ожидание повторной загрузки: " + error, false);
                }
            });
        } else if (task.type == CloudTransferManager.TYPE_DOWNLOAD && task.messageId != 0 &&
                (task.state == CloudTransferManager.WAITING_NETWORK || task.state == CloudTransferManager.FAILED_RETRYABLE)) {
            // Resume only from authoritative V2/V3 metadata. Recreating a node
            // from transfer hints would treat encrypted Vault bytes as plaintext.
            CloudNode node = null;
            if (cloud != null && cloud.getSnapshot() != null) {
                for (CloudNode candidate : cloud.getSnapshot().nodes) {
                    if (task.nodeId != null && task.nodeId.equals(candidate.id)) { node=candidate; break; }
                }
            }
            if(node==null){setSyncText("Для докачки Vault необходимо повторно открыть облако",false);return;}
            new CloudDownloadManager(account).download(node, (state, file, error) -> {
                if (error != null) setSyncText("Докачка приостановлена: " + error, false);
                else if (state == CloudDownloadManager.OFFLINE_AVAILABLE) setSyncText("✓  Докачка завершена", true);
            });
        }
    }

    /** The upload is successful ONLY after the encrypted catalog is acknowledged. */
    private void commitUploadedVaultNode(int account, CloudNode node,
                                         TelePremiumCloudManager targetCloud,
                                         CloudTransferManager targetTransfer) {
        if(node==null || targetCloud==null)return;
        targetCloud.addFile(node,error->{
            if(error==null) {
                TelePremiumVaultPendingIndexStore.remove(account,node.id);
                targetTransfer.complete();
                if(!disposed && (selectedAccount==account || selectedAccount==-1)) {
                    setSyncText("✓  Файл сохранён в Vault",true);
                    renderBody();
                }
            } else {
                targetTransfer.failRetryable("Индекс Vault не сохранён: "+shortError(error));
                if(!disposed && (selectedAccount==account || selectedAccount==-1)) {
                    setSyncText("Файл в канале, ожидает записи в каталог: "+shortError(error),false);
                    renderBody();
                }
            }
        });
    }

    @Override public void onFragmentDestroy() {
        disposed = true;
        ++syncGeneration; // Invalidates late callbacks from a previous account.
        if (transferManager != null) transferManager.removeListener(transferListener);
        super.onFragmentDestroy();
    }




    private void loadSelectedAccount() {
        quietPreviewDownload = false;
        final int generation = ++syncGeneration;
        final int requestedAccount = selectedAccount;
        activeCategory = null;
        if (search != null && search.length() > 0) search.setText("");
        // A verified in-memory catalog is immediately usable while the remote
        // generation check runs. Never present cached data as a fresh sync.
        final TelePremiumCloudManager cachedManager = requestedAccount >= 0
                ? TelePremiumCloudManager.getInstance(requestedAccount) : null;
        setSyncText(cachedManager != null && cachedManager.getSnapshot() != null
                ? "●  Локальный каталог · проверка обновлений…"
                : "●  Синхронизация облака…", false);
        renderAccounts();
        renderBody();
        if (requestedAccount >= 0) {
            configureAccount(requestedAccount);
            final TelePremiumCloudManager requestedCloud = cloud;
            final long syncStartedMs = android.os.SystemClock.elapsedRealtime();
            requestedCloud.bootstrap(error -> AndroidUtilities.runOnUIThread(() -> {
                org.telegram.messenger.FileLog.d("TPVault sync account=" + requestedAccount
                        + " duration_ms=" + (android.os.SystemClock.elapsedRealtime() - syncStartedMs)
                        + " result=" + (error == null ? "ok" : "failed"));
                if (disposed || syncGeneration != generation || selectedAccount != requestedAccount) return;
                if (error == null) {
                    setSyncText("✓  Синхронизировано", true);
                    // Resume interrupted transfers only AFTER this account has a
                    // confirmed remote index; otherwise payloads can become orphans.
                    resumePendingTransfer(requestedAccount);
                } else {
                    setSyncText("Облако недоступно: " + shortError(error)
                            + (requestedCloud.getSnapshot()==null ? "" : " · показан локальный каталог"), false);
                }
                renderBody();
            }));
        } else {
            List<Integer> accounts = activatedAccounts();
            if (accounts.isEmpty()) { setSyncText("Нет подключённых аккаунтов", false); return; }
            final int[] remaining = {accounts.size()};
            final boolean[] failed = {false};
            for (int account : accounts) TelePremiumCloudManager.getInstance(account).bootstrap(error ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || syncGeneration != generation || selectedAccount != -1) return;
                    if (error != null) failed[0] = true;
                    if (--remaining[0] == 0) {
                        setSyncText(failed[0] ? "Часть аккаунтов недоступна · показан локальный каталог" : "✓  Синхронизировано", !failed[0]);
                        renderBody();
                    }
                }));
        }
    }

    private static String shortError(String error) {
        if (error == null || error.isEmpty()) return "неизвестная ошибка";
        return error.length() > 90 ? error.substring(0,90) + "…" : error;
    }

    private void configureAccount(int account) {
        cloud = TelePremiumCloudManager.getInstance(account);
        payloads = new CloudPayloadRepository(account);
        payloads.setDedupLookup(sha256 -> cloud.findByHash(sha256));
        attachTransferManager(account);
        // Do not resume here: Cloud bootstrap may still be decrypting its index.
    }

    private void setSyncText(String text, boolean good) {
        syncState.setText(text);
        syncState.setTextColor(good ? 0xff238636 : getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    private void renderAccounts() {
        if (accountBar == null) return;
        accountBar.removeAllViews();
        for (int account : activatedAccounts()) addAccountAvatar(account, selectedAccount == account);
        addAllAccountsButton(selectedAccount == -1);
    }

    private void addAccountAvatar(int account, boolean active) {
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        frame.setBackground(roundRect(active ? visualTheme.listSelector : visualTheme.surfaceElevated, 13));
        frame.setContentDescription(accountName(account));
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(10));
        AvatarDrawable placeholder = new AvatarDrawable();
        avatar.getImageReceiver().setCurrentAccount(account);
        if (user != null) {
            placeholder.setInfo(user);
            avatar.setForUserOrChat(user, placeholder);
        } else {
            placeholder.setInfo(account, "Аккаунт " + (account + 1), null);
            avatar.setImageDrawable(placeholder);
        }
        frame.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        frame.setOnClickListener(v -> switchToCloudAccount(account));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AndroidUtilities.dp(54), AndroidUtilities.dp(54));
        lp.setMargins(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        accountBar.addView(frame, lp);
    }

    private void addAllAccountsButton(boolean active) {
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));
        frame.setBackground(roundRect(active ? visualTheme.listSelector : visualTheme.surfaceElevated, 13));
        frame.setContentDescription("Все аккаунты");
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.tp_cloud_accounts);
        frame.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        frame.setOnClickListener(v -> { if (selectedAccount != -1) {selectedAccount = -1; loadSelectedAccount();} });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AndroidUtilities.dp(54), AndroidUtilities.dp(54));
        lp.setMargins(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        accountBar.addView(frame, lp);
    }

    /** Switch the actual Telegram session as well as the Cloud view. */
    private void switchToCloudAccount(int account) {
        if (disposed || !UserConfig.getInstance(account).isClientActivated()) return;
        if (account == selectedAccount && account == UserConfig.selectedAccount) return;
        if (getParentActivity() instanceof LaunchActivity && account != UserConfig.selectedAccount) {
            // LaunchActivity owns network session / notifications / navigation.
            // A local selectedAccount assignment did not switch Telegram itself.
            final LaunchActivity host = (LaunchActivity) getParentActivity();
            ++syncGeneration; // Ignore in-flight callbacks from old Cloud instance.
            host.switchToAccount(account, true);
            TelePremiumCloudAccessGate.openCloud(host, account);
        } else {
            selectedAccount = account;
            loadSelectedAccount();
        }
    }

    private void renderBody() {
        if (body == null) return;
        body.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase();
        if (!query.isEmpty()) {
            addSectionTitle("Результаты поиска", null);
            List<AccountNode> results = collectSearchNodes(query);
            if (results.isEmpty()) addEmpty(); else for (AccountNode item : results) addFileRow(item);
            return;
        }
        if (activeCategory != null) {
            addSectionTitle(categoryTitle(activeCategory), "Фильтры");
            List<AccountNode> shown = collectCategoryNodes(activeCategory);
            Collections.sort(shown, (a,b) -> Long.compare(b.node.modifiedAt, a.node.modifiedAt));
            if (shown.isEmpty()) addEmpty(); else for (AccountNode item : shown) addFileRow(item);
            return;
        }
        if ("root".equals(folderId)) {
            addCategories();
            addUserFolders();
            return;
        }
        addSectionTitle(folderName, null);
        List<AccountNode> shown = collectNodes();
        if (shown.isEmpty()) addEmpty(); else for (AccountNode item : shown) addFileRow(item);
    }

    private void addCategories() {
        addSectionTitle("Фильтры", null);
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(3);
        body.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        addCategory(grid, "images", R.drawable.tp_cloud_photo, "Фото", 0xffdceeff);
        addCategory(grid, "video", R.drawable.tp_cloud_video, "Видео", 0xffffdede);
        addCategory(grid, "documents", R.drawable.tp_cloud_document, "Документы", 0xffffedc8);
        addCategory(grid, "audio", R.drawable.tp_cloud_audio, "Аудио", 0xffdff4ed);
        addCategory(grid, "archives", R.drawable.tp_cloud_archive, "Архивы", 0xffeadcff);
        addCategory(grid, "apk", R.drawable.tp_cloud_apk, "APK", 0xffdff0ff);
        addCategory(grid, "favorites", R.drawable.tp_cloud_favorite, "Избранное", 0xffffecd5);
        addCategory(grid, "trash", R.drawable.tp_cloud_trash, "Корзина", 0xffeceff2);
        addCategory(grid, "all", R.drawable.tp_cloud_all_files, "Все файлы", 0xffdff0ff);
    }

    private void addCategory(GridLayout grid, String kind, int icon, String title, int bg) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8));
        card.setBackground(outlinedCard());
        ImageView badge = new ImageView(context); badge.setImageResource(icon); badge.setPadding(AndroidUtilities.dp(10),AndroidUtilities.dp(10),AndroidUtilities.dp(10),AndroidUtilities.dp(10)); badge.setBackground(roundRect(bg, 11));
        card.addView(badge, new LinearLayout.LayoutParams(AndroidUtilities.dp(42), AndroidUtilities.dp(42)));
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(AndroidUtilities.dp(8),0,0,0);
        TextView name = label(title, 13, true); name.setSingleLine(true);
        boolean protectedCategory = TelePremiumCloudSecurityStore.requiresCategoryUnlock(context, kind);
        TextView value = label(protectedCategory ? "Защищено" : compactCount(collectCategoryNodes(kind).size()), 12, false); value.setSingleLine(true); value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        text.addView(name); text.addView(value); card.addView(text, new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v -> TelePremiumCloudAccessGate.openCategory(this, selectedAccount, kind, () -> { activeCategory = kind; renderBody(); }));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width=0; lp.height=AndroidUtilities.dp(72); lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f); lp.setMargins(AndroidUtilities.dp(3),AndroidUtilities.dp(4),AndroidUtilities.dp(3),AndroidUtilities.dp(4));
        grid.addView(card,lp);
    }

    private void addUserFolders() {
        addSectionTitle("Мои папки", null);
        List<AccountNode> folders = collectFolders();
        if (folders.isEmpty()) {
            TextView empty = label("Создайте первую папку кнопкой +", 14, false);
            empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(AndroidUtilities.dp(4), 0, 0, 0);
            body.addView(empty, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(54)));
            return;
        }
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(2);
        body.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        for (AccountNode item : folders) addFolderCard(grid, item);
    }

    private void addFolderCard(GridLayout grid, AccountNode item) {
        LinearLayout card = new LinearLayout(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        card.setBackground(outlinedCard());
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.tp_cloud_folder);
        icon.setPadding(AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12));
        icon.setBackground(roundRect(visualTheme.searchBackground, 12));
        card.addView(icon, new LinearLayout.LayoutParams(AndroidUtilities.dp(54), AndroidUtilities.dp(54)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(AndroidUtilities.dp(11),0,0,0);
        TextView name = label(item.node.name, 15, true); name.setSingleLine(true);
        TextView count = label(compactCount(TelePremiumCloudManager.getInstance(item.account).countFiles(item.node.id)), 13, false);
        count.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        labels.addView(name); labels.addView(count);
        card.addView(labels, new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v -> openNode(item));
        card.setOnDragListener((v,event) -> handleFolderDrop(card,item,event));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width=0; lp.height=AndroidUtilities.dp(90); lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f);
        lp.setMargins(AndroidUtilities.dp(3),AndroidUtilities.dp(4),AndroidUtilities.dp(3),AndroidUtilities.dp(4));
        grid.addView(card,lp);
    }

    private void addSectionTitle(String title, String action) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(AndroidUtilities.dp(2),AndroidUtilities.dp(13),AndroidUtilities.dp(2),AndroidUtilities.dp(6));
        TextView heading=label(title,19,true); heading.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader)); row.addView(heading,new LinearLayout.LayoutParams(0,AndroidUtilities.dp(36),1));
        if(action!=null){TextView a=label(action,15,false);a.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));a.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);a.setOnClickListener(v->{activeCategory=null;renderBody();});row.addView(a,new LinearLayout.LayoutParams(-2,AndroidUtilities.dp(36)));}
        body.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private void addFileRow(AccountNode item) {
        addFileRow(item, body);
    }

    private void addFileRow(AccountNode item, ViewGroup target) {
        CloudNode node=item.node;
        LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(AndroidUtilities.dp(4),AndroidUtilities.dp(7),AndroidUtilities.dp(4),AndroidUtilities.dp(7)); row.setBackgroundColor(visualTheme.surfaceElevated);
        ImageView icon=new ImageView(context); icon.setImageResource(node.type==CloudNode.TYPE_FOLDER?R.drawable.tp_cloud_folder:fileIcon(node)); icon.setPadding(AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12)); icon.setBackground(roundRect(visualTheme.searchBackground,10)); row.addView(icon,new LinearLayout.LayoutParams(AndroidUtilities.dp(50),AndroidUtilities.dp(50)));
        LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(AndroidUtilities.dp(12),0,AndroidUtilities.dp(8),0);
        TextView name=label(node.name,16,true);TextView detail=label(node.type==CloudNode.TYPE_FOLDER?"Папка":humanSize(node.size),13,false);detail.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));labels.addView(name);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        BackupImageView ownerAvatar = accountAvatar(item.account, 32);
        row.addView(ownerAvatar, new LinearLayout.LayoutParams(AndroidUtilities.dp(32),AndroidUtilities.dp(32)));
        TextView more=label("⋮",26,false);more.setGravity(Gravity.CENTER);more.setOnClickListener(v->showNodeMenu(item));row.addView(more,new LinearLayout.LayoutParams(AndroidUtilities.dp(42),AndroidUtilities.dp(50)));
        row.setOnClickListener(v->openNode(item));
        if(node.type==CloudNode.TYPE_FILE) row.setOnLongClickListener(v->startFileDrag(v,item));
        target.addView(row,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(66)));
    }

    private boolean startFileDrag(View view, AccountNode item) {
        ClipData data=ClipData.newPlainText("telepremium-cloud-file",item.node.id);
        View.DragShadowBuilder shadow=new View.DragShadowBuilder(view);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)view.startDragAndDrop(data,shadow,item,0);else view.startDrag(data,shadow,item,0);
        return true;
    }

    private boolean handleFolderDrop(View card,AccountNode folder,DragEvent event) {
        if(!(event.getLocalState() instanceof AccountNode))return false;
        AccountNode source=(AccountNode)event.getLocalState();
        switch(event.getAction()){
            case DragEvent.ACTION_DRAG_STARTED:
                return source.node.type==CloudNode.TYPE_FILE;
            case DragEvent.ACTION_DRAG_ENTERED:
                card.setAlpha(0.65f); return true;
            case DragEvent.ACTION_DRAG_EXITED:
                card.setAlpha(1f); return true;
            case DragEvent.ACTION_DROP:
                card.setAlpha(1f);
                if(source.account!=folder.account){setSyncText("Файл и папка относятся к разным аккаунтам",false);return true;}
                TelePremiumCloudManager.getInstance(source.account).move(source.node,folder.node.id,e->finishMutation(e,"Файл перемещён"));
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                card.setAlpha(1f); return true;
        }
        return true;
    }



    private void openNode(AccountNode item) {
        if (item.node.type == CloudNode.TYPE_FILE && !TelePremiumCloudAccessGate.canExposeNode(context, item.node)) {
            String protectedCategory = TelePremiumCloudSecurityStore.categoryForNode(item.node);
            TelePremiumCloudAccessGate.openCategory(this, item.account, protectedCategory, () -> openNode(item));
            return;
        }
        TelePremiumCloudSecuritySession.touch(context);
        if (item.node.type == CloudNode.TYPE_FOLDER) {
            presentFragment(new TelePremiumCloudActivity(item.account, item.node.id, item.node.name));
            return;
        }

        String mime = resolvedMime(item.node);
        quietPreviewDownload = true;
        if (transferStrip != null) transferStrip.setVisibility(View.GONE);
        // Vault attachments are ciphertext: never feed them to the Telegram streaming player.
        // Download, authenticate and decrypt before handing them to a media viewer.
        if (item.node.encryptionVersion==0 && TelePremiumPlayerActivity.canHandle(item.node.name, mime)) {
            // Cloud audio/video goes straight to the Telegram-backed stream URI. The
            // player itself resolves the service-channel document and starts ExoPlayer
            // as soon as the first playable range is available; no full pre-download.
            TelePremiumPlayerActivity.openCloud(
                    getParentActivity(),
                    item.account,
                    item.node.telegramMessageId,
                    item.node.name,
                    mime,
                    item.node.size);
            return;
        }

        attachTransferManager(item.account);
        new CloudDownloadManager(item.account).download(item.node, (state, file, error) -> {
            if (disposed) return;
            if (error != null) {
                setSyncText("Не удалось открыть: " + error, false);
                return;
            }
            if (state == CloudDownloadManager.DOWNLOADING) {
                // Download/verification continues, but opening is visually quiet.
                return;
            }
            if (disposed || getParentActivity() == null) return;
            if (file == null || !file.exists()) {
                setSyncText("Скачанный файл не найден в кэше", false);
                return;
            }
            try {
                // AES-GCM verified plaintext must be played by our own player,
                // NOT routed to ACTION_VIEW / FileProvider / a raw .tpv stream.
                if (TelePremiumPlayerActivity.canHandle(item.node.name, mime)) {
                    TelePremiumPlayerActivity.open(getParentActivity(), file, item.node.name, mime);
                } else if (!AndroidUtilities.openForView(file, item.node.name, mime,
                        getParentActivity(), getResourceProvider(), false)) {
                    setSyncText("Нет приложения для открытия этого формата", false);
                }
            } catch (RuntimeException viewerError) {
                org.telegram.messenger.FileLog.e(viewerError);
                setSyncText("Не удалось открыть просмотрщик: " + viewerError.getClass().getSimpleName(), false);
            }
        });
    }

    private void saveToDevice(AccountNode item){
        quietPreviewDownload = false;
        attachTransferManager(item.account);
        new CloudDownloadManager(item.account).download(item.node,(state,file,error)->{
            if(error!=null){setSyncText("Не удалось скачать: "+error,false);return;}
            if(state==CloudDownloadManager.DOWNLOADING){setSyncText("Скачивание файла…",false);return;}
            if(file==null || !file.exists()){setSyncText("Скачанный файл не найден в кэше",false);return;}
            pendingExportFile=file;pendingExportName=item.node.name;
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(resolvedMime(item.node));
            intent.putExtra(Intent.EXTRA_TITLE,item.node.name);intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent,SAVE_FILE);
        });
    }

    private void showNodeMenu(AccountNode item) {
        if (item.node.type == CloudNode.TYPE_FILE && !TelePremiumCloudAccessGate.canExposeNode(context, item.node)) {
            String protectedCategory = TelePremiumCloudSecurityStore.categoryForNode(item.node);
            TelePremiumCloudAccessGate.openCategory(this, item.account, protectedCategory, () -> showNodeMenu(item));
            return;
        }
        TelePremiumCloudSecuritySession.touch(context);
        TelePremiumCloudManager manager=TelePremiumCloudManager.getInstance(item.account); CloudNode node=item.node;
        if(node.state==CloudNode.STATE_TRASHED){
            new BottomSheet.Builder(context).setTitle(node.name).setItems(
                    new CharSequence[]{"Открыть","Восстановить","Скрыть из каталога"},
                    new int[]{R.drawable.tp_cloud_download,R.drawable.tp_cloud_restore,R.drawable.tp_cloud_delete_forever},
                    (d,w)->{if(w==0)openNode(item);else if(w==1)manager.restore(node,e->finishMutation(e,"Файл восстановлен"));else confirmPermanentDelete(manager,node);}
            ).create().show();
            return;
        }
        if(node.type==CloudNode.TYPE_FILE){
            new BottomSheet.Builder(context).setTitle(node.name).setItems(
                    new CharSequence[]{"Открыть","Скачать на устройство","Переместить","Переименовать",node.favorite?"Убрать из избранного":"В избранное","В корзину"},
                    new int[]{R.drawable.tp_cloud_download,R.drawable.tp_cloud_download,R.drawable.tp_cloud_folder,R.drawable.tp_cloud_rename,R.drawable.tp_cloud_favorite,R.drawable.tp_cloud_trash},
                    (d,w)->{if(w==0)openNode(item);else if(w==1)saveToDevice(item);else if(w==2)chooseMoveFolder(item);else if(w==3)rename(manager,node);else if(w==4)manager.toggleFavorite(node,e->finishMutation(e,"Изменения сохранены"));else manager.trash(node,e->finishMutation(e,"Перемещено в корзину"));}
            ).create().show();
        }else{
            new BottomSheet.Builder(context).setTitle(node.name).setItems(
                    new CharSequence[]{"Открыть","Переименовать","В корзину"},
                    new int[]{R.drawable.tp_cloud_folder,R.drawable.tp_cloud_rename,R.drawable.tp_cloud_trash},
                    (d,w)->{if(w==0)openNode(item);else if(w==1)rename(manager,node);else manager.trash(node,e->finishMutation(e,"Перемещено в корзину"));}
            ).create().show();
        }
    }

    private void chooseMoveFolder(AccountNode item) {
        presentFragment(new TelePremiumCloudFolderPickerActivity(item.account,"root","Куда переместить?",(id,name)->
                TelePremiumCloudManager.getInstance(item.account).move(item.node,id,e->finishMutation(e,"Перемещено в «"+name+"»"))));
    }

    private void confirmPermanentDelete(TelePremiumCloudManager manager, CloudNode node) {
        new AlertDialog.Builder(context)
                .setTitle("Скрыть из каталога?")
                .setMessage("Запись будет скрыта из каталога. Для защиты от потери сам файл остаётся в служебном канале и может быть восстановлен из резервной копии индекса.")
                .setPositiveButton("Удалить",(d,w)->manager.deleteMetadataPermanently(node,e->finishMutation(e,"Удалено из каталога; копия сохранена")))
                .setNegativeButton("Отмена",null)
                .show();
    }

    private void finishMutation(String error,String success) {
        setSyncText(error==null?"✓  "+success:"Не удалось выполнить: "+error,error==null);
        renderBody();
    }

    private void rename(TelePremiumCloudManager manager, CloudNode node) {
        EditText input=new EditText(context);input.setSingleLine(true);input.setText(node.name);
        new AlertDialog.Builder(context).setTitle("Переименовать").setView(input).setPositiveButton("Сохранить",(d,w)->{String n=input.getText().toString().trim();if(!n.isEmpty())manager.rename(node,n,e->finishMutation(e,"Имя изменено"));}).setNegativeButton("Отмена",null).show();
    }

    private void showAddMenu() {
        new BottomSheet.Builder(context)
                .setTitle("Добавить в облако")
                .setItems(
                        new CharSequence[]{"Загрузить файлы","Создать папку"},
                        new int[]{R.drawable.tp_cloud_upload,R.drawable.tp_cloud_new_folder},
                        (d,w)->chooseTargetAccount(w==0)
                )
                .create().show();
    }

    private void chooseTargetAccount(boolean upload) {
        if(selectedAccount>=0){beginCloudMutation(selectedAccount,upload);return;}
        List<Integer> accounts=activatedAccounts();
        if(accounts.isEmpty()){setSyncText("Нет подключённых аккаунтов",false);return;}
        CharSequence[] names=new CharSequence[accounts.size()];for(int i=0;i<accounts.size();i++)names[i]=accountName(accounts.get(i));
        new AlertDialog.Builder(context).setTitle("Выберите аккаунт").setItems(names,(d,w)->beginCloudMutation(accounts.get(w),upload)).show();
    }

    private void beginCloudMutation(int account, boolean upload) {
        quietPreviewDownload = false;
        if (org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager.isEnabled(context)
                && !TelePremiumCloudManager.getInstance(account).isRemoteReady()) {
            new AlertDialog.Builder(context)
                    .setTitle("Облако ещё не синхронизировано")
                    .setMessage("Дождитесь подключения выбранного аккаунта и синхронизации индекса Vault. Зашифрованная локальная версия доступна только для просмотра.")
                    .setPositiveButton("Понятно",null).show();
            return;
        }
        if (upload && TelePremiumVaultKeyManager.isEnabled(context)) {
            CloudTransferManager.Task t=CloudTransferManager.getInstance(account).getTask();
            if(t!=null && t.type==CloudTransferManager.TYPE_UPLOAD &&
                   t.state!=CloudTransferManager.COMPLETED && t.state!=CloudTransferManager.CANCELLED &&
                   (t.state==CloudTransferManager.UPLOADING || t.state==CloudTransferManager.INDEXING ||
                    (t.state==CloudTransferManager.FAILED_RETRYABLE && t.messageId>0))) {
                new AlertDialog.Builder(context).setTitle("Незавершённая загрузка Vault")
                        .setMessage("Сначала дождитесь записи предыдущего файла в зашифрованный каталог. Не загружайте его повторно, пока не восстановлен индекс.")
                        .setPositiveButton("Понятно",null).show();return;
            }
        }
        if (upload) pickFile(account); else createFolder(account);
    }

    private void pickFile(int account) {
        pendingUploadAccount=account;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_FILE);
    }

    private void createFolder(int account) {
        EditText input=new EditText(context);input.setHint("Имя папки");input.setSingleLine(true);
        new AlertDialog.Builder(context).setTitle("Новая папка").setView(input).setPositiveButton("Создать",(d,w)->{String n=input.getText().toString().trim();if(!n.isEmpty())TelePremiumCloudManager.getInstance(account).createFolder(folderId,n,e->renderBody());}).setNegativeButton("Отмена",null).show();
    }

    @Override public void onActivityResultFragment(int requestCode,int resultCode,Intent data){
        super.onActivityResultFragment(requestCode,resultCode,data);
        if(requestCode==SAVE_FILE){
            if(resultCode==Activity.RESULT_OK&&data!=null&&data.getData()!=null&&pendingExportFile!=null)exportFile(data.getData());
            else{pendingExportFile=null;pendingExportName=null;}
            return;
        }
        if(requestCode!=PICK_FILE||resultCode!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{getParentActivity().getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignore){}
        // Bind this upload to the chosen account. Never commit its metadata
        // into a different Cloud if the user switches accounts while uploading.
        final int uploadAccount = pendingUploadAccount;
        final TelePremiumCloudManager uploadCloud = TelePremiumCloudManager.getInstance(uploadAccount);
        if(TelePremiumVaultKeyManager.isEnabled(context) && !uploadCloud.isRemoteReady()) {
            setSyncText("Облако потеряло соединение. Файл не отправлен; повторите после синхронизации.",false);
            return;
        }
        final CloudPayloadRepository uploadPayloads = new CloudPayloadRepository(uploadAccount);
        uploadPayloads.setDedupLookup(sha256 -> uploadCloud.findByHash(sha256));
        final String uploadFolder = folderId;
        final String mime = getParentActivity().getContentResolver().getType(uri);
        final String name = displayName(uri);
        setSyncText("Подготовка и загрузка файла…",false);
        uploadPayloads.importUri(uri,uploadFolder,name,mime,(node,error)->{
            if(error!=null){
                if(!disposed && selectedAccount==uploadAccount) setSyncText("Не удалось загрузить: "+error,false);
                return;
            }
            if(TelePremiumVaultKeyManager.isEnabled(context)) {
                commitUploadedVaultNode(uploadAccount,node,uploadCloud,
                        CloudTransferManager.getInstance(uploadAccount));
            } else {
                uploadCloud.addFile(node,e->{
                    if(!disposed && (selectedAccount==uploadAccount || selectedAccount==-1)) {
                        setSyncText(e==null?"✓  Синхронизировано":"Не удалось синхронизировать: "+shortError(e),e==null);
                        renderBody();
                    }
                });
            }
        });
    }

    private void exportFile(Uri target){
        final File source=pendingExportFile;final String name=pendingExportName;
        pendingExportFile=null;pendingExportName=null;setSyncText("Сохранение файла…",false);
        Utilities.globalQueue.postRunnable(()->{
            String error=null;
            try(FileInputStream input=new FileInputStream(source);OutputStream output=context.getContentResolver().openOutputStream(target,"w")){
                if(output==null)throw new IllegalStateException("Хранилище недоступно");
                byte[] buffer=new byte[64*1024];int read;while((read=input.read(buffer))!=-1)output.write(buffer,0,read);output.flush();
            }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final String result=error;AndroidUtilities.runOnUIThread(()->setSyncText(result==null?"✓  «"+name+"» сохранён":"Не удалось сохранить: "+result,result==null));
        });
    }

    private String resolvedMime(CloudNode node){
        String name=node.name==null?"":node.name;int dot=name.lastIndexOf('.');String ext=dot>=0?name.substring(dot+1).toLowerCase():"";
        String mapped=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if("apk".equals(ext))mapped="application/vnd.android.package-archive";
        else if("rar".equals(ext))mapped="application/vnd.rar";
        else if("7z".equals(ext))mapped="application/x-7z-compressed";
        else if("doc".equals(ext))mapped="application/msword";
        else if("docx".equals(ext))mapped="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        else if("xls".equals(ext))mapped="application/vnd.ms-excel";
        else if("xlsx".equals(ext))mapped="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        else if("ppt".equals(ext))mapped="application/vnd.ms-powerpoint";
        else if("pptx".equals(ext))mapped="application/vnd.openxmlformats-officedocument.presentationml.presentation";
        String stored=node.mimeType;
        return mapped!=null?mapped:stored!=null&&!stored.isEmpty()?stored:"application/octet-stream";
    }

    private String displayName(Uri uri){
        Cursor cursor=null;
        try{
            cursor=getParentActivity().getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
            if(cursor!=null&&cursor.moveToFirst()){int i=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0){String name=cursor.getString(i);if(name!=null&&!name.trim().isEmpty())return name;}}
        }catch(Exception ignore){}finally{if(cursor!=null)cursor.close();}
        String fallback=uri.getLastPathSegment();
        return fallback==null||fallback.trim().isEmpty()?"cloud-file":fallback;
    }

    private List<AccountNode> collectNodes(){ArrayList<AccountNode> out=new ArrayList<>();if(selectedAccount>=0){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(selectedAccount);for(CloudNode n:m.children(folderId,false))if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(selectedAccount,n));}else for(int a:activatedAccounts()){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(a);for(CloudNode n:m.children("root",false))if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(a,n));}return out;}
    private List<AccountNode> collectFolders(){ArrayList<AccountNode> out=new ArrayList<>();for(AccountNode item:collectNodes())if(item.node.type==CloudNode.TYPE_FOLDER)out.add(item);return out;}
    private List<AccountNode> collectSearchNodes(String query){ArrayList<AccountNode> out=new ArrayList<>();List<Integer> accounts=selectedAccount>=0?Collections.singletonList(selectedAccount):activatedAccounts();for(int a:accounts)for(CloudNode n:TelePremiumCloudManager.getInstance(a).search(query))if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(a,n));Collections.sort(out,(x,y)->Long.compare(y.node.modifiedAt,x.node.modifiedAt));return out;}
    private List<AccountNode> collectCategoryNodes(String kind){ArrayList<AccountNode> out=new ArrayList<>();List<Integer> accounts=selectedAccount>=0?Collections.singletonList(selectedAccount):activatedAccounts();for(int a:accounts){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(a);List<CloudNode> nodes="all".equals(kind)?m.allFiles():"trash".equals(kind)?m.trashItems():"favorites".equals(kind)?m.favorites():m.category(kind);for(CloudNode n:nodes)if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(a,n));}return out;}
    private BackupImageView accountAvatar(int account,int size){TLRPC.User user=UserConfig.getInstance(account).getCurrentUser();BackupImageView avatar=new BackupImageView(context);avatar.setRoundRadius(AndroidUtilities.dp(Math.max(7,size/5)));avatar.getImageReceiver().setCurrentAccount(account);AvatarDrawable placeholder=new AvatarDrawable();if(user!=null){placeholder.setInfo(user);avatar.setForUserOrChat(user,placeholder);}else{placeholder.setInfo(account,"Аккаунт "+(account+1),null);avatar.setImageDrawable(placeholder);}avatar.setContentDescription(accountName(account));return avatar;}
    private List<Integer> activatedAccounts(){ArrayList<Integer> out=new ArrayList<>();for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated())out.add(a);return out;}
    private String accountName(int account){TLRPC.User u=UserConfig.getInstance(account).getCurrentUser();return u==null?"Аккаунт "+(account+1):UserObject.getFirstName(u);}
    private String accountInitial(int account){String n=accountName(account);return n.isEmpty()?"●":n.substring(0,1).toUpperCase();}
    private int count(List<AccountNode> list,String kind){int n=0;for(AccountNode i:list)if(matches(i.node,kind))n++;return n;}
    private boolean matches(CloudNode n,String kind){if(n.type!=CloudNode.TYPE_FILE)return false;String x=n.name==null?"":n.name.toLowerCase();String m=n.mimeType==null?"":n.mimeType;return "images".equals(kind)&&m.startsWith("image/")||"video".equals(kind)&&m.startsWith("video/")||"archives".equals(kind)&&(x.endsWith(".zip")||x.endsWith(".rar")||x.endsWith(".7z"))||"documents".equals(kind)&&(m.startsWith("text/")||x.endsWith(".pdf")||x.endsWith(".doc")||x.endsWith(".docx")||x.endsWith(".xls")||x.endsWith(".xlsx"));}
    private String categoryTitle(String kind){if("images".equals(kind))return"Фото";if("video".equals(kind))return"Видео";if("audio".equals(kind))return"Аудио";if("archives".equals(kind))return"Архивы";if("apk".equals(kind))return"APK";if("favorites".equals(kind))return"Избранное";if("trash".equals(kind))return"Корзина";if("all".equals(kind))return"Все файлы";return"Документы";}
    private int fileIcon(CloudNode n){String name=n.name==null?"":n.name.toLowerCase();String m=n.mimeType==null?"":n.mimeType.toLowerCase();if(m.startsWith("image/"))return R.drawable.tp_cloud_photo;if(m.startsWith("video/"))return R.drawable.tp_cloud_video;if(m.startsWith("audio/"))return R.drawable.tp_cloud_audio;if(name.endsWith(".apk"))return R.drawable.tp_cloud_apk;if(name.endsWith(".zip")||name.endsWith(".rar")||name.endsWith(".7z")||name.endsWith(".tar")||name.endsWith(".gz"))return R.drawable.tp_cloud_archive;return R.drawable.tp_cloud_document;}
    private TextView label(String text,int size,boolean bold){TextView v=new TextView(context);v.setText(text);v.setTextSize(size);v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable roundRect(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(AndroidUtilities.dp(radius));return d;}
    private GradientDrawable outlinedCard(){GradientDrawable d=roundRect(visualTheme.surfaceElevated,12);d.setStroke(AndroidUtilities.dp(1),visualTheme.divider);return d;}
    private void addEmpty(){TextView empty=label("Здесь пока нет файлов",15,false);empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));empty.setGravity(Gravity.CENTER);body.addView(empty,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(100)));}
    private String compactCount(int n){if(n>=1000000)return compactNumber(n/1000000d)+" млн";if(n>=1000)return compactNumber(n/1000d)+" тыс.";int mod100=n%100,mod10=n%10;String word=mod100>=11&&mod100<=14?"файлов":mod10==1?"файл":mod10>=2&&mod10<=4?"файла":"файлов";return n+" "+word;}
    private String compactNumber(double value){if(value>=10||Math.abs(value-Math.rint(value))<0.05)return String.valueOf((int)Math.round(value));return String.format(java.util.Locale.US,"%.1f",value).replace('.',',');}
    private String humanSize(long n){if(n<1024)return n+" Б";if(n<1024*1024)return(n/1024)+" КБ";if(n<1024L*1024*1024)return(n/(1024*1024))+" МБ";return String.format("%.1f ГБ",n/(1024d*1024d*1024d));}
    private static final class AccountNode{final int account;final CloudNode node;AccountNode(int account,CloudNode node){this.account=account;this.node=node;}}
}
