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
    private CenterReports.Report report;private String query="";private boolean detailed=true;private int reportKind;private int savedScroll;private final java.util.concurrent.atomic.AtomicBoolean aiCancelled=new java.util.concurrent.atomic.AtomicBoolean();
    private CenterSync sync;private LinearLayout root,body,navigation;private TextView scopeLine,status;
    private ScrollView scroll;private int screen;private boolean unlocked,foreground;private String secretKind="PASSWORD";
    private final VaultAttemptLimiter limiter=new VaultAttemptLimiter(new VaultAttemptLimiter.Store(){public long getLong(String k,long d){return CenterIndex.settings().optLong("lock_"+k,d);}public void putLong(String k,long v){CenterIndex.setting("lock_"+k,v);}},System::currentTimeMillis);
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
    private void input(String key,String value,java.util.function.Consumer<String> result){if(key.equals("pattern")){patternInput(result);return;}EditText in=new EditText(getParentActivity());if(key.equals("password")||key.equals("pin"))in.setInputType(key.equals("pin")?18:129);in.setText(value);in.setMaxLines(5);new AlertDialog.Builder(getParentActivity()).setTitle(t(key)).setView(in).setPositiveButton(t("save"),(d,w)->result.accept(in.getText().toString().trim())).setNegativeButton(t("cancel"),null).show();}
    private void patternInput(java.util.function.Consumer<String> done){
        class Grid extends View {
            final StringBuilder pattern=new StringBuilder();final Paint paint=new Paint(3);float lastX,lastY;
            Grid(Context c){super(c);setMinimumHeight(dp(280));}
            @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));paint.setStrokeWidth(dp(3));float x=0,y=0;for(int i=0;i<pattern.length();i++){int n=pattern.charAt(i)-'0';float nx=getWidth()*(n%3+1)/4f,ny=getHeight()*(n/3+1)/4f;if(i>0)canvas.drawLine(x,y,nx,ny,paint);x=nx;y=ny;}for(int n=0;n<9;n++)canvas.drawCircle(getWidth()*(n%3+1)/4f,getHeight()*(n/3+1)/4f,dp(9),paint);}
            @Override public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==0)pattern.setLength(0);if(e.getAction()==0||e.getAction()==2){for(int n=0;n<9;n++)if(Math.hypot(e.getX()-getWidth()*(n%3+1)/4f,e.getY()-getHeight()*(n/3+1)/4f)<dp(28)&&pattern.indexOf(""+n)<0)pattern.append(n);invalidate();}return true;}
        }
        Grid grid=new Grid(getParentActivity());new AlertDialog.Builder(getParentActivity()).setTitle(t("pattern")).setView(grid).setPositiveButton(t("confirm"),(d,w)->done.accept(grid.pattern.toString())).setNegativeButton(t("cancel"),null).show();
    }
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
    @Override public void onPause(){super.onPause();savedScroll=scroll==null?0:scroll.getScrollY();foreground=false;if(!TPAiStore.getPrivate("center_lock").isEmpty()){unlocked=false;if(body!=null)body.removeAllViews();}if(getParentActivity()!=null)getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    @Override public void onFragmentDestroy(){if(sync!=null)sync.cancel();aiCancelled.set(true);unlocked=false;super.onFragmentDestroy();}
    private void applySecure(){if(getParentActivity()!=null&&CenterIndex.settings().optBoolean("secure",true))getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private void unlock(){
        String record=TPAiStore.getPrivate("center_lock");if(record.isEmpty()){unlocked=true;reload();return;}body.removeAllViews();heading("locked");button("unlock",()->input(TPAiStore.getPrivate("center_lock_kind").equals("PATTERN")?"pattern":"password","",raw->{
            if(limiter.remainingMs("aicenter")>0){toast(t("wrong"));return;}CenterStore.IO.execute(()->{String err=null;try{byte[] master=android.util.Base64.decode(TPAiStore.getPrivate("center_lock_master"),2);char[] secret=VaultSecretPolicy.toSecret(VaultSecretPolicy.Kind.valueOf(TPAiStore.getPrivate("center_lock_kind")),raw);byte[] fk=VaultFolderLock.open(VaultFolderLock.Record.parse(record),secret,"aicenter",master);Arrays.fill(fk,(byte)0);Arrays.fill(master,(byte)0);Arrays.fill(secret,'\0');}catch(Exception e){err=t("wrong");}if(err==null)limiter.recordSuccess("aicenter");else limiter.recordFailure("aicenter");String fail=err;AndroidUtilities.runOnUIThread(()->{if(!foreground)return;if(fail==null){unlocked=true;reload();}else toast(fail);});});}));
        if(VaultFolderBiometric.isEnrolled(getParentActivity(),"aicenter"))button("biometric",()->VaultFolderBiometric.unlock(getParentActivity(),"aicenter",t("unlock"),t("cancel"),new VaultFolderBiometric.KeyCallback(){public void onKey(byte[] key){try{if(VaultFolderLock.Record.parse(record).keyId.equals(VaultFolderLock.keyId(key))){unlocked=true;reload();}}catch(Exception e){error(e.getMessage());}finally{Arrays.fill(key,(byte)0);}}public void onError(String e){error(e);}}));
    }
    private void reload(){status.setText(t("loading"));index.load(e->{if(!unlocked||!foreground)return;status.setText(e==null?"":t("error")+": "+e);show(screen);scroll.post(()->scroll.scrollTo(0,savedScroll));});}
    private void show(int tab){if(!unlocked)return;screen=tab;body.removeAllViews();scopeLine.setText(t("sources")+" · "+index.sources.stream().filter(index.scope::allows).count()+" / "+index.scope.whole.size()+" · "+index.selected().size());heading(SCREENS[tab]);
        switch(tab){case 0:sources();break;case 1:summaries(false);break;case 6:summaries(true);break;case 7:reportArchive();break;case 8:settings();break;default:info(t("empty"));}
    }
    private void sources(){
        button("refresh",()->{if(sync!=null)sync.cancel();status.setText(t("loading"));sync=new CenterSync(index,e->{error(e);status.setText("");show(0);});sync.discover();});
        button("load",()->{index.saveScope();if(sync!=null)sync.cancel();status.setText(t("loading"));sync=new CenterSync(index,e->{error(e);status.setText("");show(0);});sync.load();});button("cancel",()->{if(sync!=null)sync.cancel();status.setText("");reload();});
        button("period",()->choices("period",new String[]{"1","7","30","90",t("customDates"),t("sinceRead")},i->{index.scope.sinceRead=i==5;if(i<4){index.scope.to=System.currentTimeMillis()/1000;index.scope.from=index.scope.to-new int[]{1,7,30,90}[i]*86400L;index.saveScope();show(0);}else if(i==4)input("customDates","2026-11-01 / 2026-11-24",dates->{try{String[] v=dates.split("/");java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);f.setLenient(false);index.scope.from=f.parse(v[0].trim()).getTime()/1000;index.scope.to=f.parse(v[1].trim()).getTime()/1000+86399;if(index.scope.to<index.scope.from)throw new Exception();index.saveScope();show(0);}catch(Exception e){error(t("customDates"));}});else{index.saveScope();show(0);}}));
        toggle("own",index.scope.excludeOwn,b->{index.scope.excludeOwn=b;index.saveScope();show(0);});toggle("privateChats",index.scope.includePrivate,b->{if(b)new AlertDialog.Builder(getParentActivity()).setMessage(t("privateChats")).setPositiveButton(t("confirm"),(d,w)->{index.scope.includePrivate=true;index.saveScope();show(0);}).setNegativeButton(t("cancel"),null).show();else{index.scope.includePrivate=false;index.saveScope();show(0);}});
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){final int account=a;long n=index.sources.stream().filter(s->s.account==account).count();String mode=index.scope.excluded.contains(a)?t("excluded"):index.scope.whole.contains(a)?t("all"):t("selected");
            row(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser())+" · "+n+" · "+mode,()->choices("account",new String[]{t("all"),t("selected"),t("excluded")},i->{index.scope.excluded.remove(account);index.scope.whole.remove(account);if(i==0)index.scope.whole.add(account);if(i==2){aiCancelled.set(true);report=null;index.scope.excluded.add(account);index.messages.removeIf(m->m.account==account);CenterIndex.logout(account);}index.saveScope();show(0);}));}
        button("account",()->{java.util.ArrayList<Integer> accounts=new java.util.ArrayList<>();java.util.ArrayList<String> names=new java.util.ArrayList<>();for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()){accounts.add(a);names.add(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser()));}boolean[] chosen=new boolean[accounts.size()];for(int i=0;i<chosen.length;i++)chosen[i]=filter.accounts.isEmpty()||filter.accounts.contains(accounts.get(i));new AlertDialog.Builder(getParentActivity()).setTitle(t("account")).setMultiChoiceItems(names.toArray(new String[0]),chosen,(d,i,on)->chosen[i]=on).setPositiveButton(t("confirm"),(d,w)->{filter.accounts.clear();for(int i=0;i<chosen.length;i++)if(chosen[i])filter.accounts.add(accounts.get(i));show(0);}).show();});
        button("search",()->input("search",filter.text,v->{filter.text=v;show(0);}));
        String[] types={"","group","supergroup","channel","topic","private"};button("type",()->choices("type",new String[]{t("all"),"Group","Supergroup","Channel",t("topic"),t("privateChats")},i->{filter.type=types[i];show(0);}));
        String[] roles={"","owner","admin","member"};button("role",()->choices("role",new String[]{t("all"),"Owner","Admin","Member"},i->{filter.role=roles[i];show(0);}));
        button("folder",()->input("folder",filter.folder,v->{filter.folder=v;show(0);}));button("status",()->choices("status",new String[]{t("all"),"Pinned","Archive","Muted"},i->{filter.status=new String[]{"","pinned","archive","muted"}[i];show(0);}));
        button("active",()->choices("active",new String[]{t("all"),t("active"),t("quiet")},i->{filter.activity=new String[]{"","active","quiet"}[i];show(0);}));button("minActivity",()->input("minActivity",""+filter.minPerDay,v->{try{filter.minPerDay=Math.max(0,Integer.parseInt(v));show(0);}catch(Exception e){error(e.getMessage());}}));
        List<CenterData.Source> matching=new ArrayList<>();for(CenterData.Source s:index.sources)if(filter.matches(s,index.messages,index.scope.from,index.scope.to))matching.add(s);
        button("selectAll",()->{for(CenterData.Source s:matching){index.scope.excluded.remove(s.account);index.scope.sources.add(s.key());}index.saveScope();show(0);});
        button("excludeMatching",()->{for(CenterData.Source s:matching){if(index.scope.whole.remove(s.account))for(CenterData.Source q:index.sources)if(q.account==s.account)index.scope.sources.add(q.key());index.scope.sources.remove(s.key());}index.saveScope();show(0);});
        button("saveSet",()->input("name","",name->{try{TPAiStore.putPrivate("center_set_"+name,index.scope.json().toString());JSONObject sets=CenterIndex.settings().optJSONObject("sets");if(sets==null)sets=new JSONObject();sets.put(name,true);CenterIndex.setting("sets",sets);}catch(Exception e){error(e.getMessage());}}));
        button("sets",()->{JSONObject o=CenterIndex.settings().optJSONObject("sets");ArrayList<String> names=new ArrayList<>();if(o!=null){Iterator<String> keys=o.keys();while(keys.hasNext())names.add(keys.next());}choices("sets",names.toArray(new String[0]),i->{try{index.scope=CenterData.Scope.parse(new JSONObject(TPAiStore.getPrivate("center_set_"+names.get(i))));index.saveScope();show(0);}catch(Exception e){error(e.getMessage());}});});
        info(t("count")+": "+matching.size()+" / "+index.selected().size());
        for(CenterData.Source s:matching){CheckBox v=new CheckBox(getParentActivity());v.setText(s.title+" · "+s.accountName+" · "+s.type+" · "+s.role+"\n"+t("state")+": "+(s.available?"✓":"—")+" · "+(s.loadedAt>0?new Date(s.loadedAt).toString():"—"));v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));v.setChecked(index.scope.allows(s));v.setOnCheckedChangeListener((b,on)->{if(index.scope.whole.remove(s.account)){for(CenterData.Source q:index.sources)if(q.account==s.account)index.scope.sources.add(q.key());}index.scope.excluded.remove(s.account);if(on)index.scope.sources.add(s.key());else index.scope.sources.remove(s.key());index.saveScope();scopeLine.setText(t("count")+": "+index.selected().size());});body.addView(v);}
    }
    private void summaries(boolean chat){
        if(!chat)button("type",()->choices("type",new String[]{t("groupReport"),t("authorsReport"),t("new"),t("compare")},i->{reportKind=i;}));
        EditText composer=new EditText(getParentActivity());composer.setHint(t("query"));composer.setMaxLines(4);composer.setText(query);body.addView(composer);
        toggle("detailed",detailed,b->detailed=b);toggle("personal",index.scope.includePersonal,b->{index.scope.includePersonal=b;index.saveScope();});
        button("ask",()->{query=composer.getText().toString().trim();if(query.isEmpty()){toast(t("query"));return;}runReport(query);});
        button("cancel",()->aiCancelled.set(true));
        button("templates",()->{JSONObject templates=CenterIndex.settings().optJSONObject("templates");List<String> names=new ArrayList<>();if(templates!=null){Iterator<String> keys=templates.keys();while(keys.hasNext())names.add(keys.next());}JSONObject saved=templates;choices("templates",names.toArray(new String[0]),i->{try{JSONObject v=saved.getJSONObject(names.get(i));query=v.optString("query");index.scope=CenterData.Scope.parse(v.getJSONObject("scope"));index.saveScope();show(screen);}catch(Exception e){error(e.getMessage());}});});
        if(report!=null){renderReport(report);button("saveSet",()->input("name","",name->{try{JSONObject ts=CenterIndex.settings().optJSONObject("templates");if(ts==null)ts=new JSONObject();ts.put(name,new JSONObject().put("query",query).put("scope",index.scope.json()));CenterIndex.setting("templates",ts);}catch(Exception e){error(e.getMessage());}}));button("archive",()->persistReport(report,"report"));}
        if(chat)button("reports",this::history);
        button("markReviewed",()->{long now=System.currentTimeMillis();for(CenterData.Source source:index.sources)if(index.scope.allows(source)){source.readAt=now;CenterStore.IO.execute(()->{try{CenterStore.get().put(source.account,source.owner,"source",source.key(),source.json());}catch(Exception e){FileLog.e(e);}});}toast(t("done"));});
    }
    private void runReport(String question){
        Runnable action=()->{if(CenterIndex.settings().optBoolean("localOnly",false)){toast(t("local"));return;}
            List<CenterData.Message> selected=index.selected();if(selected.isEmpty()){toast(t("empty"));return;}
            CenterData.Scope scope;try{scope=CenterData.Scope.parse(index.scope.json());}catch(Exception e){error(e.getMessage());return;}
            List<CenterData.Source> sources=new ArrayList<>(index.sources);String request=question;
            if(reportKind==1)request+="\nReport by author across the selected groups, based only on public publications; no personality inference.";
            else if(reportKind==2){scope.sinceRead=true;selected=CenterData.select(index.messages,sources,scope);}
            else if(reportKind==3){long span=scope.to-scope.from;scope.from-=span;selected=CenterData.select(index.messages,sources,scope);request+="\nCompare the two equal adjacent periods. Explicitly identify insufficient previous-period coverage.";}
            final List<CenterData.Message> data=selected;final String prompt=request;
            int chars=0;for(CenterData.Message m:data)chars+=m.searchable().length();String estimate=t("estimate")+": "+data.size()+" · ≈"+((chars+2)/3+((chars+17999)/18000)*2500+4000)+" · "+t("usage");
            new AlertDialog.Builder(getParentActivity()).setTitle(t("estimate")).setMessage(estimate).setPositiveButton(t("continue"),(d,w)->{
                aiCancelled.set(false);status.setText(t("loading"));TPAiClient.async(()->CenterAi.run(data,sources,scope,prompt,detailed,aiCancelled,(n,total)->AndroidUtilities.runOnUIThread(()->{if(unlocked&&foreground)status.setText(t("progress")+": "+n+" / "+total);})),(result,e)->{
                    if(!unlocked||!foreground||aiCancelled.get())return;status.setText("");if(e!=null){error(e);return;}report=result;persistReport(report,"thread");show(screen);
                });
            }).setNegativeButton(t("cancel"),null).show();
        };
        if(CenterIndex.settings().optBoolean("consent",false))action.run();else new AlertDialog.Builder(getParentActivity()).setMessage(t("consent")).setPositiveButton(t("confirm"),(d,w)->{CenterIndex.setting("consent",true);action.run();}).setNegativeButton(t("cancel"),null).show();
    }
    private void renderReport(CenterReports.Report r){
        info(r.question+"\n"+new Date(r.created)+"\n"+new Date(r.from*1000)+" — "+new Date(r.to*1000));
        info(t("coverage")+": "+r.analyzed+" / "+r.indexed+" ("+(r.indexed==0?0:(100L*r.analyzed/r.indexed))+"%) · "+(r.complete?t("done"):t("partial")));
        for(CenterReports.Claim claim:r.claims){TextView title=row(claim.title,null);title.setTypeface(AndroidUtilities.bold());info(claim.text);info(t(claim.verified()?claim.certainty:"unverified"));
            final LinearLayout evidence=new LinearLayout(getParentActivity());evidence.setOrientation(LinearLayout.VERTICAL);
            row(t("evidence")+" ("+claim.evidence.size()+")",()->{if(evidence.getChildCount()>0)evidence.removeAllViews();else for(CenterReports.Evidence ev:claim.evidence){CenterData.Message m=ev.message;TextView v=label(m.authorName+" · "+m.sourceTitle+" · "+m.accountName+"\n"+new Date(m.date*1000L)+"\n“"+ev.quote+"”"+(m.deleted?"\n"+t("deleted"):""),14);v.setOnClickListener(w->openEvidence(m));v.setOnLongClickListener(w->{evidenceActions(m,claim);return true;});evidence.addView(v);}});body.addView(evidence);
        }
        if(!r.unclear.isEmpty()){heading("unknown");for(String line:r.unclear)info(CenterReports.clean(line));}
    }
    private void evidenceActions(CenterData.Message m,CenterReports.Claim claim){choices("evidence",new String[]{t("context"),t("link"),t("authors")},which->{
        if(which==0){LinearLayout lines=new LinearLayout(getParentActivity());lines.setOrientation(1);for(CenterData.Message q:CenterReports.context(index.messages,m)){TextView v=label(q.authorName+" · "+new Date(q.date*1000L)+"\n"+q.text,14);v.setOnClickListener(w->openEvidence(q));lines.addView(v);}ScrollView sv=new ScrollView(getParentActivity());sv.addView(lines);showDialog(new org.telegram.ui.ActionBar.BottomSheet.Builder(getParentActivity()).setTitle(t("context")).setCustomView(sv).create());}
        else if(which==1){String link=m.source.startsWith("c:")?"https://t.me/c/"+(-m.dialog)+"/"+m.id:"tg://openmessage?"+(m.dialog>0?"user_id="+m.dialog:"chat_id="+(-m.dialog))+"&message_id="+m.id;((ClipboardManager)getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(m.sourceTitle,link));toast(t("done"));}
        else{Set<String> authors=new LinkedHashSet<>();for(CenterReports.Evidence ev:claim.evidence)authors.add(ev.message.authorName);new AlertDialog.Builder(getParentActivity()).setTitle(t("authors")).setMessage(android.text.TextUtils.join("\n",authors)).setPositiveButton(t("done"),null).show();}
    });}
    private void openEvidence(CenterData.Message m){
        if(m.deleted){new AlertDialog.Builder(getParentActivity()).setTitle(t("deleted")).setMessage(m.text).setPositiveButton(t("done"),null).show();return;}
        Runnable open=()->{if(!UserConfig.getInstance(m.account).isClientActivated()||UserConfig.getInstance(m.account).getClientUserId()!=m.owner){toast(t("excluded"));return;}
            if(UserConfig.selectedAccount!=m.account&&getParentActivity() instanceof LaunchActivity)((LaunchActivity)getParentActivity()).switchToAccount(m.account,false);
            Bundle args=new Bundle();if(m.dialog<0)args.putLong("chat_id",-m.dialog);else args.putLong("user_id",m.dialog);args.putInt("message_id",m.id);ChatActivity chat=new ChatActivity(args);chat.setCurrentAccount(m.account);presentFragment(chat);
        };
        if(UserConfig.selectedAccount==m.account)open.run();else new AlertDialog.Builder(getParentActivity()).setMessage(t("switchAccount")).setPositiveButton(t("confirm"),(d,w)->open.run()).setNegativeButton(t("cancel"),null).show();
    }
    private void persistReport(CenterReports.Report r,String kind){
        List<CenterData.Message> messages=index.selected();if(messages.isEmpty())return;int a=messages.get(0).account;long owner=messages.get(0).owner;
        CenterStore.IO.execute(()->{try{JSONObject o=r.json().put("accounts",CenterAi.accounts(messages));CenterStore.get().put(a,owner,kind,r.id,o);AndroidUtilities.runOnUIThread(()->{if(kind.equals("report"))toast(t("done"));});}catch(Exception e){AndroidUtilities.runOnUIThread(()->error(e.getMessage()));}});
    }
    private void history(){listReports("thread");}
    private void reportArchive(){button("reports",()->listReports("report"));if(report!=null)renderReport(report);}
    private void listReports(String kind){status.setText(t("loading"));CenterStore.IO.execute(()->{List<JSONObject> values=new ArrayList<>();try{for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated()&&!index.scope.excluded.contains(a))values.addAll(CenterStore.get().list(a,UserConfig.getInstance(a).getClientUserId(),kind));}catch(Exception e){FileLog.e(e);}AndroidUtilities.runOnUIThread(()->{if(!unlocked||!foreground)return;status.setText("");body.removeAllViews();heading(kind.equals("thread")?"analyst":"reports");for(JSONObject o:values){CenterReports.Report r=CenterReports.validate(o,index.messages);row(r.question+" · "+new Date(r.created),()->{report=r;show(screen);});}button("delete",()->{CenterStore.IO.execute(()->{try{for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.getInstance(a).isClientActivated())for(JSONObject o:CenterStore.get().list(a,UserConfig.getInstance(a).getClientUserId(),kind))CenterStore.get().remove(a,UserConfig.getInstance(a).getClientUserId(),kind,o.optString("id"));}catch(Exception e){FileLog.e(e);}});report=null;show(screen);});});});}
    private void settings(){
        JSONObject o=CenterIndex.settings();toggle("local",o.optBoolean("localOnly",false),b->CenterIndex.setting("localOnly",b));toggle("secure",o.optBoolean("secure",true),b->{CenterIndex.setting("secure",b);applySecure();});
        for(String[] pair:new String[][]{{"retention","retentionDays"},{"size","indexMb"},{"budget","messageBudget"}})button(pair[0],()->input(pair[0],""+o.optInt(pair[1],pair[1].equals("retentionDays")?90:pair[1].equals("indexMb")?256:20000),v->{try{CenterIndex.setting(pair[1],Math.max(1,Integer.parseInt(v)));}catch(Exception e){error(e.getMessage());}}));
        button("security",()->choices("security",new String[]{t("pin"),t("password"),t("pattern"),t("disabled")},which->{if(which==3){TPAiStore.putPrivate("center_lock","");VaultFolderBiometric.forget(getParentActivity(),"aicenter");return;}secretKind=new String[]{"PIN","PASSWORD","PATTERN"}[which];input(which==0?"pin":which==1?"password":"pattern","",raw->{CenterStore.IO.execute(()->{String failure=null;try{VaultSecretPolicy.Kind kind=VaultSecretPolicy.Kind.valueOf(secretKind);if(!VaultSecretPolicy.isAcceptable(VaultSecretPolicy.check(kind,raw)))throw new Exception(t("wrong"));byte[] master=VaultFolderLock.newFolderKey(),fk=VaultFolderLock.newFolderKey();char[] secret=VaultSecretPolicy.toSecret(kind,raw);String record=VaultFolderLock.seal(secret,"aicenter",master,fk).encode();TPAiStore.putPrivate("center_lock_master",android.util.Base64.encodeToString(master,2));TPAiStore.putPrivate("center_lock_kind",secretKind);TPAiStore.putPrivate("center_lock",record);TPAiStore.putPrivate("center_bio_setup",android.util.Base64.encodeToString(fk,2));Arrays.fill(master,(byte)0);Arrays.fill(fk,(byte)0);Arrays.fill(secret,'\0');}catch(Exception e){failure=e.getMessage();}String f=failure;AndroidUtilities.runOnUIThread(()->{error(f);if(f==null)toast(t("done"));});});});}));
        button("biometric",()->{String k=TPAiStore.getPrivate("center_bio_setup");if(k.isEmpty()){toast(t("biometricRequired"));return;}byte[] key=android.util.Base64.decode(k,2);VaultFolderBiometric.enroll(getParentActivity(),"aicenter",key,t("biometric"),t("cancel"),e->{error(e);if(e==null)TPAiStore.putPrivate("center_bio_setup","");});Arrays.fill(key,(byte)0);});
        info(t("indexSize")+": "+CenterStore.get().bytes());button("aiConnections",()->presentFragment(new TelePremiumAiActivity()));
        button("clear",()->new AlertDialog.Builder(getParentActivity()).setMessage(t("confirmClear")).setPositiveButton(t("confirm"),(d,w)->index.clear(e->{error(e);show(8);})).setNegativeButton(t("cancel"),null).show());
    }
}
