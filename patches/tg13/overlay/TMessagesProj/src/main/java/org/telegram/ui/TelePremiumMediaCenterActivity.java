package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.telepremium.media.TelePremiumArtworkLoader;
import org.telegram.messenger.telepremium.media.TelePremiumCloudMediaRepository;
import org.telegram.messenger.telepremium.media.TelePremiumMediaItem;
import org.telegram.messenger.telepremium.media.TelePremiumMediaStoreRepository;
import org.telegram.messenger.telepremium.media.TelePremiumNetworkPlaylistParser;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.media.TelePremiumPlaylistRepository;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Unified themed library for device media, Cloud V2, network streams and mixed playlists. */
public final class TelePremiumMediaCenterActivity extends Activity implements TelePremiumPlaybackController.Listener {
    private static final int REQUEST_MEDIA_PERMISSIONS = 7718;
    private static final int SOURCE_PLAYLIST = 3;
    private static final int SOURCE_QUEUE = 4;

    private final TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
    private final TelePremiumMediaStoreRepository deviceRepo = new TelePremiumMediaStoreRepository();
    private final TelePremiumCloudMediaRepository cloudRepo = new TelePremiumCloudMediaRepository();
    private final TelePremiumPlaylistRepository playlists = TelePremiumPlaylistRepository.getInstance();
    private final ArrayList<TelePremiumMediaItem> sourceItems = new ArrayList<>();
    private final ArrayList<TelePremiumMediaItem> visibleItems = new ArrayList<>();

