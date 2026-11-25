package org.telegram.messenger.telepremium.aicenter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stable authenticated row envelope; address is bound into AAD to reject swapped accounts/rows. */
public final class CenterEnvelope {
  private CenterEnvelope() {}

  public static byte[] seal(SecretKey key, byte[] address, byte[] plain) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key);
    c.updateAAD(address);
    byte[] ct = c.doFinal(plain);
    byte[] result = new byte[12 + ct.length];
    System.arraycopy(c.getIV(), 0, result, 0, 12);
    System.arraycopy(ct, 0, result, 12, ct.length);
    return result;
  }

  public static byte[] open(SecretKey key, byte[] address, byte[] sealed) throws Exception {
    if (sealed == null || sealed.length < 28)
      throw new IllegalArgumentException("Truncated encrypted record");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
    c.updateAAD(address);
    return c.doFinal(sealed, 12, sealed.length - 12);
  }
}
