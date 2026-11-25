import java.util.*;
import java.nio.file.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public final class CenterAuthorProfileTest {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static CenterData.Message message(int id,int date,long author,String text){CenterData.Message m=new CenterData.Message();m.account=0;m.owner=100;m.dialog=-100;m.source="c:100";m.sourceTitle="Group";m.id=id;m.date=date;m.author=author;m.authorName="Author"+author;m.text=text;return m;}
 public static void main(String[] args)throws Exception {
  Map<String,String> strings=new HashMap<>();Document document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("patches/tg13/overlay/TMessagesProj/src/main/res/values/strings_telepremium_center.xml");NodeList nodes=document.getElementsByTagName("string");for(int i=0;i<nodes.getLength();i++){Element node=(Element)nodes.item(i);strings.put(node.getAttribute("name").replace("TP_AIC_",""),node.getTextContent());}
  CenterData.Source source=new CenterData.Source();source.account=0;source.owner=100;source.dialog=-100;source.canonical="c:100";source.title="Group";source.complete=true;source.rangeFrom=0;source.rangeTo=2000000;
  CenterData.Scope scope=new CenterData.Scope();scope.whole.add(0);scope.authors.add(7L);scope.from=864000;scope.to=1468799;
  List<CenterData.Message> data=new ArrayList<>();data.add(message(1,500000,7,"Previous #old"));data.add(message(2,1000000,7,"Current #new"));data.add(message(3,1000001,7,"Other #new"));for(int i=0;i<10;i++)data.add(message(10+i,1000010+i,8,"Peers #different"));
  CenterStats stats=CenterStats.compute(data,Arrays.asList(source),scope,TimeZone.getTimeZone("UTC"));CenterStats.Author author=stats.authors.get(0);check(author.count==2&&author.previous==1,"equal-period author counts");check(stats.groups.get(0).allMessages==12,"comparison denominator includes other participants despite author filter");check(CenterAuthorProfile.newTopic(author,stats,"#new"),"new topics from complete baseline");
  CenterReports.Report report=CenterAuthorProfile.build(author,stats,key->strings.getOrDefault(key,key));check(report.claims.get(0).text.contains("2 messages"),"automatic paragraph uses actual localized resource format");check(report.claims.get(0).verified(),"overview links to original publications");check(report.claims.stream().anyMatch(c->c.text.contains("of 12")),"peer comparison");check(report.statistics.optJSONArray("authors").length()==1,"profile export includes target author only");
  source.rangeFrom=900000;stats=CenterStats.compute(data,Arrays.asList(source),scope,TimeZone.getTimeZone("UTC"));check(!CenterAuthorProfile.newTopic(stats.authors.get(0),stats,"#new"),"missing history cannot establish topic novelty");
  CenterData.Message image=message(42,1000000,7,"");image.kind="photo";source.rangeFrom=0;stats=CenterStats.compute(Arrays.asList(image),Arrays.asList(source),scope,TimeZone.getTimeZone("UTC"));CenterReports.Report local=CenterAuthorProfile.build(stats.authors.get(0),stats,key->strings.getOrDefault(key,key));check(CenterReports.restore(CenterReports.archive(local),Collections.emptyList()).claims.get(0).verified(),"metadata-only local evidence survives archival without invented image observations");
  JSONObject malicious=new JSONObject().put("localReport",true).put("claims",new JSONArray().put(new JSONObject().put("title","Invented").put("evidence",new JSONArray().put(new JSONObject().put("account",0).put("chat",-100).put("message",42).put("quote","").put("metadata",true)))));check(!CenterReports.validate(malicious,Arrays.asList(image)).claims.get(0).verified(),"provider cannot request trusted local metadata mode");
  System.out.println("CenterAuthorProfileTest OK: localized automatic profile, peer denominators, topic novelty, incomplete baseline, archival metadata and provider isolation");
 }
}
