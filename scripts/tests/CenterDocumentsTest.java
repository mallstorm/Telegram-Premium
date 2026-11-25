import java.nio.charset.*;
import org.telegram.messenger.telepremium.aicenter.*;

public class CenterDocumentsTest {
  static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); }
  public static void main(String[] args) {
    String rtf = "{\\rtf1\\ansi\\ansicpg1251{\\fonttbl secret font;}\\uc1\\u1055?\\u1088?\\u1080?\\u1074?\\u1077?\\u1090?\\par visible {\\pict hidden payload}{\\*\\unknown hidden} \\'cc\\'e8\\'f0 \\{escaped\\}}";
    String text = CenterDocuments.rtf(rtf.getBytes(StandardCharsets.ISO_8859_1));
    check(text.contains("Привет\nvisible"), "RTF Unicode and fallback control");
    check(text.contains("Мир") && text.contains("{escaped}"), "RTF codepage and escaped braces");
    check(!text.contains("secret") && !text.contains("hidden") && !text.contains("payload"), "destinations excluded from indexed text");
    check(CenterDocuments.rtf("{\\rtf1\\uc0\\u-10179\\u-8704}".getBytes(StandardCharsets.US_ASCII)).equals("😀"), "signed UTF-16 surrogate pairs");
    check(CenterDocuments.text(new byte[]{(byte)255,(byte)254,65,0},"text/plain").equals("A"), "UTF-16 text BOM");
    check(!CenterDocuments.supported("application/vnd.android.package-archive") && !CenterDocuments.supported("application/zip"), "APK and archive are statistics only");
    check(CenterDocuments.supported("application/pdf") && CenterDocuments.supported("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), "supported document formats");
    System.out.println("CenterDocumentsTest OK: Cyrillic RTF, Unicode, hidden attachments, BOM and executable/archive exclusion");
  }
}
