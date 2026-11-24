import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import org.telegram.messenger.telepremium.aicenter.CenterEnvelope;
public class CenterEnvelopeTest {
 public static void main(String[]args)throws Exception{
  byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);SecretKeySpec k=new SecretKeySpec(key,"AES");
  byte[] address="AIC1|0|123|message|abc".getBytes("UTF-8"),plain="private quotation".getBytes("UTF-8");
  byte[] enc=CenterEnvelope.seal(k,address,plain);
  if(!Arrays.equals(plain,CenterEnvelope.open(k,address,enc)))throw new AssertionError("roundtrip");
  if(Arrays.equals(enc,CenterEnvelope.seal(k,address,plain)))throw new AssertionError("nonce reused");
  reject(()->CenterEnvelope.open(k,"AIC1|1|123|message|abc".getBytes("UTF-8"),enc));
  reject(()->CenterEnvelope.open(k,"AIC1|0|123|report|abc".getBytes("UTF-8"),enc));
  reject(()->CenterEnvelope.open(k,address,new byte[11]));
  enc[15]^=1;reject(()->CenterEnvelope.open(k,address,enc));
  System.out.println("CenterEnvelopeTest OK: authentication, random nonce, tamper and account/row binding");
 }
 interface Job {void run()throws Exception;}
 static void reject(Job f)throws Exception{try{f.run();}catch(Exception e){return;}throw new AssertionError("unauthenticated plaintext accepted");}
}
