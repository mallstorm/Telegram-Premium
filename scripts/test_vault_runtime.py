#!/usr/bin/env python3
"""Host tests of production V3 index/manager using deterministic Telegram/Android doubles.

Real AES-GCM, snapshot JSON, conflict resolution and catalog validation are exercised.
These tests do not substitute for AndroidKeyStore device or Telegram end-to-end tests.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROD = ROOT / "patches/tg13/overlay/TMessagesProj/src/main/java"
FILES = {
    'android/content/SharedPreferences.java': r"""package android.content;
public interface SharedPreferences {
 boolean contains(String k);long getLong(String k,long d);int getInt(String k,int d);boolean getBoolean(String k,boolean d);String getString(String k,String d);Editor edit();
 interface Editor {Editor putLong(String k,long v);Editor putInt(String k,int v);Editor putBoolean(String k,boolean v);Editor putString(String k,String v);Editor remove(String k);Editor clear();boolean commit();void apply();}
}
""",
    'android/content/Context.java': r"""package android.content;
import java.io.*;import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;public File root;public boolean failCommit,memoryOnFailedCommit;public String failPreferences;
 public Map<String,Object> prefs=new HashMap<>();public Context getApplicationContext(){return this;}public File getFilesDir(){return root;}public File getCacheDir(){return new File(root,"cache");}public File getExternalCacheDir(){return new File(root,"external-cache");}
 public SharedPreferences getSharedPreferences(String n,int mode){return new SharedPreferences(){
  String key(String k){return n+":"+k;}
  public boolean contains(String k){return prefs.containsKey(key(k));}
  public long getLong(String k,long d){Object o=prefs.get(key(k));return o instanceof Number?((Number)o).longValue():d;}
  public int getInt(String k,int d){return (int)getLong(k,d);}
  public boolean getBoolean(String k,boolean d){Object o=prefs.get(key(k));return o instanceof Boolean?(Boolean)o:d;}
  public String getString(String k,String d){Object o=prefs.get(key(k));return o instanceof String?(String)o:d;}
  public Editor edit(){return new Editor(){Map<String,Object> changes=new HashMap<>();boolean clearAll;
   public Editor clear(){clearAll=true;return this;}
   public Editor putLong(String k,long v){changes.put(key(k),v);return this;}public Editor putInt(String k,int v){return putLong(k,v);}
   public Editor putBoolean(String k,boolean v){changes.put(key(k),v);return this;}public Editor putString(String k,String v){changes.put(key(k),v);return this;}
   public Editor remove(String k){changes.put(key(k),null);return this;}public boolean commit(){boolean failed=failCommit||n.equals(failPreferences);if(failed&&!memoryOnFailedCommit)return false;if(clearAll)prefs.keySet().removeIf(k->k.startsWith(n+":"));for(var e:changes.entrySet()){if(e.getValue()==null)prefs.remove(e.getKey());else prefs.put(e.getKey(),e.getValue());}return !failed;}public void apply(){commit();}
  };}
 };}
}
""",
    'org/telegram/messenger/ApplicationLoader.java': r"""package org.telegram.messenger;
public class ApplicationLoader {public static android.content.Context applicationContext=new android.content.Context();}
""",
    'org/telegram/messenger/AndroidUtilities.java': r"""package org.telegram.messenger;
import java.util.*;
public class AndroidUtilities {public static List<Runnable> delayed=new ArrayList<>();
 public static void runOnUIThread(Runnable r){r.run();}public static void runOnUIThread(Runnable r,long ms){delayed.add(r);}
 public static void cancelRunOnUIThread(Runnable r){delayed.remove(r);}
}
""",
    'org/telegram/messenger/Utilities.java': r"""package org.telegram.messenger;
public class Utilities {public static Queue globalQueue=new Queue(); public static class Queue {public void postRunnable(Runnable r){r.run();}}}
""",
    'org/telegram/messenger/FileLog.java': r"""package org.telegram.messenger;public class FileLog {public static void e(Throwable e){}public static void e(String s){}public static void d(String s){}}
""",
    'org/telegram/messenger/AccountInstance.java': r"""package org.telegram.messenger;
