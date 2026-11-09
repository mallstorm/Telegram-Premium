#!/usr/bin/env python3
"""Production V3 media projection, cancellable requests and authenticated range playback.
No real Telegram accounts are used. AES-GCM and the production stream reader are real.
"""
from pathlib import Path
import runpy, shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
PROD=ROOT/'patches/tg13/overlay/TMessagesProj/src/main/java'
COMMON=runpy.run_path(str(ROOT/'scripts/test_vault_runtime.py'))['FILES']
FILES={k:COMMON[k] for k in ['android/content/Context.java','android/content/SharedPreferences.java','org/telegram/messenger/ApplicationLoader.java','org/telegram/messenger/FileLog.java']}
FILES.update({
'org/telegram/messenger/telepremium/xray/XrayRuntime.java':'package org.telegram.messenger.telepremium.xray;public class XrayRuntime{public static java.net.Proxy mediaProxy(){return java.net.Proxy.NO_PROXY;}}',
'android/text/TextUtils.java':'''package android.text; public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}''',
'android/webkit/MimeTypeMap.java':'''package android.webkit;public class MimeTypeMap {public static MimeTypeMap getSingleton(){return new MimeTypeMap();}public String getMimeTypeFromExtension(String x){return switch(x){case "mp4","mkv","webm"->"video/"+x;case "mp3","m4a","flac","ogg"->"audio/"+x;default->null;};}}''',
'android/net/Uri.java':'''package android.net;import java.io.File;import java.net.URI;public class Uri {final URI u;private Uri(URI u){this.u=u;}public static Uri parse(String s){return new Uri(URI.create(s));}public static Uri fromFile(File f){return new Uri(f.toURI());}public String getScheme(){return u.getScheme();}public String getHost(){return u.getHost();}public String getPath(){return u.getPath();}public String toString(){return u.toString();}}''',
'androidx/annotation/Nullable.java':'''package androidx.annotation; public @interface Nullable {}''',
'androidx/annotation/OptIn.java':'''package androidx.annotation; public @interface OptIn {Class<?>[] markerClass();}''',
'androidx/media3/common/util/UnstableApi.java':'''package androidx.media3.common.util;public @interface UnstableApi {}''',
'androidx/media3/common/C.java':'''package androidx.media3.common;public class C {public static final int RESULT_END_OF_INPUT=-1;public static final long LENGTH_UNSET=-1;}''',
'androidx/media3/datasource/DataSpec.java':'''package androidx.media3.datasource;import android.net.Uri;public class DataSpec {public Uri uri;public long position,length=-1;public static class Builder {DataSpec v=new DataSpec();public Builder setUri(Uri u){v.uri=u;return this;}public Builder setPosition(long p){v.position=p;return this;}public Builder setLength(long n){v.length=n;return this;}public DataSpec build(){return v;}}}''',
'androidx/media3/datasource/DataSource.java':'''package androidx.media3.datasource;import android.net.Uri;import java.io.*;public interface DataSource {long open(DataSpec s)throws IOException;int read(byte[] b,int o,int n)throws IOException;Uri getUri();void close()throws IOException;}''',
'androidx/media3/datasource/BaseDataSource.java':'''package androidx.media3.datasource;public abstract class BaseDataSource implements DataSource {protected BaseDataSource(boolean network){}protected void transferInitializing(DataSpec d){}protected void transferStarted(DataSpec d){}protected void bytesTransferred(int n){}protected void transferEnded(){}}''',
'androidx/media3/datasource/FileDataSource.java':'''package androidx.media3.datasource;import android.net.Uri;import java.io.*;public class FileDataSource implements DataSource {public static int readers;RandomAccessFile file;Uri uri;public long open(DataSpec d)throws IOException{uri=d.uri;file=new RandomAccessFile(uri.getPath(),"r");readers++;file.seek(d.position);return file.length()-d.position;}public int read(byte[] b,int o,int n)throws IOException{return file.read(b,o,n);}public Uri getUri(){return uri;}public void close()throws IOException{if(file!=null){file.close();file=null;readers--;}}}''',
'org/telegram/messenger/FileStreamLoadOperation.java':'''package org.telegram.messenger;public class FileStreamLoadOperation extends androidx.media3.datasource.FileDataSource {}''',
'org/telegram/messenger/FileLoader.java':'''package org.telegram.messenger;import java.io.File;import org.telegram.tgnet.TLRPC;public class FileLoader {public static FileLoader getInstance(int a){return new FileLoader();}public File getPathToAttach(TLRPC.Document d,boolean cache){return null;}public void cancelLoadFile(TLRPC.Document d,boolean all){}}''',
'org/telegram/messenger/UserConfig.java':'''package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class UserConfig {public static final int MAX_ACCOUNT_COUNT=4;public static long[] users={41,42,0,0};int a;UserConfig(int a){this.a=a;}public static UserConfig getInstance(int a){return new UserConfig(a);}public boolean isClientActivated(){return users[a]>0;}public long getClientUserId(){return users[a];}public TLRPC.User getCurrentUser(){var u=new TLRPC.User();u.id=users[a];u.first_name="Account "+users[a];return u;}}''',
'org/telegram/tgnet/TLRPC.java':'''package org.telegram.tgnet;public class TLRPC {public static class User {public long id;public String first_name,last_name;}public static class Document {public long id,size;}}''',
'org/telegram/messenger/AndroidUtilities.java':'''package org.telegram.messenger;import java.util.concurrent.*;public class AndroidUtilities {public static final BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();public static boolean queued;public static void runOnUIThread(Runnable r){if(queued)ui.add(r);else r.run();}}''',
'org/telegram/messenger/telepremium/cloud/TelePremiumCloudSecuritySession.java':'''package org.telegram.messenger.telepremium.cloud;import android.content.Context;public class TelePremiumCloudSecuritySession {public static boolean unlocked=true;public static boolean isUnlocked(Context c){return unlocked;}}''',
'org/telegram/messenger/telepremium/cloud/TelePremiumCloudManager.java':'''package org.telegram.messenger.telepremium.cloud;import java.util.*;public class TelePremiumCloudManager {public interface Callback{void onComplete(String e);}public static Map<Integer,CloudSnapshot> snapshots=new HashMap<>();public static Map<Integer,String> errors=new HashMap<>();public static List<Runnable> pending=new ArrayList<>();public static boolean defer;public static int calls;int a;public static TelePremiumCloudManager getInstance(int a){var m=new TelePremiumCloudManager();m.a=a;return m;}public CloudSnapshot getSnapshot(){return snapshots.get(a);}public void bootstrap(Callback cb){calls++;if(defer)pending.add(()->cb.onComplete(errors.get(a)));else cb.onComplete(errors.get(a));}}''',
'org/telegram/messenger/telepremium/media/MediaVaultRuntimeTest.java':r'''package org.telegram.messenger.telepremium.media;
import android.content.*;import android.net.Uri;import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import org.telegram.messenger.*;import org.telegram.messenger.telepremium.cloud.*;import org.telegram.messenger.telepremium.cloud.vault.*;import androidx.media3.datasource.*;
public class MediaVaultRuntimeTest {
 static int checks;static String error;static List<TelePremiumMediaItem> items;static Path root;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 interface Work{void run()throws Exception;}
 static void rejects(Work work,String message)throws Exception{try{work.run();throw new AssertionError(message);}catch(IOException|IllegalArgumentException expected){checks++;}}
 static void reset()throws Exception{root=Files.createTempDirectory("tp-media-vault-");Context c=new Context();c.root=root.toFile();ApplicationLoader.applicationContext=c;UserConfig.users=new long[]{41,42,0,0};TelePremiumCloudSecuritySession.unlocked=true;TelePremiumCloudManager.snapshots.clear();TelePremiumCloudManager.errors.clear();TelePremiumCloudManager.pending.clear();TelePremiumCloudManager.defer=false;TelePremiumCloudManager.calls=0;AndroidUtilities.queued=false;AndroidUtilities.ui.clear();items=null;error=null;}
 static CloudSnapshot snapshot(long uid){var s=new CloudSnapshot();s.ownerUserId=uid;s.nodes.add(CloudNode.folder(null,"root"));return s;}
 static CloudNode file(String name,String mime,int state,int version,int id){var n=CloudNode.file("root",name,mime,100,"hash");n.telegramMessageId=id;n.telegramDocumentId=id*10;n.encryptionVersion=version;n.state=state;return n;}
 static void load(int account){new TelePremiumCloudMediaRepository().load(account,(list,e)->{items=list;error=e;});}
 static void projection()throws Exception{
  reset();var a=snapshot(41);var b=snapshot(42);
  a.nodes.add(file("Video.MP4","application/octet-stream",CloudNode.STATE_ACTIVE,1,10));a.nodes.add(file("music.mp3","audio/mpeg",CloudNode.STATE_ACTIVE,1,11));
  a.nodes.add(file("gone.mp4","video/mp4",CloudNode.STATE_TRASHED,1,12));a.nodes.add(file("plain.mp4","video/mp4",CloudNode.STATE_ACTIVE,0,13));a.nodes.add(file("manual.pdf","application/pdf",CloudNode.STATE_ACTIVE,1,14));
  var renamed=file("Renamed clip","application/octet-stream",CloudNode.STATE_ACTIVE,1,20);renamed.originalName="original.mkv";renamed.allocateParts(2);renamed.partMessageIds[0]=20;renamed.partMessageIds[1]=21;renamed.partSizes[0]=40;renamed.partSizes[1]=60;b.nodes.add(renamed);
  TelePremiumCloudManager.snapshots.put(0,a);TelePremiumCloudManager.snapshots.put(1,b);load(-1);
  check(error==null&&items.size()==3,"all-account media uses authenticated V3 snapshots and excludes trash/plaintext/nonmedia");
  check(items.stream().filter(TelePremiumMediaItem::isVideo).count()==2&&items.stream().filter(TelePremiumMediaItem::isAudio).count()==1,"video/audio classification survives generic MIME and renamed files");
  check(items.get(0).cloudUserId==41&&items.get(2).cloudUserId==42&&items.get(2).cloudAccount==1,"each file retains its Telegram user/account binding");
  check(items.get(2).isMultipart()&&items.get(2).partSizes[1]==60,"media descriptors preserve encrypted multipart dimensions");items.get(2).partSizes[1]=99;check(renamed.partSizes[1]==60,"media projection never mutates the cloud catalog");
  load(1);check(items.size()==1&&items.get(0).cloudUserId==42,"explicit account filter loads the requested cloud");
  var restored=TelePremiumMediaItem.fromJson(items.get(0).toJson());check(restored.cloudUserId==42&&restored.cloudEpoch==TelePremiumCloudResetState.epoch(),"playlists preserve user and reset generation");
  TelePremiumCloudManager.errors.put(1,"NETWORK_ERROR");load(-1);check(items.size()==3&&error.contains("NETWORK_ERROR"),"one offline account does not hide other media or its authenticated cached catalog");
  TelePremiumCloudSecuritySession.unlocked=false;int before=TelePremiumCloudManager.calls;load(-1);check(items.isEmpty()&&error!=null&&before==TelePremiumCloudManager.calls,"locked Vault does not reveal metadata or start bootstrap");
  TelePremiumCloudSecuritySession.unlocked=true;TelePremiumCloudManager.defer=true;items=null;load(-1);TelePremiumCloudSecuritySession.unlocked=false;for(var cb:new ArrayList<>(TelePremiumCloudManager.pending))cb.run();check(items.isEmpty()&&error!=null,"a lock during loading rejects late catalog results");
  TelePremiumCloudSecuritySession.unlocked=true;TelePremiumCloudManager.pending.clear();items=null;load(-1);String id=UUID.randomUUID().toString();TelePremiumCloudResetState.begin(id,"test");for(var cb:new ArrayList<>(TelePremiumCloudManager.pending))cb.run();check(items.isEmpty()&&error!=null,"a reset during loading rejects old catalog results");
  check(TelePremiumCloudMediaRepository.project(0,99,0,a).isEmpty(),"projection itself rejects a catalog belonging to another Telegram user");
 }
 static byte[] bytes(int n,int seed){byte[] raw=new byte[n];new Random(seed).nextBytes(raw);return raw;}
 static Path encrypt(String name,byte[] plain,byte[] key)throws Exception{Path p=root.resolve(name);try(var out=Files.newOutputStream(p)){TelePremiumVaultCrypto.encrypt(new ByteArrayInputStream(plain),out,key,TelePremiumVaultCrypto.Purpose.FILE);}return p;}
 static byte[] read(Uri stream,long offset,long length)throws Exception{var ds=new TelePremiumMultipartDataSource();try{ds.open(new DataSpec.Builder().setUri(stream).setPosition(offset).setLength(length).build());var out=new ByteArrayOutputStream();byte[] b=new byte[65531];int n;while((n=ds.read(b,0,b.length))!=-1)out.write(b,0,n);return out.toByteArray();}finally{ds.close();}}
 static void streaming()throws Exception{
  reset();byte[] key=TelePremiumVaultCrypto.newMasterKey();byte[] p1=bytes(1024*1024+17,1),p2=bytes(1024*1024+213,2);Path f1=encrypt("a.tpv",p1,key),f2=encrypt("b.tpv",p2,key);byte[] all=new byte[p1.length+p2.length];System.arraycopy(p1,0,all,0,p1.length);System.arraycopy(p2,0,all,p1.length,p2.length);
  Uri uri=TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(f1.toFile()),Uri.fromFile(f2.toFile())},null,new long[]{p1.length,p2.length},key);
  check(Arrays.equals(read(uri,0,-1),all),"real encrypted multipart media decrypts all authenticated chunks in order");
  for(long at:new long[]{0,999,1024*1024+5,p1.length-9,p1.length,p1.length+19,all.length-17})
   check(Arrays.equals(read(uri,at,Math.min(37,all.length-at)),Arrays.copyOfRange(all,(int)at,(int)Math.min(all.length,at+37))),"authenticated random range and part-boundary seek at "+at);
  check(read(uri,all.length,-1).length==0,"EOF seek returns no ciphertext");
  check(FileDataSource.readers==0,"all media readers close after repeated seeks");
  var desc=new DataSpec.Builder().setUri(uri).build();var open=new TelePremiumMultipartDataSource();open.open(desc);TelePremiumMultipartDataSource.unregister(uri);var closedReader=open;rejects(()->closedReader.read(new byte[8],0,8),"unregistered stream must not fall back to raw ciphertext after wiping its key");open.close();check(Files.exists(f1)&&Files.exists(f2),"pre-existing offline copies are retained after playback stops");
  Path tampered=encrypt("bad.tpv",bytes(51,3),key);byte[] bad=Files.readAllBytes(tampered);bad[TelePremiumVaultCrypto.STREAM_HEADER_BYTES+8]^=1;Files.write(tampered,bad);Uri corrupt=TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(tampered.toFile())},null,new long[]{51},key);rejects(()->read(corrupt,0,-1),"tampered GCM chunks cannot reach the decoder");check(FileDataSource.readers==0,"open failure closes its acquired Telegram/file reader");TelePremiumMultipartDataSource.unregister(corrupt);
  Path truncated=encrypt("truncated.tpv",bytes(31,4),key);byte[] shortFile=Files.readAllBytes(truncated);Files.write(truncated,Arrays.copyOf(shortFile,shortFile.length-16));Uri partial=TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(truncated.toFile())},null,new long[]{31},key);rejects(()->read(partial,0,-1),"missing authenticated terminator cannot yield final plaintext");check(FileDataSource.readers==0,"truncated stream releases its reader");TelePremiumMultipartDataSource.unregister(partial);
  Uri wrong=TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(f1.toFile())},null,new long[]{p1.length},TelePremiumVaultCrypto.newMasterKey());rejects(()->read(wrong,0,-1),"a different master key never plays ciphertext as media");TelePremiumMultipartDataSource.unregister(wrong);
  Uri stale=TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(f1.toFile())},null,new long[]{p1.length},key);open=new TelePremiumMultipartDataSource();open.open(new DataSpec.Builder().setUri(stale).build());String id=UUID.randomUUID().toString();TelePremiumCloudResetState.begin(id,"test");TelePremiumCloudResetState.save(id,"clearing","test",false);TelePremiumCloudResetState.save(id,"creating","test",true);TelePremiumCloudResetState.complete(id);var oldReader=open;rejects(()->oldReader.read(new byte[8],0,8),"old playback remains rejected after a reset finishes");open.close();TelePremiumMultipartDataSource.unregister(stale);
  Uri switched=TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(f1.toFile())},null,new long[]{p1.length},key);open=new TelePremiumMultipartDataSource();open.open(new DataSpec.Builder().setUri(switched).build());UserConfig.users[0]=99;var oldOwner=open;rejects(()->oldOwner.read(new byte[8],0,8),"account-slot reuse cannot retain the old user's stream");open.close();TelePremiumMultipartDataSource.unregister(switched);
  rejects(()->TelePremiumMultipartDataSource.register(0,new Uri[]{Uri.fromFile(f1.toFile())},null,new long[0],key),"bad part arrays are rejected before registration");
  Arrays.fill(key,(byte)0);
 }
 static void cancellation()throws Exception{
  reset();AndroidUtilities.queued=true;var pool=Executors.newSingleThreadExecutor();var callbacks=new int[]{0};
  try{
   var ready=TelePremiumMediaRequest.start(pool,()->7,(value,error)->callbacks[0]++);Runnable callback=AndroidUtilities.ui.poll(5,TimeUnit.SECONDS);check(callback!=null,"network result reaches the UI dispatch queue");ready.cancel();callback.run();check(callbacks[0]==0,"cancelling after completion still suppresses a queued stale callback");
   CountDownLatch started=new CountDownLatch(1),disconnected=new CountDownLatch(1),releaseClose=new CountDownLatch(1);
   var connection=new HttpURLConnection(new URL("http://test.invalid")){public void disconnect(){disconnected.countDown();try{releaseClose.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){}}public boolean usingProxy(){return false;}public void connect(){}};
   URL fake=new URL(null,"http://test.invalid",new URLStreamHandler(){protected URLConnection openConnection(URL ignored){return connection;}protected URLConnection openConnection(URL ignored,java.net.Proxy proxy){return connection;}});
   var running=TelePremiumMediaRequest.start(pool,()->{TelePremiumMediaRequest.open(fake);started.countDown();new CountDownLatch(1).await();return 1;},(value,error)->callbacks[0]++);
   check(started.await(5,TimeUnit.SECONDS),"HTTP connection is registered with its request");long before=System.nanoTime();running.cancel();check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<1000,"cancelling never waits for a blocking disconnect on the UI thread");check(disconnected.await(5,TimeUnit.SECONDS),"cancelling actively disconnects the obsolete HTTP request");releaseClose.countDown();pool.submit(()->{}).get(5,TimeUnit.SECONDS);check(AndroidUtilities.ui.isEmpty()&&callbacks[0]==0,"cancelled network work never delivers an error or stale media result");
  }finally{pool.shutdownNow();AndroidUtilities.queued=false;}
 }
 public static void main(String[] args)throws Exception{projection();streaming();cancellation();System.out.println("MediaVaultRuntimeTest OK: "+checks+" checks");}
}'''
})

def run():
 with tempfile.TemporaryDirectory(prefix='tp-media-vault-tests-') as folder:
  base=Path(folder);src=base/'src';out=base/'classes';src.mkdir();out.mkdir()
  for name,content in FILES.items():
   p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8')
  sources=sorted(src.rglob('*.java'))+sorted((ROOT/'scripts/tests/stubs').rglob('*.java'))
  paths=['cloud/CloudNode','cloud/CloudSnapshot','cloud/TelePremiumCloudFormat','cloud/TelePremiumCloudResetState','cloud/vault/TelePremiumVaultCrypto','media/TelePremiumMediaItem','media/TelePremiumCloudMediaRepository','media/TelePremiumMultipartDataSource','media/TelePremiumMediaRequest']
  sources += [PROD/('org/telegram/messenger/telepremium/'+p+'.java') for p in paths]
  compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
  subprocess.run([*compiler,'-d',str(out),*map(str,sources)],check=True)
  subprocess.run(['java','-cp',str(out),'org.telegram.messenger.telepremium.media.MediaVaultRuntimeTest'],check=True)
if __name__=='__main__':run()
