#!/usr/bin/env python3
"""Production peer + authenticated scanner tests, with a fake server and no network."""
from pathlib import Path
import runpy, shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
PROD=ROOT/'patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/cloud'
FILES=dict(runpy.run_path(str(ROOT/'scripts/test_cloud_reset.py'))['FILES'])
del FILES['org/telegram/messenger/telepremium/cloud/TelePremiumCloudPeerManager.java']
del FILES['CloudResetTest.java']
shared=runpy.run_path(str(ROOT/'scripts/test_vault_runtime.py'))['FILES']
FILES['org/telegram/messenger/NotificationCenter.java']=shared['org/telegram/messenger/NotificationCenter.java'].replace('fileLoaded=1,fileLoadFailed=2,messageReceivedByServer=3','fileLoaded=1,fileLoadFailed=2,messageReceivedByServer=3,chatDidCreated=4,chatDidFailCreate=5')
FILES['org/telegram/messenger/ChatObject.java']='''package org.telegram.messenger;import org.telegram.tgnet.TLRPC;
public class ChatObject {public static final int CHAT_TYPE_CHANNEL=1;public static boolean isChannel(TLRPC.Chat c){return c.broadcast;}}
'''
FILES['org/telegram/tgnet/TLRPC.java']=FILES['org/telegram/tgnet/TLRPC.java'].replace('public static class InputPeer{}','public static class InputPeer {public long channel_id,access_hash;}public static class TL_inputPeerChannel extends InputPeer{}')
FILES['org/telegram/messenger/MessagesController.java']=FILES['org/telegram/messenger/MessagesController.java'].replace('public static Map<Integer,Map<Long,TLRPC.Chat>> caches=', 'public static int creates;public static Map<Integer,Map<Long,TLRPC.Chat>> caches=').replace(' public void deleteDialog(',''' public TLRPC.Chat getChat(long id){return getChats().get(id);}
 public void createChat(String title,ArrayList<Long> users,String about,int type,boolean forum,Object l,Object address,int ttl,Object fragment){
  creates++;var c=new TLRPC.Chat();c.id=1000+creates;c.title=title;c.about=about;c.creator=true;c.broadcast=true;c.access_hash=123;getChats().put(c.id,c);org.telegram.tgnet.ConnectionsManager.remote(a).put(c.id,c);NotificationCenter.getInstance(a).emit(NotificationCenter.chatDidCreated,Long.valueOf(c.id));
 }
 public void deleteDialog(''')
