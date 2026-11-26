package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.*;
import org.telegram.messenger.telepremium.xray.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextCell;
import java.util.*;

/** Shared profile library with independent account and media bindings. */
public final class TelePremiumXrayProfilesActivity extends BaseFragment {
    private LinearLayout content;
    private String t(String en,String ru){return TPStrings.isRussian()?ru:en;}
    @Override public android.view.View createView(Context context){
        actionBar.setTitle("Xray · arm64-v8a");actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView scroll=new ScrollView(context);content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,AndroidUtilities.dp(12),0,AndroidUtilities.dp(32));scroll.addView(content);fragmentView=scroll;render();return scroll;
    }
    @Override public void onResume(){super.onResume();render();}
    private TextCell cell(String title,String value,Runnable click){
        TextCell c=new TextCell(getParentActivity(),getResourceProvider());c.setTextAndValue(title,value,true);
        c.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));content.addView(c,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(60)));if(click!=null)c.setOnClickListener(v->click.run());return c;
    }
    private void label(String text){TextView v=new TextView(getParentActivity());v.setText(text);v.setTextSize(14);v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));v.setPadding(AndroidUtilities.dp(20),AndroidUtilities.dp(16),AndroidUtilities.dp(20),AndroidUtilities.dp(12));content.addView(v);}
    private void error(String value){if(getParentActivity()!=null)showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle("Xray").setMessage(value).setPositiveButton("OK",null).create());}
    private void render(){
        if(content==null||getParentActivity()==null)return;content.removeAllViews();content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        try{
            List<XrayProfile> profiles=XrayProfileStore.profiles();
            label(t("Core status: ","Состояние ядра: ")+XrayRuntime.status());
            cell(t("Add profiles","Добавить профили"),profiles.size()+" / "+XrayConfig.MAX_PROFILES,()->importDialog());
            cell(t("Check keys now","Проверить ключи сейчас"),t("Auto mode picks the fastest and rotates on failure","Режим «Авто» выбирает быстрейший и меняет при сбое"),()->{XrayRuntime.probeNow(this::render);Toast.makeText(getParentActivity(),t("Checking…","Проверяю…"),Toast.LENGTH_SHORT).show();});
            List<String[]> subs=XrayProfileStore.subscriptions();
            if(!subs.isEmpty()){
                label(t("Subscriptions","Подписки"));
                for(String[] sub:subs){int count=0;for(XrayProfile p:profiles)if(sub[0].equals(p.sub))count++;final int keys=count;
                    cell(hostOf(sub[1]),keys+" "+t("keys · tap to update","ключей · нажмите, чтобы обновить"),()->subscriptionMenu(sub,keys));}
            }
            String activeId=XrayRuntime.autoActiveId();
            for(XrayProfile p:profiles){int ms=XrayRuntime.latency(p.id);String state=ms>0?ms+" "+t("ms","мс"):ms<0?t("no response","не отвечает"):"";
                String mark=p.id.equals(activeId)?"● ":"";
                cell(mark+p.name,p.outbound.optString("protocol").toUpperCase(Locale.ROOT)+(state.isEmpty()?"":" · "+state),()->profileMenu(p));}
            label(t("Telegram accounts","Telegram-аккаунты"));
            for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){
                final int account=a;AccountNetworkProfile route=AccountNetworkController.getInstance().getProfile(a);
                String name=UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser());
                String summary=route!=null&&route.mode==AccountNetworkProfile.MODE_XRAY?profileName(route.xrayProfileId):AccountNetworkController.getInstance().getSummary(a);
                cell(name,summary,()->chooseAccount(account));
            }
            label(t("Media route","Маршрут медиатеки"));
            String route=XrayProfileStore.mediaRoute();cell(t("YouTube, radio and IPTV","YouTube, радио и IPTV"),route.isEmpty()?t("Direct","Напрямую"):route.equals("account")?t("Current account Xray","Xray текущего аккаунта"):profileName(route),()->chooseMedia());
            label(t("Cloud transfers follow each Telegram account. Media can use a separate profile. All proxy listeners are local; an unavailable chosen profile stops the connection instead of switching to direct.","Передача файлов облака идёт по маршруту соответствующего Telegram-аккаунта. Для медиатеки можно выбрать отдельный профиль. При недоступности выбранного профиля соединение останавливается."));
        }catch(Exception e){
            label(t("The device key cannot open the restored profiles. Reset this local list, then import your profile links again.","Ключ устройства не открывает восстановленные профили. Сбросьте локальный список, затем импортируйте ссылки профилей заново."));
            cell(t("Reset unreadable Xray profiles","Сбросить нечитаемые профили Xray"),"",this::resetUnreadable);
        }
    }
    private String profileName(String id)throws Exception{if(XrayRuntime.AUTO.equals(id)){String a=XrayRuntime.autoActiveId();XrayProfile ap=a.isEmpty()?null:XrayProfileStore.find(a);return t("Auto","Авто")+(ap==null?"":" · "+ap.name);}XrayProfile p=XrayProfileStore.find(id);return p==null?t("Unavailable","Недоступен"):p.name;}
    private void resetUnreadable(){
        showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider())
                .setTitle(t("Reset local Xray profiles?","Сбросить локальные профили Xray?"))
                .setMessage(t("Only this device's proxy list is reset. Vault keys and the media library are preserved. Media will use Direct; accounts assigned to missing profiles remain offline until you choose new routes.","Сбрасывается только локальный список прокси. Ключи Vault и медиатека сохраняются. Медиатека перейдёт на прямое соединение; аккаунты со старыми профилями останутся без соединения, пока вы не назначите новые маршруты."))
                .setPositiveButton(t("Reset profiles","Сбросить профили"),(d,w)->{
                    try{XrayProfileStore.resetAfterKeyLoss();XrayRuntime.refresh();org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged();render();}
                    catch(Exception e){error(t("Could not reset profiles","Не удалось сбросить профили"));}
                }).setNegativeButton(t("Cancel","Отмена"),null).create());
    }
    private String hostOf(String url){try{String h=android.net.Uri.parse(url).getHost();return h==null?url:h;}catch(Exception e){return url;}}
    private void subscriptionMenu(String[] sub,int keys){
        showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(hostOf(sub[1])).setItems(new String[]{t("Update keys","Обновить ключи"),t("Delete subscription and its keys","Удалить подписку и её ключи")},(d,which)->{
            if(which==0){
                Toast.makeText(getParentActivity(),t("Updating…","Обновляю…"),Toast.LENGTH_SHORT).show();
                XrayRuntime.refreshSubscription(sub[0],result->{if(result!=null)error(result);else Toast.makeText(getParentActivity(),XrayRuntime.lastImportSummary(),Toast.LENGTH_LONG).show();render();});
            }else removeSubscription(sub[0]);
        }).create());
    }
    private void removeSubscription(String id){
        try{
            List<XrayProfile> all=XrayProfileStore.profiles();List<XrayProfile> keep=new ArrayList<>();
            for(XrayProfile p:all){
                if(!id.equals(p.sub)){keep.add(p);continue;}
                if(p.id.equals(XrayProfileStore.mediaRoute()))throw new IllegalArgumentException(t("Select another media route first","Сначала выберите другой маршрут медиатеки"));
                for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++){AccountNetworkProfile r=AccountNetworkStore.getInstance().load(a);if(r!=null&&r.mode==AccountNetworkProfile.MODE_XRAY&&r.xrayProfileId.equals(p.id))throw new IllegalArgumentException(t("Assign another route to accounts using these keys first","Сначала измените маршрут аккаунтов, использующих эти ключи"));}
            }
            XrayProfileStore.saveAll(keep);XrayProfileStore.removeSubscription(id);XrayRuntime.refresh();render();
        }catch(Exception e){error(e instanceof IllegalArgumentException?e.getMessage():t("Could not delete","Не удалось удалить"));}
    }
    private void importDialog(){
        EditText input=new EditText(getParentActivity());input.setHint(t("Subscription https://… · vless:// · vmess:// · trojan:// · ss:// · Xray JSON","Подписка https://… · vless:// · vmess:// · trojan:// · ss:// · Xray JSON"));input.setMinLines(4);input.setMaxLines(12);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(262144)});
        showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(t("Import: subscription or links","Импорт: подписка или ссылки"))
                .setView(input).setPositiveButton(t("Import","Импортировать"),(d,w)->{
                    String raw=input.getText().toString().trim();input.setText("");
                    Toast.makeText(getParentActivity(),t("Importing…","Импортирую…"),Toast.LENGTH_SHORT).show();XrayRuntime.importProfiles(raw,result->{if(result!=null)error(result);else Toast.makeText(getParentActivity(),XrayRuntime.lastImportSummary(),Toast.LENGTH_LONG).show();render();});
                }).setNegativeButton(t("Cancel","Отмена"),null).create());
    }
    private void chooseAccount(int account){
        try{List<XrayProfile> profiles=XrayProfileStore.profiles();ArrayList<String> names=new ArrayList<>();names.add(t("Global route","Общий маршрут"));names.add(t("Direct","Напрямую"));names.add(t("Auto: best of all keys","Авто: лучший из всех ключей"));for(XrayProfile p:profiles)names.add(p.name);
            showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(t("Account route","Маршрут аккаунта")).setItems(names.toArray(new String[0]),(d,which)->{
                if(which==0)AccountNetworkController.getInstance().resetToGlobal(account);
                else{AccountNetworkProfile route=AccountNetworkController.getInstance().getEditableProfile(account);route.mode=which==1?AccountNetworkProfile.MODE_DIRECT:AccountNetworkProfile.MODE_XRAY;if(which==2){route.xrayProfileId=XrayRuntime.AUTO;route.displayName=t("Auto","Авто");}else if(which>2){XrayProfile p=profiles.get(which-3);route.xrayProfileId=p.id;route.displayName=p.name;}AccountNetworkController.getInstance().saveAndApply(account,route);}
                XrayRuntime.refresh();render();
            }).create());
        }catch(Exception e){error(t("Could not load profiles","Не удалось открыть профили"));}
    }
    private void chooseMedia(){
        try{List<XrayProfile> profiles=XrayProfileStore.profiles();ArrayList<String> names=new ArrayList<>();names.add(t("Direct","Напрямую"));names.add(t("Current account Xray","Xray текущего аккаунта"));names.add(t("Auto: best of all keys","Авто: лучший из всех ключей"));for(XrayProfile p:profiles)names.add(p.name);
            showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(t("Media route","Маршрут медиатеки")).setItems(names.toArray(new String[0]),(d,which)->{
                try{XrayProfileStore.setMediaRoute(which==0?"":which==1?"account":which==2?XrayRuntime.AUTO:profiles.get(which-3).id);XrayRuntime.refresh();org.telegram.messenger.telepremium.media.TelePremiumPlaybackController.networkRouteChanged();render();}catch(Exception e){error(t("Could not save route","Не удалось сохранить маршрут"));}
            }).create());
        }catch(Exception e){error(t("Could not load profiles","Не удалось открыть профили"));}
    }
    private void profileMenu(XrayProfile p){
        showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(p.name).setItems(new String[]{t("Rename","Переименовать"),p.bypassLan?t("Route local networks through profile","Локальные сети через профиль"):t("Bypass local networks","Локальные сети напрямую"),t("Delete","Удалить")},(d,which)->{
            if(which==0){EditText input=new EditText(getParentActivity());input.setSingleLine(true);input.setText(p.name);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});showDialog(new AlertDialog.Builder(getParentActivity(),getResourceProvider()).setTitle(t("Profile name","Имя профиля")).setView(input).setPositiveButton(t("Save","Сохранить"),(a,b)->{p.name=input.getText().toString().trim();update(p,false);}).setNegativeButton(t("Cancel","Отмена"),null).create());}
            else if(which==1){p.bypassLan=!p.bypassLan;update(p,false);}else update(p,true);
        }).create());
    }
    private void update(XrayProfile profile,boolean remove){
        try{if(remove){
                if(profile.id.equals(XrayProfileStore.mediaRoute()))throw new IllegalArgumentException(t("Select another media route first","Сначала выберите другой маршрут медиатеки"));
                for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++){AccountNetworkProfile r=AccountNetworkStore.getInstance().load(a);if(r!=null&&r.mode==AccountNetworkProfile.MODE_XRAY&&r.xrayProfileId.equals(profile.id))throw new IllegalArgumentException(t("Assign another route to accounts using this profile first","Сначала измените маршрут аккаунтов, использующих этот профиль"));}
            }
            List<XrayProfile> all=XrayProfileStore.profiles();for(int i=0;i<all.size();i++)if(all.get(i).id.equals(profile.id)){if(remove)all.remove(i);else all.set(i,profile);break;}
            XrayProfileStore.saveAll(all);XrayRuntime.refresh();render();
        }catch(Exception e){error(e instanceof IllegalArgumentException?e.getMessage():t("Could not save profiles","Не удалось сохранить профили"));}
    }
}
