package org.telegram.ui;

import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import android.text.*;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.media.*;

/** Searchable themed channel cards; rename/remove target the chosen row, never the active feed. */
final class TelePremiumYoutubeChannelsDialog extends Dialog {
    interface Select {void open(String url);}
    private final TelePremiumPlayerKit.Palette pal;
    private final Select select;
    private final Runnable add;
    private LinearLayout list;
    private EditText search;
    private TextView count;
    private final Runnable changed=this::render;
    private final java.util.Map<String,TelePremiumMediaRequest> artworkRequests=new java.util.HashMap<>();
    private final java.util.Set<String> artworkAttempted=new java.util.HashSet<>();
    TelePremiumYoutubeChannelsDialog(Context context,TelePremiumPlayerKit.Palette palette,Select select,Runnable add){super(context);this.pal=palette;this.select=select;this.add=add;}
    private int dp(float v){return org.telegram.messenger.AndroidUtilities.dp(v);}
    private String t(String en,String ru){return TPStrings.isRussian()?ru:en;}
    @Override protected void onCreate(android.os.Bundle state){super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(getContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(14),dp(16),dp(18));root.setBackground(TelePremiumPlayerKit.round(pal.panel,pal.stroke,24,1));
        LinearLayout header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        count=new TextView(getContext());count.setTextSize(21);count.setTypeface(Typeface.DEFAULT,Typeface.BOLD);count.setTextColor(pal.text);header.addView(count,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView plus=new TextView(getContext());plus.setText("＋");plus.setTextSize(30);plus.setTextColor(pal.accent);plus.setGravity(Gravity.CENTER);plus.setContentDescription(t("Add channel","Добавить канал"));plus.setOnClickListener(v->{dismiss();add.run();});header.addView(plus,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);
        search=new EditText(getContext());search.setSingleLine(true);search.setTextSize(15);search.setTextColor(pal.text);search.setHintTextColor(pal.muted);search.setHint(t("Find a channel or playlist","Найти канал или плейлист"));search.setBackground(TelePremiumPlayerKit.round(pal.background,pal.stroke,14,1));search.setPadding(dp(14),0,dp(14),0);root.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(false);list=new LinearLayout(getContext());list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,dp(10),0,0);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(440),(int)(getContext().getResources().getDisplayMetrics().heightPixels*.52f))));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(Editable e){}});
        setContentView(root);render();
    }
    @Override protected void onStart(){super.onStart();if(getWindow()!=null){getWindow().setBackgroundDrawableResource(android.R.color.transparent);getWindow().setLayout(getContext().getResources().getDisplayMetrics().widthPixels-dp(20),-2);getWindow().setGravity(Gravity.BOTTOM);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}TelePremiumMediaLibrarySync.observe(changed);}
    @Override protected void onStop(){TelePremiumMediaLibrarySync.unobserve(changed);for(TelePremiumMediaRequest request:artworkRequests.values())request.cancel();artworkRequests.clear();artworkAttempted.clear();super.onStop();}
    private void render(){if(list==null)return;list.removeAllViews();java.util.List<TelePremiumNetworkLibrary.IptvPlaylist> saved=TelePremiumNetworkLibrary.getYoutubeChannels();count.setText(t("Channels","Каналы")+" · "+saved.size());String q=search.getText().toString().toLowerCase(java.util.Locale.ROOT).trim();
        for(TelePremiumNetworkLibrary.IptvPlaylist p:saved){if(!q.isEmpty()&&!(p.name+" "+p.url).toLowerCase(java.util.Locale.ROOT).contains(q))continue;
            LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(6),dp(10));row.setBackground(TelePremiumPlayerKit.round(pal.background,pal.stroke,16,1));LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(84));rowLp.topMargin=dp(8);list.addView(row,rowLp);
            FrameLayout avatar=new FrameLayout(getContext());avatar.setBackground(TelePremiumPlayerKit.round(pal.panel,pal.stroke,12,1));avatar.setClipToOutline(true);
            TextView initial=new TextView(getContext());String label=p.name==null?"":p.name.trim();initial.setText(label.isEmpty()?"?":label.substring(0,label.offsetByCodePoints(0,1)).toUpperCase(java.util.Locale.ROOT));initial.setTextSize(23);initial.setTextColor(pal.accent);initial.setTypeface(Typeface.DEFAULT,Typeface.BOLD);initial.setGravity(Gravity.CENTER);avatar.addView(initial,new FrameLayout.LayoutParams(-1,-1));
            ImageView icon=new ImageView(getContext());icon.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.addView(icon,new FrameLayout.LayoutParams(-1,-1));row.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));loadAvatar(p,icon);

            LinearLayout text=new LinearLayout(getContext());text.setOrientation(LinearLayout.VERTICAL);text.setPadding(dp(12),0,dp(4),0);TextView name=new TextView(getContext());name.setText(p.name);name.setTextSize(16);name.setTextColor(pal.text);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);text.addView(name);
            TextView link=new TextView(getContext());link.setText(p.url.replace("https://www.youtube.com/","").replace("https://youtube.com/",""));link.setTextSize(12);link.setTextColor(pal.muted);link.setSingleLine(true);link.setEllipsize(TextUtils.TruncateAt.MIDDLE);text.addView(link);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            row.setOnClickListener(v->{dismiss();select.open(p.url);});
            TextView more=new TextView(getContext());more.setText("⋮");more.setTextSize(28);more.setTextColor(pal.muted);more.setGravity(Gravity.CENTER);more.setContentDescription(t("Channel actions","Действия с каналом"));row.addView(more,new LinearLayout.LayoutParams(dp(40),dp(54)));more.setOnClickListener(v->actions(p));
        }
        if(list.getChildCount()==0){TextView empty=new TextView(getContext());empty.setText(saved.isEmpty()?t("Add your first channel with +","Добавьте первый канал кнопкой +"):t("No matches","Ничего не найдено"));empty.setTextColor(pal.muted);empty.setTextSize(16);empty.setGravity(Gravity.CENTER);list.addView(empty,new LinearLayout.LayoutParams(-1,dp(120)));}
    }
    private void loadAvatar(TelePremiumNetworkLibrary.IptvPlaylist source,ImageView target){
        final long owner=TelePremiumMediaLibraryStore.owner();
        if(source.artworkUri!=null&&!source.artworkUri.isEmpty()){
            TelePremiumArtworkLoader.loadChannelIcon(source.artworkUri,dp(64),bitmap->{
                if(bitmap!=null&&owner==TelePremiumMediaLibraryStore.owner())target.setImageBitmap(bitmap);
            });return;
        }
        if(!artworkAttempted.add(source.url))return;
        artworkRequests.put(source.url,TelePremiumYouTube.loadArtwork(source.url,(url,error)->{
            artworkRequests.remove(source.url);
            if(!isShowing()||owner!=TelePremiumMediaLibraryStore.owner()||url==null)return;
            TelePremiumNetworkLibrary.updateYoutubeArtwork(owner,source.url,url);
            render();
        }));
    }
    private AlertDialog.Builder dialog(){int theme=androidx.core.graphics.ColorUtils.calculateLuminance(pal.background)<.5?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert;return new AlertDialog.Builder(getContext(),theme);}
    private void actions(TelePremiumNetworkLibrary.IptvPlaylist p){dialog().setTitle(p.name).setItems(new String[]{t("Rename","Переименовать"),t("Remove from library","Удалить из списка")},(d,which)->{
        if(which==1){TelePremiumNetworkLibrary.removeYoutubeChannel(p.url);render();}
        else{EditText input=new EditText(getContext());input.setSingleLine(true);input.setText(p.name);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});dialog().setTitle(t("Channel name","Имя канала")).setView(input).setPositiveButton(t("Save","Сохранить"),(a,b)->{String name=input.getText().toString().trim();if(!name.isEmpty())TelePremiumNetworkLibrary.renameYoutube(p.url,name);render();}).setNegativeButton(t("Cancel","Отмена"),null).show();}
    }).show();}
}
