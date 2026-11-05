package org.telegram.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.media.TelePremiumArtworkLoader;
import org.telegram.messenger.telepremium.media.TelePremiumMediaItem;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.media.TelePremiumPlaylistRepository;
import org.telegram.messenger.telepremium.media.TelePremiumQueueManager;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Playback queue as a bottom sheet: "Now playing" card, then every item with its thumbnail
 * (video frame / YouTube preview / cover / note tile), title, artist · duration · source.
 * Drag the handle to reorder, swipe to remove, tap to play. Header: shuffle, repeat,
 * save as playlist, clear the upcoming items.
 */
final class TelePremiumQueueSheet implements TelePremiumQueueManager.Listener {
    private final Activity activity;
    private final TelePremiumMediaTheme theme;
    private final TelePremiumPlayerKit kit;
    private final TelePremiumPlayerKit.Palette pal;
    private final TelePremiumPlaybackController controller = TelePremiumPlaybackController.getInstance();
    private final TelePremiumQueueManager queue;
    private final ArrayList<TelePremiumMediaItem> items = new ArrayList<>();
    private int currentIndex = -1;
    private Dialog dialog;
    private Adapter adapter;
    private RecyclerView list;
    private TextView subtitle;
    private ImageView shuffle, repeat;
    private LinearLayout nowCard;

    private TelePremiumQueueSheet(Activity activity, TelePremiumMediaTheme theme) {
        this.activity = activity;
        this.theme = theme;
        this.kit = new TelePremiumPlayerKit(activity);
        this.pal = TelePremiumPlayerKit.Palette.surface(theme);
        this.queue = controller.getQueue();
    }

    static void show(Activity activity, TelePremiumMediaTheme theme) {
        new TelePremiumQueueSheet(activity, theme).open();
    }

    private static int dp(float v) { return TelePremiumPlayerKit.dp(v); }

    private static String t(String key, int res) { return TPStrings.get(key, res); }

    private void open() {
        dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.surfaceElevated);
        bg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(dp(16), dp(8), dp(16), dp(12));
        root.setClickable(true);

        View grab = new View(activity);
        grab.setBackground(TelePremiumPlayerKit.round(TelePremiumPlayerKit.withAlpha(pal.muted, 0x66), 0, 3, 0));
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(dp(40), dp(5));
        grabLp.gravity = Gravity.CENTER_HORIZONTAL;
        grabLp.bottomMargin = dp(10);
        root.addView(grab, grabLp);

        // header
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(kit.text(t("TP_P_QUEUE", R.string.TP_P_QUEUE), 21, pal.text, true));
        subtitle = kit.singleLine(kit.text("", 13, pal.muted, false));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(3);
        titles.addView(subtitle, subLp);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        shuffle = kit.iconButton(R.drawable.tp_ic_shuffle, pal.text, 22, t("TP_Q_SHUFFLE", R.string.TP_Q_SHUFFLE), v -> {
            queue.setShuffle(!queue.isShuffle());
            refreshHeader();
        });
        header.addView(shuffle, new LinearLayout.LayoutParams(dp(42), dp(42)));
        repeat = kit.iconButton(R.drawable.tp_ic_repeat, pal.text, 22, t("TP_Q_REPEAT", R.string.TP_Q_REPEAT), v -> {
            int mode = queue.getRepeatMode();
            queue.setRepeatMode(mode == TelePremiumQueueManager.REPEAT_OFF ? TelePremiumQueueManager.REPEAT_ALL
                    : mode == TelePremiumQueueManager.REPEAT_ALL ? TelePremiumQueueManager.REPEAT_ONE : TelePremiumQueueManager.REPEAT_OFF);
            refreshHeader();
        });
        header.addView(repeat, new LinearLayout.LayoutParams(dp(42), dp(42)));
        header.addView(kit.iconButton(R.drawable.tp_ic_playlist_add, pal.text, 22, t("TP_L10N_337", R.string.TP_L10N_337), v -> saveAsPlaylist()),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        header.addView(kit.iconButton(R.drawable.tp_ic_clear_all, pal.text, 22, t("TP_Q_CLEAR", R.string.TP_Q_CLEAR), v -> clearUpcoming()),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // now playing
        nowCard = new LinearLayout(activity);
        nowCard.setGravity(Gravity.CENTER_VERTICAL);
        nowCard.setPadding(dp(10), dp(10), dp(12), dp(10));
        nowCard.setBackground(TelePremiumPlayerKit.round(theme.chipSelectedBackground, pal.accent, 18, 1.5f));
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(-1, -2);
        nowLp.topMargin = dp(12);
        root.addView(nowCard, nowLp);

        TextView hint = kit.singleLine(kit.text(t("TP_Q_HINT", R.string.TP_Q_HINT), 12, pal.muted, false));
        hint.setPadding(dp(4), dp(12), 0, dp(6));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter();
        list.setAdapter(adapter);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        root.addView(list, new LinearLayout.LayoutParams(-1, Math.min(dp(520), (int) (screenH * 0.55f))));
        attachTouchHelper();

        TextView library = kit.text(t("TP_L10N_484", R.string.TP_L10N_484), 15, pal.accent, true);
        library.setGravity(Gravity.CENTER);
        library.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(theme.cardBackground, theme.cardStroke, 16, 1), pal.accent));
        library.setOnClickListener(v -> {
            activity.startActivity(new android.content.Intent(activity, TelePremiumMediaCenterActivity.class));
            dialog.dismiss();
        });
        LinearLayout.LayoutParams libLp = new LinearLayout.LayoutParams(-1, dp(48));
        libLp.topMargin = dp(10);
        root.addView(library, libLp);

