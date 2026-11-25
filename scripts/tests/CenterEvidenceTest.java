import java.util.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public class CenterEvidenceTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static CenterData.Message m(int a,long chat,String text){CenterData.Message m=new CenterData.Message();m.account=a;m.dialog=chat;m.id=42;m.text=text;m.source="c:"+chat;return m;}
 static JSONObject evidence(int a,long chat,String quote)throws Exception{return new JSONObject().put("account",a).put("chat",chat).put("message",42).put("quote",quote);}
 static CenterReports.Claim validate(List<CenterData.Message> ms,JSONArray ev)throws Exception{return CenterReports.validate(new JSONObject().put("claims",new JSONArray().put(new JSONObject().put("title","Fact [msg:42]").put("text","fact").put("evidence",ev))),ms).claims.get(0);}
 public static void main(String[]args)throws Exception{
  CenterData.Message a=m(0,-100,"Original verified quotation"),b=m(1,-200,"Different quotation");List<CenterData.Message> ms=Arrays.asList(a,b);
  check(validate(ms,new JSONArray().put(evidence(0,-100,"verified"))).verified());
  check(!validate(ms,new JSONArray().put(evidence(1,-200,"verified"))).verified());
  check(!validate(ms,new JSONArray().put(evidence(0,-200,"verified"))).verified());
  check(!validate(ms,new JSONArray().put(evidence(0,-100,"fake quote"))).verified());
  check(!validate(ms,new JSONArray().put(evidence(0,-100,""))).verified());
  check(!validate(ms,new JSONArray().put(evidence(0,-100,"verified").put("page",999))).verified());
  check(!validate(ms,new JSONArray().put(evidence(0,-100,"verified").put("timeMs",999))).verified());
  check(!validate(ms,new JSONArray()).title.contains("msg:"));
  a.deleted=true;check(validate(ms,new JSONArray().put(evidence(0,-100,"verified"))).evidence.get(0).message.deleted);a.deleted=false;
  a.protectedMedia=true;check(!validate(ms,new JSONArray().put(evidence(0,-100,"verified"))).verified());a.protectedMedia=false;
  a.text=String.join("",Collections.nCopies(5000,"abcdef"));List<JSONArray> chunks=CenterReports.chunks(Arrays.asList(a),false,2000);StringBuilder restored=new StringBuilder();for(JSONArray chunk:chunks)for(int i=0;i<chunk.length();i++)restored.append(chunk.getJSONObject(i).optString("text"));check(a.searchable().equals(restored.toString()));
  a.frameTimes.add(0L);check(validate(ms,new JSONArray().put(evidence(0,-100,""))).verified());a.frameTimes.clear();
  a.text="Original verified quotation";a.pages=2;a.pageEvidence.put("1","verified");check(!validate(ms,new JSONArray().put(evidence(0,-100,"verified").put("page",2))).verified());check(validate(ms,new JSONArray().put(evidence(0,-100,"verified").put("page",1))).verified());
  a.personal=true;check(CenterReports.chunks(Arrays.asList(a),false,2000).isEmpty());
  System.out.println("CenterEvidenceTest OK: exact account/chat routing, real quotations, invalid coordinates, deleted evidence and complete chunk coverage");
 }
}