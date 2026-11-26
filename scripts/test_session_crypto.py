#!/usr/bin/env python3
import os, subprocess, tempfile, textwrap, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, 'patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/SessionCrypto.java')
test = textwrap.dedent('''
import org.telegram.messenger.telepremium.SessionCrypto;
import java.util.*;
public class SessionCryptoTest {
  static int checks;
  static void check(boolean ok, String m){ if(!ok) throw new AssertionError(m); checks++; }
  public static void main(String[] a) throws Exception {
    Map<String,byte[]> files = new LinkedHashMap<>();
    files.put("manifest.json", "{\\"v\\":1}".getBytes("UTF-8"));
    byte[] big = new byte[300000]; new Random(1).nextBytes(big); files.put("acc0/native/tgnet.dat", big);
    byte[] plain = SessionCrypto.pack(files);
    byte[] enc = SessionCrypto.encrypt(plain, "correct horse battery".toCharArray());
    Map<String,byte[]> back = SessionCrypto.unpack(SessionCrypto.decrypt(enc, "correct horse battery".toCharArray()));
    check(back.size()==2 && Arrays.equals(back.get("acc0/native/tgnet.dat"), big), "round trip keeps every file");
    try { SessionCrypto.decrypt(enc, "wrong password".toCharArray()); check(false, "wrong password accepted"); } catch (SecurityException e) { check(true, "wrong password rejected"); }
    byte[] damaged = enc.clone(); damaged[damaged.length-5] ^= 1;
    try { SessionCrypto.decrypt(damaged, "correct horse battery".toCharArray()); check(false, "damaged accepted"); } catch (SecurityException e) { check(true, "damaged file rejected"); }
    try { SessionCrypto.decrypt("not a session file at all, definitely not one".getBytes(), "x".toCharArray()); check(false, "garbage accepted"); } catch (IllegalArgumentException e) { check(true, "garbage rejected"); }
    check(!Arrays.equals(enc, SessionCrypto.encrypt(plain, "correct horse battery".toCharArray())), "fresh salt and iv per file");
    Map<String,byte[]> evil = new LinkedHashMap<>(); evil.put("../x", new byte[]{1});
    try { SessionCrypto.unpack(SessionCrypto.pack(evil)); check(false, "path traversal accepted"); } catch (IllegalArgumentException e) { check(true, "path traversal rejected"); }
    byte[] k1 = SessionCrypto.pbkdf2("password".getBytes(), "salt".getBytes(), 1);
    StringBuilder sb = new StringBuilder(); for (byte b : k1) sb.append(String.format("%02x", b));
    check(sb.toString().equals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"), "PBKDF2-HMAC-SHA256 known answer");
    System.out.println("SessionCryptoTest OK: " + checks + " checks");
  }
}
''')
with tempfile.TemporaryDirectory() as d:
    open(os.path.join(d, 'SessionCryptoTest.java'), 'w').write(test)
    out = os.path.join(d, 'out'); os.makedirs(out)
    subprocess.check_call(['javac', '-d', out, src, os.path.join(d, 'SessionCryptoTest.java')])
    r = subprocess.run(['java', '-cp', out, 'SessionCryptoTest'], capture_output=True, text=True)
    sys.stdout.write(r.stdout[-400:]); sys.stderr.write(r.stderr[-800:] if r.returncode else '')
    sys.exit(r.returncode)