        // landscape: keep the sheet readable instead of spanning a wide screen
        FrameLayout holder = new FrameLayout(activity);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        holder.addView(root, new FrameLayout.LayoutParams(Math.min(screenW, dp(640)), -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        holder.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(holder);
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
        dialog.setOnDismissListener(d -> queue.removeListener(this));
        queue.addListener(this);
        reload();
        dialog.show();
        if (currentIndex > 0) list.scrollToPosition(Math.max(0, currentIndex - 1));
    }

    @Override public void onQueueChanged(List<TelePremiumMediaItem> newItems, int index) {
        activity.runOnUiThread(this::reload);
    }

    private void reload() {
        items.clear();
        items.addAll(queue.snapshot());
        currentIndex = queue.getCurrentIndex();
        adapter.notifyDataSetChanged();
        refreshHeader();
        bindNowCard();
    }

    private void refreshHeader() {
        int upcoming = Math.max(0, items.size() - currentIndex - 1);
        long left = 0;
        for (int i = Math.max(0, currentIndex + 1); i < items.size(); i++) left += Math.max(0, items.get(i).duration);
        boolean video = !items.isEmpty() && items.get(0).isVideo();
        String count = TPStrings.format(video ? "TP_Q_COUNT_VIDEO" : "TP_Q_COUNT_AUDIO",
                video ? R.string.TP_Q_COUNT_VIDEO : R.string.TP_Q_COUNT_AUDIO, items.size());
        subtitle.setText(count + (upcoming > 0 && left > 0 ? " · " + TPStrings.format("TP_Q_LEFT", R.string.TP_Q_LEFT, formatLong(left)) : ""));
        kit.setIcon(shuffle, R.drawable.tp_ic_shuffle, queue.isShuffle() ? pal.accent : pal.muted);
        int mode = queue.getRepeatMode();
        kit.setIcon(repeat, mode == TelePremiumQueueManager.REPEAT_ONE ? R.drawable.tp_ic_repeat_one : R.drawable.tp_ic_repeat,
                mode == TelePremiumQueueManager.REPEAT_OFF ? pal.muted : pal.accent);
    }

    private void bindNowCard() {
        nowCard.removeAllViews();
        TelePremiumMediaItem item = currentIndex >= 0 && currentIndex < items.size() ? items.get(currentIndex) : null;
        if (item == null) {
            nowCard.setVisibility(View.GONE);
            return;
        }
        nowCard.setVisibility(View.VISIBLE);
        nowCard.addView(thumb(item, item.isVideo() ? 112 : 64, 64), new LinearLayout.LayoutParams(dp(item.isVideo() ? 112 : 64), dp(64)));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = kit.singleLine(kit.text("▶  " + t("TP_Q_NOW", R.string.TP_Q_NOW), 12, pal.accent, true));
        texts.addView(label);
        TextView title = kit.text(cleanTitle(item), 16, pal.text, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(3);
        texts.addView(title, tlp);
        TextView sub = kit.singleLine(kit.text(subtitleFor(item), 12, pal.muted, false));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(2);
        texts.addView(sub, slp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(12);
        nowCard.addView(texts, lp);
    }

    private void saveAsPlaylist() {
        if (items.isEmpty()) return;
        EditText name = new EditText(activity);
        name.setHint(t("TP_L10N_336", R.string.TP_L10N_336));
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(activity).setTitle(t("TP_L10N_337", R.string.TP_L10N_337)).setView(box)
                .setPositiveButton(t("TP_L10N_137", R.string.TP_L10N_137), (d, w) -> {
                    String title = name.getText().toString().trim();
                    TelePremiumPlaylistRepository.getInstance().save(null, TextUtils.isEmpty(title) ? t("TP_L10N_338", R.string.TP_L10N_338) : title, new ArrayList<>(items));
                    Toast.makeText(activity, t("TP_L10N_339", R.string.TP_L10N_339), Toast.LENGTH_SHORT).show();
                }).setNegativeButton(t("TP_L10N_135", R.string.TP_L10N_135), null).show();
    }

    private void clearUpcoming() {
        for (int i = items.size() - 1; i > currentIndex; i--) queue.remove(i);
        Toast.makeText(activity, t("TP_Q_CLEARED", R.string.TP_Q_CLEARED), Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- list

    private void attachTouchHelper() {
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public boolean isLongPressDragEnabled() { return true; }

            @Override public int getSwipeDirs(RecyclerView rv, RecyclerView.ViewHolder vh) {
                return vh.getAdapterPosition() == currentIndex ? 0 : super.getSwipeDirs(rv, vh);
            }

            @Override public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a < 0 || b < 0) return false;
                TelePremiumMediaItem moved = items.remove(a);
                items.add(b, moved);
                if (currentIndex == a) currentIndex = b;
                else if (a < currentIndex && b >= currentIndex) currentIndex--;
                else if (a > currentIndex && b <= currentIndex) currentIndex++;
                adapter.notifyItemMoved(a, b);
                queue.removeListener(TelePremiumQueueSheet.this);
                queue.move(a, b);
                queue.addListener(TelePremiumQueueSheet.this);
                return true;
            }

            @Override public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                int position = vh.getAdapterPosition();
                if (position < 0) return;
                TelePremiumMediaItem removed = items.get(position);
                queue.remove(position);
                Toast.makeText(activity, TPStrings.format("TP_Q_REMOVED", R.string.TP_Q_REMOVED, cleanTitle(removed)), Toast.LENGTH_SHORT).show();
            }

            @Override public void onChildDraw(Canvas c, RecyclerView rv, RecyclerView.ViewHolder vh, float dX, float dY, int state, boolean active) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0) {
                    View v = vh.itemView;
                    paint.setColor(0xffe5484d);
                    RectF r = dX > 0 ? new RectF(v.getLeft(), v.getTop() + dp(3), v.getLeft() + dX, v.getBottom() - dp(3))
                            : new RectF(v.getRight() + dX, v.getTop() + dp(3), v.getRight(), v.getBottom() - dp(3));
                    c.drawRoundRect(r, dp(14), dp(14), paint);
                }
                super.onChildDraw(c, rv, vh, dX, dY, state, active);
            }
        });
        helper.attachToRecyclerView(list);
        adapter.helper = helper;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        ItemTouchHelper helper;

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(2), dp(6));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.setMargins(0, dp(3), 0, dp(3));
            row.setLayoutParams(lp);
            FrameLayout thumbBox = new FrameLayout(activity);
            row.addView(thumbBox, new LinearLayout.LayoutParams(dp(88), dp(50)));
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = kit.text("", 14, pal.text, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = kit.singleLine(kit.text("", 12, pal.muted, false));
            texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(3);
            texts.addView(sub, subLp);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.setMargins(dp(12), 0, dp(4), 0);
            row.addView(texts, tlp);
            ImageView handle = kit.icon(R.drawable.tp_ic_drag, pal.muted);
            handle.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.addView(handle, new LinearLayout.LayoutParams(dp(44), dp(44)));
            return new Holder(row, thumbBox, title, sub, handle);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public void onBindViewHolder(Holder h, int position) {
            TelePremiumMediaItem item = items.get(position);
            boolean current = position == currentIndex;
            boolean played = currentIndex >= 0 && position < currentIndex;
            h.itemView.setBackground(TelePremiumPlayerKit.ripple(TelePremiumPlayerKit.round(current ? theme.chipSelectedBackground : theme.cardBackground,
                    current ? pal.accent : theme.cardStroke, 16, current ? 1.5f : 1), pal.accent));
            h.itemView.setAlpha(played ? 0.55f : 1f);
            h.thumbBox.removeAllViews();
            int w = item.isVideo() ? 88 : 50;
            h.thumbBox.getLayoutParams().width = dp(w);
            h.thumbBox.addView(thumb(item, w, 50), new FrameLayout.LayoutParams(-1, -1));
            h.title.setText((current ? "▶ " : "") + cleanTitle(item));
            h.title.setTextColor(current ? pal.accent : pal.text);
            h.sub.setText(subtitleFor(item));
            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p < 0) return;
                queue.setCurrentIndex(p);
                controller.playCurrent();
                dialog.dismiss();
            });
            h.handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && helper != null) helper.startDrag(h);
                return false;
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout thumbBox;
        final TextView title, sub;
        final ImageView handle;
        Holder(View v, FrameLayout thumbBox, TextView title, TextView sub, ImageView handle) {
            super(v);
            this.thumbBox = thumbBox;
            this.title = title;
            this.sub = sub;
            this.handle = handle;
        }
    }

    // ---------------------------------------------------------------- pieces

    /** Rounded thumbnail: real picture when available, otherwise a theme tile with a glyph; duration badge on top. */
    private View thumb(TelePremiumMediaItem item, int widthDp, int heightDp) {
        FrameLayout box = new FrameLayout(activity);
        box.setBackground(TelePremiumPlayerKit.round(theme.artworkBackground, 0, 10, 0));
        box.setClipToOutline(true);
        ImageView glyph = kit.icon(item.isVideo() ? (item.live ? R.drawable.tp_ic_tv : R.drawable.tp_ic_video)
                : item.live ? R.drawable.tp_ic_radio : R.drawable.tp_ic_music, pal.accent);
        box.addView(glyph, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        ImageView image = new ImageView(activity);
        image.setScaleType(item.live && !item.isVideo() ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(View.GONE);
        box.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (item.duration > 0 || item.live) {
            TextView badge = kit.text(item.live ? "LIVE" : formatShort(item.duration), 10, Color.WHITE, true);
            badge.setPadding(dp(4), dp(1), dp(4), dp(1));
            badge.setBackground(TelePremiumPlayerKit.round(item.live ? 0xdde53935 : 0xb3000000, 0, 5, 0));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
            blp.setMargins(0, 0, dp(3), dp(3));
            if (widthDp >= 64) box.addView(badge, blp);
        }
        final String id = item.id;
        TelePremiumArtworkLoader.load(activity, item, dp(Math.max(widthDp, heightDp) * 2), bitmap -> {
            if (bitmap == null || !id.equals(item.id)) return;
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            glyph.setVisibility(View.GONE);
        });
        return box;
    }

    private String subtitleFor(TelePremiumMediaItem item) {
        StringBuilder b = new StringBuilder();
        String artist = item.artist;
        if (!TextUtils.isEmpty(artist) && !"<unknown>".equals(artist)) b.append(artist);
        if (item.duration > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(formatShort(item.duration));
        }
        String source = org.telegram.messenger.telepremium.media.TelePremiumYouTube.isYouTube(item) ? "YouTube" : item.sourceLabel();
        if (b.length() > 0) b.append(" · ");
        b.append(source);
        return b.toString();
    }

    /** "Morozoff_-_Some_Song.mp3" → "Morozoff - Some Song". */
    static String cleanTitle(TelePremiumMediaItem item) {
        String title = item.title == null ? "" : item.title;
        if (!item.isVideo() || item.sourceType == TelePremiumMediaItem.SOURCE_DEVICE) {
            int dot = title.lastIndexOf('.');
            if (dot > 0 && title.length() - dot <= 5) title = title.substring(0, dot);
        }
        if (!title.contains(" ") && title.contains("_")) title = title.replace('_', ' ');
        return title.replaceAll("\\s+-\\s+|\\s*-\\s*-\\s*", " - ").replaceAll("\\s{2,}", " ").trim();
    }

    private static String formatShort(long ms) {
        long total = ms / 1000, h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private static String formatLong(long ms) {
        long minutes = Math.max(1, ms / 60000);
        if (minutes >= 60) return String.format(Locale.ROOT, TPStrings.isRussian() ? "%d ч %d мин" : "%dh %dm", minutes / 60, minutes % 60);
        return minutes + (TPStrings.isRussian() ? " мин" : " min");
    }
}