public class AccountInstance {public static AccountInstance getInstance(int a){return new AccountInstance();}}
""",
    'org/telegram/messenger/UserConfig.java': r"""package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class UserConfig {public static final int MAX_ACCOUNT_COUNT=10;public static UserConfig getInstance(int a){return new UserConfig();}public long getClientUserId(){return 42;}public TLRPC.User getCurrentUser(){TLRPC.User u=new TLRPC.User();u.id=42;return u;}}
""",
    'org/telegram/tgnet/TLRPC.java': r"""package org.telegram.tgnet;
import java.io.*;import java.util.*;
public class TLRPC {
 public static class Document {public File file;public byte[] server;}
 public static class Media {public Document document;}
 public static class Peer {public long channel_id=7,chat_id,user_id;}
 public static class Message {public int id,date;public String message;public Media media;public Peer peer_id=new Peer();}
 public static class User {public long id;}
 public static class TL_messages_getHistory {public Object peer;public int limit,offset_id;}
 public static class TL_channels_deleteMessages {public Object channel;public List<Integer> id=new ArrayList<>();}
 public static class messages_Messages {public List<Message> messages=new ArrayList<>();}
 public static class Error {public String text;}
}
""",
    'org/telegram/tgnet/ConnectionsManager.java': r"""package org.telegram.tgnet;
public class ConnectionsManager {public static int reads;public static TLRPC.messages_Messages history=new TLRPC.messages_Messages(),older=new TLRPC.messages_Messages();
 public static ConnectionsManager getInstance(int a){return new ConnectionsManager();}
 public interface Callback {void run(Object response,TLRPC.Error error);}
 public void sendRequest(Object r,Callback cb){reads++;if(r instanceof TLRPC.TL_messages_getHistory)cb.run(((TLRPC.TL_messages_getHistory)r).offset_id==0?history:older,null);else cb.run(null,null);}
}
""",
    'org/telegram/messenger/FileLoader.java': r"""package org.telegram.messenger;
import java.io.*;import java.nio.file.*;import org.telegram.tgnet.TLRPC;
public class FileLoader {public static final int PRIORITY_NORMAL=1;public static int downloads;
 public static FileLoader getInstance(int a){return new FileLoader();}public File getPathToAttach(TLRPC.Document d,boolean c){return d.file;}
 public static String getAttachFileName(TLRPC.Document d){return d.file.getName();}
 public void loadFile(TLRPC.Document d,TLRPC.Message m,int p,int c){try{downloads++;Files.write(d.file.toPath(),d.server);NotificationCenter.getInstance(0).emit(NotificationCenter.fileLoaded,getAttachFileName(d),d.file);}catch(IOException e){throw new RuntimeException(e);}}
}
""",
    'org/telegram/messenger/NotificationCenter.java': r"""package org.telegram.messenger;
import java.util.*;
public class NotificationCenter {public static final int fileLoaded=1,fileLoadFailed=2,messageReceivedByServer=3;
 public interface NotificationCenterDelegate {void didReceivedNotification(int id,int account,Object... args);}
 public static final NotificationCenter INSTANCE=new NotificationCenter();public static NotificationCenter getInstance(int a){return INSTANCE;}
 public Map<NotificationCenterDelegate,Set<Integer>> listeners=new HashMap<>();
 public void addObserver(NotificationCenterDelegate l,int id){listeners.computeIfAbsent(l,k->new HashSet<>()).add(id);}
 public void removeObserver(NotificationCenterDelegate l,int id){Set<Integer>s=listeners.get(l);if(s!=null){s.remove(id);if(s.isEmpty())listeners.remove(l);}}
 public void emit(int id,Object... args){for(var l:new ArrayList<>(listeners.keySet())){Set<Integer>s=listeners.get(l);if(s!=null&&s.contains(id))l.didReceivedNotification(id,0,args);}}
}
""",
    'org/telegram/messenger/SendMessagesHelper.java': r"""package org.telegram.messenger;
