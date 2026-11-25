import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.telepremium.aicenter.CenterInputFiles;

public final class CenterInputFilesTest {
  private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args)throws Exception {
    Path directory=Files.createTempDirectory("center-input-test");
    Path source=directory.resolve("existing-cloud-cache.bin");byte[] data=new byte[80000];new Random(5).nextBytes(data);Files.write(source,data);
    try {
      File first=CenterInputFiles.copy(new FileInputStream(source.toFile()),directory.toFile(),data.length,new AtomicBoolean());
      File second=CenterInputFiles.copy(new FileInputStream(source.toFile()),directory.toFile(),data.length,new AtomicBoolean());
      check(!first.equals(second)&&!first.toPath().equals(source),"each job owns its working copy");
      check(Arrays.equals(data,Files.readAllBytes(first.toPath())),"copy content");first.delete();second.delete();
      check(Arrays.equals(data,Files.readAllBytes(source)),"cleanup must preserve Cloud/SAF original");
      try{CenterInputFiles.copy(new FileInputStream(source.toFile()),directory.toFile(),1024,new AtomicBoolean());throw new AssertionError("size limit bypass");}catch(IOException expected){}
      try{CenterInputFiles.copy(new FileInputStream(source.toFile()),directory.toFile(),data.length,new AtomicBoolean(true));throw new AssertionError("cancel bypass");}catch(IOException expected){}
      try(java.util.stream.Stream<Path> entries=Files.list(directory)){check(entries.count()==1,"no partial working copies after failure/cancellation");}
      check(Arrays.equals(data,Files.readAllBytes(source)),"failure must preserve original");
    }finally{Files.deleteIfExists(source);Files.deleteIfExists(directory);}
    System.out.println("CenterInputFilesTest OK: isolated copies, original preservation, size limit and cancellation cleanup");
  }
}
