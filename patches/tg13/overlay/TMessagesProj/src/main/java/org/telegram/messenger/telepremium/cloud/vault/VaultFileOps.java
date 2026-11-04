package org.telegram.messenger.telepremium.cloud.vault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Files are never exported until the entire authenticated stream is verified. */
public final class VaultFileOps {
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final class UploadResult {
        public final long originalSize;
        public final String originalSha256;
        public final long encryptedSize;
        private UploadResult(long originalSize, String originalSha256, long encryptedSize) {
            this.originalSize=originalSize; this.originalSha256=originalSha256; this.encryptedSize=encryptedSize;
        }
    }
    private VaultFileOps() { }
    public static UploadResult encryptToFile(InputStream plain, File encrypted, byte[] master) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        CounterInputStream in=new CounterInputStream(plain,sha);
        try(FileOutputStream out=new FileOutputStream(encrypted)) {
            TelePremiumVaultCrypto.encrypt(in,out,master,TelePremiumVaultCrypto.Purpose.FILE);
            out.getFD().sync();
        } catch(Exception e) { encrypted.delete(); throw e; }
        return new UploadResult(in.count,hex(sha.digest()),encrypted.length());
    }

    /** Decrypt in a private directory; discard partial plaintext on any error. */
    public static File decryptToVerifiedFile(File ciphertext, File dir, String safeName,
                                             byte[] master, String expectedSha256, long expectedSize) throws Exception {
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create Vault cache");
        if(expectedSha256==null || expectedSha256.length()!=64) throw new SecurityException("Missing plaintext hash");
        String name = (safeName == null ? "file" : safeName.replaceAll("[^a-zA-Z0-9._-]","_"));
        if(name.isEmpty() || name.equals(".") || name.equals("..")) name="file";
        File target=new File(dir,hexRandom(12)+"_"+name);
        File pending=new File(dir, target.getName()+".partial");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long actualSize=0;
        try(FileInputStream in=new FileInputStream(ciphertext);
            FileOutputStream out=new FileOutputStream(pending)) {
            java.io.OutputStream checked = new java.io.FilterOutputStream(out) {
                @Override public void write(int b)throws java.io.IOException{digest.update((byte)b);out.write(b);}
                @Override public void write(byte[] b,int off,int len)throws java.io.IOException{digest.update(b,off,len);out.write(b,off,len);}
            };
            TelePremiumVaultCrypto.decrypt(in,checked,master,TelePremiumVaultCrypto.Purpose.FILE);
            out.getFD().sync();
        } catch(Exception e) { pending.delete(); throw e; }
        try {
            actualSize=pending.length();
            if(!MessageDigest.isEqual(expectedSha256.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                   hex(digest.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)) || actualSize!=expectedSize)
                throw new SecurityException("Vault file integrity mismatch");
            if(!pending.renameTo(target)) throw new IllegalStateException("Cannot publish authenticated Vault file");
            return target;
        } finally {pending.delete();}
    }

    public static String randomFileName(String prefix) {return prefix+hexRandom(16)+".tpv";}
    private static String hexRandom(int n){byte[] x=new byte[n];RANDOM.nextBytes(x);try{return hex(x);}finally{Arrays.fill(x,(byte)0);}}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder(bytes.length*2);for(byte x:bytes)b.append(Character.forDigit((x>>>4)&15,16)).append(Character.forDigit(x&15,16));return b.toString();}
    private static final class CounterInputStream extends InputStream {
        final InputStream delegate;final MessageDigest digest;long count;
        CounterInputStream(InputStream delegate,MessageDigest digest){this.delegate=delegate;this.digest=digest;}
        @Override public int read()throws java.io.IOException {int v=delegate.read();if(v>=0){digest.update((byte)v);count++;}return v;}
        @Override public int read(byte[] b,int off,int len)throws java.io.IOException {int n=delegate.read(b,off,len);if(n>0){digest.update(b,off,n);count+=n;}return n;}
        @Override public void close() throws java.io.IOException {delegate.close();}
    }
}
