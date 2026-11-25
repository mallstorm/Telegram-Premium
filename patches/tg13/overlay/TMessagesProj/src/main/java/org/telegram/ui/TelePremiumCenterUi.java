package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** Visual system of the AI Center: cards, chips, segmented controls, meters, KPI tiles. */
public final class TelePremiumCenterUi {
  public interface Colors {
    int get(int key);
  }

  private final Context context;
  private final Colors colors;

  public TelePremiumCenterUi(Context context, Colors colors) {
    this.context = context;
    this.colors = colors;
  }

  public Context context() {
    return context;
  }

  public int dp(float v) {
    return AndroidUtilities.dp(v);
  }

  public int page() {
    return colors.get(Theme.key_windowBackgroundGray);
  }

  public int card() {
    return colors.get(Theme.key_windowBackgroundWhite);
  }

  public int text() {
    return colors.get(Theme.key_windowBackgroundWhiteBlackText);
  }

  public int sub() {
    return colors.get(Theme.key_windowBackgroundWhiteGrayText);
  }

  public int accent() {
    return colors.get(Theme.key_featuredStickers_addButton);
  }

  public int onAccent() {
    return colors.get(Theme.key_featuredStickers_buttonText);
  }

  public int line() {
    return colors.get(Theme.key_divider);
  }

  public int good() {
    return 0xff2fa36b;
  }

  public int warn() {
    return 0xffe0a030;
  }

  public int bad() {
    return 0xffd35e62;
  }

  public int series(int i) {
    int[] keys = {
      Theme.key_avatar_backgroundBlue,
      Theme.key_avatar_backgroundGreen,
      Theme.key_avatar_backgroundOrange,
      Theme.key_avatar_backgroundViolet,
      Theme.key_avatar_backgroundCyan,
      Theme.key_avatar_backgroundPink,
      Theme.key_avatar_backgroundRed
    };
    return colors.get(keys[Math.floorMod(i, keys.length)]);
  }

  public static int alpha(int color, int a) {
    return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
  }

