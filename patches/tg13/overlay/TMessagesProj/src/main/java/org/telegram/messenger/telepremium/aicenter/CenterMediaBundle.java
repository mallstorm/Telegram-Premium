package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/**
 * Caption, indexed discussion and visual observations share one exact, account-qualified evidence
 * set.
 */
public final class CenterMediaBundle {
  private CenterMediaBundle() {}

  public static List<CenterData.Message> discussion(
      Collection<CenterData.Message> index,
      Collection<CenterData.Source> sources,
      CenterData.Scope scope,
      CenterData.Message anchor) {
    if (anchor.dialog == 0 || anchor.protectedMedia || anchor.deleted) return new ArrayList<>();
    boolean requested = false;
    for (CenterData.Source source : sources)
      if (source.account == anchor.account
          && source.dialog == anchor.dialog
          && (source.topic == 0 || source.topic == anchor.topic)
          && scope.requests(source)) requested = true;
    if (!requested) return new ArrayList<>();
    List<CenterData.Message> same = new ArrayList<>();
    for (CenterData.Message message : index)
      if (message.account == anchor.account
          && message.owner == anchor.owner
          && message.dialog == anchor.dialog
          && (anchor.topic == 0 || message.topic == anchor.topic)
          && message.date >= scope.from
          && message.date <= scope.to
          && !message.deleted
          && !message.protectedMedia
          && (scope.includePersonal || !message.personal)
          && (!scope.excludeOwn || !message.own)
          && (!message.source.startsWith("p:") || scope.includePrivate)) same.add(message);
    if (same.stream().noneMatch(message -> message.id == anchor.id)) same.add(anchor);
    same.sort(Comparator.comparingInt(message -> message.id));
    int position = 0;
    for (int i = 0; i < same.size(); i++) if (same.get(i).id == anchor.id) position = i;
    List<CenterData.Message> result = new ArrayList<>();
    for (int i = Math.max(0, position - 5); i < Math.min(same.size(), position + 6); i++)
      if (same.get(i).id != anchor.id) result.add(same.get(i));
    return result;
  }

  public static JSONArray snapshot(List<CenterData.Message> discussion) throws JSONException {
    JSONArray result = new JSONArray();
    for (CenterData.Message message : discussion) {
      result.put(
          new JSONObject()
              .put("account", message.account)
              .put("owner", message.owner)
              .put("dialog", message.dialog)
              .put("topic", message.topic)
              .put("id", message.id)
              .put("date", message.date)
              .put("source", message.source)
              .put("sourceTitle", message.sourceTitle)
              .put("accountName", message.accountName)
              .put("author", message.author)
              .put("authorName", message.authorName)
              .put("kind", message.kind)
              .put("own", message.own)
              .put("personal", message.personal)
              .put("text", CenterReports.excerpt(message.text, 6000))
              .put("excerpt", message.text.length() > 6000));
    }
    return result;
  }

  public static List<CenterData.Message> restore(JSONArray rows) {
    List<CenterData.Message> result = new ArrayList<>();
    for (int i = 0; rows != null && i < rows.length(); i++)
      if (rows.optJSONObject(i) != null)
        result.add(CenterData.Message.parse(rows.optJSONObject(i)));
    return result;
  }

  public static String payload(
      CenterData.Message anchor, List<CenterData.Message> discussion, String frames)
      throws JSONException {
    JSONArray context = new JSONArray();
    for (CenterData.Message message : discussion)
      context.put(
          new JSONObject()
              .put("account", message.account)
              .put("chat", message.dialog)
              .put("message", message.id)
              .put("text", message.text)
              .put("author", message.authorName));
    return new JSONObject()
        .put(
            "anchor",
            new JSONObject()
                .put("account", anchor.account)
                .put("chat", anchor.dialog)
                .put("message", anchor.id)
                .put("caption", anchor.text)
                .put("ocr", anchor.ocr)
                .put("transcript", anchor.transcript))
        .put("discussion", context)
        .put("visualCoordinates", frames)
        .toString();
  }

  public static String render(CenterReports.Report report, CenterExport.Labels labels) {
    StringBuilder result = new StringBuilder();
    for (CenterReports.Claim claim : report.claims) {
      if (!claim.verified()) continue;
      result.append(claim.title).append('\n').append(claim.text).append('\n');
      for (CenterReports.Evidence evidence : claim.evidence) {
        if (evidence.timeMs > 0) result.append(CenterTranscript.stamp(evidence.timeMs)).append(' ');
        if (evidence.page > 0)
          result.append(labels.get("page")).append(' ').append(evidence.page).append(' ');
        if (!evidence.quote.isEmpty()) result.append('“').append(evidence.quote).append('”');
        result.append('\n');
      }
    }
    return CenterReports.clean(result.toString());
  }
}
