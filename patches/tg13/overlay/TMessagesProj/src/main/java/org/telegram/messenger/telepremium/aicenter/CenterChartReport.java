package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/** A chart, including fully observed zero activity, can become a first-class report. */
public final class CenterChartReport {
  public static CenterReports.Report build(
      CenterStats stats,
      List<CenterStats.Group> selected,
      int mode,
      List<CenterData.Source> sources,
      CenterExport.Labels labels)
      throws Exception {
    CenterReports.Report result = new CenterReports.Report();
    result.localReport = true;
    result.question = labels.get("activity");
    result.from = stats.from;
    result.to = stats.to;
    result.complete = true;
    result.statistics = stats.json();
    JSONArray groups = new JSONArray();
    Set<String> ids = new LinkedHashSet<>(), keys = new HashSet<>();
    Set<Long> authors = new HashSet<>();
    JSONArray all = result.statistics.optJSONArray("groups");
    for (CenterStats.Group group : selected) {
      ids.add(group.id);
      result.complete &= group.currentComplete;
      for (int n = 0; n < all.length(); n++)
        if (group.id.equals(all.optJSONObject(n).optString("id"))) groups.put(all.optJSONObject(n));
      CenterReports.Claim claim = new CenterReports.Claim();
      claim.title = group.title;
      claim.text = labels.get("messages") + ": " + group.messages;
      for (CenterData.Message message : group.evidence)
        if (keys.add(message.qualified())) {
          result.dataset.add(message);
          authors.add(message.author);
          if (claim.evidence.size() < 5) {
            String quote = CenterReports.excerpt(message.text, 160);
            CenterReports.Evidence proof = new CenterReports.Evidence(message, quote, 0, 0);
            proof.localMetadata = quote.isEmpty();
            claim.evidence.add(proof);
          }
        }
      if (group.messages > 0) result.claims.add(claim);
    }
    result.indexed = result.analyzed = result.dataset.size();
    result.statistics.put("groups", groups).put("chartModes", new JSONArray().put(mode));
    JSONArray subset = new JSONArray(), allAuthors = result.statistics.optJSONArray("authors");
    for (int n = 0; allAuthors != null && n < allAuthors.length(); n++)
      if (authors.contains(allAuthors.optJSONObject(n).optLong("id")))
        subset.put(allAuthors.optJSONObject(n));
    result.statistics.put("authors", subset);
    Set<String> dependencies = new HashSet<>();
    for (CenterData.Source source : sources)
      if (ids.contains(source.canonical)) {
        result.sourceKeys.put(source.key());
        if (dependencies.add(source.account + ":" + source.owner))
          result.accountDependencies.put(
              new JSONObject().put("account", source.account).put("owner", source.owner));
        if (result.storageAccount < 0 && source.owner > 0) {
          result.storageAccount = source.account;
          result.storageOwner = source.owner;
        }
      }
    return result;
  }
}
