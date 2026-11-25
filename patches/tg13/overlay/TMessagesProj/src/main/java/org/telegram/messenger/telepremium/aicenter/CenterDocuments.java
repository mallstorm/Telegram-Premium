package org.telegram.messenger.telepremium.aicenter;

import java.nio.charset.*;
import java.util.*;

/** Text-only document handling. Attachments and RTF destinations are never executed. */
public final class CenterDocuments {
  private CenterDocuments() {}

  public static boolean supported(String mime) {
    return mime != null
        && (mime.equals("application/pdf")
            || mime.startsWith("text/")
            || mime.contains("wordprocessingml")
            || mime.contains("opendocument.text")
            || mime.equals("application/rtf"));
  }

  public static String text(byte[] bytes, String mime) {
    if (mime.equals("application/rtf") || mime.equals("text/rtf")) return rtf(bytes);
    int skip = 0;
    Charset charset = StandardCharsets.UTF_8;
    if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
      charset = StandardCharsets.UTF_16LE;
      skip = 2;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
      charset = StandardCharsets.UTF_16BE;
      skip = 2;
    } else if (bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf) skip = 3;
    return new String(bytes, skip, bytes.length - skip, charset);
  }

  private static final class State {
    boolean hidden;
    int fallback = 1;
    Charset charset = Charset.forName("windows-1252");

    State copy() {
      State s = new State();
      s.hidden = hidden;
      s.fallback = fallback;
      s.charset = charset;
      return s;
    }
  }

  public static String rtf(byte[] bytes) {
    String raw = new String(bytes, StandardCharsets.ISO_8859_1);
    StringBuilder out = new StringBuilder();
    Deque<State> stack = new ArrayDeque<>();
    State state = new State();
    int skip = 0;
    Set<String> destinations =
        new HashSet<>(
            Arrays.asList(
                "fonttbl",
                "colortbl",
                "stylesheet",
                "info",
                "pict",
                "object",
                "objdata",
                "filetbl",
                "datastore",
                "themedata",
                "xmlnstbl",
                "fldinst",
                "header",
                "footer"));
    for (int i = 0; i < raw.length(); ) {
      char c = raw.charAt(i++);
      if (c == '{') {
        stack.push(state);
        state = state.copy();
        continue;
      }
      if (c == '}') {
        if (!stack.isEmpty()) state = stack.pop();
        continue;
      }
      if (c == '\r' || c == '\n') continue;
      String literal = null;
      if (c != '\\')
        literal = c < 128 ? String.valueOf(c) : new String(new byte[] {(byte) c}, state.charset);
      else if (i < raw.length()) {
        char control = raw.charAt(i++);
        if (control == '\\' || control == '{' || control == '}') literal = String.valueOf(control);
        else if (control == '*') state.hidden = true;
        else if (control == '\'') {
          if (i + 2 <= raw.length()) {
            try {
              literal =
                  new String(
                      new byte[] {(byte) Integer.parseInt(raw.substring(i, i + 2), 16)},
                      state.charset);
            } catch (NumberFormatException ignored) {
            }
            i += 2;
          }
        } else if (Character.isLetter(control)) {
          int start = i - 1;
          while (i < raw.length() && Character.isLetter(raw.charAt(i))) i++;
          String word = raw.substring(start, i);
          int numberStart = i;
          if (i < raw.length() && raw.charAt(i) == '-') i++;
          while (i < raw.length() && Character.isDigit(raw.charAt(i))) i++;
          int value = 0;
          try {
            value = Integer.parseInt(raw.substring(numberStart, i));
          } catch (NumberFormatException ignored) {
          }
          if (i < raw.length() && raw.charAt(i) == ' ') i++;
          if (destinations.contains(word)) state.hidden = true;
          if (word.equals("ansicpg")) {
            try {
              state.charset = Charset.forName("windows-" + value);
            } catch (Exception ignored) {
            }
          }
          if (word.equals("uc")) state.fallback = Math.max(0, Math.min(16, value));
          if (word.equals("bin")) i = (int) Math.min(raw.length(), i + Math.max(0L, value));
          if (word.equals("u")) {
            if (!state.hidden) out.append((char) value);
            skip = state.fallback;
          }
          if (word.equals("par") || word.equals("line")) literal = "\n";
          if (word.equals("tab")) literal = "\t";
        } else if (control == '~') literal = "\u00a0";
        else if (control == '_') literal = "\u2011";
      }
      if (literal != null) {
        if (skip > 0) skip--;
        else if (!state.hidden) out.append(literal);
      }
    }
    return out.toString().trim();
  }
}
