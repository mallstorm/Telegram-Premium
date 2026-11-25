package org.telegram.messenger.telepremium.aicenter;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Metadata already supplied by Telegram; never fetches a web page. */
public final class CenterLinks {
  private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>\"']+");

  public static final class Link {
    public String url, title = "", domain;
    public CenterData.Message first;
    public final List<CenterData.Message> evidence = new ArrayList<>();
  }

  public static Map<String, String> in(CenterData.Message message) {
    Map<String, String> result = new LinkedHashMap<>();
    Matcher matcher = URL.matcher(message.text + "\n" + message.content);
    String[] metadata = message.content.split("\n", 3);
    while (matcher.find()) {
      String raw = matcher.group().replaceAll("[.,;!?]+$", "");
      String normalized = normalize(raw);
      if (normalized.isEmpty()) continue;
      String title =
          metadata.length > 1 && normalize(metadata[0]).equals(normalized) ? metadata[1] : "";
      result.merge(normalized, title, (old, next) -> old.isEmpty() ? next : old);
    }
    return result;
  }

  public static String normalize(String raw) {
    try {
      URI uri = new URI(raw);
      String host = uri.getHost();
      if (host == null
          || (!"http".equalsIgnoreCase(uri.getScheme())
              && !"https".equalsIgnoreCase(uri.getScheme()))
          || uri.getUserInfo() != null) return "";
      String path = uri.getRawPath();
      return uri.getScheme().toLowerCase(Locale.ROOT)
          + "://"
          + host.toLowerCase(Locale.ROOT)
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
          + (path == null || path.isEmpty() ? "/" : path)
          + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    } catch (Exception invalid) {
      return "";
    }
  }

  public static Map<String, Link> collect(List<CenterData.Message> messages) {
    Map<String, Link> result = new TreeMap<>();
    for (CenterData.Message message : messages)
      for (Map.Entry<String, String> reference : in(message).entrySet()) {
        Link link = result.get(reference.getKey());
        if (link == null) {
          link = new Link();
          link.url = reference.getKey();
          link.domain = URI.create(link.url).getHost();
          result.put(link.url, link);
        }
        if (link.title.isEmpty()) link.title = reference.getValue();
        link.evidence.add(message);
        if (link.first == null
            || message.date < link.first.date
            || message.date == link.first.date && message.id < link.first.id) link.first = message;
      }
    return result;
  }
}
