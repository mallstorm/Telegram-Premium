package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.theme.TelePremiumChatWallpapers;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeRegistry;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat wallpapers, independent from the colour preset and the shell: generated backgrounds, an automatic
 * one that follows the preset, and the entry to Telegram's own catalog / gallery / colour picker.
 */
public class TelePremiumChatWallpapersActivity extends BaseFragment {
    private static final int COLUMNS = 3;

    private final List<Thumb> thumbs = new ArrayList<>();
    private TextView catalogHint;

    private String t(String en, String ru) {
        return TPStrings.isRussian() ? ru : en;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(t("Chat wallpapers", "Обои чатов"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout screen = new FrameLayout(context);
        screen.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(12);
        column.setPadding(pad, pad, pad, AndroidUtilities.dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        screen.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView note = label(context, t("They do not depend on the colour theme or the app shell: pick once, it stays everywhere.",
                "Не зависят от цветовой темы и оболочки: выберите один раз — и они остаются везде."), 13, Theme.key_windowBackgroundWhiteGrayText2);
        note.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), AndroidUtilities.dp(10));
        column.addView(note, new LinearLayout.LayoutParams(-1, -2));

        int screenWidth = AndroidUtilities.displaySize.x;
        int gap = AndroidUtilities.dp(8);
        int cellWidth = (screenWidth - pad * 2 - gap * (COLUMNS - 1)) / COLUMNS;
        int cellHeight = (int) (cellWidth * 1.55f);

        List<TelePremiumChatWallpapers.Design> designs = TelePremiumChatWallpapers.designs();
        // first cell is "auto", then the designs
        int total = designs.size() + 1;
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = gap;
                column.addView(row, rlp);
            }
            final String mode = i == 0 ? TelePremiumChatWallpapers.MODE_AUTO : TelePremiumChatWallpapers.DESIGN_PREFIX + designs.get(i - 1).id;
            String title = i == 0 ? t("Auto", "Авто") : (TPStrings.isRussian() ? designs.get(i - 1).ru : designs.get(i - 1).en);
            Thumb thumb = new Thumb(context, mode, title, i == 0 ? null : designs.get(i - 1), cellWidth, cellHeight);
            thumbs.add(thumb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellWidth, cellHeight);
            if (i % COLUMNS != COLUMNS - 1) lp.rightMargin = gap;
            row.addView(thumb, lp);
            thumb.setOnClickListener(v -> {
                TelePremiumChatWallpapers.select(mode);
                refreshSelection();
            });
        }