import java.io.*;import java.nio.file.*;import org.telegram.tgnet.*;import org.telegram.messenger.telepremium.cloud.vault.*;
public class SendMessagesHelper {public static boolean autoAck;public static int sends;public static TLRPC.Message pending;
 public static void prepareSendingDocument(AccountInstance a,String p,String original,Object uri,String cap,Object...rest){try{
  byte[] enc=Files.readAllBytes(Path.of(p));if(!TelePremiumVaultCrypto.isVaultFile(enc))throw new AssertionError("plaintext upload");
  sends++;TLRPC.Message m=new TLRPC.Message();m.id=1000+sends;m.message=cap;m.media=new TLRPC.Media();m.media.document=new TLRPC.Document();m.media.document.server=enc;m.media.document.file=new File(ApplicationLoader.applicationContext.root,"sent-"+m.id+".tpv");pending=m;if(autoAck)ack();
 }catch(IOException e){throw new RuntimeException(e);}}
 public static void ack(){TLRPC.Message m=pending;ConnectionsManager.history.messages.add(0,m);NotificationCenter.getInstance(0).emit(NotificationCenter.messageReceivedByServer,0,1,m);}
}
""",
    'org/telegram/messenger/telepremium/cloud/TelePremiumCloudPeerManager.java': r"""package org.telegram.messenger.telepremium.cloud;
public class TelePremiumCloudPeerManager {public interface Callback{void onReady(long id,String error);}
 public static TelePremiumCloudPeerManager getVaultInstance(int a){return new TelePremiumCloudPeerManager();}public static TelePremiumCloudPeerManager getInstance(int a){return getVaultInstance(a);}
 public long getChannelId(){return 7;}public Object getInputPeer(){return new Object();}public Object getInputChannel(){return new Object();}public void ensure(Callback cb){cb.onReady(-7,null);}}
""",
    'org/telegram/messenger/telepremium/cloud/vault/TelePremiumVaultKeyManager.java': r"""package org.telegram.messenger.telepremium.cloud.vault;
public class TelePremiumVaultKeyManager {public static byte[] key;public static boolean fail;public static boolean isEnabled(android.content.Context c){return true;}public static byte[] getMasterKeyForUnlockedSession(android.content.Context c){if(fail)throw new IllegalStateException();return key.clone();}public static void markKeyUsed(android.content.Context c){}}
""",
    'org/telegram/messenger/telepremium/cloud/vault/VaultFileOps.java': r"""package org.telegram.messenger.telepremium.cloud.vault;
public class VaultFileOps {public static String randomFileName(String p){return p+java.util.UUID.randomUUID().toString().replace("-","")+".tpv";}}
""",
    'org/telegram/messenger/telepremium/cloud/vault/VaultTransportStaging.java': r"""package org.telegram.messenger.telepremium.cloud.vault;
import java.io.*;import java.nio.file.*;
public class VaultTransportStaging {public static File stageEncryptedIndex(android.content.Context c,File f)throws Exception{
 File out=new File(c.root,"upload-"+f.getName());Files.copy(f.toPath(),out.toPath(),StandardCopyOption.REPLACE_EXISTING);return out;
}}
""",
    'IndexRuntimeTest.java': r"""import java.io.*;import java.nio.file.*;import java.util.*;