  public GradientDrawable shape(int fill, float radiusDp, int stroke, float strokeDp) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(fill);
    g.setCornerRadius(dp(radiusDp));
    if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
    return g;
  }

  public Drawable ripple(Drawable content, float radiusDp) {
    return new RippleDrawable(
        ColorStateList.valueOf(alpha(accent(), 48)), content, shape(0xffffffff, radiusDp, 0, 0));
  }

  public Drawable drawable(int res, int color) {
    Drawable d = androidx.core.content.ContextCompat.getDrawable(context, res);
    if (d == null) return null;
    d = d.mutate();
    d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    return d;
  }

  public ImageView icon(int res, int color, float sizeDp) {
    ImageView v = new ImageView(context);
    v.setImageDrawable(drawable(res, color));
    v.setScaleType(ImageView.ScaleType.FIT_CENTER);
    v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
    return v;
  }

  public TextView text(String s, float sp, int color, boolean bold) {
    TextView v = new TextView(context);
    v.setText(s);
    v.setTextSize(sp);
    v.setTextColor(color);
    if (bold) v.setTypeface(AndroidUtilities.bold());
    return v;
  }

  public LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w, h);
  }

  public LinearLayout.LayoutParams cardParams() {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(12), dp(5), dp(12), dp(5));
    return p;
  }

  public LinearLayout card(float padH, float padV) {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setBackground(shape(card(), 16, line(), 1));
    l.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
    return l;
  }

  public LinearLayout clickableCard(float padH, float padV, View.OnClickListener l) {
    LinearLayout c = card(padH, padV);
    c.setBackground(ripple(shape(card(), 16, line(), 1), 16));
    c.setOnClickListener(l);
    return c;
  }

  public TextView section(String s) {
    TextView v = text(s.toUpperCase(Locale.getDefault()), 12, accent(), true);
    v.setLetterSpacing(0.06f);
    v.setPadding(dp(20), dp(18), dp(20), dp(4));
    return v;
  }

  public View divider() {
    View v = new View(context);
    v.setBackgroundColor(alpha(line(), 160));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
    p.setMargins(0, dp(10), 0, dp(10));
    v.setLayoutParams(p);
    return v;
  }

  public FrameLayout badge(int iconRes, int color, float boxDp, float iconDp) {
    FrameLayout f = new FrameLayout(context);
    f.setBackground(shape(alpha(color, 36), boxDp / 2f, 0, 0));
    ImageView i = icon(iconRes, color, iconDp);
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER);
    f.addView(i, p);
    f.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
    return f;
  }

  public LinearLayout hero(int iconRes, String title, String subtitle) {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    l.setPadding(dp(18), dp(14), dp(18), dp(8));
    FrameLayout b = badge(iconRes, accent(), 48, 26);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(48), dp(48));
    bp.rightMargin = dp(14);
    l.addView(b, bp);
    LinearLayout col = new LinearLayout(context);
    col.setOrientation(LinearLayout.VERTICAL);
    col.addView(text(title, 21, text(), true));
    TextView s = text(subtitle, 13, sub(), false);
    s.setPadding(0, dp(2), 0, 0);
    col.addView(s);
    l.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    return l;
  }

  public LinearLayout kpi(String value, String label, int tint, String foot, int footColor) {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setBackground(shape(card(), 14, line(), 1));
    l.setPadding(dp(12), dp(10), dp(12), dp(10));
    LinearLayout head = new LinearLayout(context);
    head.setGravity(Gravity.CENTER_VERTICAL);
    View dot = new View(context);
    dot.setBackground(shape(tint, 4, 0, 0));
    LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
    dotParams.rightMargin = dp(6);
    head.addView(dot, dotParams);
    TextView lab = text(label, 11.5f, sub(), false);
    lab.setSingleLine(true);
    lab.setEllipsize(android.text.TextUtils.TruncateAt.END);
    head.addView(lab);
    l.addView(head);
    TextView v = text(value, 23, text(), true);
    v.setPadding(0, dp(4), 0, 0);
    v.setSingleLine(true);
    l.addView(v);
    if (foot != null && !foot.isEmpty()) {
      TextView f = text(foot, 11.5f, footColor, false);
      f.setSingleLine(true);
      f.setEllipsize(android.text.TextUtils.TruncateAt.END);
      l.addView(f);
    }
    return l;
  }

  public LinearLayout kpiRow(LinearLayout... tiles) {
    LinearLayout r = new LinearLayout(context);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setPadding(dp(8), dp(5), dp(8), dp(5));
    for (LinearLayout t : tiles) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(4), 0, dp(4), 0);
      r.addView(t, p);
    }
    return r;
  }

  public TextView chip(String s, boolean selected, View.OnClickListener l) {
    TextView v = text(s, 13, selected ? onAccent() : text(), selected);
    v.setGravity(Gravity.CENTER);
    v.setSingleLine(true);
    v.setPadding(dp(14), dp(8), dp(14), dp(8));
    v.setBackground(
        ripple(
            selected ? shape(accent(), 18, 0, 0) : shape(card(), 18, line(), 1), 18));
    if (l != null) v.setOnClickListener(l);
    return v;
  }

  public HorizontalScrollView chipRow() {
    HorizontalScrollView h = new HorizontalScrollView(context);
    h.setHorizontalScrollBarEnabled(false);
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setPadding(dp(10), dp(4), dp(10), dp(4));
    h.addView(l);
    return h;
  }

  public void addChip(HorizontalScrollView row, TextView chip) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.setMargins(dp(3), 0, dp(3), 0);
    ((LinearLayout) row.getChildAt(0)).addView(chip, p);
  }

  public LinearLayout segmented(String[] labels, int selected, IntConsumer pick) {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setBackground(shape(page(), 12, 0, 0));
    l.setPadding(dp(3), dp(3), dp(3), dp(3));
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      boolean on = i == selected;
      TextView v = text(labels[i], 13, on ? onAccent() : text(), on);
      v.setGravity(Gravity.CENTER);
      v.setSingleLine(true);
      v.setEllipsize(android.text.TextUtils.TruncateAt.END);
      v.setPadding(dp(6), dp(9), dp(6), dp(9));
      v.setBackground(on ? shape(accent(), 10, 0, 0) : null);
      v.setOnClickListener(
          w -> {
            for (int k = 0; k < l.getChildCount(); k++) {
              TextView other = (TextView) l.getChildAt(k);
              boolean now = k == index;
              other.setTextColor(now ? onAccent() : text());
              other.setTypeface(now ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
              other.setBackground(now ? shape(accent(), 10, 0, 0) : null);
            }
            pick.accept(index);
          });
      l.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    }
    return l;
  }

  /** Re-colours a chip made by chip() without rebuilding the screen. */
  public void restyleChip(TextView v, boolean selected) {
    v.setTextColor(selected ? onAccent() : text());
    v.setTypeface(selected ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
    v.setBackground(
        ripple(selected ? shape(accent(), 18, 0, 0) : shape(card(), 18, line(), 1), 18));
  }

  /** A chip that flips on and off by itself and reports the new state. */
  public TextView toggleChip(String s, boolean initial, Consumer<Boolean> change) {
    final boolean[] state = {initial};
    final TextView v = chip(s, initial, null);
    v.setOnClickListener(
        w -> {
          state[0] = !state[0];
          restyleChip(v, state[0]);
          change.accept(state[0]);
        });
    return v;
  }

  public TextView button(String s, boolean primary, int iconRes, View.OnClickListener l) {
    int fg = primary ? onAccent() : accent();
    TextView v = text(s, 14, fg, true);
    v.setGravity(Gravity.CENTER);
    v.setMinHeight(dp(44));
    v.setSingleLine(true);
    v.setPadding(dp(16), dp(10), dp(16), dp(10));
    v.setBackground(
        ripple(shape(primary ? accent() : alpha(accent(), 30), 12, 0, 0), 12));
    if (iconRes != 0) {
      Drawable d = drawable(iconRes, fg);
      if (d != null) {
        d.setBounds(0, 0, dp(18), dp(18));
        v.setCompoundDrawables(d, null, null, null);
        v.setCompoundDrawablePadding(dp(8));
      }
    }
    if (l != null) v.setOnClickListener(l);
    return v;
  }

  public LinearLayout buttonRow(TextView... buttons) {
    LinearLayout r = new LinearLayout(context);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setPadding(dp(8), dp(5), dp(8), dp(5));
    for (TextView b : buttons) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(4), 0, dp(4), 0);
      r.addView(b, p);
    }
    return r;
  }

  public LinearLayout row(
      int iconRes, String title, String subtitle, View trailing, View.OnClickListener l) {
    LinearLayout r = new LinearLayout(context);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setMinimumHeight(dp(56));
    r.setPadding(dp(14), dp(10), dp(14), dp(10));
    if (iconRes != 0) {
      FrameLayout b = badge(iconRes, accent(), 36, 20);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(36));
      p.rightMargin = dp(12);
      r.addView(b, p);
    }
    LinearLayout col = new LinearLayout(context);
    col.setOrientation(LinearLayout.VERTICAL);
    TextView t = text(title, 15, text(), false);
    col.addView(t);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView s = text(subtitle, 12.5f, sub(), false);
      s.setPadding(0, dp(2), 0, 0);
      col.addView(s);
    }
    r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    if (trailing != null) r.addView(trailing);
    if (l != null) {
      r.setBackground(ripple(shape(card(), 14, line(), 1), 14));
      r.setOnClickListener(l);
    } else r.setBackground(shape(card(), 14, line(), 1));
    return r;
  }

  public Switch switchView(boolean checked, Consumer<Boolean> change) {
    Switch sw = new Switch(context);
    sw.setChecked(checked);
    int[][] states = {{android.R.attr.state_checked}, {}};
    sw.setThumbTintList(new ColorStateList(states, new int[] {accent(), alpha(sub(), 255)}));
    sw.setTrackTintList(
        new ColorStateList(states, new int[] {alpha(accent(), 110), alpha(sub(), 80)}));
    sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
    return sw;
  }

  public EditText field(String hint, String value, int lines) {
    EditText e = new EditText(context);
    e.setTextColor(text());
    e.setHintTextColor(alpha(sub(), 200));
    e.setHint(hint);
    e.setText(value);
    e.setTextSize(15);
    e.setBackground(shape(page(), 12, 0, 0));
    e.setPadding(dp(14), dp(12), dp(14), dp(12));
    e.setGravity(lines > 1 ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
    if (lines > 1) {
      e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
      e.setMinLines(Math.min(3, lines));
      e.setMaxLines(lines);
    } else {
      e.setSingleLine(true);
    }
    return e;
  }

  public View meter(float fraction, int color, float heightDp) {
    return new MeterView(context, fraction, color, alpha(line(), 220), dp(heightDp));
  }

  public LinearLayout labeledMeter(String left, String right, float fraction, int color) {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    LinearLayout top = new LinearLayout(context);
    TextView a = text(left, 13, text(), false);
    a.setSingleLine(true);
    a.setEllipsize(android.text.TextUtils.TruncateAt.END);
    top.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(text(right, 13, sub(), true));
    l.addView(top);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(6));
    p.topMargin = dp(5);
    l.addView(meter(fraction, color, 6), p);
    return l;
  }

  public TextView pill(String s, int color) {
    TextView v = text(s, 11.5f, color, true);
    v.setSingleLine(true);
    v.setPadding(dp(8), dp(3), dp(8), dp(3));
    v.setBackground(shape(alpha(color, 32), 9, 0, 0));
    return v;
  }

  public LinearLayout empty(int iconRes, String title, String body) {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setGravity(Gravity.CENTER_HORIZONTAL);
    l.setPadding(dp(28), dp(36), dp(28), dp(36));
    FrameLayout b = badge(iconRes, accent(), 72, 36);
    l.addView(b, new LinearLayout.LayoutParams(dp(72), dp(72)));
    TextView t = text(title, 17, text(), true);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, dp(14), 0, dp(4));
    l.addView(t);
    TextView s = text(body, 14, sub(), false);
    s.setGravity(Gravity.CENTER);
    l.addView(s);
    return l;
  }

  private static final class MeterView extends View {
    private final float fraction;
    private final int color, track, height;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    MeterView(Context c, float fraction, int color, int track, int height) {
      super(c);
      this.fraction = Math.max(0f, Math.min(1f, fraction));
      this.color = color;
      this.track = track;
      this.height = height;
    }

    @Override
    protected void onMeasure(int w, int h) {
      setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float r = getHeight() / 2f;
      paint.setColor(track);
      rect.set(0, 0, getWidth(), getHeight());
      canvas.drawRoundRect(rect, r, r, paint);
      if (fraction <= 0f) return;
      paint.setColor(color);
      rect.set(0, 0, Math.max(getHeight(), getWidth() * fraction), getHeight());
      canvas.drawRoundRect(rect, r, r, paint);
    }
  }
}