    private TelePremiumMediaTheme mediaTheme;
    private MediaAdapter adapter;
    private EditText search;
    private TextView status;
    private TextView miniTitle, miniSource, miniPlay;
    private TextView sourceDevice, sourceCloud, sourceNetwork, sourcePlaylists;
    private TextView filterAll, filterMusic, filterVideo, filterQueue;
    private int filterType = -1;
    private int currentSource = TelePremiumMediaItem.SOURCE_DEVICE;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaTheme = TelePremiumMediaTheme.from(this);
        mediaTheme.applySystemBars(this);
        controller.addListener(this);
        buildUi();
        ensureMediaPermissionsAndLoadDevice();
    }

    @Override protected void onResume() {
        super.onResume();
        if (mediaTheme == null) return;
        String currentId = TelePremiumThemeController.getCurrent(this).id;
        if (!mediaTheme.preset.id.equals(currentId)) recreate();
    }

    private void ensureMediaPermissionsAndLoadDevice() {
        if (Build.VERSION.SDK_INT < 23) {
            loadDevice();
            return;
        }
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mediaTheme.background);
        root.setPadding(dp(12), dp(7), dp(12), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = button("‹", 32, false);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(50)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("TelePremium", 23, mediaTheme.textPrimary, true);
        TextView subtitle = text("Медиатека", 12, mediaTheme.textSecondary, false);
        titleBox.addView(title, new LinearLayout.LayoutParams(-1, dp(29)));
        titleBox.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(19)));
        header.addView(titleBox, new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView save = button("☷+", 20, false);
        save.setTextColor(mediaTheme.accent);
        save.setContentDescription("Сохранить очередь как плейлист");
        save.setOnClickListener(v -> saveQueueAsPlaylist());
        header.addView(save, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView now = button("▶", 20, false);
        now.setTextColor(mediaTheme.accent);
        now.setContentDescription("Открыть текущий плеер");
        now.setOnClickListener(v -> { if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(this); });
        header.addView(now, new LinearLayout.LayoutParams(dp(46), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout sources = new LinearLayout(this);
        sources.setGravity(Gravity.CENTER);
        sourceDevice = sourceButton("▣\nУстройство");
        sourceCloud = sourceButton("☁\nОблако");
        sourceNetwork = sourceButton("◎\nСеть");
        sourcePlaylists = sourceButton("☷\nПлейлисты");
        sourceDevice.setOnClickListener(v -> loadDevice());
        sourceCloud.setOnClickListener(v -> loadCloud());
        sourceNetwork.setOnClickListener(v -> showNetworkInput());
        sourcePlaylists.setOnClickListener(v -> showPlaylistPicker());
        sources.addView(sourceDevice, weight(dp(56)));
        sources.addView(sourceCloud, weight(dp(56)));
        sources.addView(sourceNetwork, weight(dp(56)));
        sources.addView(sourcePlaylists, weight(dp(56)));
        root.addView(sources, new LinearLayout.LayoutParams(-1, dp(63)));

        search = new EditText(this);
        search.setHint("Поиск по названию, исполнителю, альбому…");
        search.setHintTextColor(mediaTheme.withAlpha(mediaTheme.textSecondary, 0xb8));
        search.setTextColor(mediaTheme.textPrimary);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(roundBg(mediaTheme.searchBackground, mediaTheme.cardStroke, 20));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout filters = new LinearLayout(this);
        filters.setPadding(0, dp(7), 0, dp(3));
        filterAll = chip("Все");
        filterMusic = chip("♫ Музыка");
        filterVideo = chip("▣ Видео");
        filterQueue = chip("☷ Очередь");
        filterAll.setOnClickListener(v -> { filterType = -1; applyFilter(); });
        filterMusic.setOnClickListener(v -> { filterType = TelePremiumMediaItem.TYPE_AUDIO; applyFilter(); });
        filterVideo.setOnClickListener(v -> { filterType = TelePremiumMediaItem.TYPE_VIDEO; applyFilter(); });
        filterQueue.setOnClickListener(v -> showQueuePicker());
        filters.addView(filterAll, wrap(dp(68)));
        filters.addView(filterMusic, wrap(dp(100)));
        filters.addView(filterVideo, wrap(dp(94)));
        filters.addView(filterQueue, wrap(dp(104)));
        root.addView(filters, new LinearLayout.LayoutParams(-1, dp(46)));

        status = text("Загрузка…", 12, mediaTheme.textSecondary, false);
        status.setPadding(dp(4), 0, 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(27)));

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(4));
        adapter = new MediaAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(createMiniPlayer(), new LinearLayout.LayoutParams(-1, dp(68)));
        setContentView(root);
        updateSourceSelection();
        updateFilterSelection();
    }

    private View createMiniPlayer() {
        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(9), dp(5), dp(7), dp(5));
        mini.setBackground(roundBg(mediaTheme.miniPlayerBackground, mediaTheme.cardStroke, 19));

        TextView icon = text("♫", 22, mediaTheme.accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundBg(mediaTheme.chipSelectedBackground, mediaTheme.cardStroke, 13));
        mini.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(4), 0);
        miniTitle = text("Ничего не воспроизводится", 14, mediaTheme.textPrimary, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniSource = text("TelePremium Media", 11, mediaTheme.textSecondary, false);
        labels.addView(miniTitle, new LinearLayout.LayoutParams(-1, dp(25)));
        labels.addView(miniSource, new LinearLayout.LayoutParams(-1, dp(19)));
        mini.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView previous = button("|◀", 18, false);
        previous.setOnClickListener(v -> controller.previous());
        mini.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(52)));
        miniPlay = roundActionButton("▶", 22);
        miniPlay.setOnClickListener(v -> controller.toggle());
        mini.addView(miniPlay, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView next = button("▶|", 18, false);
        next.setOnClickListener(v -> controller.next());
        mini.addView(next, new LinearLayout.LayoutParams(dp(42), dp(52)));
        TextView open = button("⌃", 21, false);
        open.setOnClickListener(v -> { if (controller.getCurrent() != null) TelePremiumPlayerActivity.openCurrent(this); });
        mini.addView(open, new LinearLayout.LayoutParams(dp(38), dp(52)));
        return mini;
    }

    private void loadDevice() {
        currentSource = TelePremiumMediaItem.SOURCE_DEVICE;
        updateSourceSelection();
        status.setText("Устройство · сканирование MediaStore…");
        deviceRepo.loadAll((items, error) -> {
            sourceItems.clear();
            if (items != null) sourceItems.addAll(items);
            status.setText(error == null ? "Устройство · " + sourceItems.size() + " файлов" : "Нет доступа к медиатеке: " + error);
            applyFilter();
        });
    }

    private void loadCloud() {
        currentSource = TelePremiumMediaItem.SOURCE_CLOUD;
        updateSourceSelection();
        status.setText("TelePremium Cloud · загрузка метаданных…");
        cloudRepo.load(UserConfig.selectedAccount, (items, error) -> {
            sourceItems.clear();
            if (items != null) sourceItems.addAll(items);
            status.setText(error == null ? "Облако · " + sourceItems.size() + " медиафайлов" : "Облако: " + error);
            applyFilter();
        });
    }

    private void showNetworkInput() {
        currentSource = TelePremiumMediaItem.SOURCE_NETWORK;
        updateSourceSelection();
        EditText input = new EditText(this);
        input.setHint("https://…  MP3 / MP4 / M3U8 / MPD / M3U / PLS / XSPF");
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Сетевой источник").setView(input)
                .setPositiveButton("Открыть", (d, w) -> resolveNetwork(input.getText().toString()))
                .setNegativeButton("Отмена", null).show();
    }

    private void resolveNetwork(String url) {
        status.setText("Сеть · подключение…");
        TelePremiumNetworkPlaylistParser.resolveAsync(url, (items, error) -> {
            sourceItems.clear();
            if (items != null) sourceItems.addAll(items);
            status.setText(error == null ? "Сеть · " + sourceItems.size() + " элементов" : "Сеть: " + error);
            applyFilter();
        });
    }

    private void showPlaylistPicker() {
        currentSource = SOURCE_PLAYLIST;
        updateSourceSelection();
        List<TelePremiumPlaylistRepository.Playlist> all = playlists.getAll();
        if (all.isEmpty()) { Toast.makeText(this, "Сохранённых плейлистов пока нет", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name + " · " + all.get(i).items.size();
        new AlertDialog.Builder(this).setTitle("Плейлисты").setItems(names, (d, which) -> {
            sourceItems.clear();
            sourceItems.addAll(all.get(which).items);
            status.setText("Плейлист · " + all.get(which).name + " · " + sourceItems.size() + " файлов");
            applyFilter();
        }).setNeutralButton("Управление", (d, w) -> startActivity(new Intent(this, TelePremiumPlaylistActivity.class))).show();
    }

    private void saveQueueAsPlaylist() {
        List<TelePremiumMediaItem> queue = controller.getQueue().snapshot();
        if (queue.isEmpty()) { Toast.makeText(this, "Очередь пуста", Toast.LENGTH_SHORT).show(); return; }
        EditText name = new EditText(this);
        name.setHint("Название плейлиста");
        new AlertDialog.Builder(this).setTitle("Сохранить очередь").setView(name)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String title = name.getText().toString().trim();
                    playlists.save(null, TextUtils.isEmpty(title) ? "Новый плейлист" : title, queue);
                    Toast.makeText(this, "Плейлист сохранён", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Отмена", null).show();
    }

    private void showQueuePicker() {
        List<TelePremiumMediaItem> queue = controller.getQueue().snapshot();
        if (queue.isEmpty()) { Toast.makeText(this, "Очередь пуста", Toast.LENGTH_SHORT).show(); return; }
        currentSource = SOURCE_QUEUE;
        updateSourceSelection();
        sourceItems.clear();
        sourceItems.addAll(queue);
        status.setText("Текущая очередь · " + queue.size());
        applyFilter();
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visibleItems.clear();
        for (TelePremiumMediaItem item : sourceItems) {
            if (filterType == TelePremiumMediaItem.TYPE_AUDIO && item.mediaType == TelePremiumMediaItem.TYPE_VIDEO) continue;
            if (filterType == TelePremiumMediaItem.TYPE_VIDEO && item.mediaType != TelePremiumMediaItem.TYPE_VIDEO) continue;
            if (!TextUtils.isEmpty(q)) {
                String hay = ((item.title == null ? "" : item.title) + " " + (item.artist == null ? "" : item.artist) + " " + (item.album == null ? "" : item.album)).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            visibleItems.add(item);
        }
        updateFilterSelection();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void playItem(int position) {
        if (position < 0 || position >= visibleItems.size()) return;
        controller.getQueue().replace(new ArrayList<>(visibleItems), position);
        controller.playCurrent();
        TelePremiumPlayerActivity.openCurrent(this);
    }

    @Override public void onMediaChanged(TelePremiumMediaItem item) {
        if (miniTitle == null || miniSource == null) return;
        miniTitle.setText(item == null ? "Ничего не воспроизводится" : item.title);
        miniSource.setText(item == null ? "TelePremium Media" : item.sourceLabel());
    }

    @Override public void onStateChanged(int state) {
        if (miniPlay != null) miniPlay.setText(controller.isPlaying() ? "Ⅱ" : "▶");
    }

    @Override protected void onDestroy() {
        controller.removeListener(this);
        super.onDestroy();
    }

    private void updateSourceSelection() {
        if (sourceDevice == null) return;
        styleSource(sourceDevice, currentSource == TelePremiumMediaItem.SOURCE_DEVICE);
        styleSource(sourceCloud, currentSource == TelePremiumMediaItem.SOURCE_CLOUD);
        styleSource(sourceNetwork, currentSource == TelePremiumMediaItem.SOURCE_NETWORK);
        styleSource(sourcePlaylists, currentSource == SOURCE_PLAYLIST);
    }

    private void updateFilterSelection() {
        if (filterAll == null) return;
        styleChip(filterAll, filterType == -1 && currentSource != SOURCE_QUEUE);
        styleChip(filterMusic, filterType == TelePremiumMediaItem.TYPE_AUDIO);
        styleChip(filterVideo, filterType == TelePremiumMediaItem.TYPE_VIDEO);
        styleChip(filterQueue, currentSource == SOURCE_QUEUE);
    }

    private void styleSource(TextView view, boolean selected) {
        if (view == null) return;
        view.setTextColor(selected ? mediaTheme.accent : mediaTheme.textPrimary);
        view.setBackground(roundBg(selected ? mediaTheme.selectedSurface() : mediaTheme.surfaceElevated,
                selected ? mediaTheme.accent : mediaTheme.cardStroke, 16));
    }

    private void styleChip(TextView view, boolean selected) {
        if (view == null) return;
        view.setTextColor(selected ? mediaTheme.accent : mediaTheme.textPrimary);
        view.setBackground(roundBg(selected ? mediaTheme.chipSelectedBackground : mediaTheme.chipBackground,
                selected ? mediaTheme.accent : mediaTheme.cardStroke, 18));
    }

    private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder> {
        @Override public MediaHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(TelePremiumMediaCenterActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(4), dp(5), dp(4));
            row.setBackground(roundBg(mediaTheme.cardBackground, mediaTheme.cardStroke, 15));
            RecyclerView.LayoutParams rowParams = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
            rowParams.setMargins(0, dp(2), 0, dp(2));
            row.setLayoutParams(rowParams);

            FrameLayout artwork = new FrameLayout(TelePremiumMediaCenterActivity.this);
            artwork.setBackground(roundBg(mediaTheme.artworkBackground, mediaTheme.cardStroke, 12));
            ImageView thumbnail = new ImageView(TelePremiumMediaCenterActivity.this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setVisibility(View.GONE);
            artwork.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
            TextView icon = text("♫", 23, mediaTheme.accent, true);
            icon.setGravity(Gravity.CENTER);
            artwork.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            TextView durationBadge = text("", 9, 0xffffffff, true);
            durationBadge.setGravity(Gravity.CENTER);
            durationBadge.setPadding(dp(4), 0, dp(4), 0);
            durationBadge.setBackground(roundBg(0xb0000000, 0x22000000, 7));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, dp(18), Gravity.RIGHT | Gravity.BOTTOM);
            badgeParams.setMargins(0, 0, dp(3), dp(3));
            artwork.addView(durationBadge, badgeParams);
            row.addView(artwork, new LinearLayout.LayoutParams(dp(58), dp(58)));

            LinearLayout labels = new LinearLayout(TelePremiumMediaCenterActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(10), 0, dp(2), 0);
            TextView title = text("", 14, mediaTheme.textPrimary, true);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = text("", 11, mediaTheme.textSecondary, false);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            TextView source = text("", 10, mediaTheme.accent, false);
            source.setSingleLine();
            source.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title, new LinearLayout.LayoutParams(-1, dp(22)));
            labels.addView(sub, new LinearLayout.LayoutParams(-1, dp(18)));
            labels.addView(source, new LinearLayout.LayoutParams(-1, dp(17)));
            row.addView(labels, new LinearLayout.LayoutParams(0, dp(58), 1f));

            TextView add = button("＋", 21, false);
            add.setTextColor(mediaTheme.accent);
            add.setContentDescription("Добавить в очередь");
            row.addView(add, new LinearLayout.LayoutParams(dp(38), dp(52)));
            TextView more = button("⋮", 22, false);
            more.setTextColor(mediaTheme.textSecondary);
            more.setContentDescription("Действия с файлом");
            row.addView(more, new LinearLayout.LayoutParams(dp(30), dp(52)));
            return new MediaHolder(row, thumbnail, icon, durationBadge, title, sub, source, add, more);
        }

        @Override public void onBindViewHolder(MediaHolder h, int position) {
            TelePremiumMediaItem item = visibleItems.get(position);
            h.boundId = item.id;
            h.thumbnail.setImageDrawable(null);
            h.thumbnail.setVisibility(View.GONE);
            h.icon.setVisibility(View.VISIBLE);
            h.icon.setText(item.isVideo() ? "▣" : (item.live ? "◉" : "♫"));
            h.durationBadge.setText(item.duration > 0 ? formatDuration(item.duration) : "");
            h.durationBadge.setVisibility(item.duration > 0 ? View.VISIBLE : View.GONE);
            TelePremiumArtworkLoader.load(TelePremiumMediaCenterActivity.this, item, dp(112), bitmap -> {
                if (!item.id.equals(h.boundId)) return;
                if (bitmap != null) {
                    h.thumbnail.setImageBitmap(bitmap);
                    h.thumbnail.setVisibility(View.VISIBLE);
                    h.icon.setVisibility(View.GONE);
                }
            });
            h.title.setText(item.title);
            String secondary;
            if (!TextUtils.isEmpty(item.artist)) {
                secondary = item.artist + (TextUtils.isEmpty(item.album) ? "" : " · " + item.album);
            } else {
                secondary = item.isVideo() ? mimeLabel(item) : "Неизвестный исполнитель";
            }
            h.sub.setText(secondary);
            h.source.setText((item.duration > 0 ? formatDuration(item.duration) + " · " : "") + mimeLabel(item) + " · " + item.sourceLabel());
            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p >= 0) playItem(p);
            });
            h.add.setOnClickListener(v -> {
                controller.getQueue().append(item);
                Toast.makeText(TelePremiumMediaCenterActivity.this, "Добавлено в очередь", Toast.LENGTH_SHORT).show();
            });
            h.more.setOnClickListener(v -> showItemMenu(item));
        }

        @Override public int getItemCount() { return visibleItems.size(); }
    }

    private static final class MediaHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView icon, durationBadge, title, sub, source, add, more;
        String boundId;
        MediaHolder(View itemView, ImageView thumbnail, TextView icon, TextView durationBadge, TextView title, TextView sub, TextView source, TextView add, TextView more) {
            super(itemView);
            this.thumbnail = thumbnail;
            this.icon = icon;
            this.durationBadge = durationBadge;
            this.title = title;
            this.sub = sub;
            this.source = source;
            this.add = add;
            this.more = more;
        }
    }

    private void showItemMenu(TelePremiumMediaItem item) {
        new AlertDialog.Builder(this).setTitle(item.title).setItems(new CharSequence[]{
                "Воспроизвести", "Воспроизвести следующим", "Добавить в очередь"
        }, (d, which) -> {
            if (which == 0) controller.play(item);
            else if (which == 1) controller.getQueue().insertNext(item);
            else controller.getQueue().append(item);
            if (which == 0) TelePremiumPlayerActivity.openCurrent(this);
            else Toast.makeText(this, which == 1 ? "Добавлено следующим" : "Добавлено в очередь", Toast.LENGTH_SHORT).show();
        }).show();
    }

    private String mimeLabel(TelePremiumMediaItem item) {
        if (!TextUtils.isEmpty(item.mimeType)) {
            int slash = item.mimeType.indexOf('/');
            if (slash >= 0 && slash + 1 < item.mimeType.length()) return item.mimeType.substring(slash + 1).toUpperCase(Locale.ROOT);
        }
        return item.isVideo() ? "Видео" : "Аудио";
    }

    private TextView sourceButton(String label) {
        TextView v = button(label, 12, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(roundBg(mediaTheme.surfaceElevated, mediaTheme.cardStroke, 16));
        return v;
    }

    private TextView chip(String label) {
        TextView v = button(label, 11, false);
        v.setBackground(roundBg(mediaTheme.chipBackground, mediaTheme.cardStroke, 18));
        return v;
    }

    private TextView roundActionButton(String label, int sp) {
        TextView v = button(label, sp, true);
        v.setTextColor(mediaTheme.onAccent);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(mediaTheme.accent);
        v.setBackground(d);
        return v;
    }

    private TextView button(String label, int sp, boolean bold) {
        TextView v = text(label, sp, mediaTheme.textPrimary, bold);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(5), 0, dp(5), 0);
        return v;
    }

    private TextView text(String label, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private GradientDrawable roundBg(int color, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams weight(int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1f);
        p.setMargins(dp(3), dp(2), dp(3), dp(2));
        return p;
    }

    private LinearLayout.LayoutParams wrap(int w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, dp(34));
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(float v) { return AndroidUtilities.dp(v); }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "";
        long total = ms / 1000, h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
