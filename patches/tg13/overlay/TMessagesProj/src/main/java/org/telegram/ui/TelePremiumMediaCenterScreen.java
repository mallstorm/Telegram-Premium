package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudFormat;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState;
import org.telegram.messenger.telepremium.media.TelePremiumMediaRequest;
import org.telegram.messenger.telepremium.media.TelePremiumArtworkLoader;
import org.telegram.messenger.telepremium.media.TelePremiumCloudMediaRepository;
import org.telegram.messenger.telepremium.media.TelePremiumMediaItem;
import org.telegram.messenger.telepremium.media.TelePremiumMediaStoreRepository;
import org.telegram.messenger.telepremium.media.TelePremiumNetworkLibrary;
import org.telegram.messenger.telepremium.media.TelePremiumNetworkPlaylistParser;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.media.TelePremiumPlaylistRepository;
import org.telegram.messenger.telepremium.media.TelePremiumRadioBrowser;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * TelePremium media library: Device, Cloud, Network (radio · IPTV · link) and Playlists.
 * Music and video are strictly separated: the Music filter never shows video and vice versa,
 * and the playback queue always contains one kind only.
 */
final class TelePremiumMediaCenterScreen extends android.content.ContextWrapper implements TelePremiumPlaybackController.Listener {
    private static final int REQUEST_MEDIA_PERMISSIONS = 7718;
    private static final int REQUEST_IPTV_FILE = 7720;
    private static final int REQUEST_PLAYLIST_FILE = 7721;
    private static final int SOURCE_PLAYLISTS = 3;
    private static final int NET_RADIO = 0, NET_IPTV = 1, NET_LINK = 2, NET_YT = 3;
    private static final int SORT_DEFAULT = 0, SORT_NAME = 1, SORT_DURATION = 2, SORT_SIZE = 3;
    interface Host {
        void close();
        void permissions(String[] permissions,int code);
        void pick(Intent intent,int code);
    }
    private final Activity activity;
    private final Host host;
    private final boolean embedded;
    private boolean disposed;
    private final java.util.ArrayList<android.app.Dialog> dialogs=new java.util.ArrayList<>();
    private AlertDialog.Builder dialog(){
        int theme=androidx.core.graphics.ColorUtils.calculateLuminance(pal.background)<.5?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert;
        return new AlertDialog.Builder(this,theme){
            @Override public AlertDialog create(){AlertDialog result=super.create();dialogs.add(result);result.setOnDismissListener(d->dialogs.remove(result));return result;}
        };
    }
    View contentView;
    TelePremiumMediaCenterScreen(Activity activity,Host host,boolean embedded){super(activity);this.activity=activity;this.host=host;this.embedded=embedded;}
    private static final int RESULT_OK=Activity.RESULT_OK;
    private void setContentView(View view){contentView=view;}
    private boolean isFinishing(){return disposed||activity.isFinishing();}
    private boolean isDestroyed(){return disposed||activity.isDestroyed();}
    private void finish(){host.close();}
    private void recreate(){activity.recreate();}
    private void requestPermissions(String[] permissions,int code){host.permissions(permissions,code);}
    private void startActivityForResult(Intent intent,int code){host.pick(intent,code);}
    private static final String PREFS = "telepremium_media_center";

    private final TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
    private final TelePremiumMediaStoreRepository deviceRepo = new TelePremiumMediaStoreRepository();
    private final TelePremiumCloudMediaRepository cloudRepo = new TelePremiumCloudMediaRepository();
    private final TelePremiumPlaylistRepository playlists = TelePremiumPlaylistRepository.getInstance();
    private final ArrayList<TelePremiumMediaItem> sourceItems = new ArrayList<>();
    private final ArrayList<TelePremiumMediaItem> visibleItems = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TelePremiumMediaTheme mediaTheme;
    private TelePremiumPlayerKit kit;
    private TelePremiumPlayerKit.Palette pal;
    private MediaAdapter adapter;
    private EditText search;
    private TextView sectionTitle, sectionCount, emptyView, miniTitle, miniSource;
    private ImageView miniPlay, miniArt, sortButton;
    private LinearLayout chipsRow, subChipsRow;
    private HorizontalScrollView subChipsScroll;
    private final LinearLayout[] sourceTabs = new LinearLayout[4];

