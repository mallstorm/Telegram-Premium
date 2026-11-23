package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumShell;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** Picker for the two application shells; both run on the same Telegram 13.1.1 engine and theme. */
public final class TelePremiumInterfaceModeActivity extends BaseFragment {
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(TPStrings.get("TP_ShellTitle", R.string.TP_ShellTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        TextView explanation = new TextView(context);
        explanation.setText(TPStrings.get("TP_ShellExplain", R.string.TP_ShellExplain));
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        explanation.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
        explanation.setPadding(dp(21), dp(18), dp(21), dp(18));
        body.addView(explanation);
        addChoice(body, context, TPStrings.get("TP_ShellClassic", R.string.TP_ShellClassic),
                TPStrings.get("TP_ShellClassicInfo", R.string.TP_ShellClassicInfo), true);
        addChoice(body, context, TPStrings.get("TP_ShellModern", R.string.TP_ShellModern),
                TPStrings.get("TP_ShellModernInfo", R.string.TP_ShellModernInfo), false);
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        root.addView(body, new FrameLayout.LayoutParams(-1, -2));
        fragmentView = root;
        return fragmentView;
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private final class Radio extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        Radio(Context context) {
            super(context);
        }

        void setChecked(boolean value) {
            checked = value;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(24), dp(24));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getMeasuredWidth() / 2f, cy = getMeasuredHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(getThemedColor(checked ? Theme.key_radioBackgroundChecked : Theme.key_radioBackground));
            canvas.drawCircle(cx, cy, dp(9), paint);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, dp(5), paint);
            }
        }
    }

    private void addChoice(LinearLayout body, Context context, String title, String subtitle, boolean classic) {
        LinearLayout cell = new LinearLayout(context);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(21), dp(14), dp(21), dp(14));
        cell.setBackground(Theme.getSelectorDrawable(true));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        heading.setTypeface(AndroidUtilities.bold());
        TextView desc = new TextView(context);
        desc.setText(subtitle);
        desc.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        texts.addView(heading);
        texts.addView(desc);
        cell.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Radio radio = new Radio(context);
        radio.setChecked(TelePremiumShell.isClassic() == classic);
        cell.addView(radio);
        FrameLayout holder = new FrameLayout(context);
        holder.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        holder.addView(cell, new FrameLayout.LayoutParams(-1, -2));
        holder.setOnClickListener(v -> {
            if (TelePremiumShell.isClassic() == classic) return;
            radio.setChecked(true);
            TelePremiumShell.switchTo(getParentActivity(), classic);
        });
        cell.setClickable(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(1);
        body.addView(holder, lp);
    }
}
