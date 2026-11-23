package org.telegram.ui;

import org.telegram.messenger.telepremium.cloud.TelePremiumCloudFolderScanner;

import org.telegram.messenger.telepremium.cloud.TelePremiumCloudFormat;

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
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.cloud.CloudDownloadManager;
import org.telegram.messenger.telepremium.cloud.CloudImageNames;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.CloudPayloadRepository;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;
import org.telegram.messenger.telepremium.cloud.CloudTransferManager;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager;
import org.telegram.messenger.telepremium.cloud.vault.VaultFolderSession;
import org.telegram.messenger.telepremium.cloud.vault.VaultSecretPolicy;
import org.telegram.messenger.telepremium.cloud.VaultFolderLocks;
import org.telegram.messenger.telepremium.cloud.VaultRekeyJob;
import org.telegram.messenger.telepremium.cloud.VaultSessionGuard;
import org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultPendingIndexStore;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeArtwork;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.ConnectionsManager;
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

public class TelePremiumCloudActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private Runnable mainTabBack;
    private final VaultFolderSession.Listener lockListener = () -> AndroidUtilities.runOnUIThread(this::onLockStateChanged);

    /** A folder closed (timeout, screen off, manual): hide its contents everywhere, leave it if we are inside. */
    private void onLockStateChanged() {
        if (disposed || body == null) return;
        if (!"root".equals(folderId) && selectedAccount >= 0
                && TelePremiumCloudManager.getInstance(selectedAccount).view().contentsHidden(folderId)) {
            finishFragment();
            return;
        }
        applySecureFlag();
        renderBody();
    }

    private void applySecureFlag() {
        android.app.Activity activity = getParentActivity();
        if (activity == null) return;
        boolean secure = false;
        if (VaultFolderLocks.secureScreen() && selectedAccount >= 0 && !"root".equals(folderId)) {
            TelePremiumCloudManager m = TelePremiumCloudManager.getInstance(selectedAccount);
            CloudNode here = m.findNodeById(folderId);
            secure = here != null && (here.lock != null || m.isProtected(here));
        }
        if (secure) activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override public void onPause() {
        super.onPause();
        android.app.Activity activity = getParentActivity();
        if (activity != null) activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    public TelePremiumCloudActivity asMainTab(Runnable back) { mainTabBack = back; return this; }
    private static final int PICK_FILE = 7301;
    private static final int SAVE_FILE = 7302;
    private static final int PICK_FOLDER = 7303;
    private static final int MAX_BATCH_FILES = 500;
    private final java.util.ArrayDeque<Uri> uploadQueue = new java.util.ArrayDeque<>();
    private int uploadQueueTotal, uploadQueueDone, uploadQueueAccount = -1;
    private int selectedAccount;
    private int pendingUploadAccount;
    private int syncGeneration;
    private boolean disposed;
    private final ArrayList<Integer> connectionAccounts = new ArrayList<>();
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
    private final Runnable reconnectSync = () -> {
        if (!disposed && syncState != null) syncSelectedAccount(false);
    };

    public TelePremiumCloudActivity(int account) { this(account, "root", org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_069", org.telegram.messenger.R.string.TP_L10N_069)); }
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
                if (id == -1 && !navigateBackWithinCloud()) { if (mainTabBack != null) mainTabBack.run(); else finishFragment(); }
            }
        });

        visualTheme = TelePremiumThemeController.getCurrent(context);
        FrameLayout root = new FrameLayout(context);
        if (mainTabBack != null) root.setPadding(0,0,0,AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
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
        syncState.setMinHeight(AndroidUtilities.dp(52));
        syncState.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(8), AndroidUtilities.dp(18), AndroidUtilities.dp(8));
        page.addView(syncState, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        createTransferStrip(page);

        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_070", org.telegram.messenger.R.string.TP_L10N_070));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.msg_search, 0, 0, 0);
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
        add.setContentDescription(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_071", org.telegram.messenger.R.string.TP_L10N_071));
        add.setBackground(roundRect(visualTheme.fab, 32));
        add.setElevation(AndroidUtilities.dp(6));
        add.setOnClickListener(v -> showAddMenu());
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(64), AndroidUtilities.dp(64), Gravity.RIGHT | Gravity.BOTTOM);
        addParams.setMargins(0, 0, AndroidUtilities.dp(18), AndroidUtilities.dp(88));
        root.addView(add, addParams);

        add.bringToFront();

        fragmentView = root;
        for (int account : activatedAccounts()) {
            if (connectionAccounts.contains(account)) continue;
            connectionAccounts.add(account);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        renderAccounts();
        loadSelectedAccount();
        VaultSessionGuard.install();
        VaultFolderSession.shared().addListener(lockListener);
        applySecureFlag();
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

    @Override public boolean onBackPressed(boolean invoked) {
        if (navigateBackWithinCloud()) return false;
        return super.onBackPressed(invoked);
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
        transferTitle = label(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_072", org.telegram.messenger.R.string.TP_L10N_072), 14, true);
        top.addView(transferTitle, new LinearLayout.LayoutParams(0, AndroidUtilities.dp(24), 1f));
        transferPercent = label("0%", 14, true);
        transferPercent.setTextColor(visualTheme.accent);
        transferPercent.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(transferPercent, new LinearLayout.LayoutParams(AndroidUtilities.dp(56), AndroidUtilities.dp(24)));
        transferStrip.addView(top, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(24)));

        transferDetails = label("", 12, false);
        transferDetails.setTextColor(visualTheme.textSecondary);
        transferDetails.setSingleLine(true);
        transferDetails.setMinHeight(AndroidUtilities.dp(20));
        transferStrip.addView(transferDetails, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));

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
        StringBuilder details = new StringBuilder(task.fileName == null ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_073", org.telegram.messenger.R.string.TP_L10N_073) : task.fileName);
        if (task.total > 0) {
            details.append("  ·  ").append(humanSize(task.transferred)).append(" / ").append(humanSize(task.total));
        }
        if (task.speedBytesPerSec > 1) {
            details.append("  ·  ").append(humanSize((long) task.speedBytesPerSec)).append(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_074", org.telegram.messenger.R.string.TP_L10N_074));
        } else if (task.error != null && !task.error.isEmpty()) {
            details.append("  ·  ").append(task.error);
        }
        // An error (e.g. why the Vault index was not saved) must be readable in full.
        boolean hasError = task.error != null && !task.error.isEmpty() && task.speedBytesPerSec <= 1;
        transferDetails.setSingleLine(!hasError);
        transferDetails.setMaxLines(hasError ? 12 : 1);
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

    /**
     * Called only after a successful remote sync of {@code account}: the snapshot is the
     * confirmed remote catalog. Interrupted Vault uploads are reconciled against it —
     * never against the local progress (100%) or a local thumbnail:
     * <ul>
     * <li>record present in the remote catalog → the upload is finished, the journal entry goes;</li>
     * <li>record missing but the encrypted pending journal has it → only the index entry is
     *     written again (the payload is already in Telegram and is never re-sent);</li>
     * <li>missing in both → the user is told the record has to be restored (no re-upload).</li>
     * </ul>
     * Cloud channels and keys are never recreated here.
     */
    private void resumePendingTransfer(int account) {
        if (transferManager == null) return;
        if (TelePremiumVaultKeyManager.isEnabled(context)) {
            CloudSnapshot remote = cloud == null ? null : cloud.getSnapshot();
            if (remote == null) return; // no confirmed catalog → nothing can be proven
            List<CloudNode> pending;
            try {
                pending = new java.util.ArrayList<>(TelePremiumVaultPendingIndexStore.load(account));
            } catch (Exception recoveryError) {
                org.telegram.messenger.FileLog.e(recoveryError);
                setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_075", org.telegram.messenger.R.string.TP_L10N_075)
                        + ": " + fullError(recoveryError), false);
                return;
            }
            // Journal records whose index entry the confirmed catalog already holds are done.
            for (java.util.Iterator<CloudNode> it = pending.iterator(); it.hasNext(); ) {
                CloudNode record = it.next();
                if (findIndexed(remote, record.id, record.telegramMessageId) != null) {
                    TelePremiumVaultPendingIndexStore.remove(account, record.id);
                    it.remove();
                }
            }
            CloudTransferManager.Task task = transferManager.getTask();
            boolean acknowledgedUpload = task != null && task.type == CloudTransferManager.TYPE_UPLOAD
                    && task.messageId > 0 && task.nodeId != null && task.state != CloudTransferManager.CANCELLED;
            if (acknowledgedUpload) {
                if (findIndexed(remote, task.nodeId, task.messageId) != null) {
                    // Confirmed by the remote catalog: finish the task (also if it was left at 100%).
                    if (task.state != CloudTransferManager.COMPLETED) transferManager.complete();
                } else {
                    CloudNode record = null;
                    for (CloudNode n : pending) {
                        if (task.nodeId.equals(n.id) && n.telegramMessageId == task.messageId) { record = n; break; }
                    }
                    if (record != null) {
                        // Payload acknowledged by Telegram, index entry lost: write the index only.
                        pending.remove(record);
                        commitUploadedVaultNode(account, record, cloud, transferManager, () -> resumePendingTransfer(account));
                        return;
                    }
                    if (task.state == CloudTransferManager.COMPLETED) {
                        // COMPLETED is set only after a confirmed index commit; the entry has since
                        // been removed from the catalog by the user. Nothing to restore.
                        transferManager.clear();
                    } else {
                        setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_076", org.telegram.messenger.R.string.TP_L10N_076), false);
                        return; // Do not re-upload the same .tpv payload.
                    }
                }
            }
            if (!pending.isEmpty()) {
                // Older acknowledged payloads without a live task: restore their index entries
                // one by one without touching the current transfer task.
                commitUploadedVaultNode(account, pending.get(0), cloud, null, () -> resumePendingTransfer(account));
                return;
            }
            if (acknowledgedUpload) return; // reconciled above
        }
        CloudTransferManager.Task task = transferManager.getTask();
        if (task == null) return;
        if (task.state == CloudTransferManager.INDEXING) {
            setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_077", org.telegram.messenger.R.string.TP_L10N_077),false);
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
                            setSyncText(e==null?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_078", org.telegram.messenger.R.string.TP_L10N_078):org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_079", org.telegram.messenger.R.string.TP_L10N_079)+fullError(e),e==null);
                            renderBody();
                        });
                    }
                } else if (error != null) {
                    setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_080", org.telegram.messenger.R.string.TP_L10N_080) + error, false);
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
            if(node==null){setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_081", org.telegram.messenger.R.string.TP_L10N_081),false);return;}
            new CloudDownloadManager(account).download(node, (state, file, error) -> {
                if (error != null) setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_082", org.telegram.messenger.R.string.TP_L10N_082) + error, false);
                else if (state == CloudDownloadManager.OFFLINE_AVAILABLE) setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_083", org.telegram.messenger.R.string.TP_L10N_083), true);
            });
        }
    }

    /** The upload is successful ONLY after the encrypted catalog is acknowledged. */
    /** Node of the confirmed catalog with this id and Telegram message (any part of a multipart file). */
    private static CloudNode findIndexed(CloudSnapshot remote, String nodeId, long messageId) {
        if (remote == null || nodeId == null) return null;
        for (CloudNode saved : remote.nodes) {
            if (saved == null || !nodeId.equals(saved.id)) continue;
            if (messageId <= 0 || saved.telegramMessageId == messageId) return saved;
            if (saved.partMessageIds != null) for (long part : saved.partMessageIds) if (part == messageId) return saved;
        }
        return null;
    }

    /** Index save errors are shown in full (they explain what to fix), never shortened. */
    private static String fullError(Object error) {
        String text = error instanceof Throwable
                ? (((Throwable) error).getMessage() == null ? error.getClass().getSimpleName() : ((Throwable) error).getMessage())
                : error == null ? null : String.valueOf(error);
        return text == null || text.trim().isEmpty() ? "неизвестная ошибка" : text.trim();
    }

    private void commitUploadedVaultNode(int account, CloudNode node,
                                         TelePremiumCloudManager targetCloud,
                                         CloudTransferManager targetTransfer) {
        commitUploadedVaultNode(account, node, targetCloud, targetTransfer, null);
    }

    private void commitUploadedVaultNode(int account, CloudNode node,
                                         TelePremiumCloudManager targetCloud,
                                         CloudTransferManager targetTransfer,
                                         Runnable onCommitted) {
        if(node==null || targetCloud==null)return;
        targetCloud.addFile(node,error->{
            if(error==null) {
                TelePremiumVaultPendingIndexStore.remove(account,node.id);
                if(targetTransfer!=null) targetTransfer.complete();
                if(!disposed && (selectedAccount==account || selectedAccount==-1)) {
                    setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_084", org.telegram.messenger.R.string.TP_L10N_084),true);
                    renderBody();
                }
                if(onCommitted!=null)onCommitted.run();
            } else {
                if(targetTransfer!=null) targetTransfer.failRetryable(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_085", org.telegram.messenger.R.string.TP_L10N_085)+fullError(error));
                if(!disposed && (selectedAccount==account || selectedAccount==-1)) {
                    setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_086", org.telegram.messenger.R.string.TP_L10N_086)+fullError(error),false);
                    renderBody();
                }
            }
        });
    }

    @Override public void onFragmentDestroy() {
        disposed = true;
        VaultFolderSession.shared().removeListener(lockListener);
        AndroidUtilities.cancelRunOnUIThread(reconnectSync);
        for (int account : connectionAccounts)
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        connectionAccounts.clear();
        ++syncGeneration; // Invalidates late callbacks from a previous account.
        if (transferManager != null) transferManager.removeListener(transferListener);
        super.onFragmentDestroy();
    }




    private void loadSelectedAccount() {
        syncSelectedAccount(true);
    }

    @Override public void onResume() {
        super.onResume();
        applySecureFlag();
        if (!disposed && syncState != null) syncSelectedAccount(false);
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (disposed || id != NotificationCenter.didUpdateConnectionState ||
                (selectedAccount >= 0 && selectedAccount != account)) return;
        if (ConnectionsManager.getInstance(account).getConnectionState() == ConnectionsManager.ConnectionStateConnected) {
            AndroidUtilities.cancelRunOnUIThread(reconnectSync);
            AndroidUtilities.runOnUIThread(reconnectSync, 500);
        }
    }

    private void syncSelectedAccount(boolean resetView) {
        quietPreviewDownload = false;
        final int generation = ++syncGeneration;
        final int requestedAccount = selectedAccount;
        if (resetView) {
            activeCategory = null;
            if (search != null && search.length() > 0) search.setText("");
        }
        // A verified in-memory catalog is immediately usable while the remote
        // generation check runs. Never present cached data as a fresh sync.
        final TelePremiumCloudManager cachedManager = requestedAccount >= 0
                ? TelePremiumCloudManager.getInstance(requestedAccount) : null;
        setSyncText(cachedManager != null && cachedManager.getSnapshot() != null
                ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_087", org.telegram.messenger.R.string.TP_L10N_087)
                : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_088", org.telegram.messenger.R.string.TP_L10N_088), false);
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
                    setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_089", org.telegram.messenger.R.string.TP_L10N_089), true);
                    // Resume interrupted transfers only AFTER this account has a
                    // confirmed remote index; otherwise payloads can become orphans.
                    resumePendingTransfer(requestedAccount);
                    VaultRekeyJob.kick(requestedAccount, (left, rekeyError) -> AndroidUtilities.runOnUIThread(() -> { if (!disposed) renderBody(); }));
                } else {
                    setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_090", org.telegram.messenger.R.string.TP_L10N_090) + shortError(error)
                            + (requestedCloud.getSnapshot()==null ? "" : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_091", org.telegram.messenger.R.string.TP_L10N_091)), false);
                }
                renderBody();
            }));
        } else {
            List<Integer> accounts = activatedAccounts();
            if (accounts.isEmpty()) { setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_092", org.telegram.messenger.R.string.TP_L10N_092), false); return; }
            final int[] remaining = {accounts.size()};
            final boolean[] failed = {false};
            for (int account : accounts) TelePremiumCloudManager.getInstance(account).bootstrap(error ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || syncGeneration != generation || selectedAccount != -1) return;
                    if (error != null) failed[0] = true;
                    if (--remaining[0] == 0) {
                        setSyncText(failed[0] ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_093", org.telegram.messenger.R.string.TP_L10N_093) : org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_089", org.telegram.messenger.R.string.TP_L10N_089), !failed[0]);
                        renderBody();
                    }
                }));
        }
    }

    private static String shortError(String error) { return TelePremiumCloudFormat.shortError(error); }

    private void configureAccount(int account) {
        TelePremiumCloudManager selectedCloud = TelePremiumCloudManager.getInstance(account);
        // Automatic refresh must not create a second owner for an active upload.
        if (cloud != selectedCloud || payloads == null) {
            payloads = new CloudPayloadRepository(account);
            payloads.setDedupLookup(selectedCloud::findByHash);
        }
        cloud = selectedCloud;
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
            placeholder.setInfo(account, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_002", org.telegram.messenger.R.string.TP_L10N_002) + (account + 1), null);
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
        frame.setContentDescription(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_094", org.telegram.messenger.R.string.TP_L10N_094));
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
            addSectionTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_095", org.telegram.messenger.R.string.TP_L10N_095), null);
            List<AccountNode> results = collectSearchNodes(query);
            if (results.isEmpty()) addEmpty(); else for (AccountNode item : results) addFileRow(item);
            return;
        }
        if (activeCategory != null) {
            addSectionTitle(categoryTitle(activeCategory), org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_096", org.telegram.messenger.R.string.TP_L10N_096));
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
        addSectionTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_096", org.telegram.messenger.R.string.TP_L10N_096), null);
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(3);
        body.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        addCategory(grid, "images", R.drawable.tp_cloud_photo, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_097", org.telegram.messenger.R.string.TP_L10N_097), 0xffdceeff);
        addCategory(grid, "video", R.drawable.tp_cloud_video, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_098", org.telegram.messenger.R.string.TP_L10N_098), 0xffffdede);
        addCategory(grid, "documents", R.drawable.tp_cloud_document, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_099", org.telegram.messenger.R.string.TP_L10N_099), 0xffffedc8);
        addCategory(grid, "audio", R.drawable.tp_cloud_audio, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_100", org.telegram.messenger.R.string.TP_L10N_100), 0xffdff4ed);
        addCategory(grid, "archives", R.drawable.tp_cloud_archive, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_101", org.telegram.messenger.R.string.TP_L10N_101), 0xffeadcff);
        addCategory(grid, "apk", R.drawable.tp_cloud_apk, "APK", 0xffdff0ff);
        addCategory(grid, "favorites", R.drawable.tp_cloud_favorite, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_102", org.telegram.messenger.R.string.TP_L10N_102), 0xffffecd5);
        addCategory(grid, "trash", R.drawable.tp_cloud_trash, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_103", org.telegram.messenger.R.string.TP_L10N_103), 0xffeceff2);
        addCategory(grid, "all", R.drawable.tp_cloud_all_files, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_104", org.telegram.messenger.R.string.TP_L10N_104), 0xffdff0ff);
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
        TextView value = label(protectedCategory ? org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_105", org.telegram.messenger.R.string.TP_L10N_105) : compactCount(collectCategoryNodes(kind).size()), 12, false); value.setSingleLine(true); value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        text.addView(name); text.addView(value); card.addView(text, new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v -> TelePremiumCloudAccessGate.openCategory(this, selectedAccount, kind, () -> { activeCategory = kind; renderBody(); }));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width=0; lp.height=AndroidUtilities.dp(72); lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f); lp.setMargins(AndroidUtilities.dp(3),AndroidUtilities.dp(4),AndroidUtilities.dp(3),AndroidUtilities.dp(4));
        grid.addView(card,lp);
    }

    private void addUserFolders() {
        addSectionTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_106", org.telegram.messenger.R.string.TP_L10N_106), null);
        addLockToolbar();
        List<AccountNode> folders = collectFolders();
        if (folders.isEmpty()) {
            TextView empty = label(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_107", org.telegram.messenger.R.string.TP_L10N_107), 14, false);
            empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(AndroidUtilities.dp(4), 0, 0, 0);
            body.addView(empty, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(54)));
            return;
        }
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(3);
        body.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        for (AccountNode item : folders) addFolderCard(grid, item);
        // An empty grid column has no weight, so a lone folder would stretch over the full row:
        // pad the last row with invisible cells to keep every tile exactly one third wide.
        for (int pad = (3 - folders.size() % 3) % 3; pad > 0; pad--) {
            View filler = new View(context);
            filler.setVisibility(View.INVISIBLE);
            GridLayout.LayoutParams flp = new GridLayout.LayoutParams();
            flp.width = 0; flp.height = AndroidUtilities.dp(72);
            flp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            flp.setMargins(AndroidUtilities.dp(3), AndroidUtilities.dp(4), AndroidUtilities.dp(3), AndroidUtilities.dp(4));
            grid.addView(filler, flp);
        }
    }

    /** Entry points of folder protection right under «Папки». */
    private void addLockToolbar() {
        if (VaultFolderSession.shared().unlockedIds().isEmpty()) return;
        TextView lockAll = label("Закрыть все", 14, false);
        lockAll.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        lockAll.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        lockAll.setPadding(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2), 0);
        lockAll.setOnClickListener(v -> { VaultSessionGuard.lockEverything(); renderBody(); });
        body.addView(lockAll, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(34)));
    }

    private void addFolderCard(GridLayout grid, AccountNode item) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(2), AndroidUtilities.dp(8));
        card.setBackground(outlinedCard());
        ImageView badge = new ImageView(context);
        badge.setImageResource(R.drawable.tp_cloud_folder);
        badge.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        badge.setBackground(roundRect(visualTheme.searchBackground, 11));
        card.addView(badge, new LinearLayout.LayoutParams(AndroidUtilities.dp(42), AndroidUtilities.dp(42)));
        final boolean closedLock = TelePremiumCloudManager.getInstance(item.account).view().isClosedLock(item.node);
        final boolean hasLock = item.node.lock != null && !item.node.lockOff;
        String shownName = closedLock && VaultFolderLocks.hideLockedNames() ? "Закрытая папка" : item.node.name;
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(AndroidUtilities.dp(8), 0, 0, 0);
        TextView name = label((hasLock ? (closedLock ? "🔒" : "🔓") : "") + shownName, 13, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView value = label(closedLock ? "Закрыта" : compactCount(TelePremiumCloudManager.getInstance(item.account).countFiles(item.node.id)), 12, false);
        value.setSingleLine(true);
        value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        text.addView(name);
        text.addView(value);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = label("\u22EE", 22, false);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> showNodeMenu(item));
        card.addView(more, new LinearLayout.LayoutParams(AndroidUtilities.dp(26), AndroidUtilities.dp(56)));
        card.setOnClickListener(v -> openNode(item));
        card.setOnLongClickListener(v -> { showNodeMenu(item); return true; });
        card.setOnDragListener((v, event) -> handleFolderDrop(card, item, event));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0; lp.height = AndroidUtilities.dp(72);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        lp.setMargins(AndroidUtilities.dp(3), AndroidUtilities.dp(4), AndroidUtilities.dp(3), AndroidUtilities.dp(4));
        grid.addView(card, lp);
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
        ImageView icon;
        if(CloudImageNames.isImage(node))icon=new org.telegram.ui.Components.TelePremiumCloudThumbnailView(context,item.account,node,fileIcon(node),visualTheme.searchBackground);
        else{icon=new ImageView(context);icon.setImageResource(node.type==CloudNode.TYPE_FOLDER?R.drawable.tp_cloud_folder:fileIcon(node));icon.setPadding(AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12),AndroidUtilities.dp(12));icon.setBackground(roundRect(visualTheme.searchBackground,10));}
        row.addView(icon,new LinearLayout.LayoutParams(AndroidUtilities.dp(50),AndroidUtilities.dp(50)));
        LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(AndroidUtilities.dp(12),0,AndroidUtilities.dp(8),0);
        TextView name=label(node.name,16,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);TextView detail=label(node.type==CloudNode.TYPE_FOLDER?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_108", org.telegram.messenger.R.string.TP_L10N_108):(node.isMultipart()?humanSize(node.size)+" · "+org.telegram.messenger.telepremium.TPStrings.format("TP_CLOUD_PARTS_FMT", org.telegram.messenger.R.string.TP_CLOUD_PARTS_FMT, node.partCount()):humanSize(node.size)),13,false);detail.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));labels.addView(name);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
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
                if(TelePremiumCloudManager.getInstance(folder.account).view().isClosedLock(folder.node)){setSyncText("Папка закрыта: сначала откройте её",false);return true;}
                if(source.account!=folder.account){setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_109", org.telegram.messenger.R.string.TP_L10N_109),false);return true;}
                TelePremiumCloudManager.getInstance(source.account).move(source.node,folder.node.id,e->{finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_110", org.telegram.messenger.R.string.TP_L10N_110));if(e==null)VaultRekeyJob.kick(source.account,null);});
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                card.setAlpha(1f); return true;
        }
        return true;
    }



    private List<AccountNode> previewSiblings(){
        String query=search==null?"":search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        List<AccountNode> nodes=!query.isEmpty()?collectSearchNodes(query):activeCategory!=null?collectCategoryNodes(activeCategory):collectNodes();
        if(activeCategory!=null&&query.isEmpty())Collections.sort(nodes,(a,b)->Long.compare(b.node.modifiedAt,a.node.modifiedAt));return nodes;
    }
    private org.telegram.messenger.telepremium.media.TelePremiumMediaItem previewMedia(AccountNode entry){
        org.telegram.messenger.telepremium.media.TelePremiumMediaItem media=org.telegram.messenger.telepremium.media.TelePremiumMediaItem.cloud(entry.account,entry.node.id,entry.node.telegramMessageId,entry.node.telegramDocumentId,entry.node.name,resolvedMime(entry.node),0,entry.node.size);
        media.cloudUserId=UserConfig.getInstance(entry.account).getClientUserId();media.cloudEpoch=org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState.epoch();media.encryptionVersion=entry.node.encryptionVersion;media.sha256=entry.node.sha256;media.localBackupId=entry.node.localBackupId;media.setParts(entry.node);media.ephemeral=TelePremiumCloudManager.getInstance(entry.account).isProtected(entry.node);return media;
    }

    private void openNode(AccountNode item) {
        if (item.node.type == CloudNode.TYPE_FILE && !TelePremiumCloudAccessGate.canExposeNode(context, item.node)) {
            String protectedCategory = TelePremiumCloudSecurityStore.categoryForNode(item.node);
            TelePremiumCloudAccessGate.openCategory(this, item.account, protectedCategory, () -> openNode(item));
            return;
        }
        TelePremiumCloudSecuritySession.touch(context);
        if (item.node.type == CloudNode.TYPE_FOLDER) {
            if (TelePremiumCloudManager.getInstance(item.account).view().isClosedLock(item.node)) {
                // A closed folder never loads its contents: the unlock screen comes first.
                presentFragment(new TelePremiumFolderLockActivity(item.account, item.node.id, TelePremiumFolderLockActivity.MODE_UNLOCK,
                        () -> presentFragment(new TelePremiumCloudActivity(item.account, item.node.id, item.node.name))));
                return;
            }
            presentFragment(new TelePremiumCloudActivity(item.account, item.node.id, item.node.name));
            return;
        }

        String mime = resolvedMime(item.node);
        quietPreviewDownload = true;
        if (transferStrip != null) transferStrip.setVisibility(View.GONE);
        if (CloudImageNames.isImage(item.node)) {
            try {
                ArrayList<TelePremiumCloudGalleryActivity.Entry> images = new ArrayList<>();int selected=0;boolean found=false;
                for(AccountNode candidate:previewSiblings())if(candidate.node.type==CloudNode.TYPE_FILE&&CloudImageNames.isImage(candidate.node)){
                    if(candidate.account==item.account&&candidate.node.id.equals(item.node.id)){selected=images.size();found=true;}
                    images.add(new TelePremiumCloudGalleryActivity.Entry(candidate.account,candidate.node));
                }
                if(!found){selected=images.size();images.add(new TelePremiumCloudGalleryActivity.Entry(item.account,item.node));}
                TelePremiumCloudGalleryActivity.open(getParentActivity(),images,selected);
            } catch(Exception e){setSyncText("Не удалось открыть изображения",false);}
            return;
        }
        if (TelePremiumPlayerActivity.canHandle(item.node.name,mime)) {
            ArrayList<org.telegram.messenger.telepremium.media.TelePremiumMediaItem> media=new ArrayList<>();int selected=0;boolean found=false;
            org.telegram.messenger.telepremium.media.TelePremiumMediaItem anchor=previewMedia(item);
            for(AccountNode candidate:previewSiblings())if(candidate.node.type==CloudNode.TYPE_FILE&&TelePremiumPlayerActivity.canHandle(candidate.node.name,resolvedMime(candidate.node))) {
                org.telegram.messenger.telepremium.media.TelePremiumMediaItem entry=previewMedia(candidate);
                if(entry.isVideo()!=anchor.isVideo())continue;
                if(candidate.account==item.account&&candidate.node.id.equals(item.node.id)){selected=media.size();found=true;}
                media.add(entry);
            }
            if(!found){selected=media.size();media.add(anchor);}
            org.telegram.messenger.telepremium.media.TelePremiumPlaybackController player=org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.getInstance();
            player.getQueue().replace(media,selected);player.playCurrent();TelePremiumPlayerActivity.openCurrent(getParentActivity());return;
        }

        attachTransferManager(item.account);
        new CloudDownloadManager(item.account).download(item.node, (state, file, error) -> {
            if (disposed) return;
            if (error != null) {
                setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_111", org.telegram.messenger.R.string.TP_L10N_111) + error, false);
                return;
            }
            if (state == CloudDownloadManager.DOWNLOADING) {
                // Download/verification continues, but opening is visually quiet.
                return;
            }
            if (disposed || getParentActivity() == null) return;
            if (file == null || !file.exists()) {
                setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_112", org.telegram.messenger.R.string.TP_L10N_112), false);
                return;
            }
            try {
                // AES-GCM verified plaintext must be played by our own player,
                // NOT routed to ACTION_VIEW / FileProvider / a raw .tpv stream.
                if (TelePremiumPlayerActivity.canHandle(item.node.name, mime)) {
                    TelePremiumPlayerActivity.open(getParentActivity(), file, item.node.name, mime);
                } else if (!AndroidUtilities.openForView(file, item.node.name, mime,
                        getParentActivity(), getResourceProvider(), false)) {
                    setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_113", org.telegram.messenger.R.string.TP_L10N_113), false);
                }
            } catch (RuntimeException viewerError) {
                org.telegram.messenger.FileLog.e(viewerError);
                setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_114", org.telegram.messenger.R.string.TP_L10N_114) + viewerError.getClass().getSimpleName(), false);
            }
        });
    }

    private void saveToDevice(AccountNode item){
        quietPreviewDownload = false;
        attachTransferManager(item.account);
        new CloudDownloadManager(item.account).download(item.node,(state,file,error)->{
            if(error!=null){setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_115", org.telegram.messenger.R.string.TP_L10N_115)+error,false);return;}
            if(state==CloudDownloadManager.DOWNLOADING){setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_116", org.telegram.messenger.R.string.TP_L10N_116),false);return;}
            if(file==null || !file.exists()){setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_112", org.telegram.messenger.R.string.TP_L10N_112),false);return;}
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
                    new CharSequence[]{org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_117", org.telegram.messenger.R.string.TP_L10N_117),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_118", org.telegram.messenger.R.string.TP_L10N_118),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_119", org.telegram.messenger.R.string.TP_L10N_119)},
                    new int[]{R.drawable.tp_cloud_download,R.drawable.tp_cloud_restore,R.drawable.tp_cloud_delete_forever},
                    (d,w)->{if(w==0)openNode(item);else if(w==1)manager.restore(node,e->finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_120", org.telegram.messenger.R.string.TP_L10N_120)));else confirmPermanentDelete(manager,node);}
            ).create().show();
            return;
        }
        if(node.type==CloudNode.TYPE_FILE){
            new BottomSheet.Builder(context).setTitle(node.name).setItems(
                    new CharSequence[]{org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_117", org.telegram.messenger.R.string.TP_L10N_117),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_121", org.telegram.messenger.R.string.TP_L10N_121),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_122", org.telegram.messenger.R.string.TP_L10N_122),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_123", org.telegram.messenger.R.string.TP_L10N_123),node.favorite?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_124", org.telegram.messenger.R.string.TP_L10N_124):org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_125", org.telegram.messenger.R.string.TP_L10N_125),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_126", org.telegram.messenger.R.string.TP_L10N_126)},
                    new int[]{R.drawable.tp_cloud_download,R.drawable.tp_cloud_download,R.drawable.tp_cloud_folder,R.drawable.tp_cloud_rename,R.drawable.tp_cloud_favorite,R.drawable.tp_cloud_trash},
                    (d,w)->{if(w==0)openNode(item);else if(w==1)saveToDevice(item);else if(w==2)chooseMoveFolder(item);else if(w==3)rename(manager,node);else if(w==4)manager.toggleFavorite(node,e->finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_127", org.telegram.messenger.R.string.TP_L10N_127)));else manager.trash(node,e->finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_128", org.telegram.messenger.R.string.TP_L10N_128)));}
            ).create().show();
        }else{
            showFolderMenu(item,manager,node);
        }
    }

    private void showFolderMenu(AccountNode item,TelePremiumCloudManager manager,CloudNode node){
        final boolean hasLock=node.lock!=null&&!node.lockOff;
        final boolean open=VaultFolderLocks.isOpen(node);
        ArrayList<CharSequence> labels=new ArrayList<>();ArrayList<Integer> icons=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        labels.add(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_117", org.telegram.messenger.R.string.TP_L10N_117));icons.add(R.drawable.tp_cloud_folder);actions.add(()->openNode(item));
        if(!hasLock){
            final VaultSecretPolicy.Kind[] kinds={VaultSecretPolicy.Kind.PIN,VaultSecretPolicy.Kind.PASSWORD,VaultSecretPolicy.Kind.PATTERN};
            final String[] names={"🔒 Закрыть PIN-кодом","🔒 Закрыть паролем","🔒 Закрыть графическим ключом"};
            for(int i=0;i<kinds.length;i++){
                final VaultSecretPolicy.Kind k=kinds[i];
                labels.add(names[i]);icons.add(R.drawable.tp_cloud_folder);
                actions.add(()->presentFragment(new TelePremiumFolderLockActivity(item.account,node.id,TelePremiumFolderLockActivity.MODE_PROTECT,k,this::renderBody)));
            }
        }else{
            if(open){labels.add("🔒 Закрыть сейчас");icons.add(R.drawable.tp_cloud_folder);actions.add(()->{VaultFolderLocks.lockNow(node);renderBody();});}
            labels.add("Сменить секрет");icons.add(R.drawable.tp_cloud_folder);
            actions.add(()->presentFragment(new TelePremiumFolderLockActivity(item.account,node.id,TelePremiumFolderLockActivity.MODE_CHANGE,this::renderBody)));
            labels.add("Отпечаток, автоблокировка, снять защиту");icons.add(R.drawable.tp_cloud_folder);
            actions.add(()->presentFragment(new TelePremiumFolderLockSettingsActivity(item.account)));
        }
        labels.add(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_123", org.telegram.messenger.R.string.TP_L10N_123));icons.add(R.drawable.tp_cloud_rename);actions.add(()->rename(manager,node));
        labels.add(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_126", org.telegram.messenger.R.string.TP_L10N_126));icons.add(R.drawable.tp_cloud_trash);
        actions.add(()->{
            if(node.lock!=null&&!open){setSyncText("Папка закрыта: сначала откройте её",false);return;}
            manager.trash(node,e->finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_128", org.telegram.messenger.R.string.TP_L10N_128)));
        });
        int[] iconArray=new int[icons.size()];for(int i=0;i<iconArray.length;i++)iconArray[i]=icons.get(i);
        new BottomSheet.Builder(context).setTitle(node.name).setItems(labels.toArray(new CharSequence[0]),iconArray,(d,w)->actions.get(w).run()).create().show();
    }

    private void chooseMoveFolder(AccountNode item) {
        presentFragment(new TelePremiumCloudFolderPickerActivity(item.account,"root",org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_129", org.telegram.messenger.R.string.TP_L10N_129),(id,name)->
                {
                    CloudNode target=TelePremiumCloudManager.getInstance(item.account).findNodeById(id);
                    if(target!=null&&TelePremiumCloudManager.getInstance(item.account).view().isClosedLock(target)){setSyncText("Папка закрыта: сначала откройте её",false);return;}
                    TelePremiumCloudManager.getInstance(item.account).move(item.node,id,e->{finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_130", org.telegram.messenger.R.string.TP_L10N_130)+name+"»");if(e==null)VaultRekeyJob.kick(item.account,null);});
                }));
    }

    private void confirmPermanentDelete(TelePremiumCloudManager manager, CloudNode node) {
        new AlertDialog.Builder(context)
                .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_131", org.telegram.messenger.R.string.TP_L10N_131))
                .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_132", org.telegram.messenger.R.string.TP_L10N_132))
                .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_133", org.telegram.messenger.R.string.TP_L10N_133),(d,w)->manager.deleteMetadataPermanently(node,e->finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_134", org.telegram.messenger.R.string.TP_L10N_134))))
                .setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null)
                .show();
    }

    private void finishMutation(String error,String success) {
        setSyncText(error==null?"✓  "+success:org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_136", org.telegram.messenger.R.string.TP_L10N_136)+error,error==null);
        renderBody();
    }

    private void rename(TelePremiumCloudManager manager, CloudNode node) {
        EditText input=new EditText(context);input.setSingleLine(true);input.setText(node.name);
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_123", org.telegram.messenger.R.string.TP_L10N_123)).setView(input).setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_137", org.telegram.messenger.R.string.TP_L10N_137),(d,w)->{String n=input.getText().toString().trim();if(!n.isEmpty())manager.rename(node,n,e->finishMutation(e,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_138", org.telegram.messenger.R.string.TP_L10N_138)));}).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
    }

    private void showAddMenu() {
        new BottomSheet.Builder(context)
                .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_139", org.telegram.messenger.R.string.TP_L10N_139))
                .setItems(
                        new CharSequence[]{org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_140", org.telegram.messenger.R.string.TP_L10N_140),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_141", org.telegram.messenger.R.string.TP_L10N_141)},
                        new int[]{R.drawable.tp_cloud_upload,R.drawable.tp_cloud_new_folder},
                        (d,w)->chooseTargetAccount(w==0)
                )
                .create().show();
    }

    private void chooseTargetAccount(boolean upload) {
        if(selectedAccount>=0){beginCloudMutation(selectedAccount,upload);return;}
        List<Integer> accounts=activatedAccounts();
        if(accounts.isEmpty()){setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_092", org.telegram.messenger.R.string.TP_L10N_092),false);return;}
        CharSequence[] names=new CharSequence[accounts.size()];for(int i=0;i<accounts.size();i++)names[i]=accountName(accounts.get(i));
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_142", org.telegram.messenger.R.string.TP_L10N_142)).setItems(names,(d,w)->beginCloudMutation(accounts.get(w),upload)).show();
    }

    private void beginCloudMutation(int account, boolean upload) {
        quietPreviewDownload = false;
        if (org.telegram.messenger.telepremium.cloud.vault.TelePremiumVaultKeyManager.isEnabled(context)
                && !TelePremiumCloudManager.getInstance(account).isRemoteReady()) {
            new AlertDialog.Builder(context)
                    .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_143", org.telegram.messenger.R.string.TP_L10N_143))
                    .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_144", org.telegram.messenger.R.string.TP_L10N_144))
                    .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_145", org.telegram.messenger.R.string.TP_L10N_145),null).show();
            return;
        }
        if (upload && TelePremiumVaultKeyManager.isEnabled(context)) {
            CloudTransferManager.Task t=CloudTransferManager.getInstance(account).getTask();
            if(t!=null && t.type==CloudTransferManager.TYPE_UPLOAD &&
                   t.state!=CloudTransferManager.COMPLETED && t.state!=CloudTransferManager.CANCELLED &&
                   (t.state==CloudTransferManager.UPLOADING || t.state==CloudTransferManager.INDEXING ||
                    (t.state==CloudTransferManager.FAILED_RETRYABLE && t.messageId>0))) {
                new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_146", org.telegram.messenger.R.string.TP_L10N_146))
                        .setMessage(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_147", org.telegram.messenger.R.string.TP_L10N_147))
                        .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_145", org.telegram.messenger.R.string.TP_L10N_145),null).show();return;
            }
        }
        if (!upload) { createFolder(account); return; }
        if (!uploadQueue.isEmpty()) { setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_148", org.telegram.messenger.R.string.TP_L10N_148)+uploadQueueDone+org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_149", org.telegram.messenger.R.string.TP_L10N_149)+uploadQueueTotal,false); return; }
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_150", org.telegram.messenger.R.string.TP_L10N_150))
                .setItems(new CharSequence[]{org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_151", org.telegram.messenger.R.string.TP_L10N_151),org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_152", org.telegram.messenger.R.string.TP_L10N_152)},(d,w)->{ if(w==0)pickFile(account); else pickFolder(account); })
                .show();
    }

    private void pickFile(int account) {
        pendingUploadAccount=account;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_FILE);
    }

    private void pickFolder(int account) {
        pendingUploadAccount=account;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_FOLDER);
    }

    private void enqueueUploads(int account, java.util.List<Uri> uris) {
        if (uris.isEmpty()) { setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_153", org.telegram.messenger.R.string.TP_L10N_153),false); return; }
        uploadQueue.addAll(uris);
        uploadQueueAccount = account;
        uploadQueueTotal = uploadQueue.size();
        uploadQueueDone = 0;
        uploadNextFromQueue();
    }

    private void uploadNextFromQueue() {
        if (disposed) { uploadQueue.clear(); return; }
        Uri next = uploadQueue.poll();
        if (next == null) {
            if (uploadQueueTotal > 1) setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_154", org.telegram.messenger.R.string.TP_L10N_154)+uploadQueueDone,true);
            uploadQueueTotal = 0;
            return;
        }
        if (uploadQueueTotal > 1) setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_155", org.telegram.messenger.R.string.TP_L10N_155)+(uploadQueueDone+1)+org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_149", org.telegram.messenger.R.string.TP_L10N_149)+uploadQueueTotal+"…",false);
        uploadSingle(uploadQueueAccount, next, () -> { uploadQueueDone++; uploadNextFromQueue(); });
    }

    private void createFolder(int account) {
        EditText input=new EditText(context);input.setHint(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_156", org.telegram.messenger.R.string.TP_L10N_156));input.setSingleLine(true);
        new AlertDialog.Builder(context).setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_157", org.telegram.messenger.R.string.TP_L10N_157)).setView(input).setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_158", org.telegram.messenger.R.string.TP_L10N_158),(d,w)->{String n=input.getText().toString().trim();if(!n.isEmpty())TelePremiumCloudManager.getInstance(account).createFolder(folderId,n,e->renderBody());}).setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135),null).show();
    }

    @Override public void onActivityResultFragment(int requestCode,int resultCode,Intent data){
        super.onActivityResultFragment(requestCode,resultCode,data);
        if(requestCode==SAVE_FILE){
            if(resultCode==Activity.RESULT_OK&&data!=null&&data.getData()!=null&&pendingExportFile!=null)exportFile(data.getData());
            else{pendingExportFile=null;pendingExportName=null;}
            return;
        }
        if(requestCode==PICK_FOLDER){
            if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null)return;
            final Uri tree=data.getData();
            final int account=pendingUploadAccount;
            try{getParentActivity().getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception e){org.telegram.messenger.FileLog.e(e);}
            final android.content.ContentResolver resolver=getParentActivity().getContentResolver();
            setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_159", org.telegram.messenger.R.string.TP_L10N_159),false);
            Utilities.globalQueue.postRunnable(()->{
                java.util.ArrayList<Uri> files=new java.util.ArrayList<>();
                TelePremiumCloudFolderScanner.collect(resolver,tree,MAX_BATCH_FILES,files);
                AndroidUtilities.runOnUIThread(()->enqueueUploads(account,files));
            });
            return;
        }
        if(requestCode!=PICK_FILE||resultCode!=Activity.RESULT_OK||data==null)return;
        java.util.ArrayList<Uri> picked=new java.util.ArrayList<>();
        if(data.getClipData()!=null){
            for(int i=0;i<data.getClipData().getItemCount()&&picked.size()<MAX_BATCH_FILES;i++){Uri u=data.getClipData().getItemAt(i).getUri();if(u!=null)picked.add(u);}
        } else if(data.getData()!=null) picked.add(data.getData());
        for(Uri u:picked){try{getParentActivity().getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignore){}}
        enqueueUploads(pendingUploadAccount,picked);
    }

    private void uploadSingle(final int uploadAccount, Uri uri, Runnable onUploaded) {
        // Bind this upload to the chosen account. Never commit its metadata
        // into a different Cloud if the user switches accounts while uploading.
        final TelePremiumCloudManager uploadCloud = TelePremiumCloudManager.getInstance(uploadAccount);
        if(TelePremiumVaultKeyManager.isEnabled(context) && !uploadCloud.isRemoteReady()) {
            setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_160", org.telegram.messenger.R.string.TP_L10N_160),false);
            uploadQueue.clear();
            return;
        }
        final CloudPayloadRepository uploadPayloads = new CloudPayloadRepository(uploadAccount);
        uploadPayloads.setDedupLookup(sha256 -> uploadCloud.findByHash(sha256));
        final String uploadFolder = folderId;
        final String mime = getParentActivity().getContentResolver().getType(uri);
        final String name = displayName(uri);
        setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_161", org.telegram.messenger.R.string.TP_L10N_161),false);
        uploadPayloads.importUri(uri,uploadFolder,name,mime,(node,error)->{
            if(error!=null){
                if(!disposed && selectedAccount==uploadAccount) setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_162", org.telegram.messenger.R.string.TP_L10N_162)+name+"»: "+error,false);
                uploadQueue.clear();
                return;
            }
            if(TelePremiumVaultKeyManager.isEnabled(context)) {
                commitUploadedVaultNode(uploadAccount,node,uploadCloud,
                        CloudTransferManager.getInstance(uploadAccount),onUploaded);
            } else {
                uploadCloud.addFile(node,e->{
                    if(!disposed && (selectedAccount==uploadAccount || selectedAccount==-1)) {
                        setSyncText(e==null?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_089", org.telegram.messenger.R.string.TP_L10N_089):org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_079", org.telegram.messenger.R.string.TP_L10N_079)+fullError(e),e==null);
                        renderBody();
                    }
                    if(e==null) onUploaded.run(); else uploadQueue.clear();
                });
            }
        });
    }

    private void exportFile(Uri target){
        final File source=pendingExportFile;final String name=pendingExportName;
        pendingExportFile=null;pendingExportName=null;setSyncText(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_163", org.telegram.messenger.R.string.TP_L10N_163),false);
        Utilities.globalQueue.postRunnable(()->{
            String error=null;
            try(FileInputStream input=new FileInputStream(source);OutputStream output=context.getContentResolver().openOutputStream(target,"w")){
                if(output==null)throw new IllegalStateException(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_164", org.telegram.messenger.R.string.TP_L10N_164));
                byte[] buffer=new byte[64*1024];int read;while((read=input.read(buffer))!=-1)output.write(buffer,0,read);output.flush();
            }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final String result=error;AndroidUtilities.runOnUIThread(()->setSyncText(result==null?"✓  «"+name+"» сохранён":"Не удалось сохранить: "+result,result==null));
        });
    }

    private String resolvedMime(CloudNode node){
        return TelePremiumCloudFormat.resolveMime(node.name,node.mimeType,ext->MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
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
    private BackupImageView accountAvatar(int account,int size){TLRPC.User user=UserConfig.getInstance(account).getCurrentUser();BackupImageView avatar=new BackupImageView(context);avatar.setRoundRadius(AndroidUtilities.dp(Math.max(7,size/5)));avatar.getImageReceiver().setCurrentAccount(account);AvatarDrawable placeholder=new AvatarDrawable();if(user!=null){placeholder.setInfo(user);avatar.setForUserOrChat(user,placeholder);}else{placeholder.setInfo(account,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_002", org.telegram.messenger.R.string.TP_L10N_002)+(account+1),null);avatar.setImageDrawable(placeholder);}avatar.setContentDescription(accountName(account));return avatar;}
    private List<Integer> activatedAccounts(){ArrayList<Integer> out=new ArrayList<>();for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated())out.add(a);return out;}
    private String accountName(int account){TLRPC.User u=UserConfig.getInstance(account).getCurrentUser();return u==null?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_002", org.telegram.messenger.R.string.TP_L10N_002)+(account+1):UserObject.getFirstName(u);}
    private String accountInitial(int account){String n=accountName(account);return n.isEmpty()?"●":n.substring(0,1).toUpperCase();}
    private int count(List<AccountNode> list,String kind){int n=0;for(AccountNode i:list)if(matches(i.node,kind))n++;return n;}
    private boolean matches(CloudNode n,String kind){if(n.type!=CloudNode.TYPE_FILE)return false;String x=n.name==null?"":n.name.toLowerCase();String m=n.mimeType==null?"":n.mimeType;return "images".equals(kind)&&m.startsWith("image/")||"video".equals(kind)&&m.startsWith("video/")||"archives".equals(kind)&&(x.endsWith(".zip")||x.endsWith(".rar")||x.endsWith(".7z"))||"documents".equals(kind)&&(m.startsWith("text/")||x.endsWith(".pdf")||x.endsWith(".doc")||x.endsWith(".docx")||x.endsWith(".xls")||x.endsWith(".xlsx"));}
    private String categoryTitle(String kind){return TelePremiumCloudFormat.categoryTitle(kind);}
    private int fileIcon(CloudNode n){String name=n.name==null?"":n.name.toLowerCase();String m=n.mimeType==null?"":n.mimeType.toLowerCase();if(m.startsWith("image/"))return R.drawable.tp_cloud_photo;if(m.startsWith("video/"))return R.drawable.tp_cloud_video;if(m.startsWith("audio/"))return R.drawable.tp_cloud_audio;if(name.endsWith(".apk"))return R.drawable.tp_cloud_apk;if(name.endsWith(".zip")||name.endsWith(".rar")||name.endsWith(".7z")||name.endsWith(".tar")||name.endsWith(".gz"))return R.drawable.tp_cloud_archive;return R.drawable.tp_cloud_document;}
    private TextView label(String text,int size,boolean bold){TextView v=new TextView(context);v.setText(text);v.setTextSize(size);v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable roundRect(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(AndroidUtilities.dp(radius));return d;}
    private GradientDrawable outlinedCard(){GradientDrawable d=roundRect(visualTheme.surfaceElevated,12);d.setStroke(AndroidUtilities.dp(1),visualTheme.divider);return d;}
    private void addEmpty(){TextView empty=label(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_165", org.telegram.messenger.R.string.TP_L10N_165),15,false);empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));empty.setGravity(Gravity.CENTER);body.addView(empty,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(100)));}
    private String compactCount(int n){return TelePremiumCloudFormat.compactCount(n);}
    private String humanSize(long n){return TelePremiumCloudFormat.humanSize(n);}
    private static final class AccountNode{final int account;final CloudNode node;AccountNode(int account,CloudNode node){this.account=account;this.node=node;}}
}
