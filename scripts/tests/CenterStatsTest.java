import java.util.*;
import org.telegram.messenger.telepremium.aicenter.*;
public class CenterStatsTest {
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 static CenterData.Message m(int account,int id,int date,long author){CenterData.Message m=new CenterData.Message();m.account=account;m.dialog=-100;m.source="c:100";m.sourceTitle="Group";m.id=id;m.date=date;m.author=author;m.authorName="Author";m.text="https://example.org/a #topic";return m;}
 public static void main(String[]args)throws Exception{
  CenterData.Source a=new CenterData.Source();a.account=0;a.dialog=-100;a.canonical="c:100";a.title="Group";a.complete=true;a.rangeFrom=0;a.rangeTo=2000000;
  CenterData.Source b=CenterData.Source.parse(a.json());b.account=1;
  CenterData.Scope scope=new CenterData.Scope();scope.from=864000;scope.to=1468800;scope.whole.addAll(Arrays.asList(0,1));
  List<CenterData.Message> messages=new ArrayList<>();for(int i=0;i<10;i++)messages.add(m(0,i+1,1000000,7));messages.add(m(1,1,1000000,7));messages.add(m(1,11,1000001,8));messages.add(m(0,20,500000,7));
  CenterStats stats=CenterStats.compute(messages,Arrays.asList(a,b),scope,TimeZone.getTimeZone("UTC"));
  check(stats.messages.size()==11,"cross-account group duplicates excluded");check(stats.groups.size()==1,"one group");check(stats.authors.size()==2,"one author across accounts");check(stats.authors.get(0).count==10,"author count");check(stats.groups.get(0).previous==1,"equal previous window");check(stats.groups.get(0).concentration==1,"80 percent concentration");check(stats.groups.get(0).links==11,"URL count");check(stats.domains.get("example.org").size()==11,"link evidence");
  int cells=0;for(int[] day:stats.groups.get(0).heat)for(int n:day)cells+=n;check(cells==11,"heatmap accounts for every message");
  scope.excluded.add(0);stats=CenterStats.compute(messages,Arrays.asList(a,b),scope,TimeZone.getTimeZone("UTC"));check(stats.messages.size()==2,"account filter changes charts");
  a.rangeFrom=900000;scope.excluded.clear();stats=CenterStats.compute(messages,Arrays.asList(a),scope,TimeZone.getTimeZone("UTC"));check(!stats.groups.get(0).previousComplete,"do not imply absent previous data is zero");
  System.out.println("CenterStatsTest OK: deduplicated counts, account filters, authors, baseline coverage, links, heatmap and concentration");
 }
}
