package org.telegram.ui;

import android.app.AlertDialog;
import android.content.*;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.messenger.telepremium.ai.*;
import org.telegram.messenger.telepremium.aicenter.*;
import org.telegram.messenger.telepremium.cloud.vault.*;
import org.telegram.ui.ActionBar.*;
import java.util.*;
import java.util.List;

/** One center, shared account-qualified index and theme bridge for both Telegram shells. */
public final class TelePremiumAnalyzerActivity extends BaseFragment {
    private final CenterIndex index=new CenterIndex();
    private final CenterData.SourceFilter filter=new CenterData.SourceFilter();
    private CenterSync sync;private LinearLayout root,body,navigation;private TextView scopeLine,status;
    private ScrollView scroll;private int screen;private boolean unlocked,foreground;private String secretKind="PASSWORD";
    private static final String[] SCREENS={"sources","summaries","activity","authors","media","transcriber","analyst","reports","settings"};
    public static String t(String key){return CenterText.get(key);}
    private int dp(float n){return AndroidUtilities.dp(n);}
    private TextView label(String text,int size){TextView v=new TextView(getParentActivity());v.setText(text);v.setTextSize(size);v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));v.setPadding(dp(12),dp(10),dp(12),dp(10));return v;}
    private void heading(String key){TextView v=label(t(key),19);v.setTypeface(AndroidUtilities.bold());body.addView(v);}
    private TextView row(String text,Runnable action){TextView v=label(text,15);v.setMinHeight(dp(48));v.setBackground(Theme.getSelectorDrawable(false));body.addView(v,new LinearLayout.LayoutParams(-1,-2));if(action!=null)v.setOnClickListener(w->action.run());return v;}
    private void button(String key,Runnable action){row(t(key),action);}
    private void info(String text){row(text,null);}
    private void toast(String text){if(getParentActivity()!=null)Toast.makeText(getParentActivity(),text,Toast.LENGTH_LONG).show();}
    private void error(String e){if(e!=null)toast(t("error")+": "+e);}
    private void input(String key,String value,java.util.function.Consumer<String> result){EditText in=new EditText(getParentActivity());in.setText(value);in.setMaxLines(5);new AlertDialog.Builder(getParentActivity()).setTitle(t(key)).setView(in).setPositiveButton(t("save"),(d,w)->result.accept(in.getText().toString().trim())).setNegativeButton(t("cancel"),null).show();}
    private void choices(String title,String[] values,java.util.function.IntConsumer result){new AlertDialog.Builder(getParentActivity()).setTitle(t(title)).setItems(values,(d,w)->result.accept(w)).show();}
    private void toggle(String key,boolean checked,java.util.function.Consumer<Boolean> change){CheckBox c=new CheckBox(getParentActivity());c.setText(t(key));c.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));c.setChecked(checked);c.setPadding(dp(12),dp(8),dp(12),dp(8));c.setOnCheckedChangeListener((v,b)->change.accept(b));body.addView(c);}
    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle(t("title"));actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scopeLine=label(t("sources"),14);scopeLine.setOnClickListener(v->show(0));root.addView(scopeLine);
        status=label("",12);root.addView(status);
        scroll=new ScrollView(context);body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView bottom=new HorizontalScrollView(context);bottom.setHorizontalScrollBarEnabled(false);navigation=new LinearLayout(context);bottom.addView(navigation);root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(56)));
        for(int i=0;i<SCREENS.length;i++){final int tab=i;TextView v=label(t(SCREENS[i]),13);v.setGravity(Gravity.CENTER);v.setOnClickListener(w->show(tab));navigation.addView(v,new LinearLayout.LayoutParams(-2,-1));}
        fragmentView=root;return root;
    }
    @Override public void onResume(){super.onResume();foreground=true;applySecure();if(!unlocked)unlock();else reload();}
    @Override public void onPause(){super.onPause();foreground=false;if(!TPAiStore.getPrivate("center_lock").isEmpty()){unlocked=false;if(body!=null)body.removeAllViews();}if(getParentActivity()!=null)getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    @Override public void onFragmentDestroy(){if(sync!=null)sync.cancel();unlocked=false;super.onFragmentDestroy();}
    private void applySecure(){if(getParentActivity()!=null&&CenterIndex.settings().optBoolean("secure",true))getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private void unlock(){
        String record=TPAiStore.getPrivate("center_lock");if(record.isEmpty()){unlocked=true;reload();return;}body.removeAllViews();heading("locked");button("unlock",()->input("password","",raw->{
            CenterStore.IO.execute(()->{String err=null;try{byte[] master=android.util.Base64.decode(TPAiStore.getPrivate("center_lock_master"),2);char[] secret=VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.valueOf(TPAiStore.getPrivate("center_lock_kind")),raw);byte[] fk=VaultFolderLock.open(VaultFolderLock.Record.parse(record),secret,"aicenter",master);Arrays.fill(fk,(byte)0);Arrays.fill(master,(byte)0);Arrays.fill(secret,'\0');}catch(Exception e){err=t("wrong");}String fail=err;AndroidUtilities.runOnUIThread(()->{if(!foreground)return;if(fail==null){unlocked=true;reload();}else toast(fail);});});}));
        if(VaultFolderBiometric.isEnrolled(getParentActivity(),"aicenter"))button("biometric",()->VaultFolderBiometric.unlock(getParentActivity(),"aicenter",t("unlock"),t("cancel"),new VaultFolderBiometric.KeyCallback(){public void onKey(byte[] key){try{if(VaultFolderLock.Record.parse(record).keyId.equals(VaultFolderLock.keyId(key))){unlocked=true;reload();}}catch(Exception e){error(e.getMessage());}finally{Arrays.fill(key,(byte)0);}}public void onError(String e){error(e);}}));
    }
    private void reload(){status.setText(t("loading"));index.load(e->{if(!unlocked||!foreground)return;status.setText(e==null?"":t("error")+": "+e);show(screen);});}
    private void show(int tab){if(!unlocked)return;screen=tab;body.removeAllViews();scopeLine.setText(t("sources")+" · "+index.scope.sources.size()+" / "+index.scope.whole.size()+" · "+index.selected().size());heading(SCREENS[tab]);
        switch(tab){case 0:sources();break;case 8:settings();break;default:info(t("empty"));}
    }
    private void sources(){
        button("refresh",()->{if(sync!=null)sync.cancel();status.setText(t("loading"));sync=new CenterSync(index,e->{error(e);status.setText("");show(0);});sync.discover();});
        button("load",()->{index.saveScope();if(sync!=null)sync.cancel();status.setText(t("loading"));sync=new CenterSync(index,e->{error(e);status.setText("");show(0);});sync.load();});button("cancel",()->{if(sync!=null)sync.cancel();status.setText("");reload();});
        button("period",()->choices("period",new String[]{"1","7","30","90",t("customDates"),t("sinceRead")},i->{index.scope.sinceRead=i==5;if(i<4){index.scope.to=System.currentTimeMillis()/1000;index.scope.from=index.scope.to-new int[]{1,7,30,90}[i]*86400L;index.saveScope();show(0);}else if(i==4)input("customDates","2026-11-01 / 2026-11-24",dates->{try{String[] v=dates.split("/");java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);f.setLenient(false);index.scope.from=f.parse(v[0].trim()).getTime()/1000;index.scope.to=f.parse(v[1].trim()).getTime()/1000+86399;if(index.scope.to<index.scope.from)throw new Exception();index.saveScope();show(0);}catch(Exception e){error(t("customDates"));}});else{index.saveScope();show(0);}}));
        toggle("own",index.scope.excludeOwn,b->{index.scope.excludeOwn=b;index.saveScope();show(0);});toggle("privateChats",index.scope.includePrivate,b->{if(b)new AlertDialog.Builder(getParentActivity()).setMessage(t("privateChats")).setPositiveButton(t("confirm"),(d,w)->{index.scope.includePrivate=true;index.saveScope();show(0);}).setNegativeButton(t("cancel"),null).show();else{index.scope.includePrivate=false;index.saveScope();show(0);}});
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){final int account=a;long n=index.sources.stream().filter(s->s.account==account).count();String mode=index.scope.excluded.contains(a)?t("excluded"):index.scope.whole.contains(a)?t("all"):t("selected");
            row(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser())+" · "+n+" · "+mode,()->choices("account",new String[]{t("all"),t("selected"),t("excluded")},i->{index.scope.excluded.remove(account);index.scope.whole.remove(account);if(i==0)index.scope.whole.add(account);if(i==2){index.scope.excluded.add(account);index.messages.removeIf(m->m.account==account);CenterIndex.logout(account);}index.saveScope();show(0);}));}
        button("search",()->input("search",filter.text,v->{filter.text=v;show(0);}));
        String[] types={"","group","supergroup","channel","topic","private"};button("type",()->choices("type",new String[]{t("all"),"Group","Supergroup","Channel",t("topic"),t("privateChats")},i->{filter.type=types[i];show(0);}));
        String[] roles={"","owner","admin","member"};button("role",()->choices("role",new String[]{t("all"),"Owner","Admin","Member"},i->{filter.role=roles[i];show(0);}));
        button("folder",()->input("folder",filter.folder,v->{filter.folder=v;show(0);}));button("status",()->choices("status",new String[]{t("all"),"Pinned","Archive","Muted"},i->{filter.status=new String[]{"","pinned","archive","muted"}[i];show(0);}));
        button("active",()->choices("active",new String[]{t("all"),t("active"),t("quiet")},i->{filter.activity=new String[]{"","active","quiet"}[i];show(0);}));button("minActivity",()->input("minActivity",""+filter.minPerDay,v->{try{filter.minPerDay=Math.max(0,Integer.parseInt(v));show(0);}catch(Exception e){error(e.getMessage());}}));
        List<CenterData.Source> matching=new ArrayList<>();for(CenterData.Source s:index.sources)if(filter.matches(s,index.messages,index.scope.from,index.scope.to))matching.add(s);
        button("selectAll",()->{for(CenterData.Source s:matching){index.scope.excluded.remove(s.account);index.scope.sources.add(s.key());}index.saveScope();show(0);});
        button("excludeMatching",()->{for(CenterData.Source s:matching){index.scope.whole.remove(s.account);index.scope.sources.remove(s.key());}index.saveScope();show(0);});
        button("saveSet",()->input("name","",name->{try{TPAiStore.putPrivate("center_set_"+name,index.scope.json().toString());JSONObject sets=CenterIndex.settings().optJSONObject("sets");if(sets==null)sets=new JSONObject();sets.put(name,true);CenterIndex.setting("sets",sets);}catch(Exception e){error(e.getMessage());}}));
        button("sets",()->{JSONObject o=CenterIndex.settings().optJSONObject("sets");ArrayList<String> names=new ArrayList<>();if(o!=null){Iterator<String> keys=o.keys();while(keys.hasNext())names.add(keys.next());}choices("sets",names.toArray(new String[0]),i->{try{index.scope=CenterData.Scope.parse(new JSONObject(TPAiStore.getPrivate("center_set_"+names.get(i))));index.saveScope();show(0);}catch(Exception e){error(e.getMessage());}});});
        info(t("count")+": "+matching.size()+" / "+index.selected().size());
        for(CenterData.Source s:matching){CheckBox v=new CheckBox(getParentActivity());v.setText(s.title+" · "+s.accountName+" · "+s.type+" · "+s.role+"\n"+t("state")+": "+(s.available?"✓":"—")+" · "+new Date(s.readAt));v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));v.setChecked(index.scope.allows(s));v.setOnCheckedChangeListener((b,on)->{if(index.scope.whole.remove(s.account)){for(CenterData.Source q:index.sources)if(q.account==s.account)index.scope.sources.add(q.key());}index.scope.excluded.remove(s.account);if(on)index.scope.sources.add(s.key());else index.scope.sources.remove(s.key());index.saveScope();scopeLine.setText(t("count")+": "+index.selected().size());});body.addView(v);}
    }
    private void settings(){
        JSONObject o=CenterIndex.settings();toggle("local",o.optBoolean("localOnly",false),b->CenterIndex.setting("localOnly",b));toggle("secure",o.optBoolean("secure",true),b->{CenterIndex.setting("secure",b);applySecure();});
        for(String[] pair:new String[][]{{"retention","retentionDays"},{"size","indexMb"},{"budget","messageBudget"}})button(pair[0],()->input(pair[0],""+o.optInt(pair[1],pair[1].equals("retentionDays")?90:pair[1].equals("indexMb")?256:20000),v->{try{CenterIndex.setting(pair[1],Math.max(1,Integer.parseInt(v)));}catch(Exception e){error(e.getMessage());}}));
        button("security",()->choices("security",new String[]{t("pin"),t("password"),t("pattern"),t("disabled")},which->{if(which==3){TPAiStore.putPrivate("center_lock","");VaultFolderBiometric.forget(getParentActivity(),"aicenter");return;}secretKind=new String[]{"PIN","PASSWORD","PATTERN"}[which];input(which==0?"pin":which==1?"password":"pattern","",raw->{CenterStore.IO.execute(()->{String failure=null;try{VaultSecretPolicy.Kind kind=VaultSecretPolicy.Kind.valueOf(secretKind);if(!VaultSecretPolicy.isAcceptable(VaultSecretPolicy.check(kind,raw)))throw new Exception(t("wrong"));byte[] master=VaultFolderLock.newFolderKey(),fk=VaultFolderLock.newFolderKey();char[] secret=VaultSecretPolicy.toSecret(kind,raw);String record=VaultFolderLock.seal(secret,"aicenter",master,fk).encode();TPAiStore.putPrivate("center_lock_master",android.util.Base64.encodeToString(master,2));TPAiStore.putPrivate("center_lock_kind",secretKind);TPAiStore.putPrivate("center_lock",record);TPAiStore.putPrivate("center_bio_setup",android.util.Base64.encodeToString(fk,2));Arrays.fill(master,(byte)0);Arrays.fill(fk,(byte)0);Arrays.fill(secret,'\0');}catch(Exception e){failure=e.getMessage();}String f=failure;AndroidUtilities.runOnUIThread(()->{error(f);if(f==null)toast(t("done"));});});});}));
        button("biometric",()->{String k=TPAiStore.getPrivate("center_bio_setup");if(k.isEmpty()){toast(t("biometricRequired"));return;}byte[] key=android.util.Base64.decode(k,2);VaultFolderBiometric.enroll(getParentActivity(),"aicenter",key,t("biometric"),t("cancel"),e->{error(e);if(e==null)TPAiStore.putPrivate("center_bio_setup","");});Arrays.fill(key,(byte)0);});
        info(t("indexSize")+": "+CenterStore.get().bytes());button("aiConnections",()->presentFragment(new TelePremiumAiActivity()));
        button("clear",()->new AlertDialog.Builder(getParentActivity()).setMessage(t("confirmClear")).setPositiveButton(t("confirm"),(d,w)->index.clear(e->{error(e);show(8);})).setNegativeButton(t("cancel"),null).show());
    }
}
