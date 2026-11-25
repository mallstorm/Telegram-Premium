package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import java.util.regex.*;

/** Neutral local topic tokens; no domain-specific assumptions or personality inference. */
public final class CenterTopics {
  private static final Pattern WORD =
      Pattern.compile("(?iu)#[\\p{L}\\p{N}_]+|\\b[\\p{L}][\\p{L}\\p{N}_-]{4,}\\b");
  private static final Set<String> STOP =
      new HashSet<>(
          Arrays.asList(
              ("который которая которые этого потому когда только очень можно нужно будет будут"
                   + " чтобы также после перед через сегодня сейчас просто вообще такой такая такие"
                   + " своем своей своих здесь there their these those which would could should"
                   + " about being because where after before https http message messages telegram")
                  .split(" ")));

  public static Set<String> words(String text) {
    Set<String> out = new LinkedHashSet<>();
    Matcher matcher = WORD.matcher(text);
    while (matcher.find()) {
      String word = matcher.group().toLowerCase(Locale.ROOT);
      if (!STOP.contains(word)) out.add(word);
    }
    return out;
  }

  public static String category(CenterData.Message message) {
    String text =
        (message.ocr + " " + message.text + " " + message.description).toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*(чек|итого|касса|оплат|receipt|invoice|total|payment).*"))
      return "receiptImage";
    if (text.matches("(?s).*(переписк|сообщени[яе] в чат|chat message|conversation|messenger).*"))
      return "chatImage";
    if (text.matches("(?s).*(мем|шутк|meme|joke).*")) return "memeImage";
    if (text.matches("(?s).*(товар|каталог|артикул|product|catalog|item for sale).*"))
      return "productImage";
    if (text.matches("(?s).*(люди|человек|прохож|people|person|portrait).*")) return "peopleImage";
    if (text.matches("(?s).*(график|свеч|котиров|скриншот|chart|trading|screenshot).*"))
      return "chartImage";
    return "photoImage";
  }
}
