#!/usr/bin/env python3
"""Exercise the production key manager with a host AndroidKeyStore test provider.

The provider stores test AES keys in memory; Cipher/PBKDF2 use the JDK providers.
Device hardware-backed key storage still needs an Android smoke test.
"""
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROD = ROOT / "patches/tg13/overlay/TMessagesProj/src/main/java"
FILES = {
    "android/os/Build.java": """package android.os;
public class Build {public static class VERSION {public static int SDK_INT=26;}
public static class VERSION_CODES {public static final int M=23,O=26;}}
""",
    "android/util/Base64.java": """package android.util;
public class Base64 {public static final int NO_WRAP=2;
public static String encodeToString(byte[] b,int flags){return java.util.Base64.getEncoder().encodeToString(b);}
public static byte[] decode(String s,int flags){return java.util.Base64.getDecoder().decode(s);}}
""",
    "android/security/keystore/KeyProperties.java": """package android.security.keystore;
public class KeyProperties {public static final String KEY_ALGORITHM_AES="AES",BLOCK_MODE_GCM="GCM",ENCRYPTION_PADDING_NONE="NoPadding";
public static final int PURPOSE_ENCRYPT=1,PURPOSE_DECRYPT=2;}
""",
    "android/security/keystore/KeyGenParameterSpec.java": """package android.security.keystore;
public class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {
public final String alias;KeyGenParameterSpec(String a){alias=a;}
public static class Builder {String alias;public Builder(String a,int p){alias=a;}
public Builder setBlockModes(String...m){return this;}public Builder setEncryptionPaddings(String...p){return this;}
public Builder setKeySize(int b){return this;}public Builder setRandomizedEncryptionRequired(boolean b){return this;}
public KeyGenParameterSpec build(){return new KeyGenParameterSpec(alias);}}}
""",
    "org/telegram/messenger/telepremium/cloud/TelePremiumCloudSecurityStore.java": """package org.telegram.messenger.telepremium.cloud;
public class TelePremiumCloudSecurityStore {public static boolean protectedScreen;
public static boolean isProtectionEnabled(android.content.Context c){return protectedScreen;}}
""",
    "org/telegram/messenger/telepremium/cloud/TelePremiumCloudSecuritySession.java": """package org.telegram.messenger.telepremium.cloud;
public class TelePremiumCloudSecuritySession {public static boolean unlocked;
public static boolean isUnlocked(android.content.Context c){return unlocked;}}
""",
    "TestKeyStoreProvider.java": """import java.security.*;import java.security.cert.Certificate;import java.security.spec.*;
import java.io.*;import java.util.*;import javax.crypto.*;import javax.crypto.spec.*;
import android.security.keystore.KeyGenParameterSpec;
public class TestKeyStoreProvider extends Provider {
 public static Map<String,SecretKey> keys=new HashMap<>();
 public TestKeyStoreProvider(){super("AndroidKeyStore",1.0,"Test-only in-memory AndroidKeyStore");
 put("KeyStore.AndroidKeyStore",Store.class.getName());put("KeyGenerator.AES",Generator.class.getName());}
 public static class Generator extends KeyGeneratorSpi {
  String alias;protected void engineInit(SecureRandom r){}protected void engineInit(int n,SecureRandom r){}
  protected void engineInit(AlgorithmParameterSpec p,SecureRandom r){alias=((KeyGenParameterSpec)p).alias;}
  protected SecretKey engineGenerateKey(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);SecretKey key=new SecretKeySpec(bytes,"AES");keys.put(alias,key);return key;}
 }
 public static class Store extends KeyStoreSpi {
  public Key engineGetKey(String alias,char[] password){return keys.get(alias);}
  public KeyStore.Entry engineGetEntry(String alias,KeyStore.ProtectionParameter p){SecretKey k=keys.get(alias);return k==null?null:new KeyStore.SecretKeyEntry(k);}
  public Certificate[] engineGetCertificateChain(String a){return null;}public Certificate engineGetCertificate(String a){return null;}
  public Date engineGetCreationDate(String a){return new Date();}
  public void engineSetKeyEntry(String a,Key k,char[] p,Certificate[] chain){keys.put(a,(SecretKey)k);}
  public void engineSetKeyEntry(String a,byte[] k,Certificate[] chain){}public void engineSetCertificateEntry(String a,Certificate c){}
  public void engineDeleteEntry(String a){keys.remove(a);}public Enumeration<String> engineAliases(){return Collections.enumeration(keys.keySet());}
  public boolean engineContainsAlias(String a){return keys.containsKey(a);}public int engineSize(){return keys.size();}
  public boolean engineIsKeyEntry(String a){return keys.containsKey(a);}public boolean engineIsCertificateEntry(String a){return false;}
  public String engineGetCertificateAlias(Certificate c){return null;}public void engineStore(OutputStream s,char[] p){}public void engineLoad(InputStream s,char[] p){}
 }
}
""",
    "VaultKeyManagerTest.java": """import java.util.*;import java.security.*;import android.content.*;
import org.telegram.messenger.telepremium.cloud.*;import org.telegram.messenger.telepremium.cloud.vault.*;
public class VaultKeyManagerTest {
 static int checks;static final String PREF="telepremium_e2ee_vault_v1";
 interface Work {void run()throws Exception;}
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);checks++;}
 static void fails(Work r,String msg)throws Exception {try{r.run();}catch(Exception expected){checks++;return;}throw new AssertionError(msg);}
 static byte[] key(Context c)throws Exception{return TelePremiumVaultKeyManager.getMasterKeyForUnlockedSession(c);}
 static boolean same(byte[] a,byte[] b){return MessageDigest.isEqual(a,b);}
 public static void main(String[] args)throws Exception {
  Security.addProvider(new TestKeyStoreProvider());Context first=new Context();android.os.Build.VERSION.SDK_INT=23;
  check(TelePremiumVaultKeyManager.isEnabled(first),"V3 must be enabled before any setup");
  byte[] original=key(first);check(original.length==32&&same(original,key(first)),"first launch must provision and persist one random master key on Android 6");
  first.getSharedPreferences(PREF,0).edit().putBoolean("enabled",false).commit();
  check(TelePremiumVaultKeyManager.isEnabled(first)&&same(original,key(first)),"legacy disabled flag cannot select V2 or replace existing master");
  String raw=TelePremiumVaultKeyManager.exportRecoveryEnvelope(first);check(raw.startsWith("TPVAULT-KEY-1:"),"default recovery requires no password setup");
  Context second=new Context();byte[] unused=key(second);check(!same(unused,original),"independent fresh installation gets an independent random master");
  TelePremiumVaultKeyManager.recoverFromCode(second,raw,new char[0]);check(same(original,key(second)),"recovery may replace only an unused auto-created key");
  TelePremiumVaultKeyManager.markKeyUsed(first);String another=TelePremiumVaultKeyManager.exportRecoveryEnvelope(new Context());
  fails(()->TelePremiumVaultKeyManager.recoverFromCode(first,another,new char[0]),"must refuse replacement of a key with encrypted data");
  check(same(original,key(first)),"rejected recovery keeps the original key");
  fails(()->TelePremiumVaultKeyManager.recoverFromCode(second,raw.substring(0,raw.length()-1)+"x",new char[0]),"damaged recovery checksum must fail");
  TestKeyStoreProvider.keys.clear();fails(()->key(first),"lost Android wrapper must not regenerate the master");
  TelePremiumVaultKeyManager.recoverFromCode(first,raw,new char[0]);check(same(original,key(first)),"matching fingerprint allows recovery after AndroidKeyStore loss");
  android.os.Build.VERSION.SDK_INT=26;char[] password="a sufficiently long recovery password".toCharArray();
  TelePremiumVaultKeyManager.initialize(first,password);check(same(original,key(first)),"adding a recovery password must not change the cloud key");
  check(TelePremiumVaultKeyManager.verifyRecoveryPassphrase(first,password),"password-backed recovery verifies");
  check(!TelePremiumVaultKeyManager.verifyRecoveryPassphrase(first,"a different wrong password".toCharArray()),"wrong recovery password is rejected");
  String legacy=TelePremiumVaultKeyManager.exportRecoveryEnvelope(first);check(legacy.startsWith("TPVAULT-RECOVERY-1:"),"legacy encrypted recovery format remains compatible");
  Context third=new Context();TelePremiumVaultKeyManager.recoverFromCode(third,legacy,password);check(same(original,key(third)),"legacy password-backed recovery restores the original master");
  first.getSharedPreferences(PREF,0).edit().remove("master_fingerprint").commit();TestKeyStoreProvider.keys.clear();
  TelePremiumVaultKeyManager.recoverFromCode(first,legacy,password);check(same(original,key(first)),"legacy recovery repairs a lost wrapper even without new fingerprint metadata");
  TelePremiumCloudSecurityStore.protectedScreen=true;TelePremiumCloudSecuritySession.unlocked=false;
  fails(()->key(first),"optional screen protection must still enforce unlocking");TelePremiumCloudSecuritySession.unlocked=true;
  check(same(original,key(first)),"screen unlock releases the existing key");TelePremiumCloudSecurityStore.protectedScreen=false;
  Context failed=new Context();failed.failCommit=true;fails(()->key(failed),"failed durable key persistence must stop encryption");
  check(!TelePremiumVaultKeyManager.hasMasterKey(failed),"failed persistence cannot leave a configured key flag");
  Context incomplete=new Context();incomplete.getSharedPreferences(PREF,0).edit().putString("wrapped_recovery","existing-data").commit();
  fails(()->key(incomplete),"partial legacy key state must require recovery, never reset");
  org.telegram.messenger.ApplicationLoader.applicationContext=first;
  String wrappedBefore=first.getSharedPreferences(PREF,0).getString("wrapped_local",null);
  String resetId=java.util.UUID.randomUUID().toString();
  org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState.begin(resetId,"approved-test-plan");
  fails(()->TelePremiumVaultKeyManager.rotateAfterCloudReset(first,resetId),"deleting stage cannot rotate the old key");
  fails(()->key(first),"normal key access is blocked during destructive reset");
  check(wrappedBefore.equals(first.getSharedPreferences(PREF,0).getString("wrapped_local",null)),"partial reset preserves original encrypted master");
  var state=org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState.class;
  org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState.save(resetId,"clearing","all-deletions-confirmed",false);
  first.failCommit=true;
  fails(()->TelePremiumVaultKeyManager.rotateAfterCloudReset(first,resetId),"archive persistence failure prevents rotation");first.failCommit=false;
  check(wrappedBefore.equals(first.getSharedPreferences(PREF,0).getString("wrapped_local",null)),"archive failure preserves old active key");
  TelePremiumVaultKeyManager.rotateAfterCloudReset(first,resetId);
  String wrappedNew=first.getSharedPreferences(PREF,0).getString("wrapped_local",null);
  check(!wrappedBefore.equals(wrappedNew),"explicit completed reset rotates the key");
  check(wrappedBefore.equals(first.getSharedPreferences("telepremium_vault_retired_keys_v1",0).getString(resetId+".wrapped_local",null)),"archive retains only original AndroidKeyStore ciphertext");
  check(!TelePremiumVaultKeyManager.hasPasswordRecovery(first)&&!TelePremiumVaultKeyManager.isRecoveryConfirmed(first),"old recovery envelope and confirmation are not reused for a new key");
  TelePremiumVaultKeyManager.rotateAfterCloudReset(first,resetId);
  check(wrappedNew.equals(first.getSharedPreferences(PREF,0).getString("wrapped_local",null)),"retry after interruption never generates a second master");
  org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState.save(resetId,"creating","ready-to-create",true);
  byte[] newMaster=key(first);check(!same(original,newMaster),"newly created V3 uses a different master");
  String newCode=TelePremiumVaultKeyManager.exportRecoveryEnvelope(first);Context restoredNew=new Context();TelePremiumVaultKeyManager.recoverFromCode(restoredNew,newCode,new char[0]);
  check(same(newMaster,key(restoredNew)),"new recovery code restores the new cloud master");
  TelePremiumVaultKeyManager.markKeyUsed(first);
  fails(()->TelePremiumVaultKeyManager.recoverFromCode(first,legacy,password),"old recovery code cannot overwrite the new key after new cloud use");
  org.telegram.messenger.telepremium.cloud.TelePremiumCloudResetState.complete(resetId);
  fails(()->TelePremiumVaultKeyManager.rotateAfterCloudReset(first,resetId),"completed reset cannot be reused to rotate keys");
  System.out.println("VaultKeyManagerTest OK: "+checks+" checks");
 }
}
""",
}


def run():
    common = runpy.run_path(str(ROOT / "scripts/test_vault_runtime.py"))["FILES"]
    files = {k: common[k] for k in ["android/content/Context.java", "android/content/SharedPreferences.java"]}
    files["org/telegram/messenger/ApplicationLoader.java"] = common["org/telegram/messenger/ApplicationLoader.java"]
    files.update(FILES)
    with tempfile.TemporaryDirectory(prefix="tp-vault-key-tests-") as folder:
        base = Path(folder)
        src, out = base / "src", base / "classes"
        src.mkdir()
        out.mkdir()
        for name, content in files.items():
            path = src / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        sources = sorted(src.rglob("*.java"))
        sources += [PROD / "org/telegram/messenger/telepremium/cloud/TelePremiumCloudResetState.java"]
        sources += [PROD / ("org/telegram/messenger/telepremium/cloud/vault/" + n + ".java")
                    for n in ["TelePremiumVaultCrypto", "TelePremiumVaultKeyManager"]]
        compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        subprocess.run([*compiler, "-d", str(out), *map(str, sources)], check=True)
        subprocess.run(["java", "-cp", str(out), "VaultKeyManagerTest"], check=True)


if __name__ == "__main__":
    run()
