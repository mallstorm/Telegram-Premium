import java.util.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public final class CenterLinksAndChartTest {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static CenterData.Message message(int id,int date){CenterData.Message m=new CenterData.Message();m.account=0;m.owner=100;m.dialog=-100;m.source="c:100";m.sourceTitle="Group";m.id=id;m.date=date;m.author=id;m.authorName="Author "+id;return m;}
 public static void main(String[] args)throws Exception{
  CenterData.Message late=message(20,100);late.text="https://Example.org/a#part https://example.org/a";late.content="https://example.org/a\nActual Telegram title\nexample.org";CenterData.Message early=message(10,90);early.text="https://example.org/a";Map<String,CenterLinks.Link> links=CenterLinks.collect(Arrays.asList(late,early));check(links.size()==1,"canonical URL and fragment deduplication");CenterLinks.Link link=links.values().iterator().next();check(link.evidence.size()==2&&link.first.id==10,"mention frequency counts publications, not repeated URL occurrences");check(link.title.equals("Actual Telegram title"),"title comes from Telegram metadata, without loading a page");
  CenterData.Source source=new CenterData.Source();source.account=0;source.owner=100;source.dialog=-100;source.canonical="c:100";source.title="Group";source.complete=true;source.rangeFrom=0;source.rangeTo=2000;CenterData.Scope scope=new CenterData.Scope();scope.whole.add(0);scope.from=80;scope.to=200;
  CenterStats stats=CenterStats.compute(Arrays.asList(late,early),Arrays.asList(source),scope,TimeZone.getTimeZone("UTC"));CenterReports.Report chart=CenterChartReport.build(stats,stats.groups,2,Arrays.asList(source),key->key);check(Arrays.equals(CenterExport.chartModes(chart.statistics),new int[]{2}),"a selected chart is retained in the report builder");check(chart.claims.get(0).verified(),"chart counts retain source publications");
  CenterStats quiet=CenterStats.compute(Collections.emptyList(),Arrays.asList(source),scope,TimeZone.getTimeZone("UTC"));CenterReports.Report zero=CenterChartReport.build(quiet,quiet.groups,0,Arrays.asList(source),key->key);check(zero.complete&&zero.indexed==0&&zero.storageOwner==100,"fully loaded zero-activity charts have account-backed storage");check(zero.claims.isEmpty(),"no invented message as evidence for zero activity");check(CenterReports.restore(CenterReports.archive(zero),Collections.emptyList()).statistics.optJSONArray("groups").optJSONObject(0).optInt("messages")==0,"zero report survives archival");
  JSONObject invalid=new JSONObject().put("chartModes",new JSONArray().put(2).put(2).put(99).put("invalid"));check(Arrays.equals(CenterExport.chartModes(invalid),new int[]{2}),"malformed chart selections do not render arbitrary modes");
  CenterExport.Options options=new CenterExport.Options();
  for(int mode=0;mode<5;mode++)check(CenterExport.chartRows(stats.json(),mode,options,key->key).size()>1,"each chart has an exact data table");
  String csv=CenterExport.csv(chart.statistics,options,key->key);check(csv.contains("heatmap")&&!csv.contains("concentration"),"CSV retains only selected chart tables");
  java.io.StringWriter html=new java.io.StringWriter();options.images=false;CenterExport.html(chart,options,key->key,chart.statistics,null,html);check(html.toString().contains("hour"),"image-free report retains exact heat map values");
  CenterData.Message foreign=message(30,100);foreign.owner=999;check(CenterData.select(Arrays.asList(foreign),Arrays.asList(source),scope).isEmpty(),"reused account slots cannot admit another owner data");
  System.out.println("CenterLinksAndChartTest OK: real URL metadata, first publication, deduplication, selected charts, zero-activity reports and archival");
 }
}
