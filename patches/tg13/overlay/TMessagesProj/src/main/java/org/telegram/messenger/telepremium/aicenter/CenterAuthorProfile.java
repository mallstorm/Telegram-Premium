package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/**
 * Automatic publication profile: every statement is a local aggregate with original-message
 * evidence.
 */
public final class CenterAuthorProfile {
  private CenterAuthorProfile() {}

  public static boolean baselineComplete(CenterStats.Author author, CenterStats stats) {
    if (stats.groups.isEmpty()) return false;
    for (CenterStats.Group group : stats.groups) if (!group.previousComplete) return false;
    return true;
  }

  public static List<String> topics(CenterStats.Author author) {
    List<String> result = new ArrayList<>(author.topics.keySet());
    result.sort(
        Comparator.<String>comparingInt(k -> author.topics.get(k))
            .reversed()
            .thenComparing(k -> k));
    return result;
  }

  public static boolean newTopic(CenterStats.Author author, CenterStats stats, String topic) {
    return baselineComplete(author, stats)
        && author.topics.getOrDefault(topic, 0) > 0
        && author.previousTopics.getOrDefault(topic, 0) == 0;
  }

  public static CenterReports.Report build(
      CenterStats.Author author, CenterStats stats, CenterExport.Labels labels) throws Exception {
    CenterReports.Report report = new CenterReports.Report();
    report.localReport = true;
    report.question = author.name;
    report.from = stats.from;
    report.to = stats.to;
    report.indexed = report.analyzed = author.count;
    report.complete = true;
    for (CenterStats.Group group : stats.groups)
      if (!group.currentComplete) report.complete = false;
    report.dataset.addAll(author.publications);
    report.dataset.addAll(author.previousPublications);
    List<String> topics = topics(author);
    String topicText = String.join(", ", topics.subList(0, Math.min(3, topics.size())));
    CenterReports.Claim overview = new CenterReports.Claim();
    overview.title = labels.get("authorOverview");
    overview.text =
        String.format(
            Locale.getDefault(),
            labels.get("authorOverviewText"),
            author.name,
            author.count,
            author.media,
            author.links,
            author.groups.size(),
            author.count == 0 ? 0 : author.characters / author.count,
            topicText.isEmpty() ? labels.get("emptyTopics") : topicText);
    attach(overview, author.publications, stats.includePersonal);
    report.claims.add(overview);
    boolean baseline = baselineComplete(author, stats);
    CenterReports.Claim dynamics = new CenterReports.Claim();
    dynamics.title = labels.get("growth");
    dynamics.text =
        baseline
            ? String.format(
                Locale.getDefault(),
                labels.get("authorChangeText"),
                author.count,
                author.previous,
                author.count - author.previous)
            : labels.get("baselineMissing");
    attach(dynamics, author.publications, stats.includePersonal);
    attach(dynamics, author.previousPublications, stats.includePersonal);
    report.claims.add(dynamics);
    for (CenterStats.Group group : stats.groups)
      if (author.groups.contains(group.id)) {
        List<CenterData.Message> own = new ArrayList<>(), peers = new ArrayList<>();
        Set<Long> others = new HashSet<>();
        int peerMedia = 0;
        for (CenterData.Message message : stats.peerMessages)
          if (group.id.equals(message.source) && !message.source.startsWith("p:")) {
            if (message.author == author.id) own.add(message);
            else {
              peers.add(message);
              if (message.author != 0) others.add(message.author);
              if (!message.kind.equals("text") && !message.kind.equals("link")) peerMedia++;
            }
          }
        CenterReports.Claim comparison = new CenterReports.Claim();
        comparison.title = group.title;
        int total = own.size() + peers.size();
        comparison.text =
            String.format(
                Locale.getDefault(),
                labels.get("authorPeerText"),
                own.size(),
                total,
                total == 0 ? 0 : 100.0 * own.size() / total,
                others.isEmpty() ? 0 : peers.size() / (double) others.size(),
                peers.isEmpty() ? 0 : 100.0 * peerMedia / peers.size());
        attach(comparison, own, stats.includePersonal);
        attach(comparison, peers, stats.includePersonal);
        report.claims.add(comparison);
      }
    for (String topic : topics.subList(0, Math.min(30, topics.size()))) {
      CenterReports.Claim claim = new CenterReports.Claim();
      claim.title =
          (newTopic(author, stats, topic) ? labels.get("newTopic") : labels.get("topic"))
              + ": "
              + topic;
      claim.text =
          labels.get("messages")
              + ": "
              + author.topics.get(topic)
              + (baseline
                  ? " · "
                      + labels.get("previousPeriod")
                      + ": "
                      + author.previousTopics.getOrDefault(topic, 0)
                  : " · " + labels.get("baselineMissing"));
      List<CenterData.Message> examples = new ArrayList<>();
      for (CenterData.Message message : author.publications)
        if (CenterTopics.words(message.text).contains(topic)) examples.add(message);
      attach(claim, examples, stats.includePersonal);
      report.claims.add(claim);
    }
    Set<String> accounts = new HashSet<>();
    for (CenterData.Message message : stats.peerMessages)
      if (author.groups.contains(message.source)
          && accounts.add(message.account + ":" + message.owner))
        report.accountDependencies.put(
            new JSONObject().put("account", message.account).put("owner", message.owner));
    for (CenterData.Message message : author.previousPublications)
      if (accounts.add(message.account + ":" + message.owner))
        report.accountDependencies.put(
            new JSONObject().put("account", message.account).put("owner", message.owner));
    report.statistics = stats.json();
    JSONArray only = new JSONArray();
    JSONArray all = report.statistics.optJSONArray("authors");
    for (int i = 0; all != null && i < all.length(); i++)
      if (all.optJSONObject(i).optLong("id") == author.id) only.put(all.optJSONObject(i));
    report.statistics.put("authors", only);
    JSONArray selectedGroups = new JSONArray();
    JSONArray groups = report.statistics.optJSONArray("groups");
    for (int i = 0; groups != null && i < groups.length(); i++)
      if (author.groups.contains(groups.optJSONObject(i).optString("id")))
        selectedGroups.put(groups.optJSONObject(i));
    report.statistics.put("groups", selectedGroups);
    if (!baseline) report.unclear.add(labels.get("baselineMissing"));
    return report;
  }

  private static void attach(
      CenterReports.Claim claim, List<CenterData.Message> messages, boolean personal) {
    Set<String> keys = new HashSet<>();
    for (CenterReports.Evidence evidence : claim.evidence) keys.add(evidence.message.qualified());
    Set<String> groups = new HashSet<>();
    for (CenterData.Message message : messages) {
      if (claim.evidence.size() >= 30) break;
      if ((message.personal && !personal)
          || message.deleted
          || message.protectedMedia
          || message.source.startsWith("p:")
          || !keys.add(message.qualified())) continue;
      if (!groups.add(message.source) && claim.evidence.size() >= 5) continue;
      String text =
          message.text.isEmpty()
              ? (message.ocr.isEmpty() ? message.transcript : message.ocr)
              : message.text;
      String quote = CenterReports.excerpt(text, 160);
      CenterReports.Evidence evidence = new CenterReports.Evidence(message, quote, 0, 0);
      evidence.localMetadata = quote.isEmpty();
      claim.evidence.add(evidence);
    }
    claim.certainty = claim.evidence.size() > 1 ? "multiple" : "single";
  }
}
