package go.poligon.messenger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import go.poligon.messenger.domain.Messenger;
import go.poligon.messenger.transport.TdlibMessenger;
import go.poligon.messenger.ui.ShellPreferences;
import go.poligon.messenger.ui.ShellUi;

import java.util.ArrayList;
import java.util.List;

/**
 * Polygon owns the two screens and their navigation. The domain Messenger contract
 * is the only boundary into the messaging engine; upstream UI code is not used.
 */
public final class MainActivity extends Activity implements Messenger.Observer, ShellUi.Actions {
    private LinearLayout root, content;
    private ScrollView scroller;
    private ShellPreferences appearance;
    private ShellUi shell;
    private Messenger messenger;
    private String step="connecting",status="Подключение…",section="chats";
    private long currentChat;
    private String currentTitle="";
    private List<Messenger.Conversation> conversations=new ArrayList<>();
    private List<Messenger.Entry> messages=new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        appearance=new ShellPreferences(this);
        shell=new ShellUi(this,appearance,this);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);
        messenger=new TdlibMessenger(this);
        messenger.start(this);
        render();
    }

    @Override public void onDestroy() {
        if(messenger!=null)messenger.close();
        super.onDestroy();
    }

    private int dp(int size) { return shell.dp(size); }
    private ShellPreferences.Palette p() { return appearance.palette(); }
    private TextView label(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private void gap(LinearLayout parent,int size) { shell.gap(parent,size); }
    private String screenTitle() {
        if(currentChat!=0)return currentTitle;
        if(!"ready".equals(step))return "Polygon";
        switch(section) {
            case "chats":return "Polygon";
            case "calls":return "Звонки";
            case "contacts":return "Контакты";
            case "cloud":return "Облако";
            case "media":return "Медиатека";
            case "settings":return "Настройки";
            case "appearance":return "Оформление";
            case "interface":return "Интерфейс";
            case "saved":return "Избранное";
            case "profile":return "Мой профиль";
            case "search":return "Поиск";
            default:return "Polygon";
        }
    }

    private void reset() {
        ShellPreferences.Palette theme=p();
        root.removeAllViews();
        root.setBackgroundColor(theme.background);
        getWindow().setStatusBarColor(shell.classic()?0xff598fbd:theme.background);
        getWindow().setNavigationBarColor(theme.background);
        root.setSystemUiVisibility(theme.dark?0:
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        boolean ready="ready".equals(step);
        boolean home=ready && currentChat==0 && "chats".equals(section);
        shell.header(root,screenTitle(),currentChat!=0,ready,home);
        scroller=new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(shell.classic()?12:17),dp(16),dp(shell.classic()?12:17),dp(18));
        scroller.addView(content);
        root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void render() {
        reset();
        if(!"ready".equals(step)) { auth(); return; }
        if(currentChat!=0) {
            drawChat();
            composer();
            return;
        }
        switch(section) {
            case "chats":drawChats();break;
            case "settings":shell.settings(content);break;
            case "appearance":shell.appearance(content);break;
            case "interface":shell.interfacePicker(content);break;
            default:drawSection();
        }
        shell.bottomNavigation(root,section);
    }

    private void auth() {
        ShellPreferences.Palette theme=p();
        gap(content,30);
        content.addView(label("Добро пожаловать в Polygon",24,theme.text,true));
        gap(content,12);
        content.addView(label(status,15,theme.muted,false));
        gap(content,26);
        boolean isPhone="phone".equals(step);
        boolean isCode="code".equals(step);
        boolean isPassword="password".equals(step);
        if(!isPhone&&!isCode&&!isPassword)return;
        EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setPadding(dp(15),dp(8),dp(15),dp(8));
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.muted);
        input.setBackground(shell.shape(theme.surface,12));
        input.setInputType(isPhone?InputType.TYPE_CLASS_PHONE:isPassword?
            (InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD):InputType.TYPE_CLASS_NUMBER);
        input.setHint(isPhone?"Номер телефона":isCode?"Код подтверждения":"Пароль");
        content.addView(input,new LinearLayout.LayoutParams(-1,dp(56)));
        gap(content,17);
        TextView proceed=label("Продолжить",16,Color.WHITE,true);
        proceed.setGravity(Gravity.CENTER);
        proceed.setBackground(shell.shape(theme.accent,13));
        proceed.setOnClickListener(v->{
            String value=input.getText().toString();
            hideKeyboard(input);
            if(isPhone)messenger.authenticatePhone(value);
            else if(isCode)messenger.authenticateCode(value);
            else messenger.authenticatePassword(value);
        });
        content.addView(proceed,new LinearLayout.LayoutParams(-1,dp(50)));
    }

    private void drawChats() {
        ShellPreferences.Palette theme=p();
        if(!shell.classic()){
            content.addView(label("Сообщения",24,theme.text,true));
            gap(content,14);
        }
        if(conversations.isEmpty()){
            gap(content,25);
            content.addView(label("Диалоги загружаются или список пока пуст.",14,theme.muted,false));
        }
        for(Messenger.Conversation c:conversations)chatItem(c);
    }

    private void chatItem(Messenger.Conversation c) {
        ShellPreferences.Palette theme=p();
        LinearLayout cell=new LinearLayout(this);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(13),dp(10),dp(13),dp(10));
        cell.setBackground(shell.shape(theme.surface,shell.classic()?2:14));
        TextView avatar=label(c.title.isEmpty()?"◉":c.title.substring(0,1).toUpperCase(),20,Color.WHITE,true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(shell.shape(theme.accent,60));
        cell.addView(avatar,new LinearLayout.LayoutParams(dp(49),dp(49)));
        LinearLayout captions=new LinearLayout(this);
        captions.setOrientation(LinearLayout.VERTICAL);
        captions.setPadding(dp(12),0,0,0);
        captions.addView(label(c.title,16,theme.text,true));
        gap(captions,5);
        TextView preview=label(c.preview,13,theme.muted,false);
        preview.setMaxLines(1);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        captions.addView(preview);
        cell.addView(captions,new LinearLayout.LayoutParams(0,-2,1));
        cell.setOnClickListener(v->{
            currentChat=c.id;
            currentTitle=c.title;
            messages.clear();
            render();
            messenger.loadHistory(c.id);
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(72));
        lp.bottomMargin=dp(shell.classic()?1:9);
        content.addView(cell,lp);
    }

    private void drawChat() {
        ShellPreferences.Palette theme=p();
        if(messages.isEmpty())content.addView(label("Загрузка переписки…",13,theme.muted,false));
        for(Messenger.Entry message:messages) {
            if(message.text==null||message.text.isEmpty())continue;
            TextView bubble=label(message.text,15,theme.text,false);
            bubble.setPadding(dp(13),dp(10),dp(13),dp(10));
            bubble.setBackground(shell.shape(message.outgoing?theme.outgoing:theme.incoming,
                shell.classic()?9:16));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
            lp.gravity=message.outgoing?Gravity.RIGHT:Gravity.LEFT;
            lp.bottomMargin=dp(9);
            content.addView(bubble,lp);
        }
        scroller.post(()->scroller.fullScroll(View.FOCUS_DOWN));
    }

    private void composer() {
        ShellPreferences.Palette theme=p();
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8),dp(5),dp(8),dp(5));
        bar.setBackgroundColor(theme.surface);
        EditText entry=new EditText(this);
        entry.setHint("Сообщение");
        entry.setHintTextColor(theme.muted);
        entry.setTextColor(theme.text);
        entry.setTextSize(15);
        entry.setMinLines(1);
        entry.setMaxLines(4);
        entry.setPadding(dp(12),dp(7),dp(12),dp(7));
        entry.setBackground(shell.shape(theme.background,16));
        bar.addView(entry,new LinearLayout.LayoutParams(0,-2,1));
        TextView send=label("➤",24,theme.accent,true);
        send.setPadding(dp(15),dp(10),dp(11),dp(10));
        send.setOnClickListener(v->{
            String message=entry.getText().toString().trim();
            if(message.isEmpty())return;
            entry.setText("");
            messenger.sendText(currentChat,message);
        });
        bar.addView(send);
        root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
    }

    private void drawSection() {
        ShellPreferences.Palette theme=p();
        String title=screenTitle(),note;
        switch(section){
            case "cloud":note="Polygon Vault будет перенесён вместе с механизмом шифрования и восстановления. Сейчас раздел недоступен.";break;
            case "calls":note="Собственный экран звонков и интеграция с движком звонков ещё не перенесены.";break;
            case "media":note="Медиатека, проигрыватель и загрузки переносятся отдельным модулем Polygon.";break;
            case "contacts":note="Контакты будут подключены к модели данных Polygon.";break;
            case "saved":note="Избранные сообщения появятся после реализации избранного и поиска.";break;
            case "accounts":note="Менеджер нескольких аккаунтов ещё не подключён.";break;
            case "new_group":note="Создание группы будет подключено к транспортному адаптеру.";break;
            case "profile":note="Экран профиля будет создан на собственных компонентах Polygon.";break;
            case "search":note="Глобальный поиск будет подключён к индексам диалогов и сообщений.";break;
            default:note="Раздел будет перенесён с сохранением назначения, но на собственной архитектуре Polygon.";
        }
        content.addView(label(title.equals("Polygon")?"Раздел Polygon":title,23,theme.text,true));
        gap(content,16);
        content.addView(label(note,14,theme.muted,false));
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(view.getWindowToken(),0);
    }

    @Override public void navigate(String destination) {
        if(!"ready".equals(step))return;
        currentChat=0;
        messages.clear();
        section=destination;
        render();
        if("chats".equals(destination))messenger.loadConversations();
    }
    @Override public void redraw() {render();}
    @Override public void refreshChats() {
        if("ready".equals(step))messenger.loadConversations();
    }
    @Override public void back() {
        if(currentChat!=0) {
            currentChat=0;
            messages.clear();
            section="chats";
        } else if("appearance".equals(section)||"interface".equals(section))section="settings";
        else section="chats";
        render();
    }
    @Override public void onBackPressed() {
        if(currentChat!=0||!"chats".equals(section))back();
        else super.onBackPressed();
    }

    @Override public void onAuthorization(String newStep,String message) {
        step=newStep;
        status=message;
        render();
    }
    @Override public void onConversations(List<Messenger.Conversation> items) {
        conversations=new ArrayList<>(items);
        if(currentChat==0 && "chats".equals(section))render();
    }
    @Override public void onHistory(long chatId,List<Messenger.Entry> entries) {
        if(currentChat==chatId) {
            messages=new ArrayList<>(entries);
            render();
        }
    }
    @Override public void onError(String error) {
        status=error;
        if(!"ready".equals(step))render();
        else android.widget.Toast.makeText(this,error,android.widget.Toast.LENGTH_LONG).show();
    }
}
