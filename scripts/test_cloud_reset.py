#!/usr/bin/env python3
"""Production reset planner/scanner/coordinator tests with an isolated fake Telegram server.
Never connects to Telegram or accesses a real user's cloud.
"""
from pathlib import Path
import runpy, shutil, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
PROD = ROOT / 'patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/cloud'
COMMON = runpy.run_path(str(ROOT/'scripts/test_vault_runtime.py'))['FILES']
FILES = {k: COMMON[k] for k in ['android/content/Context.java','android/content/SharedPreferences.java','org/telegram/messenger/ApplicationLoader.java','org/telegram/messenger/AndroidUtilities.java','org/telegram/messenger/Utilities.java']}
FILES.update({
'org/telegram/messenger/UserConfig.java': r'''package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class UserConfig {public static final int MAX_ACCOUNT_COUNT=4;public static long[] users={41,42,0,0};int a;UserConfig(int a){this.a=a;}
 public static UserConfig getInstance(int a){return new UserConfig(a);}public long getClientUserId(){return users[a];}public boolean isClientActivated(){return users[a]>0;}
 public TLRPC.User getCurrentUser(){var u=new TLRPC.User();u.id=users[a];u.first_name="Account "+users[a];return u;}}
''',
'org/telegram/tgnet/TLRPC.java': r'''package org.telegram.tgnet;import java.util.*;
public class TLRPC {
 public static class User {public long id;public String first_name,last_name;}
 public static class Chat {public long id,access_hash;public String title,about;public boolean creator,broadcast,megagroup;public int folder;}
 public static class Peer {public long user_id,chat_id,channel_id;}
 public static class InputPeer{}public static class TL_inputPeerEmpty extends InputPeer{}
 public static class InputChannel {public long channel_id,access_hash;}public static class TL_inputChannel extends InputChannel{}
 public static class Dialog {public Peer peer;public int top_message;}
 public static class Message {public int id,date;public Peer peer_id;}
 public static class messages_Dialogs {public ArrayList<Dialog> dialogs=new ArrayList<>();public ArrayList<Chat> chats=new ArrayList<>();public ArrayList<User> users=new ArrayList<>();public ArrayList<Message> messages=new ArrayList<>();}
 public static class TL_messages_getDialogs {public int flags,folder_id,limit,offset_date,offset_id;public InputPeer offset_peer;public boolean exclude_pinned;}
 public static class ChatFull {public long id;public String about;}
 public static class TL_messages_chatFull {public ChatFull full_chat;public ArrayList<Chat> chats=new ArrayList<>();}
 public static class TL_channels_getFullChannel {public InputChannel channel;}
 public static class TL_channels_deleteChannel {public InputChannel channel;}
 public static class Updates{}public static class Error {public String text;}
}
''',
'org/telegram/messenger/MessagesController.java': r'''package org.telegram.messenger;import java.util.*;import org.telegram.tgnet.TLRPC;
public class MessagesController {public static Map<Integer,Map<Long,TLRPC.Chat>> caches=new HashMap<>();int a;MessagesController(int a){this.a=a;}
 public static MessagesController getInstance(int a){return new MessagesController(a);}public Map<Long,TLRPC.Chat> getChats(){return caches.computeIfAbsent(a,k->new HashMap<>());}
 public void putChats(ArrayList<TLRPC.Chat> cs,boolean cache){for(var c:cs)getChats().put(c.id,c);}public void putUsers(ArrayList<TLRPC.User> u,boolean cache){}
 public TLRPC.InputPeer getInputPeer(TLRPC.Peer p){return new TLRPC.TL_inputPeerEmpty();}public void processUpdates(TLRPC.Updates u,boolean b){}
 public void deleteDialog(long id,int mode){if(id>=0||mode!=3)throw new AssertionError("unexpected remote or Saved Messages deletion");}
}
''',
'org/telegram/tgnet/ConnectionsManager.java': r'''package org.telegram.tgnet;import java.util.*;import org.telegram.messenger.UserConfig;
public class ConnectionsManager {
 public static Map<Integer,Map<Long,TLRPC.Chat>> server=new HashMap<>();public static List<Long> deleted=new ArrayList<>();
 public static long failDelete,loseAck;public static boolean paginate,loop,failScan;public static int folderMask,reads;public static Runnable onDelete;
 int a;ConnectionsManager(int a){this.a=a;}public static ConnectionsManager getInstance(int a){return new ConnectionsManager(a);}public interface Callback{void run(Object r,TLRPC.Error e);}
 public static Map<Long,TLRPC.Chat> remote(int a){return server.computeIfAbsent(a,k->new LinkedHashMap<>());}
 static TLRPC.Error err(String s){var e=new TLRPC.Error();e.text=s;return e;}
 public void sendRequest(Object req,Callback cb){
  if(req instanceof TLRPC.TL_messages_getDialogs){var r=(TLRPC.TL_messages_getDialogs)req;reads++;folderMask|=1<<r.folder_id;if(failScan){cb.run(null,err("NETWORK_ERROR"));return;}
   if(r.folder_id==0&&(r.flags&2)==0)throw new AssertionError("folder scan must be explicit");if(r.offset_id==0&&r.exclude_pinned)throw new AssertionError("first page must include pinned channels");
   var out=new TLRPC.messages_Dialogs();
   if(a==0&&r.folder_id==0&&paginate&&(r.offset_id==0||loop)){
    for(int i=0;i<100;i++){var d=new TLRPC.Dialog();d.peer=new TLRPC.Peer();d.peer.user_id=i+500;d.top_message=i+1;out.dialogs.add(d);var m=new TLRPC.Message();m.id=i+1;m.date=200-i;m.peer_id=d.peer;out.messages.add(m);}
   }else for(var c:remote(a).values())if(c.folder==r.folder_id){out.chats.add(c);var d=new TLRPC.Dialog();d.peer=new TLRPC.Peer();d.peer.channel_id=c.id;d.top_message=1;out.dialogs.add(d);}
   cb.run(out,null);
  }else if(req instanceof TLRPC.TL_channels_getFullChannel){var input=((TLRPC.TL_channels_getFullChannel)req).channel;var c=remote(a).get(input.channel_id);
   if(c==null){cb.run(null,err("CHANNEL_INVALID"));return;}if(c.access_hash!=input.access_hash)throw new AssertionError("wrong channel hash");
   var out=new TLRPC.TL_messages_chatFull();out.chats.add(c);out.full_chat=new TLRPC.ChatFull();out.full_chat.id=c.id;out.full_chat.about=c.about;cb.run(out,null);
  }else if(req instanceof TLRPC.TL_channels_deleteChannel){var input=((TLRPC.TL_channels_deleteChannel)req).channel;
   if(input.channel_id==failDelete){cb.run(null,err("FLOOD_WAIT_12"));return;}var c=remote(a).get(input.channel_id);
   if(c==null){cb.run(null,err("CHANNEL_INVALID"));return;}if(!c.creator||!c.broadcast||c.megagroup)throw new AssertionError("unowned channel deletion");
   deleted.add(c.id);remote(a).remove(c.id);if(onDelete!=null){var run=onDelete;onDelete=null;run.run();}
   cb.run(input.channel_id==loseAck?null:new TLRPC.Updates(),input.channel_id==loseAck?err("NETWORK_ERROR"):null);
  }else throw new AssertionError("unexpected API request");
 }
}
''',
'org/telegram/messenger/telepremium/cloud/TelePremiumCloudPeerManager.java': r'''package org.telegram.messenger.telepremium.cloud;
public class TelePremiumCloudPeerManager {public static boolean busy(){return false;}}
''',
'org/telegram/messenger/telepremium/cloud/CloudPayloadRepository.java': r'''package org.telegram.messenger.telepremium.cloud;
public class CloudPayloadRepository {public static int cancels;public static void cancelAllForReset(){cancels++;}}
''',
'org/telegram/messenger/telepremium/cloud/CloudDownloadManager.java': r'''package org.telegram.messenger.telepremium.cloud;
public class CloudDownloadManager {public static int cancels;public static void cancelAllForReset(){cancels++;}}
''',
'org/telegram/messenger/telepremium/cloud/TelePremiumCloudManager.java': r'''package org.telegram.messenger.telepremium.cloud;import java.util.*;import org.telegram.messenger.UserConfig;
public class TelePremiumCloudManager {public static Set<Long> ready=new HashSet<>();public static long failUser;int account;public interface Callback{void onComplete(String e);}
 public static TelePremiumCloudManager getInstance(int a){var m=new TelePremiumCloudManager();m.account=a;return m;}
 public void bootstrap(Callback cb){if(!TelePremiumCloudResetState.allowed(TelePremiumCloudResetState.epoch()))throw new AssertionError("new cloud created before rotation");long u=UserConfig.getInstance(account).getClientUserId();if(u==failUser){cb.onComplete("NETWORK_ERROR");return;}ready.add(u);cb.onComplete(null);}
 public boolean isRemoteReady(){return ready.contains(UserConfig.getInstance(account).getClientUserId());}}
''',
'org/telegram/messenger/telepremium/cloud/vault/TelePremiumVaultKeyManager.java': r'''package org.telegram.messenger.telepremium.cloud.vault;
import android.content.Context;import java.util.*;import org.telegram.messenger.telepremium.cloud.*;
public class TelePremiumVaultKeyManager {public static Set<String> rotations=new HashSet<>();
 public static void rotateAfterCloudReset(Context c,String id)throws Exception{if(!TelePremiumCloudResetState.mayRotate(id)||!TelePremiumCloudResetPlan.decode(TelePremiumCloudResetState.plan()).deletionComplete())throw new AssertionError("early rotation");rotations.add(id);}}
''',
'CloudResetTest.java': r'''import android.content.*;import java.nio.file.*;import java.io.*;import java.util.*;
import org.telegram.messenger.*;import org.telegram.tgnet.*;import org.telegram.messenger.telepremium.cloud.*;import org.telegram.messenger.telepremium.cloud.vault.*;
public class CloudResetTest {
 static int checks;static String error;static TelePremiumCloudResetPlan plan;static Path root;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 static void reset()throws Exception{root=Files.createTempDirectory("reset-case-");var c=new Context();c.root=root.toFile();ApplicationLoader.applicationContext=c;UserConfig.users=new long[]{41,42,0,0};
  ConnectionsManager.server.clear();ConnectionsManager.deleted.clear();ConnectionsManager.failDelete=0;ConnectionsManager.loseAck=0;ConnectionsManager.paginate=false;ConnectionsManager.loop=false;ConnectionsManager.failScan=false;ConnectionsManager.folderMask=0;ConnectionsManager.reads=0;ConnectionsManager.onDelete=null;
  TelePremiumVaultKeyManager.rotations.clear();TelePremiumCloudManager.ready.clear();TelePremiumCloudManager.failUser=0;MessagesController.caches.clear();plan=null;error=null;}
 static TLRPC.Chat chat(int a,long id,String marker,boolean owner,boolean group,int folder){var c=new TLRPC.Chat();c.id=id;c.access_hash=id*3;c.title="TelePremium Vault";c.about=marker;c.creator=owner;c.broadcast=!group;c.megagroup=group;c.folder=folder;ConnectionsManager.remote(a).put(id,c);return c;}
 static void scan(){TelePremiumCloudResetCoordinator.review((p,e)->{plan=p;error=e;});}
 static void start(){error=null;TelePremiumCloudResetCoordinator.startReviewed(plan,e->error=e);}
 static Context context(){return ApplicationLoader.applicationContext;}
 static TelePremiumCloudResetPlan saved()throws Exception{return TelePremiumCloudResetPlan.decode(TelePremiumCloudResetState.plan());}
 public static void main(String[] args)throws Exception {
  reset();chat(0,1,"TP_VAULT_V3",true,false,0);chat(1,2,"TELEPREMIUM_CLOUD_STORAGE_V2",true,false,1);
  chat(0,3,"ordinary about",true,false,0);chat(0,4,"TP_VAULT_V3",false,false,0);chat(0,5,"TP_VAULT_V3",true,true,0);
  var renamed=chat(0,6,"custom about",true,false,1);renamed.title="My old storage";context().getSharedPreferences("telepremium_cloud_peer_0",0).edit().putLong("channel_id",6).commit();
  ConnectionsManager.paginate=true;scan();check(error==null&&plan.targets.size()==3,"discover only owned marked or locally bound channels, including renamed and archive");
  check(ConnectionsManager.folderMask==3&&ConnectionsManager.reads>=5,"paginate regular+archived dialogs for every connected account");check(ConnectionsManager.deleted.isEmpty()&&TelePremiumVaultKeyManager.rotations.isEmpty(),"preview must be read-only");
  Path journal=root.resolve("tpvault_journal/a0/old.tpv");Files.createDirectories(journal.getParent());Files.writeString(journal,"old");Path keep=root.resolve("ordinary-user-file");Files.writeString(keep,"keep");
  start();check(error==null&&!TelePremiumCloudResetState.pending(),"all-account reset completes");check(new HashSet<>(ConnectionsManager.deleted).equals(Set.of(1L,2L,6L)),"delete exactly the reviewed channels");
  check(ConnectionsManager.remote(0).containsKey(3L)&&ConnectionsManager.remote(0).containsKey(4L)&&ConnectionsManager.remote(0).containsKey(5L),"preserve title lookalikes, noncreator channels and groups");
  check(TelePremiumVaultKeyManager.rotations.size()==1&&TelePremiumCloudManager.ready.equals(Set.of(41L,42L)),"one new shared key, fresh V3 for both accounts");
  check(!Files.exists(journal)&&Files.exists(keep),"clear selected journals, preserve unrelated private files");check(TelePremiumCloudResetState.epoch()==2,"invalidate managers before deletion and again before creation");
  reset();chat(0,10,"TP_VAULT_V3",true,false,0);chat(1,11,"TP_VAULT_V3",true,false,0);scan();ConnectionsManager.failDelete=11;start();
  check("FLOOD_WAIT_12".equals(error)&&saved().deletedCount()==1,"partial deletion failure saves receipts and real error");
  check(TelePremiumVaultKeyManager.rotations.isEmpty()&&!TelePremiumCloudResetState.allowed(TelePremiumCloudResetState.epoch()),"partial failure keeps old master and blocks old operations");
  ConnectionsManager.failDelete=0;scan();start();check(error==null&&Collections.frequency(ConnectionsManager.deleted,10L)==1,"resume skips acknowledged deletion");
  reset();chat(0,20,"TP_VAULT_V3",true,false,0);scan();ConnectionsManager.loseAck=20;start();check(error!=null&&TelePremiumVaultKeyManager.rotations.isEmpty(),"lost deletion acknowledgement pauses before rotation");
  scan();start();check(error==null&&TelePremiumVaultKeyManager.rotations.size()==1,"complete authenticated rescan proves absence after lost ack");
  reset();var changed=chat(0,30,"TP_VAULT_V3",true,false,0);scan();changed.title="Changed after review";start();check(error!=null&&ConnectionsManager.deleted.isEmpty(),"channel metadata change requires a new explicit review");
  scan();check(plan.targets.size()==1&&"Changed after review".equals(plan.targets.get(0).title),"updated review has exact new metadata");start();check(error==null,"reviewed renamed channel can be reset");
  reset();chat(0,40,"TP_VAULT_V3",true,false,0);scan();UserConfig.users[2]=99;start();check(error!=null&&!TelePremiumCloudResetState.pending()&&ConnectionsManager.deleted.isEmpty(),"new unapproved Telegram account prevents beginning");
  reset();chat(0,50,"TP_VAULT_V3",true,false,0);scan();ConnectionsManager.onDelete=()->chat(1,51,"TP_VAULT_V3",true,false,0);start();
  check(error!=null&&TelePremiumVaultKeyManager.rotations.isEmpty()&&ConnectionsManager.remote(1).containsKey(51L),"new external channel is never silently added to destructive scope");scan();check(plan.targets.size()==2,"new channels are added only to an explicit second preview");start();check(error==null,"reviewed extra channel can be deleted on resume");
  reset();chat(0,60,"TP_VAULT_V3",true,false,0);scan();TelePremiumCloudManager.failUser=42;start();check(error!=null&&TelePremiumCloudResetState.CREATING.equals(saved().phase)&&saved().readyUsers.contains(41L),"new-cloud sync failure persists creation phase and successful accounts");
  long ep=TelePremiumCloudResetState.epoch();TelePremiumCloudManager.failUser=0;scan();start();check(error==null&&TelePremiumVaultKeyManager.rotations.size()==1&&ConnectionsManager.deleted.size()==1&&ep==TelePremiumCloudResetState.epoch(),"resume creation never deletes new clouds or rotates again");
  reset();ConnectionsManager.paginate=true;ConnectionsManager.loop=true;scan();check(error!=null&&ConnectionsManager.deleted.isEmpty(),"repeated pagination cursor fails closed");
  reset();chat(0,70,"TP_VAULT_V3",true,false,0);scan();context().failCommit=true;start();check(error!=null&&ConnectionsManager.deleted.isEmpty()&&TelePremiumVaultKeyManager.rotations.isEmpty(),"cannot delete without durable approved plan");
  reset();Path outside=Files.createTempDirectory("reset-outside-");Path sentinel=outside.resolve("sentinel");Files.writeString(sentinel,"keep");
  Files.createSymbolicLink(root.resolve("cache-link"),outside);TelePremiumCloudResetFiles.remove(root.toFile(),"cache-link");check(!Files.exists(root.resolve("cache-link"))&&Files.exists(sentinel),"unlink cache symlink without touching its target");
  Files.createSymbolicLink(root.resolve("parent-link"),outside);boolean rejected=false;try{TelePremiumCloudResetFiles.remove(root.toFile(),"parent-link/sentinel");}catch(IOException e){rejected=true;}check(rejected&&Files.exists(sentinel),"reject symbolic-link parents");
  rejected=false;try{TelePremiumCloudResetFiles.remove(root.toFile(),"../"+outside.getFileName()+"/sentinel");}catch(IOException e){rejected=true;}check(rejected&&Files.exists(sentinel),"reject path traversal before any deletion");
  System.out.println("CloudResetTest OK: "+checks+" checks");
 }
}
'''
})

def run():
    with tempfile.TemporaryDirectory(prefix='tp-cloud-reset-tests-') as directory:
        base=Path(directory);src=base/'src';out=base/'classes';src.mkdir();out.mkdir()
        for name,content in FILES.items():
            p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8')
        sources=sorted(src.rglob('*.java'))+sorted((ROOT/'scripts/tests/stubs').rglob('*.java'))
        sources += [PROD/(name+'.java') for name in ['TelePremiumCloudResetState','TelePremiumCloudResetPlan','TelePremiumCloudResetFiles','TelePremiumCloudResetScanner','TelePremiumCloudResetCoordinator']]
        compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
        subprocess.run([*compiler,'-d',str(out),*map(str,sources)],check=True)
        subprocess.run(['java','-cp',str(out),'CloudResetTest'],check=True)
if __name__=='__main__':run()