        TextView catalog = label(context, t("Telegram catalog, photo from gallery, solid colour…", "Каталог Telegram, фото из галереи, цвет…"), 16, Theme.key_windowBackgroundWhiteBlueText4);
        catalog.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        catalog.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 8, 8));
        catalog.setOnClickListener(v -> presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = AndroidUtilities.dp(6);
        column.addView(catalog, clp);
        catalogHint = label(context, "", 12, Theme.key_windowBackgroundWhiteGrayText2);
        catalogHint.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        column.addView(catalogHint, new LinearLayout.LayoutParams(-1, -2));

        fragmentView = screen;
        refreshSelection();
        return fragmentView;
    }

    private TextView label(Context context, String text, int sp, int colorKey) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        v.setTextColor(getThemedColor(colorKey));
        v.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        return v;
    }

    private void refreshSelection() {
        String mode = TelePremiumChatWallpapers.mode();
        for (Thumb th : thumbs) th.setSelectedMode(th.mode.equals(mode));
        if (catalogHint != null) {
            catalogHint.setText(TelePremiumChatWallpapers.MODE_STOCK.equals(mode)
                    ? t("Your own wallpaper from Telegram is in use.", "Сейчас используются ваши обои из каталога Telegram или галереи.")
                    : "");
            catalogHint.setVisibility(TelePremiumChatWallpapers.MODE_STOCK.equals(mode) ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSelection();
    }

    private final class Thumb extends View {
        final String mode;
        private final String title;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private Bitmap bitmap;
        private boolean selected;

        Thumb(Context context, String mode, String title, TelePremiumChatWallpapers.Design design, int w, int h) {
            super(context);
            this.mode = mode;
            this.title = title;
            setClickable(true);
            final String presetId = TelePremiumThemeStore.getThemeId(ApplicationLoader.applicationContext, TelePremiumThemeRegistry.DEFAULT_THEME);
            Utilities.globalQueue.postRunnable(() -> {
                Bitmap b;
                try {
                    b = design != null
                            ? TelePremiumChatWallpapers.render(design, 240, 420)
                            : TelePremiumChatWallpapers.renderAuto(TelePremiumThemeRegistry.get(presetId), 240, 420);
                } catch (Throwable e) {
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    bitmap = b;
                    invalidate();
                });
            });
        }

        void setSelectedMode(boolean value) {
            selected = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = AndroidUtilities.dp(12);
            rect.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, null);
            } else {
                paint.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                canvas.drawRect(rect, paint);
            }
            // two sample bubbles show how messages sit on this background
            paint.setStyle(Paint.Style.FILL);
            boolean dark = bitmap != null && mode.startsWith(TelePremiumChatWallpapers.DESIGN_PREFIX)
                    && TelePremiumChatWallpapers.design(mode.substring(TelePremiumChatWallpapers.DESIGN_PREFIX.length())) != null
                    && TelePremiumChatWallpapers.design(mode.substring(TelePremiumChatWallpapers.DESIGN_PREFIX.length())).dark;
            paint.setColor(dark ? 0x33FFFFFF : 0xB3FFFFFF);
            float bw = getWidth() * 0.55f, bh = AndroidUtilities.dp(18);
            canvas.drawRoundRect(getWidth() * 0.10f, getHeight() * 0.28f, getWidth() * 0.10f + bw, getHeight() * 0.28f + bh, bh / 2, bh / 2, paint);
            paint.setColor(getThemedColor(Theme.key_featuredStickers_addButton) & 0x00FFFFFF | 0xB3000000);
            canvas.drawRoundRect(getWidth() * 0.90f - bw * 0.8f, getHeight() * 0.28f + bh * 1.5f, getWidth() * 0.90f, getHeight() * 0.28f + bh * 2.5f, bh / 2, bh / 2, paint);
            canvas.restore();

            // caption chip
            paint.setTextSize(AndroidUtilities.dp(11));
            paint.setTypeface(AndroidUtilities.bold());
            float tw = paint.measureText(title);
            float chipW = tw + AndroidUtilities.dp(14), chipH = AndroidUtilities.dp(20);
            float cx = getWidth() / 2f, cy = getHeight() - AndroidUtilities.dp(18);
            paint.setColor(0x99000000);
            canvas.drawRoundRect(cx - chipW / 2, cy - chipH / 2, cx + chipW / 2, cy + chipH / 2, chipH / 2, chipH / 2, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText(title, cx - tw / 2, cy + AndroidUtilities.dp(4), paint);

            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(3));
                paint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                rect.inset(AndroidUtilities.dp(1.5f), AndroidUtilities.dp(1.5f));
                canvas.drawRoundRect(rect, r, r, paint);
                paint.setStyle(Paint.Style.FILL);
                float rad = AndroidUtilities.dp(11);
                float ox = getWidth() - AndroidUtilities.dp(20), oy = AndroidUtilities.dp(20);
                canvas.drawCircle(ox, oy, rad, paint);
                paint.setColor(0xFFFFFFFF);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(AndroidUtilities.dp(2));
                canvas.drawLine(ox - rad * 0.4f, oy, ox - rad * 0.1f, oy + rad * 0.3f, paint);
                canvas.drawLine(ox - rad * 0.1f, oy + rad * 0.3f, ox + rad * 0.45f, oy - rad * 0.35f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