import org.telegram.messenger.*;import org.telegram.tgnet.*;import org.telegram.messenger.telepremium.cloud.*;import org.telegram.messenger.telepremium.cloud.vault.*;
public class IndexRuntimeTest {
 static List<CloudSnapshot> loaded;static String error;static int checks,number;
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);checks++;}
 static TelePremiumVaultIndexStore reset()throws Exception {
  ApplicationLoader.applicationContext=new android.content.Context();ApplicationLoader.applicationContext.root=Files.createTempDirectory(Path.of(System.getProperty("test.root")),"index-").toFile();
  AndroidUtilities.delayed.clear();NotificationCenter.INSTANCE.listeners.clear();ConnectionsManager.history=new TLRPC.messages_Messages();ConnectionsManager.older=new TLRPC.messages_Messages();ConnectionsManager.reads=0;
  TelePremiumVaultKeyManager.key=TelePremiumVaultCrypto.newMasterKey();TelePremiumVaultKeyManager.fail=false;SendMessagesHelper.autoAck=false;SendMessagesHelper.sends=0;FileLoader.downloads=0;loaded=null;error=null;return new TelePremiumVaultIndexStore(0);
 }
 static CloudSnapshot snapshot(long gen){CloudSnapshot s=new CloudSnapshot();s.generation=gen;s.ownerUserId=42;s.cloudId="test-cloud";CloudNode n=CloudNode.folder(null,"Облако");n.id="root";s.nodes.add(n);return s;}
 static TLRPC.Message index(CloudSnapshot s,byte[] key,int id)throws Exception {TLRPC.Message m=new TLRPC.Message();m.id=id;m.date=id;m.message="#TPV3I:test";m.media=new TLRPC.Media();m.media.document=new TLRPC.Document();var d=m.media.document;d.file=new File(ApplicationLoader.applicationContext.root,"remote-"+(++number)+".tpv");d.server=TelePremiumVaultCrypto.encryptBytes(s.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8),key,TelePremiumVaultCrypto.Purpose.INDEX);Files.write(d.file.toPath(),d.server);return m;}
 static void load(TelePremiumVaultIndexStore s){s.loadGenerations((l,e)->{loaded=l;error=e;});}
 static long latest(){return loaded.stream().mapToLong(s->s.generation).max().orElse(0);}
 public static void main(String[] args)throws Exception {
  var store=reset();var current=index(snapshot(5),TelePremiumVaultKeyManager.key,2);var old=index(snapshot(4),TelePremiumVaultCrypto.newMasterKey(),1);ConnectionsManager.history.messages.addAll(List.of(current,old));load(store);
  check(latest()==5&&error==null,"older index encrypted with another key must not block latest valid catalog");
  store=reset();current=index(snapshot(5),TelePremiumVaultKeyManager.key,2);old=index(snapshot(4),TelePremiumVaultKeyManager.key,1);current.media.document.server[45]^=1;Files.write(current.media.document.file.toPath(),current.media.document.server);ConnectionsManager.history.messages.addAll(List.of(current,old));load(store);
  check(latest()==4&&error!=null,"corrupt newest index retains intact older catalog read-only");check(SendMessagesHelper.sends==0,"corrupt newest index must never be replaced automatically");
  store=reset();current=index(snapshot(7),TelePremiumVaultKeyManager.key,1);byte[] bad=current.media.document.server.clone();bad[45]^=1;Files.write(current.media.document.file.toPath(),bad);ConnectionsManager.history.messages.add(current);load(store);
  check(latest()==7&&error==null&&FileLoader.downloads==1,"damaged download cache is repaired from Telegram once");
  store=reset();ConnectionsManager.history.messages.add(index(snapshot(2),TelePremiumVaultKeyManager.key,1));var failed=new ArrayList<String>();store.save(snapshot(3),failed::add);AndroidUtilities.delayed.get(0).run();SendMessagesHelper.autoAck=true;load(store);
  check(failed.size()==1&&failed.get(0)!=null,"failed upload reports failure once");check(latest()==4&&error==null,"pending local catalog automatically reconciles after failed upload");
  store=reset();var first=new ArrayList<String>();var second=new ArrayList<String>();store.save(snapshot(2),first::add);Runnable oldTimer=AndroidUtilities.delayed.get(0);SendMessagesHelper.ack();store.save(snapshot(3),second::add);oldTimer.run();SendMessagesHelper.ack();
  check(first.size()==1&&first.get(0)==null&&second.size()==1&&second.get(0)==null,"completed timer cannot affect subsequent upload or call callback twice");
  store=reset();ConnectionsManager.history.messages.add(index(snapshot(7),TelePremiumVaultKeyManager.key,1));load(store);check(error==null&&latest()==7,"valid server catalog loads");ConnectionsManager.history.messages.clear();load(store);
  check(error!=null&&latest()==7,"missing remote index uses cached verified journal and does not reset cloud");check(SendMessagesHelper.sends==0,"missing confirmed remote catalog is never auto-overwritten");
  store=reset();for(int i=0;i<100;i++){var m=new TLRPC.Message();m.id=300-i;ConnectionsManager.history.messages.add(m);}ConnectionsManager.older.messages.add(index(snapshot(9),TelePremiumVaultKeyManager.key,100));load(store);
  check(latest()==9&&error==null&&ConnectionsManager.reads==2,"index beyond the first 100 messages is found through pagination");
  store=reset();var foreign=snapshot(8);foreign.ownerUserId=43;ConnectionsManager.history.messages.add(index(foreign,TelePremiumVaultKeyManager.key,3));ConnectionsManager.history.messages.add(index(snapshot(7),TelePremiumVaultKeyManager.key,2));load(store);
  check(error!=null&&latest()==7&&SendMessagesHelper.sends==0,"foreign account catalog is rejected even with a shared encryption key");
  store=reset();ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_peer_0",0).edit().putLong("channel_id",7).commit();ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_index_checkpoint_0",0).edit().putLong("generation",10).commit();ConnectionsManager.history.messages.add(index(snapshot(7),TelePremiumVaultKeyManager.key,1));load(store);
  check(error!=null&&latest()==7&&SendMessagesHelper.sends==0,"upgrade preserves confirmed rollback checkpoint for the same rediscovered V3 channel");
  store=reset();var payload=new TLRPC.Message();payload.id=1;payload.message="#TPV3F:test";payload.media=new TLRPC.Media();payload.media.document=new TLRPC.Document();ConnectionsManager.history.messages.add(payload);load(store);
  check(error!=null&&latest()==0&&SendMessagesHelper.sends==0,"payload without catalog cannot initialize a replacement empty vault on a fresh device");
  store=reset();var journal=new File(ApplicationLoader.applicationContext.root,"tpvault_journal/a0");journal.mkdirs();Files.write(new File(journal,"g9_bad.tpv").toPath(),new byte[70]);load(store);
  check(error!=null&&latest()==0&&SendMessagesHelper.sends==0,"unreadable local catalog cannot be mistaken for a brand-new cloud");
  store=reset();var plaintext=snapshot(1);plaintext.nodes.add(CloudNode.file("root","private.jpg","image/jpeg",1,"hash"));ConnectionsManager.history.messages.add(index(plaintext,TelePremiumVaultKeyManager.key,1));load(store);
  check(error!=null&&latest()==0&&SendMessagesHelper.sends==0,"V3 refuses plaintext file references even inside an authenticated catalog");
  store=reset();TelePremiumVaultKeyManager.fail=true;load(store);check(error!=null&&latest()==0,"provider exceptions without a message must never report a successful catalog load");var failedSave=new ArrayList<String>();store.save(snapshot(1),failedSave::add);check(failedSave.size()==1&&failedSave.get(0)!=null&&SendMessagesHelper.sends==0,"provider exceptions without a message must never report a successful encrypted save");
  store=reset();SendMessagesHelper.autoAck=true;var manager=TelePremiumCloudManager.getInstance(0);var result=new ArrayList<String>();manager.bootstrap(result::add);
  check(result.size()==1&&result.get(0)==null&&manager.isRemoteReady(),"fresh cloud bootstrap completes without V2 migration or setup");check(manager.getSnapshot()!=null&&manager.getSnapshot().nodes.size()==1&&SendMessagesHelper.sends==1,"first root-only catalog is sent as one authenticated ciphertext document");
  ConnectionsManager.history.messages.add(index(manager.getSnapshot(),TelePremiumVaultKeyManager.key,10));
  CloudNode newImage=CloudNode.file("root","file_0123456789abcdef0123456789abcdef.png","image/png",100,"a".repeat(64));newImage.encryptionVersion=1;newImage.telegramMessageId=100;newImage.telegramDocumentId=1000;var added=new ArrayList<String>();manager.addFile(newImage,added::add);
  check(added.size()==1&&added.get(0)==null&&newImage.name.startsWith("Image_1."),"successful image upload is committed under a short numbered display name");
  check(newImage.originalName.equals("file_0123456789abcdef0123456789abcdef.png")&&newImage.telegramDocumentId==1000&&newImage.sha256.equals("a".repeat(64)),"shortening upload metadata preserves original name and encrypted payload references");
  store=reset();SendMessagesHelper.autoAck=true;CloudSnapshot legacyNames=snapshot(1);CloudNode generic=CloudNode.file("root","file_fedcba98765432100123456789abcdef.png","image/png",100,"b".repeat(64));generic.encryptionVersion=1;generic.telegramMessageId=200;generic.telegramDocumentId=2000;legacyNames.nodes.add(generic);ConnectionsManager.history.messages.add(index(legacyNames,TelePremiumVaultKeyManager.key,20));var migrated=new ArrayList<String>();manager.bootstrap(migrated::add);CloudNode renamed=manager.getSnapshot().nodes.stream().filter(n->n.type==CloudNode.TYPE_FILE).findFirst().orElseThrow();
  check(migrated.size()==1&&migrated.get(0)==null&&renamed.name.startsWith("Image_1.")&&SendMessagesHelper.sends==1,"bootstrap shortens a generic old image name with one encrypted catalog commit");
  check(renamed.originalName.equals(generic.name)&&renamed.telegramDocumentId==2000&&renamed.sha256.equals("b".repeat(64)),"existing file normalization changes metadata without touching payload identity");
  ConnectionsManager.history.messages.add(index(manager.getSnapshot(),TelePremiumVaultKeyManager.key,21));int renamedSends=SendMessagesHelper.sends;manager.bootstrap(e->error=e);check(error==null&&SendMessagesHelper.sends==renamedSends,"a later bootstrap does not repeatedly rename or publish the same image");
  store=reset();var staleStore=store;var late=new ArrayList<String>();store.save(snapshot(1),late::add);
  long oldEpoch=TelePremiumCloudResetState.epoch();String resetId=java.util.UUID.randomUUID().toString();
  TelePremiumCloudResetState.begin(resetId,"test");TelePremiumCloudResetState.save(resetId,"clearing","test",false);TelePremiumCloudResetState.save(resetId,"creating","test",true);TelePremiumCloudResetState.complete(resetId);
  SendMessagesHelper.ack();
  check(late.size()==1&&late.get(0)!=null,"late index upload acknowledgement must remain rejected after reset finishes");
  check(ApplicationLoader.applicationContext.getSharedPreferences("telepremium_vault_index_checkpoint_u42",0).getLong("generation",0)==0,"old acknowledgement cannot recreate a cleared checkpoint");
  int sendsBefore=SendMessagesHelper.sends;staleStore.save(snapshot(2),e->error=e);
  check(error!=null&&sendsBefore==SendMessagesHelper.sends&&!TelePremiumCloudResetState.allowed(oldEpoch),"old index instance cannot send after new epoch is enabled");
  System.out.println("IndexRuntimeTest OK: "+checks+" checks");
 }
}
""",
}

def run():
    with tempfile.TemporaryDirectory(prefix="tp-vault-tests-") as folder:
        base = Path(folder)
        src, out = base / "src", base / "classes"
        src.mkdir(); out.mkdir()
        for name, content in FILES.items():
            p = src / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
        names = ["CloudImageNames", "TelePremiumCloudResetState", "CloudNode", "CloudSnapshot", "CloudConflictResolver", "CloudIntegrityChecker", "CloudRecoveryEngine", "TelePremiumCloudManager", "TelegramCloudMetadataStore"]
        sources = [PROD / ("org/telegram/messenger/telepremium/cloud/" + n + ".java") for n in names]
        sources += [PROD / ("org/telegram/messenger/telepremium/cloud/vault/" + n + ".java") for n in ["TelePremiumVaultCrypto", "TelePremiumVaultIndexStore"]]
        sources += sorted((ROOT / "scripts/tests/stubs").rglob("*.java"))
        sources += sorted(src.rglob("*.java"))
        compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        subprocess.run([*compiler, "-d", str(out), *map(str, sources)], check=True)
        subprocess.run(["java", "-Dtest.root=" + str(base), "-cp", str(out), "IndexRuntimeTest"], check=True)

if __name__ == "__main__":
    run()
