import java.util.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public final class CenterMediaBundleTest {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static CenterData.Message m(int account,int id){CenterData.Message m=new CenterData.Message();m.account=account;m.owner=100+account;m.dialog=-100;m.source="c:100";m.date=100;m.id=id;m.text="Discussion "+id;return m;}
 public static void main(String[] args)throws Exception {
  CenterData.Source source=new CenterData.Source();source.account=0;source.owner=100;source.dialog=-100;source.canonical="c:100";CenterData.Scope scope=new CenterData.Scope();scope.whole.add(0);scope.from=0;scope.to=200;CenterData.Message anchor=m(0,10);anchor.kind="photo";anchor.frameTimes.add(0L);
  List<CenterData.Message> rows=new ArrayList<>();for(int i=0;i<22;i++)rows.add(m(0,i));rows.add(m(1,9));CenterData.Message protectedMessage=m(0,9);protectedMessage.protectedMedia=true;rows.removeIf(message->message.id==9&&message.account==0);rows.add(protectedMessage);
  List<CenterData.Message> context=CenterMediaBundle.discussion(rows,Arrays.asList(source),scope,anchor);check(context.size()==10,"five eligible messages before and after");check(context.stream().allMatch(message->message.account==0&&!message.protectedMedia&&message.id!=10),"same account, no protected data, anchor is separate");
  JSONArray snapshot=CenterMediaBundle.snapshot(context);check(snapshot.getJSONObject(0).optString("transport").isEmpty(),"provider context has no Telegram handles");List<CenterData.Message> restored=CenterMediaBundle.restore(snapshot);List<CenterData.Message> evidence=new ArrayList<>(restored);evidence.add(anchor);
  JSONObject report=new JSONObject().put("claims",new JSONArray().put(new JSONObject().put("title","Caption and discussion").put("evidence",new JSONArray().put(new JSONObject().put("account",0).put("chat",-100).put("message",restored.get(0).id).put("quote",restored.get(0).text)).put(new JSONObject().put("account",0).put("chat",-100).put("message",10).put("timeMs",0).put("quote","")))));
  check(CenterReports.validate(report,evidence).claims.get(0).evidence.size()==2,"visual anchor and exact discussion quotation validate together");anchor.pages=100;anchor.frameTimes.clear();report.getJSONArray("claims").getJSONObject(0).getJSONArray("evidence").getJSONObject(1).put("page",80);check(CenterReports.validate(report,evidence).claims.get(0).evidence.size()==1,"unprocessed PDF page is not visual evidence");
  scope.excluded.add(0);check(CenterMediaBundle.discussion(rows,Arrays.asList(source),scope,anchor).isEmpty(),"revoked scope has no discussion");
  check(CenterReports.excerpt("a\ud83d\ude80",2).equals("a"),"quotation truncation preserves Unicode");check(CenterReports.clean("fact [MSG:42]").equals("fact"),"case-insensitive raw reference cleanup");
  System.out.println("CenterMediaBundleTest OK: scoped discussion, account/protection isolation, exact visual/text citations, unprocessed pages and Unicode");
 }
}
