package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.telepremium.media.TelePremiumPlaybackController;
import org.telegram.messenger.telepremium.media.TelePremiumPlaylistRepository;
import org.telegram.messenger.telepremium.theme.TelePremiumMediaTheme;

import java.util.ArrayList;
import java.util.List;

/** Saved mixed-source playlist manager using the active TelePremium shell theme. */
public final class TelePremiumPlaylistActivity extends Activity {
    private final TelePremiumPlaylistRepository repository = TelePremiumPlaylistRepository.getInstance();
    private LinearLayout list;
    private TelePremiumMediaTheme mediaTheme;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaTheme = TelePremiumMediaTheme.from(this);
        mediaTheme.applySystemBars(this);
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mediaTheme.background);
        root.setPadding(dp(12), dp(8), dp(12), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34, mediaTheme.textPrimary, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(52)));
        TextView title = text(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_332", org.telegram.messenger.R.string.TP_L10N_332), 22, mediaTheme.textPrimary, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        render();
    }

    private void render() {
        list.removeAllViews();
        List<TelePremiumPlaylistRepository.Playlist> playlists = repository.getAll();
        if (playlists.isEmpty()) {
            TextView empty = text(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_486", org.telegram.messenger.R.string.TP_L10N_486), 15, mediaTheme.textSecondary, false);
            empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(180)));
            return;
        }

        for (TelePremiumPlaylistRepository.Playlist playlist : playlists) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(7), dp(7));
            row.setBackground(roundBg(mediaTheme.cardBackground, mediaTheme.cardStroke, 15));

            TextView info = text(playlist.name + "\n" + playlist.items.size() + org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_324", org.telegram.messenger.R.string.TP_L10N_324), 15, mediaTheme.textPrimary, true);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(60), 1f));

            TextView play = text("▶", 22, mediaTheme.accent, false);
            play.setGravity(Gravity.CENTER);
            play.setOnClickListener(v -> {
                if (playlist.items.isEmpty()) return;
                TelePremiumPlaybackController.getInstance().getQueue().replace(new ArrayList<>(playlist.items), 0);
                TelePremiumPlaybackController.getInstance().playCurrent();
                TelePremiumPlayerActivity.openCurrent(this);
            });
            row.addView(play, new LinearLayout.LayoutParams(dp(48), dp(54)));

            TextView delete = text("⌫", 21, mediaTheme.textSecondary, false);
            delete.setGravity(Gravity.CENTER);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_487", org.telegram.messenger.R.string.TP_L10N_487))
                    .setMessage(playlist.name)
                    .setPositiveButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_133", org.telegram.messenger.R.string.TP_L10N_133), (d, w) -> { repository.delete(playlist.id); render(); })
                    .setNegativeButton(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_135", org.telegram.messenger.R.string.TP_L10N_135), null)
                    .show());
            row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(54)));

            info.setOnClickListener(v -> {
                TelePremiumPlaybackController.getInstance().getQueue().replace(new ArrayList<>(playlist.items), 0);
                Toast.makeText(this, org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_488", org.telegram.messenger.R.string.TP_L10N_488) + playlist.name, Toast.LENGTH_SHORT).show();
            });

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(76));
            rp.setMargins(0, dp(3), 0, dp(3));
            list.addView(row, rp);
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
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

    private int dp(float value) { return AndroidUtilities.dp(value); }
}
