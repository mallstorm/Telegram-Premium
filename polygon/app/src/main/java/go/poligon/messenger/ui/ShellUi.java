package go.poligon.messenger.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Two independently implemented Polygon layouts, modeled on the familiar
 * compact 11.x drawer/settings and the 13.x bottom-tab/navigation patterns.
 * This file does not embed or call Telegram Android activities.
 */
public final class ShellUi {
    public interface Actions {
        void navigate(String destination);
        void back();
        void redraw();
        void refreshChats();
    }

    private static final int CLASSIC_BLUE=0xff598fbd;
    private final Activity activity;
    private final ShellPreferences preferences;
    private final Actions actions;

    public ShellUi(Activity activity, ShellPreferences preferences, Actions actions) {
        this.activity=activity;
        this.preferences=preferences;
        this.actions=actions;
    }

    public boolean classic() { return preferences.classic(); }
    public ShellPreferences.Palette palette() { return preferences.palette(); }
    public int dp(float v) { return (int)(v*activity.getResources().getDisplayMetrics().density+0.5f); }

    public GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    public TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(activity);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    public void gap(LinearLayout view,int height) {
        View v=new View(activity);
        view.addView(v,new LinearLayout.LayoutParams(1,dp(height)));
    }

    private TextView clickable(String label,int size,int color,Runnable onClick) {
        TextView t=text(label,size,color,true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11),dp(10),dp(11),dp(10));
        t.setOnClickListener(v->onClick.run());
        return t;
    }

    public void header(LinearLayout root,String title,boolean inChat,boolean ready,boolean home) {
        ShellPreferences.Palette p=palette();
        boolean isClassic=classic();
        int fill=isClassic?CLASSIC_BLUE:p.surface;
        int foreground=isClassic?Color.WHITE:p.text;
        LinearLayout bar=new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8),dp(4),dp(9),dp(4));
        bar.setBackgroundColor(fill);
        boolean back=inChat || (ready && !home);
        TextView left=clickable(back?"‹":isClassic?"☰":"⬡",28,foreground,
            back?actions::back:isClassic?this::showDrawer:()->actions.navigate("chats"));
        bar.addView(left,new LinearLayout.LayoutParams(dp(51),dp(51)));
        LinearLayout captions=new LinearLayout(activity);
        captions.setOrientation(LinearLayout.VERTICAL);
        captions.setPadding(dp(8),0,0,0);
        TextView main=text(title,19,foreground,true);
        main.setSingleLine(true);
        captions.addView(main);
        if (!isClassic && home && ready) captions.addView(text("POLYGON MESSENGER",10,p.muted,false));
        bar.addView(captions,new LinearLayout.LayoutParams(0,-2,1));
        if(ready && home) {
            bar.addView(clickable("↻",23,foreground,actions::refreshChats));
            bar.addView(clickable("⌕",25,foreground,()->actions.navigate("search")));
        } else if(ready && !back && !isClassic) {
            bar.addView(clickable("⚙",20,foreground,()->actions.navigate("settings")));
        }
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(isClassic?58:64)));
        View divider=new View(activity);
        divider.setBackgroundColor(p.divider);
        root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }

    public void bottomNavigation(LinearLayout root,String selected) {
        if(classic())return;
        ShellPreferences.Palette p=palette();
        LinearLayout bar=new LinearLayout(activity);
        bar.setBackgroundColor(p.surface);
        bar.setGravity(Gravity.CENTER);
        addTab(bar,"◉","Чаты","chats",selected);
        addTab(bar,"☏","Звонки","calls",selected);
        addTab(bar,"♙","Контакты","contacts",selected);
        addTab(bar,"☁","Облако","cloud",selected);
        addTab(bar,"⚙","Настройки","settings",selected);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(63)));
    }

    private void addTab(LinearLayout bar,String icon,String title,String section,String selected) {
        ShellPreferences.Palette p=palette();
        int color=section.equals(selected)?p.accent:p.muted;
        LinearLayout item=new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.addView(text(icon,22,color,false));
        item.addView(text(title,10,color,section.equals(selected)));
        item.setOnClickListener(v->actions.navigate(section));
        bar.addView(item,new LinearLayout.LayoutParams(0,-1,1));
    }

    private void group(LinearLayout parent,String title) {
        ShellPreferences.Palette p=palette();
        TextView t=text(title,classic()?13:15,classic()?p.muted:p.accent,true);
        t.setPadding(dp(8),dp(21),0,dp(12));
        parent.addView(t);
    }

    private void row(LinearLayout parent,String symbol,String title,String value,String section) {
        ShellPreferences.Palette p=palette();
        LinearLayout item=new LinearLayout(activity);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(15),dp(10),dp(13),dp(10));
        item.setBackground(shape(p.surface,classic()?3:13));
        TextView icon=text(symbol,23,p.accent,false);
        icon.setGravity(Gravity.CENTER);
        item.addView(icon,new LinearLayout.LayoutParams(dp(35),dp(43)));
        LinearLayout names=new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(9),0,0,0);
        names.addView(text(title,15,p.text,true));
        if(value!=null&&!value.isEmpty()){
            gap(names,2);
            names.addView(text(value,12,p.muted,false));
        }
        item.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        item.addView(text("›",23,p.muted,false));
        item.setOnClickListener(v->actions.navigate(section));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(classic()?1:9);
        parent.addView(item,lp);
    }

    public void settings(LinearLayout page) {
        ShellPreferences.Palette p=palette();
        LinearLayout profile=new LinearLayout(activity);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(17),dp(17),dp(17),dp(17));
        profile.setBackground(shape(p.surface,classic()?3:18));
        TextView avatar=text("⬡",31,p.accent,true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(shape(p.background,22));
        profile.addView(avatar,new LinearLayout.LayoutParams(dp(58),dp(58)));
        LinearLayout names=new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16),0,0,0);
        names.addView(text("Polygon",19,p.text,true));
        names.addView(text("Мессенджер",12,p.muted,false));
        profile.addView(names);
        page.addView(profile);
        group(page,classic()?"Учётная запись":"Аккаунт");
        row(page,"◉","Мой профиль",null,"profile");
        row(page,"▤","Настройки чатов",null,"chat_settings");
        row(page,"♙","Конфиденциальность",null,"privacy");
        row(page,"◌","Уведомления и звуки",null,"notifications");
        row(page,"▣","Данные и память",null,"storage");
        row(page,"▤","Папки с чатами",null,"folders");
        row(page,"⌘","Устройства",null,"devices");
        row(page,"文","Язык",null,"language");
        group(page,"Polygon");
        row(page,"☁","Облако","Личные файлы и защита","cloud");
        row(page,"♫","Медиатека",null,"media");
        row(page,"◈","Оформление","13 тем — обе оболочки","appearance");
        row(page,"▥","Интерфейс приложения",
            classic()?"Классический режим":"Современный режим","interface");
        row(page,"☏","Звонки",null,"calls");
        row(page,"?","Помощь",null,"help");
        gap(page,15);
        TextView footer=text("Polygon Messenger · самостоятельный Android-клиент",12,p.muted,false);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer);
        gap(page,22);
    }

    public void appearance(LinearLayout page) {
        ShellPreferences.Palette p=palette();
        page.addView(text("Оформление",23,p.text,true));
        gap(page,8);
        page.addView(text("Единая тема применяется к классической и современной оболочкам.",13,p.muted,false));
        gap(page,14);
        for(ShellPreferences.Palette theme:preferences.allThemes()) {
            LinearLayout cell=new LinearLayout(activity);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(13),dp(12),dp(13),dp(12));
            cell.setBackground(shape(p.surface,12));
            FrameLayout thumb=new FrameLayout(activity);
            thumb.setBackground(shape(theme.accent,16));
            cell.addView(thumb,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView name=text(theme.name,15,p.text,theme.id.equals(p.id));
            name.setPadding(dp(13),0,0,0);
            cell.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            if(theme.id.equals(p.id))cell.addView(text("✓",19,p.accent,true));
            cell.setOnClickListener(v->{preferences.themeId(theme.id);actions.redraw();});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.bottomMargin=dp(8);
            page.addView(cell,params);
        }
    }

    public void interfacePicker(LinearLayout page) {
        ShellPreferences.Palette p=palette();
        page.addView(text("Интерфейс Polygon",23,p.text,true));
        gap(page,9);
        page.addView(text("Обе оболочки — собственная реализация Polygon. История, аккаунт и тема общие.",13,p.muted,false));
        gap(page,20);
        modeCard(page,true,"Классический","Компактный список чатов, боковая шторка, привычное меню и настройки");
        modeCard(page,false,"Современный","Нижняя панель из пяти разделов, новые карточки настроек");
    }

    private void modeCard(LinearLayout page,boolean classic,String title,String subtitle) {
        ShellPreferences.Palette p=palette();
        LinearLayout cell=new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(20),dp(18),dp(20),dp(18));
        cell.setBackground(shape(p.surface,16));
        cell.addView(text((preferences.classic()==classic?"◉  ":"◯  ")+title,18,p.text,true));
        gap(cell,7);
        cell.addView(text(subtitle,13,p.muted,false));
        cell.setOnClickListener(v->{preferences.classic(classic);actions.redraw();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(12);
        page.addView(cell,lp);
    }

    public void showDrawer() {
        if(!classic())return;
        ShellPreferences.Palette p=palette();
        Dialog drawer=new Dialog(activity);
        LinearLayout pane=new LinearLayout(activity);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(p.surface);
        ScrollView scroll=new ScrollView(activity);
        LinearLayout body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout banner=new LinearLayout(activity);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(22),dp(35),dp(22),dp(24));
        banner.setBackgroundColor(CLASSIC_BLUE);
        banner.addView(text("⬡",45,Color.WHITE,true));
        gap(banner,15);
        banner.addView(text("Polygon Messenger",21,Color.WHITE,true));
        banner.addView(text("Ваша связь. Ваш интерфейс.",12,0xffe7f4ff,false));
        body.addView(banner);
        drawerRow(body,drawer,"◉","Мой профиль","profile");
        drawerRow(body,drawer,"✚","Добавить аккаунт","accounts");
        drawerRow(body,drawer,"＋","Создать группу","new_group");
        drawerRow(body,drawer,"♙","Контакты","contacts");
        drawerRow(body,drawer,"☏","Звонки","calls");
        drawerRow(body,drawer,"☆","Избранное","saved");
        divider(body);
        drawerRow(body,drawer,"☁","Облако","cloud");
        drawerRow(body,drawer,"♫","Медиатека","media");
        drawerRow(body,drawer,"⚙","Настройки","settings");
        drawerRow(body,drawer,"◈","Внешний вид","appearance");
        drawerRow(body,drawer,"▥","Выбрать интерфейс","interface");
        scroll.addView(body);
        pane.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        drawer.setContentView(pane);
        Window w=drawer.getWindow();
        if(w!=null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        drawer.show();
        w=drawer.getWindow();
        if(w!=null) {
            WindowManager.LayoutParams lp=w.getAttributes();
            lp.gravity=Gravity.TOP|Gravity.LEFT;
            lp.width=Math.min(dp(344),(int)(activity.getResources().getDisplayMetrics().widthPixels*.86f));
            lp.height=WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount=.38f;
            w.setAttributes(lp);
        }
    }

    private void divider(LinearLayout body) {
        View v=new View(activity);
        v.setBackgroundColor(palette().divider);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));
        lp.topMargin=dp(8);lp.bottomMargin=dp(8);
        body.addView(v,lp);
    }

    private void drawerRow(LinearLayout body,Dialog dialog,String icon,String name,String destination) {
        ShellPreferences.Palette p=palette();
        LinearLayout cell=new LinearLayout(activity);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(23),0,dp(12),0);
        TextView i=text(icon,24,p.muted,false);
        cell.addView(i,new LinearLayout.LayoutParams(dp(45),-2));
        cell.addView(text(name,16,p.text,false));
        cell.setOnClickListener(v->{dialog.dismiss();actions.navigate(destination);});
        body.addView(cell,new LinearLayout.LayoutParams(-1,dp(53)));
    }
}