FILES['PeerRuntimeTest.java']=r'''import android.content.*;import java.nio.file.*;import java.util.*;import org.telegram.messenger.*;import org.telegram.tgnet.*;import org.telegram.messenger.telepremium.cloud.*;
public class PeerRuntimeTest {static int checks;static long uid=100,dialog;static String error;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 static void reset(){UserConfig.users=new long[]{++uid,0,0,0};ApplicationLoader.applicationContext=new Context();MessagesController.caches.clear();MessagesController.creates=0;ConnectionsManager.server.clear();ConnectionsManager.paginate=false;ConnectionsManager.loop=false;ConnectionsManager.failScan=false;ConnectionsManager.folderMask=0;ConnectionsManager.reads=0;NotificationCenter.INSTANCE.listeners.clear();dialog=0;error=null;}
 static TLRPC.Chat vault(long id,String title,String about,int folder){var c=new TLRPC.Chat();c.id=id;c.access_hash=id*3;c.title=title;c.about=about;c.creator=true;c.broadcast=true;c.folder=folder;ConnectionsManager.remote(0).put(id,c);return c;}
 static void ensure(){TelePremiumCloudPeerManager.getInstance(0).ensure((d,e)->{dialog=d;error=e;});}
 public static void main(String[] args)throws Exception{
  reset();vault(7,"Renamed encrypted storage","TP_VAULT_V3",1);ensure();check(dialog==-7&&error==null&&MessagesController.creates==0,"discover authenticated renamed archived V3 before creating another namespace");
  check(ConnectionsManager.folderMask==3,"normal discovery checks both regular and archived dialogs");
  check(TelePremiumCloudPeerManager.getInstance(0)==TelePremiumCloudPeerManager.getVaultInstance(0),"default peer is V3");
  reset();ensure();check(dialog==-1001&&error==null&&MessagesController.creates==1,"fresh account creates one private V3 channel");
  reset();vault(8,"TelePremium Vault","ordinary channel",0);ensure();check(dialog==-1001&&ConnectionsManager.remote(0).containsKey(8L),"title-only lookalike cannot become the encrypted backend");
  reset();ConnectionsManager.failScan=true;ensure();check(error!=null&&MessagesController.creates==0,"failed scan never creates a duplicate empty namespace");
  reset();ConnectionsManager.paginate=true;vault(7,"TelePremium Vault","TP_VAULT_V3",0);ensure();check(dialog==-7&&ConnectionsManager.reads==3&&MessagesController.creates==0,"authenticated discovery paginates before creation");
  reset();vault(7,"Vault A","TP_VAULT_V3",0);vault(8,"Vault B","TP_VAULT_V3",1);ensure();check(error!=null&&MessagesController.creates==0,"ambiguous old V3 channels require explicit reset instead of arbitrary choice");
  reset();vault(7,"TelePremium Vault","TP_VAULT_V3",0);var old=TelePremiumCloudPeerManager.getVaultInstance(0);ensure();String id=UUID.randomUUID().toString();TelePremiumCloudResetState.begin(id,"test");TelePremiumCloudResetState.save(id,"clearing","test",false);TelePremiumCloudResetState.save(id,"creating","test",true);TelePremiumCloudResetState.complete(id);
  old.ensure((d,e)->{dialog=d;error=e;});check(error!=null&&dialog==0&&old.getInputPeer()==null,"old peer remains invalid after reset unblocks");
  var fresh=TelePremiumCloudPeerManager.getVaultInstance(0);check(fresh!=old,"epoch changes invalidate cached peers");
  reset();vault(7,"TelePremium Vault","TP_VAULT_V3",0);ApplicationLoader.applicationContext.getSharedPreferences("telepremium_retired_cloud_u"+uid,0).edit().putBoolean("c7",true).commit();ensure();check(dialog==-1001,"late Telegram cache update cannot rediscover a retired cloud");
  reset();id=UUID.randomUUID().toString();TelePremiumCloudResetState.begin(id,"test");TelePremiumCloudResetState.save(id,"clearing","test",false);TelePremiumCloudResetState.save(id,"creating","test",true);
  vault(7,"Old service","TP_VAULT_V3",0);vault(8,"New service","TP_VAULT_V3:"+id,1);ensure();check(dialog==-8&&MessagesController.creates==0,"resume creation rebinds only this reset transaction marker");
  var before=TelePremiumCloudPeerManager.getVaultInstance(0);reset();var after=TelePremiumCloudPeerManager.getVaultInstance(0);check(before!=after&&after.getChannelId()==0,"account-slot reuse never reuses another owner's peer");
  System.out.println("PeerRuntimeTest OK: "+checks+" checks");
 }
}'''
def run():
 with tempfile.TemporaryDirectory(prefix='tp-vault-peer-tests-') as directory:
  base=Path(directory);src=base/'src';out=base/'classes';src.mkdir();out.mkdir()
  for name,content in FILES.items():
   p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8')
  sources=sorted(src.rglob('*.java'))+sorted((ROOT/'scripts/tests/stubs').rglob('*.java'))
  sources += [PROD/(n+'.java') for n in ['TelePremiumCloudPeerManager','TelePremiumCloudResetState','TelePremiumCloudResetPlan','TelePremiumCloudResetScanner']]
  compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
  subprocess.run([*compiler,'-d',str(out),*map(str,sources)],check=True)
  subprocess.run(['java','-cp',str(out),'PeerRuntimeTest'],check=True)
if __name__=='__main__':run()
