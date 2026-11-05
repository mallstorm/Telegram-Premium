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
public final class TelePremiumMediaCenterActivity extends Activity implements TelePremiumPlaybackController.Listener {
    private static final int REQUEST_MEDIA_PERMISSIONS = 7718;
    private static final int REQUEST_IPTV_FILE = 7720;
    private static final int REQUEST_PLAYLIST_FILE = 7721;
    private static final int SOURCE_PLAYLISTS = 3;
    private static final int NET_RADIO = 0, NET_IPTV = 1, NET_LINK = 2;
    private static final int SORT_DEFAULT = 0, SORT_NAME = 1, SORT_DURATION = 2, SORT_SIZE = 3;
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
    private String sourceLabelText = "";
    private int loadGeneration;
    private final Runnable radioSearch = this::runRadioSearch;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaTheme = TelePremiumMediaTheme.from(this);
        mediaTheme.applySystemBars(this);
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

    @Override protected void onResume() {
        super.onResume();
        if (mediaTheme == null) return;
        String currentId = TelePremiumThemeController.getCurrent(this).id;
        if (!mediaTheme.preset.id.equals(currentId)) recreate();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        controller.removeListener(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.background);
        root.setPadding(dp(14), dp(6), dp(14), dp(10));

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
        header.addView(kit.iconButton(R.drawable.tp_ic_playlist_add, pal.accent, 28, t("TP_L10N_305", R.string.TP_L10N_305), v -> saveQueueAsPlaylist()), new LinearLayout.LayoutParams(dp(50), dp(50)));
        header.addView(kit.iconButton(R.drawable.tp_ic_play, pal.accent, 32, t("TP_L10N_306", R.string.TP_L10N_306), v -> {
            if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(this);
        }), new LinearLayout.LayoutParams(dp(50), dp(50)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(68)));

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
        chipsScroll.addView(chipsRow, new FrameLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(-1, dp(48));
        chipsLp.topMargin = dp(12);
        root.addView(chipsScroll, chipsLp);

        // Second row (Network only): actions and filters of the selected mode
        subChipsScroll = new HorizontalScrollView(this);
        subChipsScroll.setHorizontalScrollBarEnabled(false);
        subChipsRow = new LinearLayout(this);
        subChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        subChipsScroll.addView(subChipsRow, new FrameLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, dp(44));
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
            if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(this);
        }), new LinearLayout.LayoutParams(dp(42), dp(48)));
        mini.setOnClickListener(v -> { if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(this); });
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.rightMargin = dp(10);
        chipsRow.addView(chip, lp);
    }

    private void addSubChip(TextView chip) {
        chip.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
        lp.rightMargin = dp(8);
        subChipsRow.addView(chip, lp);
    }

    /** Two equal segments that always fit the width: Radio | TV. */
    private void addModeChip(TextView chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.rightMargin = chipsRow.getChildCount() == 0 ? dp(5) : 0;
        lp.leftMargin = chipsRow.getChildCount() == 0 ? 0 : dp(5);
        chipsRow.addView(chip, lp);
    }

    private void rebuildChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        if (chipsRow.getParent() instanceof HorizontalScrollView) ((HorizontalScrollView) chipsRow.getParent()).scrollTo(0, 0);
        subChipsRow.removeAllViews();
        subChipsScroll.scrollTo(0, 0);
        boolean network = currentSource == TelePremiumMediaItem.SOURCE_NETWORK;
        subChipsScroll.setVisibility(network ? View.VISIBLE : View.GONE);
        // In Network mode the mode chips fill the row width; elsewhere chips wrap their text.
        View scroll = (View) chipsRow.getParent();
        if (scroll instanceof HorizontalScrollView) ((HorizontalScrollView) scroll).setFillViewport(network);
        if (network) {
            addModeChip(chip(R.drawable.tp_ic_radio, t("TP_NET_RADIO", R.string.TP_NET_RADIO), networkMode == NET_RADIO, v -> selectNetworkMode(NET_RADIO)));
            addModeChip(chip(R.drawable.tp_ic_tv, t("TP_NET_IPTV", R.string.TP_NET_IPTV), networkMode == NET_IPTV, v -> selectNetworkMode(NET_IPTV)));
            if (networkMode == NET_RADIO) {
                String country = TelePremiumNetworkLibrary.getRadioCountryName();
                addSubChip(chip(R.drawable.tp_ic_public, TextUtils.isEmpty(country) ? TelePremiumNetworkLibrary.getRadioCountry() : country, false, v -> pickRadioCountry()));
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
        if (source == SOURCE_PLAYLISTS) { showPlaylistPicker(); return; }
        currentSource = source;
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSIONS) loadDevice();
    }

    private void loadDevice() {
        currentSource = TelePremiumMediaItem.SOURCE_DEVICE;
        refreshTabs();
        setSection(t("TP_L10N_411", R.string.TP_L10N_411), t("TP_L10N_316", R.string.TP_L10N_316));
        int gen = ++loadGeneration;
        deviceRepo.loadAll((items, error) -> {
            if (gen != loadGeneration) return;
            setSource(items, t("TP_L10N_411", R.string.TP_L10N_411), error);
        });
    }

    private void loadCloud() {
        currentSource = TelePremiumMediaItem.SOURCE_CLOUD;
        refreshTabs();
        setSection(t("TP_L10N_069", R.string.TP_L10N_069), t("TP_L10N_316", R.string.TP_L10N_316));
        int gen = ++loadGeneration;
        cloudRepo.load(UserConfig.selectedAccount, (items, error) -> {
            if (gen != loadGeneration) return;
            setSource(items, t("TP_L10N_069", R.string.TP_L10N_069), error);
        });
    }

    private void setSource(List<TelePremiumMediaItem> items, String label, String error) {
        sourceItems.clear();
        if (items != null) sourceItems.addAll(items);
        sourceLabelText = label;
        applyFilter();
        if (error != null) {
            emptyView.setText(tf("TP_NET_ERROR", R.string.TP_NET_ERROR, error));
            emptyView.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ network: radio / IPTV / link

    private void selectNetworkMode(int mode) {
        if (mode == NET_LINK) mode = NET_IPTV;
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
        } else {
            setSource(Collections.emptyList(), t("TP_NET_LINK", R.string.TP_NET_LINK), null);
            showNetworkInput();
        }
    }

    private void loadRadio() {
        String query = search.getText().toString().trim();
        if (radioFavoritesOnly) {
            setSource(TelePremiumNetworkLibrary.getRadioFavorites(), t("TP_NET_RADIO", R.string.TP_NET_RADIO), null);
            return;
        }
        if (!query.isEmpty()) { runRadioSearch(); return; }
        String country = TelePremiumNetworkLibrary.getRadioCountry();
        setSection(t("TP_NET_RADIO", R.string.TP_NET_RADIO), t("TP_NET_RADIO_LOADING", R.string.TP_NET_RADIO_LOADING));
        int gen = ++loadGeneration;
        TelePremiumRadioBrowser.byCountry(country, 300, (stations, error) -> {
            if (gen != loadGeneration) return;
            setSource(stations, t("TP_NET_RADIO", R.string.TP_NET_RADIO), error);
        });
    }

    private void runRadioSearch() {
        if (currentSource != TelePremiumMediaItem.SOURCE_NETWORK || networkMode != NET_RADIO || radioFavoritesOnly) return;
        String query = search.getText().toString().trim();
        if (query.length() < 2) { loadRadio(); return; }
        setSection(t("TP_NET_RADIO", R.string.TP_NET_RADIO), t("TP_NET_RADIO_LOADING", R.string.TP_NET_RADIO_LOADING));
        int gen = ++loadGeneration;
        // Search worldwide: a station name is rarely ambiguous, and users look for foreign stations too.
        TelePremiumRadioBrowser.search(query, null, 100, (stations, error) -> {
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
        TelePremiumRadioBrowser.countries((countries, error) -> {
            if (isFinishing()) return;
            if (error != null || countries.isEmpty()) {
                Toast.makeText(this, tf("TP_NET_ERROR", R.string.TP_NET_ERROR, error == null ? "—" : error), Toast.LENGTH_LONG).show();
                return;
            }
            String[] names = new String[countries.size()];
            for (int i = 0; i < names.length; i++) {
                TelePremiumRadioBrowser.Country c = countries.get(i);
                names[i] = flag(c.code) + "  " + c.name + "  ·  " + c.stations;
            }
            new AlertDialog.Builder(this).setTitle(t("TP_NET_COUNTRY", R.string.TP_NET_COUNTRY)).setItems(names, (d, which) -> {
                TelePremiumRadioBrowser.Country c = countries.get(which);
                TelePremiumNetworkLibrary.setRadioCountry(c.code, flag(c.code) + " " + c.name);
                radioFavoritesOnly = false;
                rebuildChips();
                loadRadio();
            }).show();
        });
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
        new AlertDialog.Builder(this).setTitle(t("TP_NET_ADD", R.string.TP_NET_ADD)).setItems(options, (d, which) -> {
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_IPTV_FILE && requestCode != REQUEST_PLAYLIST_FILE) || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            // Keep access after restart: the saved playlist points at this document.
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {
        }
        String name = displayName(uri);
        if (requestCode == REQUEST_IPTV_FILE) {
            TelePremiumNetworkLibrary.addIptvPlaylist(name, uri.toString());
            loadIptv(name, uri.toString());
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
        new AlertDialog.Builder(this).setTitle(t("TP_NET_IPTV_ADD", R.string.TP_NET_IPTV_ADD)).setView(box)
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
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_332", R.string.TP_L10N_332)).setItems(names, (d, which) -> loadIptv(saved.get(which).name, saved.get(which).url))
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
        new AlertDialog.Builder(this).setTitle(t("TP_NET_GROUP", R.string.TP_NET_GROUP)).setItems(list.toArray(new String[0]), (d, which) -> {
            iptvGroup = which == 0 ? null : list.get(which);
            rebuildChips();
            applyFilter();
        }).show();
    }

    /** Link tab: open a playlist file from the device, or type a stream / playlist address. */
    private void showNetworkInput() {
        String[] options = {t("TP_NET_FROM_FILE_ANY", R.string.TP_NET_FROM_FILE_ANY), t("TP_NET_FROM_URL", R.string.TP_NET_FROM_URL)};
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_326", R.string.TP_L10N_326)).setItems(options, (d, which) -> {
            if (which == 0) pickPlaylistFile(REQUEST_PLAYLIST_FILE);
            else showNetworkUrlInput();
        }).show();
    }

    private void showNetworkUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://…  MP3 / MP4 / M3U8 / MPD / M3U / PLS / XSPF");
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_326", R.string.TP_L10N_326)).setView(input)
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
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_332", R.string.TP_L10N_332)).setItems(names, (d, which) -> {
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
        new AlertDialog.Builder(this).setTitle(t("TP_L10N_337", R.string.TP_L10N_337)).setView(name)
                .setPositiveButton(t("TP_L10N_137", R.string.TP_L10N_137), (d, w) -> {
                    String title = name.getText().toString().trim();
                    playlists.save(null, TextUtils.isEmpty(title) ? t("TP_L10N_338", R.string.TP_L10N_338) : title, queue);
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
        new AlertDialog.Builder(this).setTitle(t("TP_MC_SORT", R.string.TP_MC_SORT)).setSingleChoiceItems(labels, sortMode, (d, which) -> {
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
        controller.getQueue().replace(new ArrayList<>(visibleItems), position);
        controller.playCurrent();
        TelePremiumRadioBrowser.countClick(item);
        if (item.isVideo() || currentSource != TelePremiumMediaItem.SOURCE_NETWORK) TelePremiumPlayerActivity.openCurrent(this);
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
            LinearLayout row = new LinearLayout(TelePremiumMediaCenterActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(4), 0);
            row.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(mediaTheme.cardBackground, pal.stroke, 16, 1), pal.accent));
            RecyclerView.LayoutParams rowParams = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
            rowParams.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(rowParams);

            FrameLayout art = new FrameLayout(TelePremiumMediaCenterActivity.this);
            art.setBackground(TelePremiumPlayerKit.round(mediaTheme.artworkBackground, pal.stroke, 12, 0));
            art.setClipToOutline(true);
            ImageView fallback = kit.icon(R.drawable.tp_ic_music, pal.accent);
            art.addView(fallback, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
            ImageView thumb = new ImageView(TelePremiumMediaCenterActivity.this);
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

            LinearLayout labels = new LinearLayout(TelePremiumMediaCenterActivity.this);
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
            TelePremiumArtworkLoader.load(TelePremiumMediaCenterActivity.this, item, dp(140), bitmap -> {
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
        new AlertDialog.Builder(this).setTitle(item.title).setItems(actions.toArray(new String[0]), (d, which) -> {
            if (which == 0) {
                controller.play(item);
                TelePremiumRadioBrowser.countClick(item);
                if (item.isVideo() || item.sourceType != TelePremiumMediaItem.SOURCE_NETWORK) TelePremiumPlayerActivity.openCurrent(this);
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
