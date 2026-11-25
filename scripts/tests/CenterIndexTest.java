import org.telegram.messenger.telepremium.aicenter.*;
import java.util.*;
public class CenterIndexTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static CenterData.Source s(int a,String canonical){CenterData.Source s=new CenterData.Source();s.account=a;s.dialog=-100;s.canonical=canonical;s.title="Forex";return s;}
 static CenterData.Message m(int a,String source,int id){CenterData.Message m=new CenterData.Message();m.account=a;m.dialog=-100;m.source=source;m.id=id;m.date=100;m.text="verified quote";return m;}
 public static void main(String[]args)throws Exception{
  CenterData.Source a=s(0,"c:100"),b=s(1,"c:100"),c=s(2,"g:100");CenterData.Scope scope=new CenterData.Scope();scope.from=0;scope.to=200;scope.whole.addAll(Arrays.asList(0,1,2));
  List<CenterData.Message> ms=Arrays.asList(m(0,"c:100",42),m(1,"c:100",42),m(2,"g:100",42));
  check(CenterData.select(ms,Arrays.asList(a,b,c),scope).size()==2);
  scope.excluded.add(2);check(CenterData.select(ms,Arrays.asList(a,b,c),scope).size()==1);
  scope.excluded.add(0);check(CenterData.select(ms,Arrays.asList(a,b,c),scope).get(0).account==1);
  scope.from=101;check(CenterData.select(ms,Arrays.asList(a,b,c),scope).isEmpty());scope.from=0;
  b.available=false;check(CenterData.select(ms,Arrays.asList(a,b,c),scope).isEmpty());b.available=true;
  check(CenterData.Scope.parse(scope.json()).excluded.equals(scope.excluded));
  check(CenterData.Message.parse(ms.get(0).json()).text.equals("verified quote"));
  CenterData.SourceFilter f=new CenterData.SourceFilter();f.text="forex";f.role="admin";check(!f.matches(a,ms,0,200));a.role="admin";check(f.matches(a,ms,0,200));f.accounts.add(2);check(!f.matches(a,ms,0,200));
  scope.excluded.clear();scope.whole.clear();scope.sources.add(a.key());scope.from=0;
  CenterData.Message topic=m(0,"c:100",43);topic.topic=7;
  check(CenterData.select(Arrays.asList(topic),Arrays.asList(a),scope).size()==1);
  a.readAt=101000;scope.sinceRead=true;check(CenterData.select(ms,Arrays.asList(a),scope).isEmpty());scope.sinceRead=false;
  ms.get(0).deleted=true;check(CenterData.select(ms,Arrays.asList(a),scope).isEmpty());ms.get(0).deleted=false;
  a.type="private";check(CenterData.select(ms,Arrays.asList(a),scope).isEmpty());scope.includePrivate=true;check(CenterData.select(ms,Arrays.asList(a),scope).size()==1);
  ArrayList<CenterData.Message> large=new ArrayList<>();for(int i=0;i<40000;i++)large.add(m(0,"c:100",i+1));
  check(CenterData.select(large,Arrays.asList(a),scope).size()==40000);
  CenterData.Scope reviewScope=new CenterData.Scope();reviewScope.whole.add(0);CenterData.Source reviewed=new CenterData.Source();reviewed.account=0;reviewed.dialog=-101;reviewed.readAt=20000;CenterData.Source outside=new CenterData.Source();outside.account=1;outside.dialog=-102;outside.readAt=1000;check(CenterData.reviewFrom(Arrays.asList(reviewed,outside),reviewScope,10,50)==20);reviewed.readAt=0;check(CenterData.reviewFrom(Arrays.asList(reviewed),reviewScope,10,50)==10);
        System.out.println("CenterIndexTest OK: account isolation, chat identity, deduplication, filters, dates and persistence");
 }
}