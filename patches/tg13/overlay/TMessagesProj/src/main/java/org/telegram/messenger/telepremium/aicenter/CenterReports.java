package org.telegram.messenger.telepremium.aicenter;
import java.util.*;
public final class CenterReports {
 public static boolean personal(String s){String t=s.toLowerCase(Locale.ROOT);return t.matches("(?s).*(соболезну|сочувствую|умерла? |похорон|condolence|rest in peace|my condolences).*");}
}