    private int currentSource = TelePremiumMediaItem.SOURCE_DEVICE;
    private boolean videoFilter;
    private boolean queueMode;
    private int networkMode = NET_RADIO;
    private int sortMode = SORT_DEFAULT;
    private boolean radioFavoritesOnly;
    private String iptvUrl, iptvName, iptvGroup;
    private org.telegram.messenger.telepremium.media.TelePremiumYouTube.Feed ytFeed;
    private boolean ytLoading;
    private String sourceLabelText = "";
    private int loadGeneration;
    private long cloudUserFilter;
    private long cloudCatalogEpoch = -1;
    private TelePremiumMediaRequest sourceRequest;
    private TelePremiumMediaRequest auxiliaryRequest;
    private TelePremiumMediaVaultUnlock vaultUnlock;
    private TextView librarySyncStatus;
    private String lastLibraryState="";
    private long lastLibraryOwner;
    private final Runnable libraryChanged=()->{
        if(disposed)return;
        if(librarySyncStatus!=null)librarySyncStatus.setText(org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.status());
        try {
            long uid=org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.owner();
            if(lastLibraryOwner!=uid){
                if(lastLibraryOwner!=0&&currentSource==TelePremiumMediaItem.SOURCE_NETWORK){cancelSourceRequest();++loadGeneration;ytFeed=null;iptvUrl=null;ytLoading=false;setSource(Collections.emptyList(),"",null);}
                lastLibraryOwner=uid;
            }
            String signature=uid+"|"+(uid>0?org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.state(uid).toString():"");
            if(signature.equals(lastLibraryState))return;lastLibraryState=signature;
            if(contentView!=null){
                boolean remoteVideo=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("video",false);
                int remoteSort=getSharedPreferences(PREFS,MODE_PRIVATE).getInt("sort",SORT_DEFAULT);
                boolean filtering=videoFilter!=remoteVideo||sortMode!=remoteSort;videoFilter=remoteVideo;sortMode=remoteSort;
                if(filtering){refreshTabs();applyFilter();}rebuildChips();
            }
            if(ytFeed!=null&&!org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.hasYoutube(ytFeed.url)) {
                ytFeed=null;cancelSourceRequest();ytLoading=false;
                if(currentSource==TelePremiumMediaItem.SOURCE_NETWORK&&networkMode==NET_YT)setSource(Collections.emptyList(),"YouTube",null);
            }
        } catch(Exception ignored){}
    };
    private final Runnable vaultLockCheck = new Runnable() {
        @Override public void run() {
            if (currentSource == TelePremiumMediaItem.SOURCE_CLOUD && !sourceItems.isEmpty()
                    && (!TelePremiumCloudSecuritySession.isUnlocked(TelePremiumMediaCenterScreen.this)
                    || !TelePremiumCloudResetState.allowed(cloudCatalogEpoch))) {
                ++loadGeneration;
                setSource(Collections.emptyList(), t("TP_L10N_069", R.string.TP_L10N_069), t("TP_MEDIA_VAULT_LOCKED", R.string.TP_MEDIA_VAULT_LOCKED));
            }
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable radioSearch = this::runRadioSearch;

    void create(Bundle savedInstanceState) {
        mediaTheme = TelePremiumMediaTheme.from(this);
        mediaTheme.applySystemBars(activity);
        kit = new TelePremiumPlayerKit(this);
        pal = TelePremiumPlayerKit.Palette.surface(mediaTheme);
        videoFilter = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("video", false);
        sortMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("sort", SORT_DEFAULT);
        controller.addListener(this);
        buildUi();
        ensureMediaPermissionsAndLoadDevice();
        onMediaChanged(controller.getCurrent());
        onStateChanged(controller.getState());
    }

    void resume() {
        org.telegram.messenger.telepremium.media.TelePremiumPlaylistMigration.captureLocalFiles();
        org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.observe(libraryChanged);
        if (mediaTheme == null) return;
        String currentId = TelePremiumThemeController.getCurrent(this).id;
        if (!mediaTheme.preset.id.equals(currentId)) { recreate(); return; }
        handler.removeCallbacks(vaultLockCheck); handler.post(vaultLockCheck);
        if (currentSource == TelePremiumMediaItem.SOURCE_CLOUD) loadCloud();
    }

    void pause() {
        org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.unobserve(libraryChanged);
        handler.removeCallbacks(vaultLockCheck);
        if (currentSource == TelePremiumMediaItem.SOURCE_CLOUD) {
            ++loadGeneration;
            setSource(Collections.emptyList(), t("TP_L10N_069", R.string.TP_L10N_069), null);
        }

    }

    void destroy() {
        disposed = true;
        for(android.app.Dialog d:new java.util.ArrayList<>(dialogs))d.dismiss();dialogs.clear();
        org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.unobserve(libraryChanged);
        ++loadGeneration;
        cancelSourceRequest();
        if (vaultUnlock != null) vaultUnlock.close();
        handler.removeCallbacksAndMessages(null);
        controller.removeListener(this);

    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.background);
        root.setPadding(dp(14), dp(6), dp(14), dp(10) + (embedded ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(kit.iconButton(R.drawable.tp_ic_back, pal.text, 28, t("TP_L10N_413", R.string.TP_L10N_413), v -> finish()), new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(kit.text("TelePremium", 26, pal.text, true));
        titles.addView(kit.text(t("TP_L10N_304", R.string.TP_L10N_304), 14, pal.muted, false));
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titlesLp.leftMargin = dp(10);
        header.addView(titles, titlesLp);
        header.addView(kit.iconButton(R.drawable.tp_ic_folder, pal.accent, 26, t("TP_MC_FOLDERS", R.string.TP_MC_FOLDERS), v -> showLibraryFolders()), new LinearLayout.LayoutParams(dp(46), dp(50)));
        header.addView(kit.iconButton(R.drawable.tp_ic_playlist_add, pal.accent, 28, t("TP_L10N_305", R.string.TP_L10N_305), v -> saveQueueAsPlaylist()), new LinearLayout.LayoutParams(dp(50), dp(50)));
        header.addView(kit.iconButton(R.drawable.tp_ic_play, pal.accent, 32, t("TP_L10N_306", R.string.TP_L10N_306), v -> {
            if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(activity);
        }), new LinearLayout.LayoutParams(dp(50), dp(50)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(68)));
        librarySyncStatus=kit.text(org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.status(),12,pal.muted,false);
        librarySyncStatus.setSingleLine(true);librarySyncStatus.setEllipsize(TextUtils.TruncateAt.END);
        librarySyncStatus.setPadding(dp(12),dp(8),dp(12),dp(8));
        librarySyncStatus.setContentDescription(TPStrings.isRussian()?"Синхронизация медиатеки":"Media library sync");
        librarySyncStatus.setOnClickListener(v->showLibrarySync());
        root.addView(librarySyncStatus,new LinearLayout.LayoutParams(-1,dp(38)));


        // Source tabs
        LinearLayout tabs = new LinearLayout(this);
        int[] icons = {R.drawable.tp_ic_device, R.drawable.tp_ic_cloud, R.drawable.tp_ic_wifi, R.drawable.tp_ic_list};
        String[] labels = {t("TP_L10N_411", R.string.TP_L10N_411), t("TP_L10N_069", R.string.TP_L10N_069), t("TP_L10N_431", R.string.TP_L10N_431), t("TP_L10N_332", R.string.TP_L10N_332)};
        for (int i = 0; i < 4; i++) {
            final int source = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.addView(kit.icon(icons[i], pal.text), new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView label = kit.singleLine(kit.text(labels[i], 14, pal.text, true));
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
            labelLp.topMargin = dp(6);
            tab.addView(label, labelLp);
            tab.setOnClickListener(v -> selectSource(source));
            sourceTabs[i] = tab;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(82), 1f);
            lp.setMargins(i == 0 ? 0 : dp(5), 0, i == 3 ? 0 : dp(5), 0);
            tabs.addView(tab, lp);
        }
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, -2);
        tabsLp.topMargin = dp(6);
        root.addView(tabs, tabsLp);

        // Search
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(16), 0, dp(12), 0);
        searchBox.setBackground(TelePremiumPlayerKit.round(mediaTheme.searchBackground, pal.stroke, 26, 1));
        searchBox.addView(kit.icon(R.drawable.tp_ic_search, pal.muted), new LinearLayout.LayoutParams(dp(24), dp(24)));
        search = new EditText(this);
        search.setHint(t("TP_L10N_311", R.string.TP_L10N_311));
        search.setHintTextColor(TelePremiumPlayerKit.withAlpha(pal.muted, 0xc0));
        search.setTextColor(pal.text);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setBackground(null);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { onSearchChanged(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, -1, 1f);
        searchLp.leftMargin = dp(10);
        searchBox.addView(search, searchLp);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, dp(54));
        boxLp.topMargin = dp(14);
        root.addView(searchBox, boxLp);

        // Chips
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(this);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsScroll.addView(chipsRow, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(-1, -2);
        chipsLp.topMargin = dp(12);
        root.addView(chipsScroll, chipsLp);

        // Second row (Network only): actions and filters of the selected mode
        subChipsScroll = new HorizontalScrollView(this);
        subChipsScroll.setHorizontalScrollBarEnabled(false);
        subChipsRow = new LinearLayout(this);
        subChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        subChipsScroll.addView(subChipsRow, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(6);
        root.addView(subChipsScroll, subLp);
        subChipsScroll.setVisibility(View.GONE);

        // Section header
        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle = kit.singleLine(kit.text("", 17, pal.accent, true));
        section.addView(sectionTitle, new LinearLayout.LayoutParams(-2, -2));
        sectionCount = kit.singleLine(kit.text("", 15, pal.muted, false));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, -2, 1f);
        countLp.leftMargin = dp(6);
        section.addView(sectionCount, countLp);
        sortButton = kit.iconButton(R.drawable.tp_ic_sort, pal.accent, 24, t("TP_MC_SORT", R.string.TP_MC_SORT), v -> showSortMenu());
        section.addView(sortButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, dp(48));
        sectionLp.topMargin = dp(6);
        root.addView(section, sectionLp);

        // List + empty state
        FrameLayout listBox = new FrameLayout(this);
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        adapter = new MediaAdapter();
        list.setAdapter(adapter);
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));
        emptyView = kit.text("", 14, pal.muted, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(24), 0, dp(24), 0);
        emptyView.setVisibility(View.GONE);
        listBox.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(listBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(-1, dp(84));
        miniLp.topMargin = dp(8);
        root.addView(createMiniPlayer(), miniLp);
        setContentView(root);
        refreshTabs();
        rebuildChips();
    }

    private View createMiniPlayer() {
        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(12), 0, dp(10), 0);
        mini.setBackground(TelePremiumPlayerKit.round(mediaTheme.miniPlayerBackground, pal.stroke, 26, 1));
        FrameLayout art = new FrameLayout(this);
        art.setBackground(TelePremiumPlayerKit.round(mediaTheme.chipSelectedBackground, pal.stroke, 14, 0));
        art.addView(kit.icon(R.drawable.tp_ic_music, pal.accent), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        miniArt = new ImageView(this);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniArt.setClipToOutline(true);
        miniArt.setBackground(TelePremiumPlayerKit.round(Color.TRANSPARENT, Color.TRANSPARENT, 14, 0));
        miniArt.setVisibility(View.GONE);
        art.addView(miniArt, new FrameLayout.LayoutParams(-1, -1));
        mini.addView(art, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        miniTitle = kit.singleLine(kit.text(t("TP_L10N_317", R.string.TP_L10N_317), 16, pal.text, true));
        miniSource = kit.singleLine(kit.text("TelePremium Media", 13, pal.muted, false));
        labels.addView(miniTitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(-1, -2);
        srcLp.topMargin = dp(4);
        labels.addView(miniSource, srcLp);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        labelsLp.setMargins(dp(12), 0, dp(4), 0);
        mini.addView(labels, labelsLp);
        mini.addView(kit.iconButton(R.drawable.tp_ic_prev, pal.text, 26, t("TP_L10N_419", R.string.TP_L10N_419), v -> controller.previous()), new LinearLayout.LayoutParams(dp(44), dp(48)));
        miniPlay = kit.playButton(pal, 56, v -> controller.toggle());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        playLp.setMargins(dp(4), 0, dp(4), 0);
        mini.addView(miniPlay, playLp);
        mini.addView(kit.iconButton(R.drawable.tp_ic_next, pal.text, 26, t("TP_L10N_420", R.string.TP_L10N_420), v -> controller.next()), new LinearLayout.LayoutParams(dp(44), dp(48)));
        View sep = kit.divider(TelePremiumPlayerKit.withAlpha(pal.muted, 0x40), true);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(dp(1), dp(34));
        sepLp.setMargins(dp(4), 0, dp(2), 0);
        mini.addView(sep, sepLp);
        mini.addView(kit.iconButton(R.drawable.tp_ic_expand_less, pal.text, 26, t("TP_L10N_306", R.string.TP_L10N_306), v -> {
            if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(activity);
        }), new LinearLayout.LayoutParams(dp(42), dp(48)));
        mini.setOnClickListener(v -> { if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(activity); });
        return mini;
    }

    private void refreshTabs() {
        int selected = currentSource;
        for (int i = 0; i < sourceTabs.length; i++) {
            LinearLayout tab = sourceTabs[i];
            if (tab == null) continue;
            boolean on = i == selected;
            tab.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(on ? mediaTheme.chipSelectedBackground : mediaTheme.cardBackground,
                    on ? pal.accent : pal.stroke, 18, on ? 2 : 1), pal.accent));
            kit.setIcon((ImageView) tab.getChildAt(0), iconForSource(i), on ? pal.text : pal.muted);
            ((TextView) tab.getChildAt(1)).setTextColor(on ? pal.text : pal.muted);
        }
    }

    private int iconForSource(int source) {
        switch (source) {
            case 1: return R.drawable.tp_ic_cloud;
            case 2: return R.drawable.tp_ic_wifi;
            case 3: return R.drawable.tp_ic_list;
            default: return R.drawable.tp_ic_device;
        }
    }

    private TextView chip(int iconRes, String label, boolean selected, View.OnClickListener listener) {
        TextView chip = kit.singleLine(kit.text(label, 15, selected ? pal.text : pal.muted, selected));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(18), 0, dp(20), 0);
        if (iconRes != 0) {
            chip.setCompoundDrawablesWithIntrinsicBounds(kit.tinted(iconRes, selected ? pal.accent : pal.muted), null, null, null);
            chip.setCompoundDrawablePadding(dp(10));
        }
        chip.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(selected ? mediaTheme.chipSelectedBackground : mediaTheme.chipBackground,
                selected ? pal.accent : pal.stroke, 22, selected ? 2 : 1), pal.accent));
        chip.setOnClickListener(listener);
        return chip;
    }

    private void addChip(TextView chip) {
        sizeChip(chip, 14, 20, 9);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        lp.topMargin = lp.bottomMargin = dp(2);
        chipsRow.addView(chip, lp);
    }

    private void addSubChip(TextView chip) {
        sizeChip(chip, 13, 17, 7);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        lp.topMargin = lp.bottomMargin = dp(2);
        subChipsRow.addView(chip, lp);
    }

    /** Three equal segments that always fit the width: Radio | TV | YouTube. */
    private void addModeChip(TextView chip) {
        sizeChip(chip, 14, 19, 9);
        chip.setPadding(dp(6), dp(9), dp(8), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = chipsRow.getChildCount() == 0 ? 0 : dp(6);
        lp.topMargin = lp.bottomMargin = dp(2);
        chipsRow.addView(chip, lp);
    }

    /** Compact chip: height follows the text (never clipped), smaller icon. */
    private void sizeChip(TextView chip, float sp, int iconDp, int vPadDp) {
        chip.setTextSize(sp);
        chip.setIncludeFontPadding(true);
        chip.setPadding(chip.getPaddingLeft() > 0 ? dp(12) : dp(12), dp(vPadDp), dp(14), dp(vPadDp));
        chip.setCompoundDrawablePadding(dp(6));
        android.graphics.drawable.Drawable[] d = chip.getCompoundDrawables();
        if (d[0] != null) {
            d[0].setBounds(0, 0, dp(iconDp), dp(iconDp));
            chip.setCompoundDrawables(d[0], null, null, null);
        }
    }

    private void rebuildChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        if (chipsRow.getParent() instanceof HorizontalScrollView) ((HorizontalScrollView) chipsRow.getParent()).scrollTo(0, 0);
        subChipsRow.removeAllViews();
        subChipsScroll.scrollTo(0, 0);
        boolean network = currentSource == TelePremiumMediaItem.SOURCE_NETWORK;
        boolean cloudSource = currentSource == TelePremiumMediaItem.SOURCE_CLOUD;
        subChipsScroll.setVisibility(network || cloudSource ? View.VISIBLE : View.GONE);
        if (cloudSource) {
            addSubChip(chip(R.drawable.tp_ic_cloud, t("TP_MEDIA_ALL_ACCOUNTS", R.string.TP_MEDIA_ALL_ACCOUNTS), cloudUserFilter == 0, v -> { cloudUserFilter = 0; loadCloud(); }));
            for (int account : TelePremiumCloudMediaRepository.accounts()) {
                long user = UserConfig.getInstance(account).getClientUserId();
                addSubChip(chip(R.drawable.tp_ic_cloud, TelePremiumCloudMediaRepository.accountName(account), cloudUserFilter == user, v -> { cloudUserFilter = user; loadCloud(); }));
            }
            addSubChip(chip(R.drawable.tp_ic_expand, t("TP_MEDIA_REFRESH", R.string.TP_MEDIA_REFRESH), false, v -> loadCloud()));
        }
        // In Network mode the mode chips fill the row width; elsewhere chips wrap their text.
        View scroll = (View) chipsRow.getParent();
        if (scroll instanceof HorizontalScrollView) ((HorizontalScrollView) scroll).setFillViewport(network);
        if (network) {
            addModeChip(chip(R.drawable.tp_ic_radio, t("TP_NET_RADIO", R.string.TP_NET_RADIO), networkMode == NET_RADIO, v -> selectNetworkMode(NET_RADIO)));
            addModeChip(chip(R.drawable.tp_ic_tv, t("TP_NET_TV", R.string.TP_NET_TV), networkMode == NET_IPTV, v -> selectNetworkMode(NET_IPTV)));
            addModeChip(chip(R.drawable.tp_ic_play, "YouTube", networkMode == NET_YT, v -> selectNetworkMode(NET_YT)));
            if (networkMode == NET_YT) {
                addSubChip(chip(R.drawable.tp_ic_add, t("TP_YT_ADD", R.string.TP_YT_ADD), false, v -> addYoutubeChannel()));
                List<TelePremiumNetworkLibrary.IptvPlaylist> channels = TelePremiumNetworkLibrary.getYoutubeChannels();
                if (ytFeed != null) addSubChip(chip(R.drawable.tp_ic_list, ytFeed.title == null ? "YouTube" : ytFeed.title, true, v -> pickYoutubeChannel()));
                else if (!channels.isEmpty()) addSubChip(chip(R.drawable.tp_ic_list, t("TP_YT_CHANNELS", R.string.TP_YT_CHANNELS), false, v -> pickYoutubeChannel()));
                if (ytFeed != null && ytFeed.hasMore()) addSubChip(chip(R.drawable.tp_ic_expand, t("TP_YT_MORE", R.string.TP_YT_MORE), false, v -> loadMoreYoutube()));
                addSubChip(chip(R.drawable.tp_ic_tune, "≤ " + org.telegram.messenger.telepremium.media.TelePremiumYouTube.getMaxQuality() + "p", false, v -> pickYoutubeQuality()));
            } else if (networkMode == NET_RADIO) {
                String code = TelePremiumNetworkLibrary.getRadioCountry();
                String country = TextUtils.isEmpty(code) ? TelePremiumNetworkLibrary.getRadioCountryName()
                        : flag(code) + " " + countryName(new TelePremiumRadioBrowser.Country(code, TelePremiumNetworkLibrary.getRadioCountryName(), 0));
                addSubChip(chip(R.drawable.tp_ic_public, TextUtils.isEmpty(country) ? code : country, false, v -> pickRadioCountry()));
                addSubChip(chip(R.drawable.tp_ic_star, t("TP_L10N_102", R.string.TP_L10N_102), radioFavoritesOnly, v -> { radioFavoritesOnly = !radioFavoritesOnly; rebuildChips(); loadRadio(); }));
            } else {
                addSubChip(chip(R.drawable.tp_ic_add, t("TP_NET_ADD", R.string.TP_NET_ADD), false, v -> addIptvPlaylist()));
                if (iptvUrl != null) {
                    addSubChip(chip(R.drawable.tp_ic_list, iptvName == null ? "IPTV" : iptvName, true, v -> pickIptvPlaylist()));
                    addSubChip(chip(0, iptvGroup == null ? t("TP_NET_ALL_GROUPS", R.string.TP_NET_ALL_GROUPS) : iptvGroup, iptvGroup != null, v -> pickIptvGroup()));
                } else if (!TelePremiumNetworkLibrary.getIptvPlaylists().isEmpty()) {
                    addSubChip(chip(R.drawable.tp_ic_list, t("TP_L10N_332", R.string.TP_L10N_332), false, v -> pickIptvPlaylist()));
                }
            }
            return;
        }
        addChip(chip(R.drawable.tp_ic_music, t("TP_L10N_485", R.string.TP_L10N_485), !queueMode && !videoFilter, v -> setFilter(false)));
        addChip(chip(R.drawable.tp_ic_video, t("TP_L10N_098", R.string.TP_L10N_098), !queueMode && videoFilter, v -> setFilter(true)));
        addChip(chip(R.drawable.tp_ic_list, t("TP_L10N_465", R.string.TP_L10N_465), queueMode, v -> showQueue()));
    }

    // ------------------------------------------------------------------ sources

    private void selectSource(int source) {
        queueMode = false;
        cancelSourceRequest();
        ++loadGeneration;
        ytLoading = false;
        if (source == SOURCE_PLAYLISTS) { showPlaylistPicker(); return; }
        currentSource = source;
        if (search != null) search.setText("");
        setSource(Collections.emptyList(), "", null);
        refreshTabs();
        rebuildChips();
        if (source == TelePremiumMediaItem.SOURCE_CLOUD) loadCloud();
        else if (source == TelePremiumMediaItem.SOURCE_NETWORK) selectNetworkMode(networkMode);
        else ensureMediaPermissionsAndLoadDevice();
    }

    private void setFilter(boolean video) {
        if (queueMode) {
            queueMode = false;
            restoreSourceAfterQueue();
        }
        videoFilter = video;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("video", video).apply();
        rebuildChips();
        applyFilter();
    }

    private void restoreSourceAfterQueue() {
        if (currentSource == TelePremiumMediaItem.SOURCE_CLOUD) loadCloud();
        else if (currentSource == TelePremiumMediaItem.SOURCE_DEVICE) loadDevice();
    }

    private void ensureMediaPermissionsAndLoadDevice() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean audio = checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean video = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            if (!audio || !video) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO}, REQUEST_MEDIA_PERMISSIONS);
                return;
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_MEDIA_PERMISSIONS);
            return;
        }
        loadDevice();
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

        if (requestCode == REQUEST_MEDIA_PERMISSIONS) loadDevice();
    }

    private void loadDevice() {
        currentSource = TelePremiumMediaItem.SOURCE_DEVICE;
        refreshTabs();
        setSection(t("TP_L10N_411", R.string.TP_L10N_411), t("TP_L10N_316", R.string.TP_L10N_316));
        int gen = ++loadGeneration;
        deviceRepo.loadAll((items, error) -> {
            org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.registerDeviceItems(items);
            if (gen != loadGeneration) return;
            setSource(items, t("TP_L10N_411", R.string.TP_L10N_411), error);
        });
    }

    private void loadCloud() {
        if (isFinishing() || isDestroyed()) return;
        cancelSourceRequest();
        currentSource = TelePremiumMediaItem.SOURCE_CLOUD;
        refreshTabs();
        int selected = -1;
        for (int account : TelePremiumCloudMediaRepository.accounts())
            if (UserConfig.getInstance(account).getClientUserId() == cloudUserFilter) selected = account;
        if (selected < 0) cloudUserFilter = 0;
        rebuildChips();
        final int gen = ++loadGeneration;
        cloudCatalogEpoch = TelePremiumCloudResetState.epoch();
        emptyView.setOnClickListener(v -> { if (currentSource == TelePremiumMediaItem.SOURCE_CLOUD) loadCloud(); });
        if (!TelePremiumCloudResetState.allowed(TelePremiumCloudResetState.epoch())) {
            setSource(Collections.emptyList(), t("TP_L10N_069", R.string.TP_L10N_069), TelePremiumCloudResetState.MESSAGE); return;
        }
        if (!TelePremiumCloudSecuritySession.isUnlocked(this)) {
            setSource(Collections.emptyList(), t("TP_L10N_069", R.string.TP_L10N_069), t("TP_MEDIA_VAULT_LOCKED", R.string.TP_MEDIA_VAULT_LOCKED));
            unlockMediaVault(() -> { if (currentSource == TelePremiumMediaItem.SOURCE_CLOUD) loadCloud(); });
            return;
        }
        TelePremiumCloudSecuritySession.touch(this);
        setSource(Collections.emptyList(), t("TP_L10N_069", R.string.TP_L10N_069), null);
        setSection(t("TP_L10N_069", R.string.TP_L10N_069), t("TP_L10N_316", R.string.TP_L10N_316));
        cloudRepo.load(selected, (items, error) -> {
            if (isFinishing() || isDestroyed() || gen != loadGeneration || currentSource != TelePremiumMediaItem.SOURCE_CLOUD) return;
            setSource(items, t("TP_L10N_069", R.string.TP_L10N_069), error);
        });
    }

    private void unlockMediaVault(Runnable after) {
        if (vaultUnlock != null || isFinishing()) return;
        vaultUnlock = new TelePremiumMediaVaultUnlock(activity, unlocked -> {
            vaultUnlock = null;
            if (unlocked && !isFinishing() && !isDestroyed()) after.run();
        });
        vaultUnlock.show();
    }

    private void showLibrarySync() {
        ArrayList<Integer> accounts=new ArrayList<>(TelePremiumCloudMediaRepository.accounts());
        ArrayList<String> actions=new ArrayList<>();actions.add(TPStrings.isRussian()?"Синхронизировать сейчас":"Sync now");
        long owner=org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.owner();
        for(int account:accounts)actions.add((UserConfig.getInstance(account).getClientUserId()==owner?"✓ ":"")+TelePremiumCloudMediaRepository.accountName(account));
        dialog().setTitle(TPStrings.isRussian()?"Медиатека · облако синхронизации":"Media library · sync cloud")

                .setItems(actions.toArray(new String[0]),(d,which)->{
                    try{if(which>0){org.telegram.messenger.telepremium.media.TelePremiumMediaPreferences.flush();org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.setOwner(UserConfig.getInstance(accounts.get(which-1)).getClientUserId());}
                        if(TelePremiumCloudSecuritySession.isUnlocked(this))org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.start();
                        else unlockMediaVault(()->org.telegram.messenger.telepremium.media.TelePremiumMediaLibrarySync.start());
                        libraryChanged.run();
                    }catch(Exception e){Toast.makeText(this,TPStrings.isRussian()?"Не удалось выбрать облако":"Could not select cloud",Toast.LENGTH_LONG).show();}
                }).setNeutralButton(TPStrings.isRussian()?"О синхронизации":"About sync",(a,b)->dialog().setTitle("Vault V3")
                        .setMessage(TPStrings.isRussian()?"Каналы, плейлисты и избранное хранятся в зашифрованном облаке выбранного аккаунта. Выбор другого облака переносит туда текущую медиатеку. После переустановки восстановите существующий ключ в Настройки → Защита облака.":"Channels, playlists and favorites are stored in the selected account's encrypted cloud. Choosing another cloud moves the current library there. After reinstalling, restore the existing key in Settings → Cloud protection.")
                        .setPositiveButton("OK",null).show()).setNegativeButton(TPStrings.isRussian()?"Закрыть":"Close",null).show();
    }

    private void cancelSourceRequest() {
        if (sourceRequest != null) { sourceRequest.cancel(); sourceRequest = null; }
        if (auxiliaryRequest != null) { auxiliaryRequest.cancel(); auxiliaryRequest = null; }
        handler.removeCallbacks(radioSearch);
    }

    private void setSource(List<TelePremiumMediaItem> items, String label, String error) {
        sourceItems.clear();
        if (items != null) sourceItems.addAll(items);
        sourceLabelText = label;
        applyFilter();
        if (error != null) {
            if (sourceItems.isEmpty()) {
                emptyView.setText(tf("TP_NET_ERROR", R.string.TP_NET_ERROR, error)); emptyView.setVisibility(View.VISIBLE);
            } else {
                sectionCount.append(" · " + t("TP_MEDIA_PARTIAL", R.string.TP_MEDIA_PARTIAL));
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ library folders

    /** Which device folders feed the library: e.g. only "Music" for music and "Media" for video. */
    private void showLibraryFolders() {
        java.util.Set<String> audio = TelePremiumMediaStoreRepository.getFolders(false);
        java.util.Set<String> video = TelePremiumMediaStoreRepository.getFolders(true);
        String all = t("TP_MC_ALL_FOLDERS", R.string.TP_MC_ALL_FOLDERS);
        String[] items = {
                t("TP_MC_FOLDERS_MUSIC", R.string.TP_MC_FOLDERS_MUSIC) + "\n" + (audio.isEmpty() ? all : TextUtils.join(", ", audio)),
                t("TP_MC_FOLDERS_VIDEO", R.string.TP_MC_FOLDERS_VIDEO) + "\n" + (video.isEmpty() ? all : TextUtils.join(", ", video))};
        dialog().setTitle(t("TP_MC_FOLDERS", R.string.TP_MC_FOLDERS)).setItems(items, (d, which) -> pickFolders(which == 1)).show();
    }

    private void pickFolders(boolean video) {
        Toast.makeText(this, t("TP_L10N_316", R.string.TP_L10N_316), Toast.LENGTH_SHORT).show();
        TelePremiumMediaStoreRepository.loadFolders((audioFolders, videoFolders) -> {
            if (isFinishing()) return;
            java.util.LinkedHashMap<String, Integer> folders = video ? videoFolders : audioFolders;
            java.util.Set<String> selected = TelePremiumMediaStoreRepository.getFolders(video);
            // keep previously chosen folders visible even if they are empty now
            for (String f : selected) if (!folders.containsKey(f)) folders.put(f, 0);
            if (folders.isEmpty()) { Toast.makeText(this, t("TP_L10N_165", R.string.TP_L10N_165), Toast.LENGTH_SHORT).show(); return; }
            String[] names = folders.keySet().toArray(new String[0]);
            String[] labels = new String[names.length];
            boolean[] checked = new boolean[names.length];
            for (int i = 0; i < names.length; i++) {
                labels[i] = names[i] + "  ·  " + folders.get(names[i]);
                checked[i] = selected.contains(names[i]);
            }
            dialog()
                    .setTitle(video ? t("TP_MC_FOLDERS_VIDEO", R.string.TP_MC_FOLDERS_VIDEO) : t("TP_MC_FOLDERS_MUSIC", R.string.TP_MC_FOLDERS_MUSIC))
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton(t("TP_L10N_137", R.string.TP_L10N_137), (d, w) -> {
                        java.util.HashSet<String> result = new java.util.HashSet<>();
                        for (int i = 0; i < names.length; i++) if (checked[i]) result.add(names[i]);
                        TelePremiumMediaStoreRepository.setFolders(video, result);
                        if (currentSource == TelePremiumMediaItem.SOURCE_DEVICE) loadDevice();
                    })
                    .setNeutralButton(t("TP_MC_ALL_FOLDERS", R.string.TP_MC_ALL_FOLDERS), (d, w) -> {
                        TelePremiumMediaStoreRepository.setFolders(video, new java.util.HashSet<>());
                        if (currentSource == TelePremiumMediaItem.SOURCE_DEVICE) loadDevice();
                    })
                    .setNegativeButton(t("TP_L10N_135", R.string.TP_L10N_135), null)
                    .show();
        });
    }

    // ------------------------------------------------------------------ network: radio / IPTV / link

    private void selectNetworkMode(int mode) {
        cancelSourceRequest(); ++loadGeneration; ytLoading = false;
        if (mode == NET_LINK) mode = NET_IPTV;
        if (networkMode != mode && search != null) search.setText("");
        setSource(Collections.emptyList(), "", null);
        currentSource = TelePremiumMediaItem.SOURCE_NETWORK;
        networkMode = mode;
        refreshTabs();
        rebuildChips();
        search.setHint(mode == NET_RADIO ? t("TP_NET_RADIO_SEARCH", R.string.TP_NET_RADIO_SEARCH) : t("TP_L10N_311", R.string.TP_L10N_311));
        if (mode == NET_RADIO) loadRadio();
        else if (mode == NET_IPTV) {
            if (iptvUrl == null) {
                List<TelePremiumNetworkLibrary.IptvPlaylist> saved = TelePremiumNetworkLibrary.getIptvPlaylists();
                if (!saved.isEmpty()) { loadIptv(saved.get(0).name, saved.get(0).url); return; }
                setSource(Collections.emptyList(), "IPTV", null);
                emptyView.setText(t("TP_NET_IPTV_EMPTY", R.string.TP_NET_IPTV_EMPTY));
                emptyView.setVisibility(View.VISIBLE);
            } else {
                loadIptv(iptvName, iptvUrl);
            }
        } else if (mode == NET_YT) {
            if (ytFeed != null) {
                setSource(ytFeed.items, ytFeed.title, null);
            } else {
                List<TelePremiumNetworkLibrary.IptvPlaylist> saved = TelePremiumNetworkLibrary.getYoutubeChannels();
                if (!saved.isEmpty()) { loadYoutube(saved.get(0).url); return; }
                setSource(Collections.emptyList(), "YouTube", null);
                emptyView.setText(t("TP_YT_EMPTY", R.string.TP_YT_EMPTY));
                emptyView.setVisibility(View.VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ YouTube

    private void addYoutubeChannel() {
        EditText input = new EditText(this);
        input.setHint("https://www.youtube.com/@channel");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        dialog().setTitle(t("TP_YT_ADD_TITLE", R.string.TP_YT_ADD_TITLE)).setMessage(t("TP_YT_ADD_HINT", R.string.TP_YT_ADD_HINT)).setView(box)
                .setPositiveButton(t("TP_L10N_117", R.string.TP_L10N_117), (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) { TelePremiumNetworkLibrary.addYoutubeChannel(url,url); loadYoutube(url); }
                }).setNegativeButton(t("TP_L10N_135", R.string.TP_L10N_135), null).show();
    }

    private void loadYoutube(String url) {
        cancelSourceRequest();
        setSection("YouTube", t("TP_L10N_316", R.string.TP_L10N_316));
        int gen = ++loadGeneration;
        ytLoading = true;
        sourceRequest = org.telegram.messenger.telepremium.media.TelePremiumYouTube.loadFeed(url, (feed, error) -> {
            ytLoading = false;
            if (gen != loadGeneration) return;
            if (feed == null) {
                setSource(Collections.emptyList(), "YouTube", error);
                return;
            }
            ytFeed = feed;
            TelePremiumNetworkLibrary.updateYoutubeTitle(url, feed.title);
            TelePremiumNetworkLibrary.updateYoutubeArtwork(org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.owner(),url,feed.artworkUri);
            rebuildChips();
            setSource(feed.items, TelePremiumNetworkLibrary.youtubeName(feed.url,feed.title), null);
        });
    }

    private void loadMoreYoutube() {
        if (ytFeed == null || ytLoading) return;
        ytLoading = true;
        Toast.makeText(this, t("TP_L10N_316", R.string.TP_L10N_316), Toast.LENGTH_SHORT).show();
        org.telegram.messenger.telepremium.media.TelePremiumYouTube.Feed feed = ytFeed;
        sourceRequest = org.telegram.messenger.telepremium.media.TelePremiumYouTube.loadMore(feed, (items, error) -> {
            ytLoading = false;
            if (feed != ytFeed || networkMode != NET_YT) return;
            if (error != null) { Toast.makeText(this, error, Toast.LENGTH_LONG).show(); return; }
            rebuildChips();
            setSource(feed.items, TelePremiumNetworkLibrary.youtubeName(feed.url,feed.title), null);
        });
    }

    private void pickYoutubeChannel() {
        TelePremiumYoutubeChannelsDialog chooser=new TelePremiumYoutubeChannelsDialog(this,pal,this::loadYoutube,this::addYoutubeChannel);dialogs.add(chooser);chooser.setOnDismissListener(d->dialogs.remove(chooser));chooser.show();
    }

    private void pickYoutubeQuality() {
        int[] values = org.telegram.messenger.telepremium.media.TelePremiumYouTube.qualities();
        String[] labels = new String[values.length];
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + "p";
            if (values[i] == org.telegram.messenger.telepremium.media.TelePremiumYouTube.getMaxQuality()) selected = i;
        }
        dialog().setTitle(t("TP_YT_QUALITY", R.string.TP_YT_QUALITY)).setSingleChoiceItems(labels, selected, (d, which) -> {
            org.telegram.messenger.telepremium.media.TelePremiumYouTube.setMaxQuality(values[which]);
            d.dismiss();
            rebuildChips();
        }).show();
    }

    /** Quality and audio track for a download; "audio only" is the first entry. */
    private void showYoutubeDownload(TelePremiumMediaItem item) {
        Toast.makeText(this, t("TP_L10N_316", R.string.TP_L10N_316), Toast.LENGTH_SHORT).show();
        if (auxiliaryRequest != null) auxiliaryRequest.cancel();
        auxiliaryRequest = org.telegram.messenger.telepremium.media.TelePremiumYouTube.loadFormats(item.uri, (formats, error) -> {
            if (isFinishing()) return;
            if (formats == null) { Toast.makeText(this, error == null ? "YouTube" : error, Toast.LENGTH_LONG).show(); return; }
            if (formats.live) { Toast.makeText(this, t("TP_YT_LIVE_NO_DL", R.string.TP_YT_LIVE_NO_DL), Toast.LENGTH_LONG).show(); return; }
            ArrayList<org.telegram.messenger.telepremium.media.TelePremiumYouTube.VideoOption> videos = new ArrayList<>();
            for (org.telegram.messenger.telepremium.media.TelePremiumYouTube.VideoOption v : formats.videos) {
                // WebM (VP9) needs the WebM muxer with Opus: Android 10+
                if (v.webm && Build.VERSION.SDK_INT < 29) continue;
                videos.add(v);
            }
            String[] labels = new String[videos.size() + 1];
            labels[0] = "🎵 " + t("TP_YT_AUDIO_ONLY", R.string.TP_YT_AUDIO_ONLY);
            for (int i = 0; i < videos.size(); i++) labels[i + 1] = videos.get(i).label;
            dialog().setTitle(t("TP_YT_DL_QUALITY", R.string.TP_YT_DL_QUALITY)).setItems(labels, (d, which) -> {
                org.telegram.messenger.telepremium.media.TelePremiumYouTube.VideoOption video = which == 0 ? null : videos.get(which - 1);
                if (video != null && !video.videoOnly) { startYoutubeDownload(formats.title, video, null); return; }
                ArrayList<org.telegram.messenger.telepremium.media.TelePremiumYouTube.AudioOption> audios = new ArrayList<>();
                for (org.telegram.messenger.telepremium.media.TelePremiumYouTube.AudioOption a : formats.audios) {
                    if (video == null || a.webm == video.webm) audios.add(a);
                }
                if (audios.isEmpty()) { Toast.makeText(this, t("TP_YT_NO_AUDIO", R.string.TP_YT_NO_AUDIO), Toast.LENGTH_LONG).show(); return; }
                if (audios.size() == 1) { startYoutubeDownload(formats.title, video, audios.get(0)); return; }
                String[] audioLabels = new String[audios.size()];
                for (int i = 0; i < audios.size(); i++) audioLabels[i] = audios.get(i).label;
                dialog().setTitle(t("TP_YT_DL_AUDIO", R.string.TP_YT_DL_AUDIO)).setItems(audioLabels,
                        (d2, which2) -> startYoutubeDownload(formats.title, video, audios.get(which2))).show();
            }).show();
        });
    }

    private void startYoutubeDownload(String title, org.telegram.messenger.telepremium.media.TelePremiumYouTube.VideoOption video,
                                      org.telegram.messenger.telepremium.media.TelePremiumYouTube.AudioOption audio) {
        Toast.makeText(this, t("TP_YT_DL_STARTED", R.string.TP_YT_DL_STARTED), Toast.LENGTH_LONG).show();
        org.telegram.messenger.telepremium.media.TelePremiumYouTubeDownloader.download(title, video, audio, (saved, error) -> {
            if (isFinishing()) return;
            Toast.makeText(this, saved != null ? tf("TP_REC_SAVED", R.string.TP_REC_SAVED, saved) : (error == null ? "YouTube" : error), Toast.LENGTH_LONG).show();
        });
    }

    private void loadRadio() {
        cancelSourceRequest();
        String query = search.getText().toString().trim();
        if (radioFavoritesOnly) {
            setSource(TelePremiumNetworkLibrary.getRadioFavorites(), t("TP_NET_RADIO", R.string.TP_NET_RADIO), null);
            return;
        }
        if (query.length() >= 2) { runRadioSearch(); return; }
        String country = TelePremiumNetworkLibrary.getRadioCountry();
        setSection(t("TP_NET_RADIO", R.string.TP_NET_RADIO), t("TP_NET_RADIO_LOADING", R.string.TP_NET_RADIO_LOADING));
        int gen = ++loadGeneration;
        sourceRequest = TelePremiumRadioBrowser.byCountry(country, 300, (stations, error) -> {
            if (gen != loadGeneration) return;
            setSource(stations, t("TP_NET_RADIO", R.string.TP_NET_RADIO), error);
        });
    }

    private void runRadioSearch() {
        cancelSourceRequest();
        if (currentSource != TelePremiumMediaItem.SOURCE_NETWORK || networkMode != NET_RADIO || radioFavoritesOnly) return;
        String query = search.getText().toString().trim();
        if (query.length() < 2) { loadRadio(); return; }
        setSection(t("TP_NET_RADIO", R.string.TP_NET_RADIO), t("TP_NET_RADIO_LOADING", R.string.TP_NET_RADIO_LOADING));
        int gen = ++loadGeneration;
        // Search worldwide: a station name is rarely ambiguous, and users look for foreign stations too.
        sourceRequest = TelePremiumRadioBrowser.search(query, null, 100, (stations, error) -> {
            if (gen != loadGeneration) return;
            sourceItems.clear();
            if (stations != null) sourceItems.addAll(stations);
            sourceLabelText = t("TP_NET_RADIO", R.string.TP_NET_RADIO);
            applyFilter(true);
            if (error != null) { emptyView.setText(tf("TP_NET_ERROR", R.string.TP_NET_ERROR, error)); emptyView.setVisibility(View.VISIBLE); }
        });
    }

    private void pickRadioCountry() {
        Toast.makeText(this, t("TP_L10N_316", R.string.TP_L10N_316), Toast.LENGTH_SHORT).show();
        if (auxiliaryRequest != null) auxiliaryRequest.cancel();
        auxiliaryRequest = TelePremiumRadioBrowser.countries((countries, error) -> {
            if (isFinishing()) return;
            if (error != null || countries.isEmpty()) {
                Toast.makeText(this, tf("TP_NET_ERROR", R.string.TP_NET_ERROR, error == null ? "—" : error), Toast.LENGTH_LONG).show();
                return;
            }
            showCountrySheet(countries);
        });
    }

    /** Localized country name (Russian UI → «Германия», not «Germany»). */
    private static String countryName(TelePremiumRadioBrowser.Country c) {
        try {
            Locale target = org.telegram.messenger.telepremium.TPStrings.isRussian() ? new Locale("ru") : Locale.getDefault();
            String n = new Locale("", c.code.toUpperCase(Locale.ROOT)).getDisplayCountry(target);
            if (!TextUtils.isEmpty(n) && !n.equalsIgnoreCase(c.code)) return n;
        } catch (Exception ignore) {
        }
        return c.name;
    }

    private static String stationsLabel(int n) {
        if (!org.telegram.messenger.telepremium.TPStrings.isRussian()) return n + (n == 1 ? " station" : " stations");
        int m10 = n % 10, m100 = n % 100;
        String word = m10 == 1 && m100 != 11 ? "станция" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "станции" : "станций";
        return n + " " + word;
    }

    /**
     * Country picker: search, «Популярные» (most stations, your country first) and the full
     * alphabetical list with letter headers; every row shows the flag, the localized name and
     * the number of stations.
     */
    private void showCountrySheet(List<TelePremiumRadioBrowser.Country> countries) {
        boolean ru = org.telegram.messenger.telepremium.TPStrings.isRussian();
        String current = TelePremiumNetworkLibrary.getRadioCountry();
        String home = Locale.getDefault().getCountry();
        java.util.ArrayList<TelePremiumRadioBrowser.Country> sorted = new java.util.ArrayList<>(countries);
        java.text.Collator collator = java.text.Collator.getInstance(ru ? new Locale("ru") : Locale.getDefault());
        java.util.Collections.sort(sorted, (x, y) -> collator.compare(countryName(x), countryName(y)));
        java.util.ArrayList<TelePremiumRadioBrowser.Country> popular = new java.util.ArrayList<>(countries);
        java.util.Collections.sort(popular, (x, y) -> y.stations - x.stations);
        if (popular.size() > 10) popular = new java.util.ArrayList<>(popular.subList(0, 10));
        for (TelePremiumRadioBrowser.Country c : countries) {
            if (c.code.equalsIgnoreCase(home) || (ru && c.code.equalsIgnoreCase("RU"))) {
                popular.remove(c);
                popular.add(0, c);
            }
        }
        final java.util.ArrayList<Object> rows = new java.util.ArrayList<>();
        final String[] query = {""};
        final java.util.List<TelePremiumRadioBrowser.Country> popularFinal = popular;
        Runnable rebuild = () -> {
            rows.clear();
            String q = query[0].toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                rows.add(ru ? "Популярные" : "Popular");
                rows.addAll(popularFinal);
            }
            String letter = null;
            for (TelePremiumRadioBrowser.Country c : sorted) {
                String name = countryName(c);
                if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !c.name.toLowerCase(Locale.ROOT).contains(q)) continue;
                String first = name.isEmpty() ? "#" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                if (q.isEmpty() && !first.equals(letter)) { rows.add(first); letter = first; }
                rows.add(c);
            }
        };
        rebuild.run();

        int text = Theme.getColor(Theme.key_dialogTextBlack);
        int gray = Theme.getColor(Theme.key_dialogTextGray2);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int tileBg = Theme.getColor(Theme.key_windowBackgroundGray);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setHint(ru ? "Поиск страны" : "Search country");
        search.setSingleLine(true);
        search.setTextColor(text);
        search.setHintTextColor(gray);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(tileBg);
        sbg.setCornerRadius(AndroidUtilities.dp(14));
        search.setBackground(sbg);
        search.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        box.addView(search, slp);

        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return rows.get(i) instanceof String ? 0 : 1; }
            @Override public boolean isEnabled(int i) { return !(rows.get(i) instanceof String); }
            @Override public View getView(int i, View convert, android.view.ViewGroup parent) {
                Object row = rows.get(i);
                if (row instanceof String) {
                    TextView h = convert instanceof TextView ? (TextView) convert : new TextView(parent.getContext());
                    h.setText((String) row);
                    h.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    h.setTypeface(AndroidUtilities.bold());
                    h.setTextColor(accent);
                    h.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), AndroidUtilities.dp(4));
                    return h;
                }
                TelePremiumRadioBrowser.Country c = (TelePremiumRadioBrowser.Country) row;
                LinearLayout r = convert instanceof LinearLayout ? (LinearLayout) convert : null;
                if (r == null) {
                    r = new LinearLayout(parent.getContext());
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
                    TextView f = new TextView(parent.getContext());
                    f.setGravity(Gravity.CENTER);
                    f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
                    android.graphics.drawable.GradientDrawable fbg = new android.graphics.drawable.GradientDrawable();
                    fbg.setColor(tileBg);
                    fbg.setCornerRadius(AndroidUtilities.dp(12));
                    f.setBackground(fbg);
                    r.addView(f, new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
                    LinearLayout texts = new LinearLayout(parent.getContext());
                    texts.setOrientation(LinearLayout.VERTICAL);
                    TextView title = new TextView(parent.getContext());
                    title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                    title.setTextColor(text);
                    title.setSingleLine(true);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    TextView sub = new TextView(parent.getContext());
                    sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    sub.setTextColor(gray);
                    texts.addView(title);
                    texts.addView(sub);
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
                    tlp.leftMargin = AndroidUtilities.dp(14);
                    r.addView(texts, tlp);
                    TextView check = new TextView(parent.getContext());
                    check.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                    check.setTextColor(accent);
                    r.addView(check);
                }
                ((TextView) r.getChildAt(0)).setText(flag(c.code));
                LinearLayout texts = (LinearLayout) r.getChildAt(1);
                ((TextView) texts.getChildAt(0)).setText(countryName(c));
                ((TextView) texts.getChildAt(1)).setText(stationsLabel(c.stations));
                ((TextView) r.getChildAt(2)).setText(c.code.equalsIgnoreCase(current) ? "✓" : "");
                return r;
            }
        };
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(Theme.getSelectorDrawable(false));
        list.setFastScrollEnabled(true);
        box.addView(list, new LinearLayout.LayoutParams(-1, (int) (AndroidUtilities.displaySize.y * 0.66f)));

        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(this, true, null);
        sheet.setTitle(ru ? "Страна радиостанций" : "Radio country", true);
        sheet.setCustomView(box);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (!(row instanceof TelePremiumRadioBrowser.Country)) return;
            TelePremiumRadioBrowser.Country c = (TelePremiumRadioBrowser.Country) row;
            TelePremiumNetworkLibrary.setRadioCountry(c.code, flag(c.code) + " " + countryName(c));
            radioFavoritesOnly = false;
            sheet.dismiss();
            rebuildChips();
            loadRadio();
        });
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable e) { query[0] = e.toString().trim(); rebuild.run(); adapter.notifyDataSetChanged(); }
        });
        sheet.show();
    }

    private static String flag(String code) {
        if (code == null || code.length() != 2) return "";
        int a = Character.codePointAt(code.toUpperCase(Locale.ROOT), 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(code.toUpperCase(Locale.ROOT), 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** IPTV playlist source: a file on the device first, then a network link. */
    private void addIptvPlaylist() {
        String[] options = {t("TP_NET_FROM_FILE_ANY", R.string.TP_NET_FROM_FILE_ANY), t("TP_NET_FROM_URL", R.string.TP_NET_FROM_URL)};
        dialog().setTitle(t("TP_NET_ADD", R.string.TP_NET_ADD)).setItems(options, (d, which) -> {
            if (which == 0) pickPlaylistFile(REQUEST_IPTV_FILE);
            else addIptvPlaylistUrl();
        }).show();
    }

    private void pickPlaylistFile(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Playlists come with all kinds of MIME types (or none): let the user pick any file.
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(fallback, request); } catch (Exception ignore) {
                Toast.makeText(this, t("TP_NET_NO_PICKER", R.string.TP_NET_NO_PICKER), Toast.LENGTH_LONG).show();
            }
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(disposed)return;

        if ((requestCode != REQUEST_IPTV_FILE && requestCode != REQUEST_PLAYLIST_FILE) || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            // Keep access after restart: the saved playlist points at this document.
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {
        }
        String name = displayName(uri);
        if (requestCode == REQUEST_IPTV_FILE) {
            final long owner=org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.owner();
            final int token=++loadGeneration;
            new Thread(()->{
                String body=null,problem=null;try{body=TelePremiumNetworkPlaylistParser.readPlaylistForSync(uri.toString());}catch(Exception e){problem=e.getMessage();}
                final String text=body,failure=problem;handler.post(()->{
                    if(disposed||token!=loadGeneration||owner!=org.telegram.messenger.telepremium.media.TelePremiumMediaLibraryStore.owner())return;
                    if(failure!=null){Toast.makeText(this,failure,Toast.LENGTH_LONG).show();return;}
                    try{TelePremiumNetworkLibrary.addIptvFile(name,uri.toString(),text);loadIptv(name,uri.toString());}catch(Exception e){Toast.makeText(this,TPStrings.isRussian()?"Не удалось сохранить плейлист":"Could not save playlist",Toast.LENGTH_LONG).show();}
                });
            },"tp-playlist-import").start();
        } else {
            resolveNetwork(uri.toString());
        }
    }

    private String displayName(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                String name = c.getString(0);
                int dot = name.lastIndexOf('.');
                return dot > 0 ? name.substring(0, dot) : name;
            }
        } catch (Exception ignore) {
        }
        return Uri_lastSegment(uri.toString());
    }

    private void addIptvPlaylistUrl() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        EditText url = new EditText(this);
        url.setHint(t("TP_NET_IPTV_HINT", R.string.TP_NET_IPTV_HINT));
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = new EditText(this);
        name.setHint(t("TP_L10N_336", R.string.TP_L10N_336));
        name.setSingleLine(true);
        box.addView(url);
        box.addView(name);
        dialog().setTitle(t("TP_NET_IPTV_ADD", R.string.TP_NET_IPTV_ADD)).setView(box)
                .setPositiveButton(t("TP_L10N_117", R.string.TP_L10N_117), (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) return;
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = Uri_lastSegment(u);
                    TelePremiumNetworkLibrary.addIptvPlaylist(n, u);
                    loadIptv(n, u);
                }).setNegativeButton(t("TP_L10N_135", R.string.TP_L10N_135), null).show();
    }

    private static String Uri_lastSegment(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        return slash >= 0 && slash + 1 < s.length() ? s.substring(slash + 1) : "IPTV";
    }

    private void pickIptvPlaylist() {
        List<TelePremiumNetworkLibrary.IptvPlaylist> saved = TelePremiumNetworkLibrary.getIptvPlaylists();
        if (saved.isEmpty()) { addIptvPlaylist(); return; }
        String[] names = new String[saved.size()];
        for (int i = 0; i < names.length; i++) names[i] = saved.get(i).name;
        dialog().setTitle(t("TP_L10N_332", R.string.TP_L10N_332)).setItems(names, (d, which) -> loadIptv(saved.get(which).name, saved.get(which).url))
                .setNeutralButton(t("TP_NET_DELETE_PLAYLIST", R.string.TP_NET_DELETE_PLAYLIST), (d, w) -> {
                    if (iptvUrl != null) TelePremiumNetworkLibrary.removeIptvPlaylist(iptvUrl);
                    iptvUrl = null; iptvName = null; iptvGroup = null;
                    selectNetworkMode(NET_IPTV);
                }).show();
    }

    private void loadIptv(String name, String url) {
        iptvName = name;
        iptvUrl = url;
        iptvGroup = null;
        rebuildChips();
        setSection("IPTV", t("TP_L10N_316", R.string.TP_L10N_316));
        int gen = ++loadGeneration;
        TelePremiumNetworkPlaylistParser.resolveIptvAsync(url, (items, error) -> {
            if (gen != loadGeneration) return;
            setSource(items, name, error);
        });
    }

    private void pickIptvGroup() {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (TelePremiumMediaItem item : sourceItems) if (!TextUtils.isEmpty(item.album)) groups.add(item.album);
        ArrayList<String> list = new ArrayList<>(groups);
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        list.add(0, t("TP_NET_ALL_GROUPS", R.string.TP_NET_ALL_GROUPS));
        dialog().setTitle(t("TP_NET_GROUP", R.string.TP_NET_GROUP)).setItems(list.toArray(new String[0]), (d, which) -> {
            iptvGroup = which == 0 ? null : list.get(which);
            rebuildChips();
            applyFilter();
        }).show();
    }

    /** Link tab: open a playlist file from the device, or type a stream / playlist address. */
    private void showNetworkInput() {
        String[] options = {t("TP_NET_FROM_FILE_ANY", R.string.TP_NET_FROM_FILE_ANY), t("TP_NET_FROM_URL", R.string.TP_NET_FROM_URL)};
        dialog().setTitle(t("TP_L10N_326", R.string.TP_L10N_326)).setItems(options, (d, which) -> {
            if (which == 0) pickPlaylistFile(REQUEST_PLAYLIST_FILE);
            else showNetworkUrlInput();
        }).show();
    }

    private void showNetworkUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://…  MP3 / MP4 / M3U8 / MPD / M3U / PLS / XSPF");
        input.setSingleLine(true);
        dialog().setTitle(t("TP_L10N_326", R.string.TP_L10N_326)).setView(input)
                .setPositiveButton(t("TP_L10N_117", R.string.TP_L10N_117), (d, w) -> resolveNetwork(input.getText().toString()))
                .setNegativeButton(t("TP_L10N_135", R.string.TP_L10N_135), null).show();
    }

    private void resolveNetwork(String url) {
        setSection(t("TP_NET_LINK", R.string.TP_NET_LINK), t("TP_L10N_327", R.string.TP_L10N_327));
        int gen = ++loadGeneration;
        TelePremiumNetworkPlaylistParser.resolveAsync(url, (items, error) -> {
            if (gen != loadGeneration) return;
            setSource(items, t("TP_NET_LINK", R.string.TP_NET_LINK), error);
        });
    }

    // ------------------------------------------------------------------ playlists / queue

    private void showPlaylistPicker() {
        List<TelePremiumPlaylistRepository.Playlist> all = playlists.getAll();
        if (all.isEmpty()) {
            Toast.makeText(this, t("TP_L10N_331", R.string.TP_L10N_331), Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, TelePremiumPlaylistActivity.class));
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name + " · " + all.get(i).items.size();
        dialog().setTitle(t("TP_L10N_332", R.string.TP_L10N_332)).setItems(names, (d, which) -> {
            currentSource = SOURCE_PLAYLISTS;
            refreshTabs();
            rebuildChips();
            setSource(all.get(which).items, all.get(which).name, null);
        }).setNeutralButton(t("TP_L10N_334", R.string.TP_L10N_334), (d, w) -> startActivity(new Intent(this, TelePremiumPlaylistActivity.class))).show();
    }

    private void saveQueueAsPlaylist() {
        List<TelePremiumMediaItem> queue = controller.getQueue().snapshot();
        if (queue.isEmpty()) { Toast.makeText(this, t("TP_L10N_335", R.string.TP_L10N_335), Toast.LENGTH_SHORT).show(); return; }
        EditText name = new EditText(this);
        name.setHint(t("TP_L10N_336", R.string.TP_L10N_336));
        dialog().setTitle(t("TP_L10N_337", R.string.TP_L10N_337)).setView(name)
                .setPositiveButton(t("TP_L10N_137", R.string.TP_L10N_137), (d, w) -> {
                    String title = name.getText().toString().trim();
                    try { playlists.save(null, TextUtils.isEmpty(title) ? t("TP_L10N_338", R.string.TP_L10N_338) : title, queue); } catch (Exception e) { Toast.makeText(this, "Не удалось сохранить плейлист", Toast.LENGTH_LONG).show(); return; }
                    Toast.makeText(this, t("TP_L10N_339", R.string.TP_L10N_339), Toast.LENGTH_SHORT).show();
                }).setNegativeButton(t("TP_L10N_135", R.string.TP_L10N_135), null).show();
    }

    private void showQueue() {
        List<TelePremiumMediaItem> queue = controller.getQueue().snapshot();
        if (queue.isEmpty()) { Toast.makeText(this, t("TP_L10N_335", R.string.TP_L10N_335), Toast.LENGTH_SHORT).show(); return; }
        queueMode = true;
        rebuildChips();
        sourceItems.clear();
        sourceItems.addAll(queue);
        sourceLabelText = t("TP_L10N_465", R.string.TP_L10N_465);
        applyFilter();
    }

    // ------------------------------------------------------------------ filtering / sorting

    private void onSearchChanged() {
        if (currentSource == TelePremiumMediaItem.SOURCE_NETWORK && networkMode == NET_RADIO && !radioFavoritesOnly) {
            handler.removeCallbacks(radioSearch);
            handler.postDelayed(radioSearch, 650);
            return;
        }
        applyFilter();
    }

    private void applyFilter() { applyFilter(false); }

    private void applyFilter(boolean skipTextFilter) {
        String q = search == null || skipTextFilter ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean typed = currentSource != TelePremiumMediaItem.SOURCE_NETWORK && !queueMode;
        visibleItems.clear();
        for (TelePremiumMediaItem item : sourceItems) {
            if (typed && item.isVideo() != videoFilter) continue;
            if (currentSource == TelePremiumMediaItem.SOURCE_NETWORK && networkMode == NET_IPTV && iptvGroup != null && !iptvGroup.equals(item.album)) continue;
            if (!TextUtils.isEmpty(q)) {
                String hay = ((item.title == null ? "" : item.title) + " " + (item.artist == null ? "" : item.artist) + " " + (item.album == null ? "" : item.album)).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            visibleItems.add(item);
        }
        sortVisible();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateSectionHeader();
    }

    private void sortVisible() {
        if (queueMode || sortMode == SORT_DEFAULT) return;
        Collections.sort(visibleItems, (a, b) -> {
            switch (sortMode) {
                case SORT_NAME: return String.CASE_INSENSITIVE_ORDER.compare(a.title == null ? "" : a.title, b.title == null ? "" : b.title);
                case SORT_DURATION: return Long.compare(b.duration, a.duration);
                case SORT_SIZE: return Long.compare(b.size, a.size);
                default: return 0;
            }
        });
    }

    private void showSortMenu() {
        String[] labels = {t("TP_MC_SORT_DEFAULT", R.string.TP_MC_SORT_DEFAULT), t("TP_MC_SORT_NAME", R.string.TP_MC_SORT_NAME),
                t("TP_MC_SORT_DURATION", R.string.TP_MC_SORT_DURATION), t("TP_MC_SORT_SIZE", R.string.TP_MC_SORT_SIZE)};
        dialog().setTitle(t("TP_MC_SORT", R.string.TP_MC_SORT)).setSingleChoiceItems(labels, sortMode, (d, which) -> {
            sortMode = which;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("sort", which).apply();
            d.dismiss();
            applyFilter();
        }).show();
    }

    private void setSection(String title, String subtitle) {
        sectionTitle.setText(title);
        sectionCount.setText(subtitle == null ? "" : " · " + subtitle);
        emptyView.setVisibility(View.GONE);
    }

    private void updateSectionHeader() {
        int n = visibleItems.size();
        String count;
        if (currentSource == TelePremiumMediaItem.SOURCE_NETWORK && networkMode == NET_RADIO) count = tf("TP_NET_STATIONS_FMT", R.string.TP_NET_STATIONS_FMT, n);
        else if (currentSource == TelePremiumMediaItem.SOURCE_NETWORK && networkMode == NET_IPTV) count = tf("TP_NET_CHANNELS_FMT", R.string.TP_NET_CHANNELS_FMT, n);
        else count = TPStrings.isRussian() ? TelePremiumCloudFormat.compactCount(n) : n + (n == 1 ? " file" : " files");
        sectionTitle.setText(sourceLabelText);
        sectionCount.setText(" · " + count);
        emptyView.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (n == 0) emptyView.setText(currentSource == TelePremiumMediaItem.SOURCE_NETWORK && networkMode == NET_IPTV && iptvUrl == null
                ? t("TP_NET_IPTV_EMPTY", R.string.TP_NET_IPTV_EMPTY) : t("TP_L10N_165", R.string.TP_L10N_165));
        sortButton.setVisibility(currentSource == TelePremiumMediaItem.SOURCE_NETWORK && networkMode == NET_RADIO ? View.INVISIBLE : View.VISIBLE);
    }

    private void playItem(int position) {
        if (position < 0 || position >= visibleItems.size()) return;
        TelePremiumMediaItem item = visibleItems.get(position);
        if (item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD && !TelePremiumCloudSecuritySession.isUnlocked(this)) {
            unlockMediaVault(() -> { controller.play(item); TelePremiumPlayerActivity.openCurrent(activity); }); return;
        }
        controller.getQueue().replace(new ArrayList<>(visibleItems), position);
        controller.playCurrent();
        TelePremiumRadioBrowser.countClick(item);
        if (item.isVideo() || currentSource != TelePremiumMediaItem.SOURCE_NETWORK) TelePremiumPlayerActivity.openCurrent(activity);
    }

    private void addToQueue(TelePremiumMediaItem item, boolean next) {
        boolean ok = next ? controller.getQueue().insertNext(item) : controller.getQueue().append(item);
        if (!ok) {
            Toast.makeText(this, controller.getQueue().isVideoQueue() ? t("TP_Q_ONLY_VIDEO", R.string.TP_Q_ONLY_VIDEO) : t("TP_Q_ONLY_AUDIO", R.string.TP_Q_ONLY_AUDIO), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, next ? t("TP_L10N_347", R.string.TP_L10N_347) : t("TP_L10N_344", R.string.TP_L10N_344), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ player callbacks

    @Override public void onMediaChanged(TelePremiumMediaItem item) {
        if (miniTitle == null) return;
        miniTitle.setText(item == null ? t("TP_L10N_317", R.string.TP_L10N_317) : item.title);
        miniSource.setText(item == null ? "TelePremium Media" : (TextUtils.isEmpty(item.album) || item.sourceType != TelePremiumMediaItem.SOURCE_NETWORK ? item.sourceLabel() : item.album));
        miniArt.setVisibility(View.GONE);
        if (item != null) {
            final String id = item.id;
            TelePremiumArtworkLoader.load(this, item, dp(116), bitmap -> {
                TelePremiumMediaItem current = controller.getCurrent();
                if (bitmap == null || current == null || !id.equals(current.id)) return;
                miniArt.setImageBitmap(bitmap);
                miniArt.setVisibility(View.VISIBLE);
            });
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override public void onStateChanged(int state) {
        if (miniPlay != null) kit.setIcon(miniPlay, controller.isPlaying() ? R.drawable.tp_ic_pause : R.drawable.tp_ic_play, pal.text);
    }

    // ------------------------------------------------------------------ list

    private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder> {
        @Override public MediaHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(TelePremiumMediaCenterScreen.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(4), 0);
            row.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(mediaTheme.cardBackground, pal.stroke, 16, 1), pal.accent));
            RecyclerView.LayoutParams rowParams = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
            rowParams.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(rowParams);

            FrameLayout art = new FrameLayout(TelePremiumMediaCenterScreen.this);
            art.setBackground(TelePremiumPlayerKit.round(mediaTheme.artworkBackground, pal.stroke, 12, 0));
            art.setClipToOutline(true);
            ImageView fallback = kit.icon(R.drawable.tp_ic_music, pal.accent);
            art.addView(fallback, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
            ImageView thumb = new ImageView(TelePremiumMediaCenterScreen.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setVisibility(View.GONE);
            art.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            ImageView playing = kit.icon(R.drawable.tp_ic_play, Color.WHITE);
            playing.setBackground(TelePremiumPlayerKit.oval(0x88000000, 0, 0));
            playing.setPadding(dp(6), dp(6), dp(6), dp(6));
            playing.setVisibility(View.GONE);
            art.addView(playing, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
            TextView badge = kit.text("", 12, Color.WHITE, true);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            badge.setBackground(TelePremiumPlayerKit.round(0xb3000000, 0, 6, 0));
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
            badgeLp.setMargins(0, 0, dp(5), dp(5));
            art.addView(badge, badgeLp);
            row.addView(art, new LinearLayout.LayoutParams(dp(112), dp(70)));

            LinearLayout labels = new LinearLayout(TelePremiumMediaCenterScreen.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView title = kit.singleLine(kit.text("", 16, pal.text, true));
            TextView sub = kit.singleLine(kit.text("", 13, pal.muted, false));
            labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(6);
            labels.addView(sub, subLp);
            LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1f);
            labelsLp.setMargins(dp(14), 0, dp(4), 0);
            row.addView(labels, labelsLp);

            ImageView add = kit.iconButton(R.drawable.tp_ic_add, pal.accent, 28, t("TP_L10N_341", R.string.TP_L10N_341), null);
            row.addView(add, new LinearLayout.LayoutParams(dp(44), dp(52)));
            ImageView more = kit.iconButton(R.drawable.tp_ic_more, pal.text, 24, t("TP_L10N_342", R.string.TP_L10N_342), null);
            row.addView(more, new LinearLayout.LayoutParams(dp(36), dp(52)));
            return new MediaHolder(row, fallback, thumb, playing, badge, title, sub, add, more);
        }

        @Override public void onBindViewHolder(MediaHolder h, int position) {
            TelePremiumMediaItem item = visibleItems.get(position);
            h.boundId = item.id;
            TelePremiumMediaItem current = controller.getCurrent();
            boolean isCurrent = current != null && item.id.equals(current.id);
            h.thumb.setImageDrawable(null);
            h.thumb.setVisibility(View.GONE);
            kit.setIcon(h.fallback, item.isVideo() ? (item.live ? R.drawable.tp_ic_tv : R.drawable.tp_ic_video)
                    : item.live ? R.drawable.tp_ic_radio : R.drawable.tp_ic_music, pal.accent);
            h.fallback.setVisibility(View.VISIBLE);
            h.playing.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            h.badge.setText(item.live ? "LIVE" : item.duration > 0 ? formatDuration(item.duration) : "");
            h.badge.setVisibility(item.live || item.duration > 0 ? View.VISIBLE : View.GONE);
            TelePremiumArtworkLoader.load(TelePremiumMediaCenterScreen.this, item, dp(140), bitmap -> {
                if (!item.id.equals(h.boundId) || bitmap == null) return;
                h.thumb.setImageBitmap(bitmap);
                h.thumb.setVisibility(View.VISIBLE);
                h.fallback.setVisibility(View.GONE);
            });
            h.title.setText(item.title);
            h.title.setTextColor(isCurrent ? pal.accent : pal.text);
            h.sub.setText(subtitleFor(item));
            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p >= 0) playItem(p);
            });
            h.add.setOnClickListener(v -> addToQueue(item, false));
            h.more.setOnClickListener(v -> showItemMenu(item));
        }

        @Override public int getItemCount() { return visibleItems.size(); }
    }

    private String subtitleFor(TelePremiumMediaItem item) {
        if (item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK) {
            StringBuilder b = new StringBuilder();
            if (!TextUtils.isEmpty(item.album)) b.append(item.album);
            if (!TextUtils.isEmpty(item.artist)) { if (b.length() > 0) b.append(" · "); b.append(item.artist); }
            return b.length() == 0 ? item.sourceLabel() : b.toString();
        }
        StringBuilder b = new StringBuilder();
        if (item.duration > 0) b.append(formatDuration(item.duration)).append("  •  ");
        b.append(mimeLabel(item)).append("  •  ");
        b.append(!TextUtils.isEmpty(item.artist) && !item.isVideo() ? item.artist : sourceShort(item));
        return b.toString();
    }

    private String sourceShort(TelePremiumMediaItem item) {
        return item.sourceType == TelePremiumMediaItem.SOURCE_CLOUD ? t("TP_L10N_069", R.string.TP_L10N_069) : t("TP_L10N_411", R.string.TP_L10N_411);
    }

    private static final class MediaHolder extends RecyclerView.ViewHolder {
        final ImageView fallback, thumb, playing, add, more;
        final TextView badge, title, sub;
        String boundId;
        MediaHolder(View itemView, ImageView fallback, ImageView thumb, ImageView playing, TextView badge, TextView title, TextView sub, ImageView add, ImageView more) {
            super(itemView);
            this.fallback = fallback;
            this.thumb = thumb;
            this.playing = playing;
            this.badge = badge;
            this.title = title;
            this.sub = sub;
            this.add = add;
            this.more = more;
        }
    }

    private void showItemMenu(TelePremiumMediaItem item) {
        boolean radio = item.sourceType == TelePremiumMediaItem.SOURCE_NETWORK && item.live && !item.isVideo();
        ArrayList<String> actions = new ArrayList<>();
        actions.add(t("TP_L10N_345", R.string.TP_L10N_345));
        actions.add(t("TP_L10N_346", R.string.TP_L10N_346));
        actions.add(t("TP_L10N_341", R.string.TP_L10N_341));
        if (radio) actions.add(TelePremiumNetworkLibrary.isRadioFavorite(item) ? t("TP_L10N_124", R.string.TP_L10N_124) : t("TP_L10N_125", R.string.TP_L10N_125));
        boolean youtube = org.telegram.messenger.telepremium.media.TelePremiumYouTube.isYouTube(item);
        if (youtube) actions.add(t("TP_YT_DOWNLOAD", R.string.TP_YT_DOWNLOAD));
        dialog().setTitle(item.title).setItems(actions.toArray(new String[0]), (d, which) -> {
            if (youtube && which == 3) { showYoutubeDownload(item); return; }
            if (which == 0) {
                controller.play(item);
                TelePremiumRadioBrowser.countClick(item);
                if (item.isVideo() || item.sourceType != TelePremiumMediaItem.SOURCE_NETWORK) TelePremiumPlayerActivity.openCurrent(activity);
            } else if (which == 1) addToQueue(item, true);
            else if (which == 2) addToQueue(item, false);
            else {
                TelePremiumNetworkLibrary.toggleRadioFavorite(item);
                if (radioFavoritesOnly) loadRadio();
            }
        }).show();
    }

    private String mimeLabel(TelePremiumMediaItem item) {
        if (!TextUtils.isEmpty(item.mimeType)) {
            int slash = item.mimeType.indexOf('/');
            if (slash >= 0 && slash + 1 < item.mimeType.length()) {
                String sub = item.mimeType.substring(slash + 1).toUpperCase(Locale.ROOT);
                if ("MPEG".equals(sub)) return "MP3";
                if (sub.startsWith("X-")) sub = sub.substring(2);
                return sub;
            }
        }
        return item.isVideo() ? t("TP_L10N_098", R.string.TP_L10N_098) : t("TP_L10N_100", R.string.TP_L10N_100);
    }

    private int dp(float v) { return TelePremiumPlayerKit.dp(v); }

    private static String t(String key, int res) { return TPStrings.get(key, res); }

    private static String tf(String key, int res, Object... args) { return TPStrings.format(key, res, args); }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "";
        long total = ms / 1000, h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
