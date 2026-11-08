#!/usr/bin/env python3
"""Exercise production V3 channel discovery against deterministic Telegram responses."""
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
FILES = {
    'org/telegram/messenger/ChatObject.java': r"""package org.telegram.messenger;import org.telegram.tgnet.TLRPC;
public class ChatObject {public static final int CHAT_TYPE_CHANNEL=1;public static boolean isChannel(TLRPC.Chat c){return c.channel;}}
""",
    'org/telegram/messenger/MessagesController.java': r"""package org.telegram.messenger;
import java.util.*;import org.telegram.tgnet.TLRPC;
public class MessagesController {public static Map<Long,TLRPC.Chat> chats=new HashMap<>();public static int creates;public static String title,marker;
 public static MessagesController getInstance(int a){return new MessagesController();}public Map<Long,TLRPC.Chat> getChats(){return chats;}
 public TLRPC.Chat getChat(long id){return chats.get(id);}public void putChats(ArrayList<TLRPC.Chat> c,boolean cache){for(var chat:c)chats.put(chat.id,chat);}public void putUsers(ArrayList<TLRPC.User> u,boolean cache){}
 public TLRPC.InputPeer getInputPeer(TLRPC.Peer p){return new TLRPC.TL_inputPeerChannel();}
 public void createChat(String t,ArrayList<Long> users,String about,int type,boolean forum,Object a,Object b,int c,Object d){creates++;title=t;marker=about;TLRPC.Chat chat=new TLRPC.Chat();chat.id=77;chat.title=t;chat.creator=true;chat.channel=true;chat.access_hash=123;chats.put(chat.id,chat);NotificationCenter.INSTANCE.emit(NotificationCenter.chatDidCreated,Long.valueOf(chat.id));}
}
""",
    'org/telegram/messenger/UserConfig.java': r"""package org.telegram.messenger;
public class UserConfig {public static final int MAX_ACCOUNT_COUNT=10;public static long user=40;public static UserConfig getInstance(int a){return new UserConfig();}public long getClientUserId(){return user;}}
""",
    'org/telegram/tgnet/TLRPC.java': r"""package org.telegram.tgnet;import java.util.*;
public class TLRPC {
 public static class User{}public static class Chat {public long id,access_hash;public String title;public boolean creator,channel;}
 public static class Peer {public long channel_id,chat_id,user_id;}public static class InputPeer {public long channel_id,access_hash;}
 public static class InputChannel {public long channel_id,access_hash;}public static class TL_inputChannel extends InputChannel{}
 public static class TL_inputPeerChannel extends InputPeer{}public static class TL_inputPeerEmpty extends InputPeer{}
 public static class Dialog {public Peer peer;public int top_message;}public static class Message {public Peer peer_id;public int id,date;}
 public static class messages_Dialogs {public ArrayList<Dialog> dialogs=new ArrayList<>();public ArrayList<Chat> chats=new ArrayList<>();public ArrayList<User> users=new ArrayList<>();public ArrayList<Message> messages=new ArrayList<>();}
 public static class TL_messages_getDialogs {public int limit,offset_date,offset_id;public InputPeer offset_peer;public boolean exclude_pinned;}
 public static class Error {public String text;}
}
""",
    'org/telegram/tgnet/ConnectionsManager.java': r"""package org.telegram.tgnet;
public class ConnectionsManager {public static int reads;public static boolean fail,firstExcludedPinned;public static TLRPC.messages_Dialogs first,second;
 public static ConnectionsManager getInstance(int a){return new ConnectionsManager();}public interface Callback {void run(Object r,TLRPC.Error e);}
 public void sendRequest(TLRPC.TL_messages_getDialogs r,Callback cb){reads++;if(reads==1)firstExcludedPinned=r.exclude_pinned;if(fail){TLRPC.Error e=new TLRPC.Error();e.text="NETWORK_ERROR";cb.run(null,e);}else cb.run(r.offset_id==0?first:second,null);}
}
""",
    'PeerRuntimeTest.java': r"""import android.content.*;import java.util.*;import org.telegram.messenger.*;import org.telegram.tgnet.*;import org.telegram.messenger.telepremium.cloud.*;
public class PeerRuntimeTest {static int checks;static long dialog;static String error;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 static void reset(){UserConfig.user++;ApplicationLoader.applicationContext=new Context();MessagesController.chats.clear();MessagesController.creates=0;ConnectionsManager.reads=0;ConnectionsManager.fail=false;ConnectionsManager.first=new TLRPC.messages_Dialogs();ConnectionsManager.second=new TLRPC.messages_Dialogs();NotificationCenter.INSTANCE.listeners.clear();dialog=0;error=null;}
 static TLRPC.Chat vault(){var c=new TLRPC.Chat();c.id=7;c.access_hash=123;c.title="TelePremium Vault";c.creator=true;c.channel=true;return c;}
 static void ensure(){TelePremiumCloudPeerManager.getInstance(0).ensure((d,e)->{dialog=d;error=e;});}
 public static void main(String[] args){
  reset();ConnectionsManager.first.chats.add(vault());ensure();
  check(dialog==-7&&error==null&&MessagesController.creates==0,"existing remote vault must be discovered before creating a replacement");check(!ConnectionsManager.firstExcludedPinned,"first discovery page must include pinned vault channels");
  check(TelePremiumCloudPeerManager.getInstance(0)==TelePremiumCloudPeerManager.getVaultInstance(0),"default peer must always be V3");
  reset();ensure();check(dialog==-77&&error==null&&MessagesController.creates==1&&"TelePremium Vault".equals(MessagesController.title)&&"TP_VAULT_V3".equals(MessagesController.marker),"fresh account creates only the V3 namespace");
  reset();ConnectionsManager.fail=true;ensure();check(error!=null&&MessagesController.creates==0,"network failure must not create a duplicate empty namespace");
  reset();for(int i=0;i<100;i++){var d=new TLRPC.Dialog();d.peer=new TLRPC.Peer();d.peer.user_id=i+100;d.top_message=i+1;ConnectionsManager.first.dialogs.add(d);var m=new TLRPC.Message();m.id=i+1;m.date=200-i;m.peer_id=d.peer;ConnectionsManager.first.messages.add(m);}ConnectionsManager.second.chats.add(vault());ensure();check(dialog==-7&&error==null&&ConnectionsManager.reads==2&&MessagesController.creates==0,"remote discovery must paginate instead of creating a duplicate channel");
  var before=TelePremiumCloudPeerManager.getVaultInstance(0);reset();var after=TelePremiumCloudPeerManager.getVaultInstance(0);check(before!=after&&after.getChannelId()==0,"account-slot reuse must not reuse another Telegram user's peer");
  System.out.println("PeerRuntimeTest OK: "+checks+" checks");
 }
}
""",
}

