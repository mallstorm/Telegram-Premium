package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.telepremium.TelePremiumUiScale;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeController;
import org.telegram.messenger.telepremium.theme.TelePremiumThemePreset;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeRegistry;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeResources;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeStore;
import org.telegram.messenger.telepremium.theme.TelePremiumThemeWallpaper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

public class TelePremiumAppearanceActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        TelePremiumThemeController.ensureShellTheme(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Оформление");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        TelePremiumThemePreset current = TelePremiumThemeController.getCurrent(context);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(current.background);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(-1, -2));

        addTabs(context, content, current);
        addLivePreview(context, content, current);
        addSectionTitle(context, content, "Оболочка приложения", current);
        addInfoRow(context, content, "Акцент оболочки", accentModeLabel(TelePremiumThemeStore.getShellAccentMode(context)), current,
                () -> showAccentDialog(context, false));

        addSectionTitle(context, content, "10 глобальных тем", current);
        for (TelePremiumThemePreset preset : TelePremiumThemeRegistry.all()) addThemeCard(context, content, preset, current.id.equals(preset.id));

        addSectionTitle(context, content, "Чат и сообщения", current);
        addInfoRow(context, content, "Фон чатов", backgroundModeLabel(TelePremiumThemeStore.getChatBackgroundMode(context)), current,
                () -> showChatBackgroundDialog(context));
        addInfoRow(context, content, "Акцент сообщений", accentModeLabel(TelePremiumThemeStore.getChatAccentMode(context)), current,
                () -> showAccentDialog(context, true));
        addInfoRow(context, content, "Скругление пузырей", TelePremiumThemeStore.getBubbleRoundness(context) + " dp", current,
                () -> showBubbleDialog(context));
        addInfoRow(context, content, "Расширенные настройки чатов", "Текст, жесты, медиа и прочие параметры — без смены оболочки", current,
                () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));

        addSectionTitle(context, content, "Размер интерфейса", current);
        addScale(content, context, 85); addScale(content, context, 90); addScale(content, context, 92); addScale(content, context, 95); addScale(content, context, 100);

        TextView reset = actionButton(context, "Сбросить дополнительные настройки оформления", current);
        reset.setOnClickListener(v -> {
            TelePremiumThemeStore.resetAppearance(context);
            TelePremiumThemeController.reapplyCurrent(context, true);
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(54));
        resetLp.topMargin = AndroidUtilities.dp(14);
        content.addView(reset, resetLp);

        fragmentView = scrollView;
        return fragmentView;
    }

    private void addTabs(Context context, LinearLayout parent, TelePremiumThemePreset current) {
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Темы", "Фон чатов", "Акценты"};
        for (int i=0;i<names.length;i++) {
            final int index=i;
            TextView tab=new TextView(context); tab.setText(names[i]); tab.setTextSize(13); tab.setGravity(Gravity.CENTER); tab.setTextColor(i==0?current.accent:current.textSecondary);
            GradientDrawable bg=new GradientDrawable(); bg.setColor(i==0?current.outgoingBubble:current.surface); bg.setCornerRadius(AndroidUtilities.dp(18)); tab.setBackground(bg);
            tab.setOnClickListener(v->{if(index==1)showChatBackgroundDialog(context);else if(index==2)showAccentChooser(context);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,AndroidUtilities.dp(38),1f); if(i>0)lp.leftMargin=AndroidUtilities.dp(7); tabs.addView(tab,lp);
        }
        parent.addView(tabs,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(38)));
    }

    private void addLivePreview(Context context, LinearLayout parent, TelePremiumThemePreset current) {
        LinearLayout preview=new LinearLayout(context); preview.setOrientation(LinearLayout.VERTICAL); preview.setPadding(AndroidUtilities.dp(14),AndroidUtilities.dp(14),AndroidUtilities.dp(14),AndroidUtilities.dp(14)); preview.setBackground(TelePremiumThemeWallpaper.createPreview(current));
        int chatAccent=TelePremiumThemeResources.resolveAccent(context,current,true);
        int outBg=TelePremiumThemeResources.resolveOutgoingBubble(context,current,chatAccent);
        int inBg=TelePremiumThemeStore.getChatBackgroundMode(context)==TelePremiumThemeStore.CHAT_BG_CLEAN?current.surface:current.incomingBubble;
        preview.addView(bubble(context,"Привет! Как дела?",current.textPrimary,inBg),bubbleParams(Gravity.LEFT));
        preview.addView(bubble(context,"Всё отлично!   ✓✓",current.textPrimary,outBg),bubbleParams(Gravity.RIGHT));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(116)); lp.topMargin=AndroidUtilities.dp(12); parent.addView(preview,lp);
    }

    private TextView bubble(Context context,String text,int textColor,int background){TextView v=new TextView(context);v.setText(text);v.setTextSize(13);v.setTextColor(textColor);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(AndroidUtilities.dp(12),0,AndroidUtilities.dp(12),0);GradientDrawable bg=new GradientDrawable();bg.setColor(background);bg.setCornerRadius(AndroidUtilities.dp(TelePremiumThemeStore.getBubbleRoundness(context)));v.setBackground(bg);return v;}
    private LinearLayout.LayoutParams bubbleParams(int gravity){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,AndroidUtilities.dp(38));lp.gravity=gravity;lp.topMargin=AndroidUtilities.dp(7);return lp;}

    private void addThemeCard(Context context,LinearLayout parent,TelePremiumThemePreset preset,boolean selected){
        LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(AndroidUtilities.dp(14),AndroidUtilities.dp(12),AndroidUtilities.dp(14),AndroidUtilities.dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(preset.surface);bg.setCornerRadius(AndroidUtilities.dp(18));bg.setStroke(AndroidUtilities.dp(selected?2:1),selected?preset.accent:preset.divider);card.setBackground(bg);
        LinearLayout top=new LinearLayout(context);top.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(context);icon.setImageResource(preset.iconResource);icon.setColorFilter(preset.accent, PorterDuff.Mode.SRC_IN);top.addView(icon,new LinearLayout.LayoutParams(AndroidUtilities.dp(34),AndroidUtilities.dp(34)));
        LinearLayout texts=new LinearLayout(context);texts.setOrientation(LinearLayout.VERTICAL);TextView title=new TextView(context);title.setText(preset.displayName);title.setTextSize(16);title.setTextColor(preset.textPrimary);title.setTypeface(AndroidUtilities.bold());texts.addView(title);TextView subtitle=new TextView(context);subtitle.setText(themeDescription(preset));subtitle.setTextSize(12);subtitle.setTextColor(preset.textSecondary);texts.addView(subtitle);LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(0,-2,1f);tlp.leftMargin=AndroidUtilities.dp(12);top.addView(texts,tlp);card.addView(top);
        LinearLayout swatches=new LinearLayout(context);int[] colors={preset.accent,preset.outgoingBubble,preset.incomingBubble,preset.background};for(int color:colors){View dot=new View(context);GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(AndroidUtilities.dp(10));d.setStroke(AndroidUtilities.dp(1),preset.divider);dot.setBackground(d);LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(AndroidUtilities.dp(38),AndroidUtilities.dp(18));dl.topMargin=AndroidUtilities.dp(10);dl.rightMargin=AndroidUtilities.dp(7);swatches.addView(dot,dl);}card.addView(swatches);
        TextView apply=actionButton(context,selected?"✓ Выбрана":"Применить",preset);LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(38));alp.topMargin=AndroidUtilities.dp(10);card.addView(apply,alp);
        card.setOnClickListener(v->{if(!selected&&!TelePremiumThemeController.applyTheme(context,preset.id,true))Toast.makeText(context,"Не удалось применить тему",Toast.LENGTH_SHORT).show();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=AndroidUtilities.dp(10);parent.addView(card,lp);
    }

    private String themeDescription(TelePremiumThemePreset p){String id=p.id;if(TelePremiumThemeRegistry.SOFT_GREEN.equals(id))return"Светлая · листья и мягкая зелень";if(TelePremiumThemeRegistry.FROST_MORNING.equals(id))return"Светлая · морозный воздух и горы";if(TelePremiumThemeRegistry.LAVENDER_TWILIGHT.equals(id))return"Светлая · лавандовые сумерки";if(TelePremiumThemeRegistry.SEA_TURQUOISE.equals(id))return"Светлая · бирюзовые волны";if(TelePremiumThemeRegistry.PINK_SAND.equals(id))return"Светлая · розовый песок";if(TelePremiumThemeRegistry.WARM_BEIGE.equals(id))return"Светлая · тёплая бумага и песок";if(TelePremiumThemeRegistry.SUNSET_PEACH.equals(id))return"Светлая · персиковый закат";if(TelePremiumThemeRegistry.GRAPHITE_ICE.equals(id))return"Светлая · графит и ледяные грани";if(TelePremiumThemeRegistry.SILVER_GRAY.equals(id))return"Светлая · чистое серебро";return"Тёмная · глубокий графит";}

    private void showChatBackgroundDialog(Context context){String[] names={"По теме","Чистый фон","Мягкий тематический рисунок"};new AlertDialog.Builder(context).setTitle("Фон чатов").setItems(names,(d,w)->{TelePremiumThemeStore.setChatBackgroundMode(context,w);TelePremiumThemeController.reapplyCurrent(context,true);}).show();}
    private void showAccentChooser(Context context){String[] names={"Акцент оболочки","Акцент сообщений"};new AlertDialog.Builder(context).setTitle("Акценты").setItems(names,(d,w)->showAccentDialog(context,w==1)).show();}
    private void showAccentDialog(Context context,boolean chat){String[] names={"По теме","Мягкий","Контрастный"};new AlertDialog.Builder(context).setTitle(chat?"Акцент сообщений":"Акцент оболочки").setItems(names,(d,w)->{if(chat)TelePremiumThemeStore.setChatAccentMode(context,w);else TelePremiumThemeStore.setShellAccentMode(context,w);TelePremiumThemeController.reapplyCurrent(context,true);}).show();}
    private void showBubbleDialog(Context context){String[] names={"Компактные · 12 dp","Стандартные · 18 dp","Мягкие · 24 dp"};int[] values={12,18,24};new AlertDialog.Builder(context).setTitle("Скругление пузырей").setItems(names,(d,w)->{int value=values[w];TelePremiumThemeStore.setBubbleRoundness(context,value);SharedConfig.bubbleRadius=value;SharedPreferences.Editor e=MessagesController.getGlobalMainSettings().edit();e.putInt("bubbleRadius",value);e.apply();TelePremiumThemeController.reapplyCurrent(context,true);}).show();}

    private String backgroundModeLabel(int mode){return mode==TelePremiumThemeStore.CHAT_BG_CLEAN?"Чистый фон":mode==TelePremiumThemeStore.CHAT_BG_ARTWORK?"Мягкий тематический рисунок":"По теме";}
    private String accentModeLabel(int mode){return mode==TelePremiumThemeStore.MODE_SOFT?"Мягкий":mode==TelePremiumThemeStore.MODE_STRONG?"Контрастный":"По теме";}

    private void addSectionTitle(Context context,LinearLayout parent,String text,TelePremiumThemePreset current){TextView title=new TextView(context);title.setText(text);title.setTextSize(14);title.setTextColor(current.accent);title.setTypeface(AndroidUtilities.bold());title.setPadding(AndroidUtilities.dp(5),AndroidUtilities.dp(16),0,AndroidUtilities.dp(8));parent.addView(title);}
    private void addInfoRow(Context context,LinearLayout parent,String text,String value,TelePremiumThemePreset current,Runnable action){TextView row=new TextView(context);row.setText(text+"\n"+value);row.setTextSize(14);row.setTextColor(current.textPrimary);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(AndroidUtilities.dp(16),0,AndroidUtilities.dp(16),0);GradientDrawable bg=new GradientDrawable();bg.setColor(current.surface);bg.setCornerRadius(AndroidUtilities.dp(14));row.setBackground(bg);row.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(62));lp.bottomMargin=AndroidUtilities.dp(7);parent.addView(row,lp);}
    private TextView actionButton(Context context,String text,TelePremiumThemePreset current){TextView v=new TextView(context);v.setText(text);v.setTextSize(14);v.setTextColor(current.accent);v.setGravity(Gravity.CENTER);GradientDrawable bg=new GradientDrawable();bg.setColor(current.surfaceElevated);bg.setCornerRadius(AndroidUtilities.dp(16));bg.setStroke(AndroidUtilities.dp(1),current.divider);v.setBackground(bg);return v;}

    private void addScale(LinearLayout parent,Context context,int percent){int currentScale=TelePremiumUiScale.getScalePercent(context);TextCell cell=new TextCell(context,getResourceProvider());cell.setTextAndValue(percent+"%",currentScale==percent?"Выбрано":"",true);cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));cell.setOnClickListener(v->{if(TelePremiumUiScale.getScalePercent(context)==percent)return;TelePremiumUiScale.setScalePercent(context,percent);TelePremiumUiScale.apply(context);Toast.makeText(context,"Масштаб интерфейса: "+percent+"%",Toast.LENGTH_SHORT).show();Activity activity=getParentActivity();if(activity!=null)activity.recreate();});parent.addView(cell,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(58)));}
}
