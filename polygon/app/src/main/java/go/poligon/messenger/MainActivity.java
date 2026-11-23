package go.poligon.messenger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import go.poligon.messenger.domain.Messenger;
import go.poligon.messenger.transport.TdlibMessenger;

import java.util.ArrayList;
import java.util.List;

/** Native Polygon presentation: no Telegram Activity or resource dependency. */
public final class MainActivity extends Activity implements Messenger.Observer {
    private static final int BG=0xff0a1120, SURFACE=0xff142038, ACCENT=0xff65d7ca;
    private static final int TEXT=0xfff2f5fb, MUTED=0xff9daac1;
    private LinearLayout root, content;
    private Messenger messenger;
    private String step="connecting", status="Подключение…";
    private String section="chats";
    private long currentChat=0;
    private String currentTitle="";
    private List<Messenger.Conversation> conversations=new ArrayList<>();
    private List<Messenger.Entry> messages=new ArrayList<>();
    private EditText editor;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        setContentView(root);
        messenger = new TdlibMessenger(this);
        messenger.start(this);
        render();
    }
    @Override public void onDestroy() { super.onDestroy(); /* connection persists only while Activity lives */ if (messenger!=null) messenger.close(); }
    private int dp(float v) { return (int)(v*getResources().getDisplayMetrics().density+.5f); }
    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private void verticalGap(LinearLayout c,int height) { View v=new View(this);c.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private void reset() {
        root.removeAllViews();
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(22),dp(20),dp(22),dp(16));
        TextView mark=label("⬡",34,ACCENT,true);header.addView(mark);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL); titles.setPadding(dp(12),0,0,0);
        titles.addView(label("POLYGON",22,TEXT,true));titles.addView(label("MESSENGER",10,MUTED,true));
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView state=label("●",14,"ready".equals(step)?ACCENT:MUTED,true);
        header.addView(state);
        root.addView(header);
        View line=new View(this); line.setBackgroundColor(0xff25334c); root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private Button button(String s, Runnable act) {
        Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextSize(15);
        b.setTextColor(BG);b.setBackground(shape(ACCENT,14));b.setOnClickListener(v->act.run());return b;
    }
    private void line(String title,String subtitle,Runnable click) {
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(16),dp(16),dp(16),dp(16));cell.setBackground(shape(SURFACE,15));
        cell.addView(label(title,16,TEXT,true));verticalGap(cell,5);
        cell.addView(label(subtitle==null?"":subtitle,13,MUTED,false));
        cell.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);content.addView(cell,lp);
    }
    private void render() {
        reset();
        if (!"ready".equals(step)) { authScreen(); return; }
        if (currentChat!=0) { chatScreen(); return; }
        tabs();
        switch(section) {
            case "chats": chatsScreen();break;
            case "cloud": placeholder("Облако","Независимый Polygon Vault переносится в отдельный модуль.");break;
            case "calls": placeholder("Звонки","Модуль звонков в этой сборке не реализован.");break;
            default: placeholder("Настройки","Настройки клиента и аккаунтов будут отдельным модулем Polygon.");
        }
    }
    private void authScreen() {
        verticalGap(content,40);
        content.addView(label("Добро пожаловать в Polygon",25,TEXT,true));
        verticalGap(content,12);
        content.addView(label(status,15,MUTED,false));
        verticalGap(content,28);
        boolean isPhone="phone".equals(step),isCode="code".equals(step),isPwd="password".equals(step);
        if(!(isPhone||isCode||isPwd))return;
        EditText input=new EditText(this);
        input.setSingleLine(true);input.setPadding(dp(16),dp(12),dp(16),dp(12));
        input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setBackground(shape(SURFACE,12));
        input.setInputType(isPhone?InputType.TYPE_CLASS_PHONE:isPwd?(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD):InputType.TYPE_CLASS_NUMBER);
        input.setHint(isPhone?"+7 900 000-00-00":isCode?"Код подтверждения":"Пароль");
        content.addView(input,new LinearLayout.LayoutParams(-1,dp(56)));
        verticalGap(content,16);
        content.addView(button("Продолжить",()->{
            String value=input.getText().toString();hideKeyboard(input);
            if(isPhone)messenger.authenticatePhone(value);
            else if(isCode)messenger.authenticateCode(value);
            else messenger.authenticatePassword(value);
        }),new LinearLayout.LayoutParams(-1,dp(50)));
    }
    private void tabs() {
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER);
        tab(bar,"Чаты","chats");tab(bar,"Облако","cloud");tab(bar,"Звонки","calls");tab(bar,"Ещё","settings");
        content.addView(bar);verticalGap(content,20);
    }
    private void tab(LinearLayout bar,String text,String target) {
        TextView t=label(text,12,section.equals(target)?ACCENT:MUTED,true);
        t.setGravity(Gravity.CENTER);t.setPadding(dp(3),dp(10),dp(3),dp(10));
        t.setOnClickListener(v->{section=target;render();});
        bar.addView(t,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void chatsScreen() {
        LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(label("Сообщения",25,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView reload=label("Обновить",13,ACCENT,true);
        reload.setOnClickListener(v->messenger.loadConversations());h.addView(reload);content.addView(h);
        verticalGap(content,20);
        if(conversations.isEmpty())content.addView(label("Загрузка диалогов или пока нет чатов",14,MUTED,false));
        for(Messenger.Conversation c:conversations)line(c.title,c.preview,()->{
            currentChat=c.id;currentTitle=c.title;messages.clear();render();messenger.loadHistory(c.id);
        });
    }
    private void chatScreen() {
        TextView back=label("‹  Назад",16,ACCENT,true);
        back.setOnClickListener(v->{currentChat=0;messages.clear();render();});content.addView(back);
        verticalGap(content,16);content.addView(label(currentTitle,22,TEXT,true));verticalGap(content,18);
        for(Messenger.Entry e:messages) {
            if(e.text==null||e.text.isEmpty())continue;
            TextView bubble=label(e.text,15,TEXT,false);bubble.setPadding(dp(14),dp(10),dp(14),dp(10));
            bubble.setBackground(shape(e.outgoing?0xff205d65:SURFACE,14));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);
            p.gravity=e.outgoing?Gravity.RIGHT:Gravity.LEFT;p.bottomMargin=dp(9);content.addView(bubble,p);
        }
        verticalGap(content,20);
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.CENTER_VERTICAL);
        editor=new EditText(this);editor.setSingleLine(false);editor.setMaxLines(4);
        editor.setTextColor(TEXT);editor.setHintTextColor(MUTED);editor.setHint("Сообщение");
        editor.setPadding(dp(13),dp(6),dp(13),dp(6));editor.setBackground(shape(SURFACE,12));
        composer.addView(editor,new LinearLayout.LayoutParams(0,dp(54),1));
        TextView send=label("  ➤  ",26,ACCENT,true);send.setOnClickListener(v->{
            String s=editor.getText().toString();editor.setText("");messenger.sendText(currentChat,s);
        });composer.addView(send);
        content.addView(composer);
    }
    private void placeholder(String title,String body) {
        content.addView(label(title,25,TEXT,true));verticalGap(content,18);
        content.addView(label(body,15,MUTED,false));
    }
    private void hideKeyboard(View v){
        InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if(imm!=null)imm.hideSoftInputFromWindow(v.getWindowToken(),0);
    }
    @Override public void onBackPressed() {
        if(currentChat!=0){currentChat=0;render();return;}super.onBackPressed();
    }
    @Override public void onAuthorization(String newStep,String message){step=newStep;status=message;render();}
    @Override public void onConversations(List<Messenger.Conversation> items){
        conversations=new ArrayList<>(items);if(currentChat==0&&"chats".equals(section))render();
    }
    @Override public void onHistory(long id,List<Messenger.Entry> entries){
        if(currentChat==id){messages=new ArrayList<>(entries);render();}
    }
    @Override public void onError(String message){
        status=message;
        if(!"ready".equals(step))render();
        else android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();
    }
}