def run():
    shared = runpy.run_path(str(ROOT / "scripts/test_vault_runtime.py"))["FILES"]
    files = {k: shared[k] for k in ["android/content/Context.java", "android/content/SharedPreferences.java", "org/telegram/messenger/ApplicationLoader.java", "org/telegram/messenger/AndroidUtilities.java", "org/telegram/messenger/NotificationCenter.java"]}
    files["org/telegram/messenger/NotificationCenter.java"] = files["org/telegram/messenger/NotificationCenter.java"].replace("fileLoaded=1,fileLoadFailed=2,messageReceivedByServer=3", "fileLoaded=1,fileLoadFailed=2,messageReceivedByServer=3,chatDidCreated=4,chatDidFailCreate=5")
    files.update(FILES)
    with tempfile.TemporaryDirectory(prefix="tp-vault-peer-tests-") as folder:
        base = Path(folder); src=base/"src"; out=base/"classes"; src.mkdir();out.mkdir()
        for name, content in files.items():
            p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding="utf-8")
        production=ROOT/"patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/cloud/TelePremiumCloudPeerManager.java"
        compiler=["javac"] if shutil.which("javac") else ["java","com.sun.tools.javac.Main"]
        subprocess.run([*compiler,"-d",str(out),*map(str,src.rglob("*.java")),str(production)],check=True)
        subprocess.run(["java","-cp",str(out),"PeerRuntimeTest"],check=True)

if __name__ == "__main__":
    run()
