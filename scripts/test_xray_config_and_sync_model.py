#!/usr/bin/env python3
"""Real profile/config and cloud metadata merge tests; no Android runtime or external network."""
from pathlib import Path
import shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
PROD=ROOT/'patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium'
TEST=r'''import org.json.*;import java.util.*;import org.telegram.messenger.telepremium.xray.*;import org.telegram.messenger.telepremium.cloud.*;
public class XrayAndSyncModelTest{
 static int checks;static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}interface Work{void run()throws Exception;}static void rejects(Work w,String m)throws Exception{try{w.run();throw new AssertionError(m);}catch(IllegalArgumentException expected){checks++;}}
 static XrayProfile p(String id,int server)throws Exception{var p=new XrayProfile();p.id=id;p.name="Profile "+id;p.bypassLan=false;p.outbound=new JSONObject().put("protocol","socks").put("settings",new JSONObject().put("servers",new JSONArray().put(new JSONObject().put("address","127.0.0.1").put("port",server))));return p;}
 static JSONObject record(String id,String birth,long clock,boolean deleted)throws Exception{return new JSONObject().put("id",id).put("birth",birth).put("clock",clock).put("actor","device-a").put("kind","youtube").put("key",id).put("deleted",deleted).put("value",new JSONObject().put("name","Channel "+clock).put("url",id));}
 static JSONArray merge(JSONArray a,JSONArray b){return CloudSnapshot.mergeMediaLibrary(a,b);}
 static void model()throws Exception{
  var a=p("a",12001);var b=p("b",12002);Map<String,int[]> ports=new HashMap<>();ports.put("a",new int[]{13001,13002});ports.put("b",new int[]{13003,13004});JSONObject config=XrayConfig.build(Arrays.asList(a,b),ports);
  JSONArray inbound=config.getJSONArray("inbounds"),out=config.getJSONArray("outbounds"),rules=config.getJSONObject("routing").getJSONArray("rules");
  check(inbound.length()==4,"each profile has independent MTProto SOCKS and HTTP listeners");
  for(int i=0;i<4;i++)check("127.0.0.1".equals(inbound.getJSONObject(i).getString("listen")),"listeners never expose credentials/routes on LAN");
  check(out.getJSONObject(0).getString("protocol").equals("blackhole"),"unmatched traffic is blocked instead of going direct");
  check(rules.length()==2&&rules.getJSONObject(0).getString("outboundTag").equals("a")&&rules.getJSONObject(1).getString("outboundTag").equals("b"),"profile tags never fall through to another outbound");
  check(inbound.getJSONObject(0).getString("protocol").equals("socks")&&inbound.getJSONObject(1).getString("protocol").equals("http"),"native MTProto and HTTP use supported independent protocols");
  a.outbound.put("tag","changed");check(out.getJSONObject(2).getString("tag").equals("a"),"config owns a copy of outbound metadata");
  a.bypassLan=true;check(XrayConfig.build(Arrays.asList(a,b),ports).getJSONObject("routing").getJSONArray("rules").length()==3,"LAN bypass is opt-in per profile without geodata bundles");
  var duplicate=new HashMap<>(ports);duplicate.put("b",new int[]{13001,13005});rejects(()->XrayConfig.build(Arrays.asList(a,b),duplicate),"duplicate listener ports are rejected");
  rejects(()->XrayConfig.build(Arrays.asList(a,a),ports),"duplicate profiles cannot share an inbound tag");
  rejects(()->XrayConfig.build(Arrays.asList(a,b),Collections.emptyMap()),"missing ports do not start a default route");
  List<XrayProfile> ten=new ArrayList<>();Map<String,int[]> tenPorts=new HashMap<>();for(int i=0;i<10;i++){ten.add(p("p"+i,12001));tenPorts.put("p"+i,new int[]{14000+i*2,14001+i*2});}check(XrayConfig.build(ten,tenPorts).getJSONArray("inbounds").length()==20,"ten simultaneous profiles produce twenty loopback listeners");ten.add(p("eleven",12001));rejects(()->XrayConfig.build(ten,tenPorts),"profile eleven is rejected before runtime mutation");
  rejects(()->XrayConfig.validateOutbound(new JSONObject().put("protocol","wireguard")),"unsupported constructor resources are rejected");
  rejects(()->XrayConfig.validateOutbound(new JSONObject().put("protocol","socks").put("proxySettings",new JSONObject())),"deprecated chained route config cannot silently change destination");
  rejects(()->XrayConfig.validateOutbound(new JSONObject().put("protocol","socks").put("streamSettings",new JSONObject().put("sockopt",new JSONObject().put("dialerProxy","b")))),"cross-profile dependency cannot silently route via another account");
  check(XrayProfile.fromJson(b.toJson()).id.equals(b.id),"profile stable identity survives storage");
  check(XrayConfig.importJson(a.outbound.toString()).length()==1,"single outbound JSON imports without native link conversion");
  check(XrayConfig.importJson(new JSONArray().put(a.outbound).put(b.outbound).toString()).length()==2,"outbound arrays import multiple profiles");
  check(XrayConfig.importJson(config.toString()).length()==2,"full config imports proxy outbounds while ignoring direct and block helpers");
  rejects(()->XrayConfig.importJson(new JSONObject().put("outbounds",new JSONArray().put(new JSONObject().put("protocol","freedom"))).toString()),"direct-only root does not create a fake proxy profile");
  rejects(()->XrayConfig.importJson(new JSONArray().put(new JSONObject().put("protocol","socks").put("sendThrough","127.0.0.1")).toString()),"JSON import validates route isolation before saving");
  JSONObject added=record("channel","birth-a",1,false),removed=record("channel","birth-a",2,true),stale=record("channel","birth-a",99999,false);
  JSONArray deletion=merge(new JSONArray().put(added),new JSONArray().put(removed));check(deletion.getJSONObject(0).optBoolean("deleted"),"deletion is retained in the catalog");check(merge(deletion,new JSONArray().put(stale)).getJSONObject(0).optBoolean("deleted"),"stale offline title updates cannot resurrect deleted channels even with newer clocks");
  JSONArray readd=merge(deletion,new JSONArray().put(record("channel","birth-b",3,false)));check(readd.length()==2&&!readd.getJSONObject(1).optBoolean("deleted"),"explicit re-add creates a new incarnation while retaining the old tombstone");
  check(merge(deletion,readd).toString().equals(merge(readd,deletion).toString()),"merge is commutative");check(merge(readd,readd).toString().equals(readd.toString()),"merge is idempotent");
  Random random=new Random(42);for(int n=0;n<30;n++){JSONArray[] sets={new JSONArray(),new JSONArray(),new JSONArray()};for(int j=0;j<30;j++)sets[random.nextInt(3)].put(record("channel"+random.nextInt(4),"birth"+random.nextInt(3),random.nextInt(50),random.nextBoolean()).put("legacy",random.nextBoolean()));check(merge(merge(sets[0],sets[1]),sets[2]).toString().equals(merge(sets[0],merge(sets[1],sets[2])).toString()),"offline merge is associative seed "+n);}
  CloudSnapshot local=new CloudSnapshot(),remote=new CloudSnapshot();remote.cloudId=local.cloudId;local.mediaLibrary=deletion;remote.mediaLibrary=new JSONArray().put(stale);local.nodes.add(CloudNode.folder(null,"a"));remote.nodes.add(CloudNode.folder(null,"b"));CloudSnapshot combined=new CloudConflictResolver().merge(local,remote);check(combined.nodes.size()==2&&combined.mediaLibrary.getJSONObject(0).optBoolean("deleted"),"file operations preserve media tombstones when merging catalog generations");
  check(CloudSnapshot.decode(combined.encode()).mediaLibrary.toString().equals(combined.mediaLibrary.toString()),"encrypted catalog plaintext codec roundtrip retains complete metadata");
  CloudSnapshot old=new CloudSnapshot();check(CloudSnapshot.decode(old.encode().replace(",\"mediaLibrary\":[]","")).mediaLibrary.length()==0,"catalogs from before media sync remain readable");
  rejects(()->merge(new JSONArray().put(record("bad","birth",-1,false)),new JSONArray()),"malformed media clocks fail validation without dropping tombstones");
  System.out.println("XrayAndSyncModelTest OK: "+checks+" checks");
 }
 public static void main(String[] args)throws Exception{
  if(args.length>0){JSONObject fixture=new JSONObject(args[0]);JSONArray nodes=fixture.getJSONArray("profiles");List<XrayProfile> profiles=new ArrayList<>();Map<String,int[]> ports=new HashMap<>();for(int i=0;i<nodes.length();i++){JSONObject row=nodes.getJSONObject(i);XrayProfile p=p(row.getString("id"),row.getInt("server"));profiles.add(p);ports.put(p.id,new int[]{row.getInt("socks"),row.getInt("http")});}System.out.println(XrayConfig.build(profiles,ports));return;}
  model();
 }
}'''
def compile_test(base):
 src=base/'XrayAndSyncModelTest.java';src.write_text(TEST);out=base/'classes';out.mkdir(exist_ok=True)
 names=['xray/XrayProfile','xray/XrayConfig','cloud/CloudNode','cloud/CloudSnapshot','cloud/CloudConflictResolver']
 compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
 subprocess.run([*compiler,'-d',str(out),*map(str,sorted((ROOT/'scripts/tests/stubs').rglob('*.java'))),*[str(PROD/(n+'.java')) for n in names],str(src)],check=True)
 return out
if __name__=='__main__':
 with tempfile.TemporaryDirectory(prefix='tp-xray-model-') as folder:
  out=compile_test(Path(folder));subprocess.run(['java','-cp',str(out),'XrayAndSyncModelTest'],check=True)
