#!/usr/bin/env python3
"""Exercise the production usage ledger on a JDK, replacing only Android persistence/logging."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / 'patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/aicenter'
with tempfile.TemporaryDirectory(prefix='tp-center-usage-') as directory:
    root = Path(directory)
    files = {
        'org/telegram/messenger/telepremium/aicenter/CenterIndex.java': '''
package org.telegram.messenger.telepremium.aicenter;
import org.json.*;
public final class CenterIndex {
 private static JSONObject state=new JSONObject();
 public static synchronized JSONObject settings(){try{return new JSONObject(state.toString());}catch(Exception e){throw new AssertionError(e);}}
 public static synchronized void setting(String key,Object value){try{state.put(key,value);}catch(Exception e){throw new AssertionError(e);}}
}
''',
        'org/telegram/messenger/telepremium/aicenter/CenterText.java': '''
package org.telegram.messenger.telepremium.aicenter;
public final class CenterText {public static String get(String key){return key;}}
''',
        'org/telegram/messenger/FileLog.java': '''
package org.telegram.messenger;
public final class FileLog {public static void e(Throwable error){throw new AssertionError(error);}}
''',
        'UsageLedgerTest.java': '''
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public final class UsageLedgerTest {
 static String day=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());
 static String[] periods={"d:"+day,"m:"+day.substring(0,7)};
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static JSONObject ledger(){return CenterIndex.settings().optJSONObject("usageLedger");}
 static void limitBlocked(long amount)throws Exception{try{CenterUsage.reserve(amount);throw new AssertionError("Limit bypass");}catch(IllegalStateException expected){}}
 public static void main(String[] args)throws Exception{
  JSONObject crashed=new JSONObject();for(String period:periods)crashed.put(period,new JSONObject().put("reserved",7));
  CenterIndex.setting("usageLedger",crashed);CenterIndex.setting("dailyLimit",10000);CenterIndex.setting("monthlyLimit",10000);
  CenterUsage.Ticket first=CenterUsage.reserve(50);CenterUsage.observe(new JSONObject().put("usage",new JSONObject().put("total_tokens",11)));CenterUsage.finish(first);
  for(String period:periods){JSONObject row=ledger().getJSONObject(period);check(row.optLong("reserved")==0,"crash reservation not recovered");check(row.optLong("actual")==11,"actual usage");check(row.optLong("estimated")==7,"crash accounting");}
  CenterUsage.Ticket estimated=CenterUsage.reserve(20);CenterUsage.finish(estimated);
  CenterUsage.Ticket anthropic=CenterUsage.reserve(100);CenterUsage.observe(new JSONObject().put("usage",new JSONObject().put("input_tokens",3).put("output_tokens",4)));CenterUsage.finish(anthropic);
  for(String period:periods){JSONObject row=ledger().getJSONObject(period);check(row.optLong("actual")==18&&row.optLong("estimated")==27,"actual/estimated separation");check(row.optLong("reserved")==0,"reservation released");}
  CenterIndex.setting("dailyLimit",50);limitBlocked(6);CenterUsage.Ticket edge=CenterUsage.reserve(5);CenterUsage.finish(edge);limitBlocked(1);
  CenterIndex.setting("dailyLimit",10000);CenterIndex.setting("monthlyLimit",50);limitBlocked(1);
  CenterIndex.setting("usageLedger",new JSONObject());CenterIndex.setting("dailyLimit",100);CenterIndex.setting("monthlyLimit",100);
  CountDownLatch ready=new CountDownLatch(8),go=new CountDownLatch(1);List<Thread> workers=new ArrayList<>();AtomicInteger accepted=new AtomicInteger();ConcurrentLinkedQueue<Throwable> errors=new ConcurrentLinkedQueue<>();
  for(int i=0;i<8;i++){Thread worker=new Thread(()->{ready.countDown();try{go.await();CenterUsage.Ticket ticket=CenterUsage.reserve(30);accepted.incrementAndGet();CenterUsage.finish(ticket);}catch(IllegalStateException limited){}catch(Throwable failure){errors.add(failure);}});workers.add(worker);worker.start();}
  ready.await();go.countDown();for(Thread worker:workers)worker.join();check(errors.isEmpty(),"concurrent ledger failed");check(accepted.get()==3,"concurrent limit overspent");
  for(String period:periods){JSONObject row=ledger().getJSONObject(period);check(row.optLong("estimated")==90&&row.optLong("reserved")==0,"concurrent reservations lost");}
  System.out.println("CenterUsageLedgerTest OK: actual/estimated usage, crash recovery, daily/monthly limits and concurrent reservations");
 }
}
''',
        'Compile.java': 'import javax.tools.ToolProvider; class Compile {public static void main(String[]args){System.exit(ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,args));}}',
    }
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
    sources = [CORE / 'CenterUsage.java', *sorted((ROOT / 'scripts/tests/stubs').rglob('*.java'))]
    sources.extend(root / name for name in files if name != 'Compile.java')
    subprocess.run(['java', str(root / 'Compile.java'), '-d', str(root / 'classes'), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', str(root / 'classes'), 'UsageLedgerTest'], check=True)
