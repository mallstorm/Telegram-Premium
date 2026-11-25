package org.telegram.messenger.telepremium.aicenter;

import android.util.Base64;
import java.util.*;
import org.json.*;

/** Explicit archival copy of evidence images; independent of the expiring preview cache. */
public final class CenterAssets {
  private CenterAssets() {}

  public static void save(CenterReports.Report report, String kind, CenterExport.Options options)
      throws Exception {
    List<CenterData.Message> data = new ArrayList<>(report.dataset);
    if (data.isEmpty())
      for (CenterReports.Claim claim : report.claims)
        for (CenterReports.Evidence e : claim.evidence) data.add(e.message);
    if (data.isEmpty()) throw new IllegalStateException(CenterText.get("empty"));
    if (report.statistics == null) {
      CenterData.Scope scope = new CenterData.Scope();
      scope.from = report.from;
      scope.to = report.to;
      Map<String, CenterData.Source> sources = new LinkedHashMap<>();
      for (CenterData.Message m : data) {
        CenterData.Source source = new CenterData.Source();
        source.account = m.account;
        source.owner = m.owner;
        source.dialog = m.dialog;
        source.topic = m.topic;
        source.canonical = m.source;
        source.title = m.sourceTitle;
        source.type = m.dialog == 0 ? "device" : "group";
        sources.put(source.key(), source);
        scope.sources.add(source.key());
      }
      report.statistics =
          CenterStats.compute(data, new ArrayList<>(sources.values()), scope, TimeZone.getDefault())
              .json();
    }
    int account = data.get(0).account;
    long owner = data.get(0).owner;
    report.storageAccount = account;
    report.storageOwner = owner;
    report.transcriptions.clear();
    for (CenterData.Message m : data) if (!m.transcript.isEmpty()) report.transcriptions.add(m);
    JSONObject saved =
        CenterReports.archive(report)
            .put("storageAccount", account)
            .put("storageOwner", owner)
            .put(
                "accounts",
                (report.accountDependencies.length() > 0
                    ? report.accountDependencies
                    : CenterAi.accounts(data)))
            .put("cloud", data.stream().anyMatch(m -> !m.cloudNode.isEmpty()))
            .put("options", options.json());
    CenterStore.get()
        .transaction(
            () -> {
              CenterStore.get().put(account, owner, kind, report.id, saved);
              if (kind.equals("report") && options.images) {
                Set<String> seen = new HashSet<>();
                for (CenterReports.Claim claim : report.claims)
                  for (CenterReports.Evidence e : claim.evidence) {
                    String id = imageId(report, e);
                    if (!seen.add(id)) continue;
                    JSONObject preview = preview(e);
                    if (preview != null)
                      CenterStore.get()
                          .put(
                              account,
                              owner,
                              "report_image",
                              id,
                              new JSONObject()
                                  .put("id", id)
                                  .put("report", report.id)
                                  .put("jpeg", preview.optString("jpeg"))
                                  .put(
                                      "accounts",
                                      (report.accountDependencies.length() > 0
                                          ? report.accountDependencies
                                          : CenterAi.accounts(data)))
                                  .put("cloud", !e.message.cloudNode.isEmpty()));
                  }
              }
            });
  }

  private static String imageId(CenterReports.Report r, CenterReports.Evidence e) {
    return r.id + ":" + e.message.qualified() + ":" + e.timeMs + ":" + e.page;
  }

  public static JSONObject preview(CenterReports.Evidence e) throws Exception {
    CenterData.Message m = e.message;
    long time = e.timeMs;
    int page = e.page;
    if (page == 0 && m.pages > 0) page = 1;
    if (page == 0 && !m.frameTimes.isEmpty() && !m.frameTimes.contains(time)) {
      long best = m.frameTimes.get(0);
      for (long stamp : m.frameTimes)
        if (Math.abs(stamp - time) < Math.abs(best - time)) best = stamp;
      time = best;
    }
    return CenterStore.get()
        .get(
            m.account,
            m.owner,
            "preview",
            (m.previewBase.isEmpty() ? m.qualified() : m.previewBase) + ":" + time + ":" + page);
  }

  public static byte[] image(CenterReports.Report report, CenterReports.Evidence e)
      throws Exception {
    if (!CenterMedia.readableDerivative(e.message)) return null;
    JSONObject value = null;
    if (report.storageAccount >= 0)
      value =
          CenterStore.get()
              .get(report.storageAccount, report.storageOwner, "report_image", imageId(report, e));
    if (value == null) value = preview(e);
    return value == null ? null : Base64.decode(value.optString("jpeg"), Base64.NO_WRAP);
  }
}
