package org.telegram.messenger.telepremium.aicenter;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.telegram.messenger.*;

/**
 * Device-local, explicitly configured digests. Archives privately; never posts into Telegram chats.
 */
public final class CenterDigests {
  private static volatile CenterSync activeSync;
  private static volatile AtomicBoolean activeToken;

  public static void cancelWork() {
    AtomicBoolean token = activeToken;
    if (token != null) token.set(true);
    CenterSync sync = activeSync;
    activeSync = null;
    if (sync != null) sync.cancel();
  }

  public static final int JOB = 72582;

  private CenterDigests() {}

  public static synchronized void add(
      String name, String query, CenterData.Scope scope, int days, int hour, int minute)
      throws Exception {
    JSONObject schedules = CenterIndex.settings().optJSONObject("schedules");
    if (schedules == null) schedules = new JSONObject();
    String id = UUID.randomUUID().toString();
    JSONArray owners = new JSONArray();
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated() && !scope.excluded.contains(a))
        owners.put(
            new JSONObject()
                .put("account", a)
                .put("owner", UserConfig.getInstance(a).getClientUserId()));
    JSONObject job =
        new JSONObject()
            .put("id", id)
            .put("name", name)
            .put("query", query)
            .put("scope", scope.json())
            .put("owners", owners)
            .put("rolling", !scope.sinceRead)
            .put("span", Math.max(86400, scope.to - scope.from))
            .put("days", days)
            .put("hour", hour)
            .put("minute", minute)
            .put("next", next(days, hour, minute, TimeZone.getDefault().getID()))
            .put("zone", TimeZone.getDefault().getID())
            .put("last", 0)
            .put("enabled", true);
    schedules.put(id, job);
    CenterIndex.setting("schedules", schedules);
    schedule();
  }

  private static long next(int days, int hour, int minute, String zone) {
    Calendar clock = Calendar.getInstance(TimeZone.getTimeZone(zone));
    clock.set(Calendar.HOUR_OF_DAY, hour);
    clock.set(Calendar.MINUTE, minute);
    clock.set(Calendar.SECOND, 0);
    clock.set(Calendar.MILLISECOND, 0);
    if (clock.getTimeInMillis() <= System.currentTimeMillis())
      clock.add(Calendar.DAY_OF_YEAR, days);
    return clock.getTimeInMillis();
  }

  public static synchronized void update(JSONObject job) throws Exception {
    JSONObject schedules = CenterIndex.settings().optJSONObject("schedules");
    if (schedules == null) schedules = new JSONObject();
    if (job.optBoolean("deleted")) {
      schedules.remove(job.optString("id"));
    } else schedules.put(job.optString("id"), job);
    CenterIndex.setting("schedules", schedules);
  }

  public static void schedule() {
    try {
      JSONObject schedules = CenterIndex.settings().optJSONObject("schedules");
      long due = Long.MAX_VALUE;
      if (schedules != null) {
        Iterator<String> keys = schedules.keys();
        while (keys.hasNext()) {
          JSONObject s = schedules.optJSONObject(keys.next());
          if (s != null && s.optBoolean("enabled"))
            due = Math.min(due, s.optLong("next", Long.MAX_VALUE));
        }
      }
      JobScheduler scheduler =
          (JobScheduler)
              ApplicationLoader.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (due == Long.MAX_VALUE) {
        scheduler.cancel(JOB);
        return;
      }
      JobInfo.Builder b =
          new JobInfo.Builder(
                  JOB,
                  new ComponentName(ApplicationLoader.applicationContext, CenterDigestJob.class))
              .setPersisted(true)
              .setMinimumLatency(Math.max(1000, due - System.currentTimeMillis()))
              .setRequiredNetworkType(
                  CenterIndex.settings().optBoolean("wifi", true)
                      ? JobInfo.NETWORK_TYPE_UNMETERED
                      : JobInfo.NETWORK_TYPE_ANY)
              .setRequiresCharging(CenterIndex.settings().optBoolean("charging", false));
      scheduler.schedule(b.build());
    } catch (Exception e) {
      FileLog.e(e);
    }
  }

  public static void runDue(AtomicBoolean cancelled, Runnable done) {
    activeToken = cancelled;
    JSONObject schedules = CenterIndex.settings().optJSONObject("schedules");
    List<JSONObject> due = new ArrayList<>();
    if (schedules != null) {
      Iterator<String> keys = schedules.keys();
      while (keys.hasNext()) {
        JSONObject s = schedules.optJSONObject(keys.next());
        if (s != null && s.optBoolean("enabled") && s.optLong("next") <= System.currentTimeMillis())
          due.add(s);
      }
    }
    runNext(due, 0, cancelled, done);
  }

  private static void runNext(List<JSONObject> due, int n, AtomicBoolean cancelled, Runnable done) {
    if (cancelled.get() || n >= due.size()) {
      done.run();
      return;
    }
    JSONObject job = due.get(n);
    CenterIndex index = new CenterIndex();
    index.load(
        error -> {
          if (cancelled.get()) {
            done.run();
            return;
          }
          try {
            JSONObject latest = CenterIndex.settings().optJSONObject("schedules");
            JSONObject entry = latest == null ? null : latest.optJSONObject(job.optString("id"));
            if (entry == null || !entry.optBoolean("enabled")) {
              runNext(due, n + 1, cancelled, done);
              return;
            }
            CenterData.Scope scope = CenterData.Scope.parse(job.getJSONObject("scope"));
            JSONArray owners = job.optJSONArray("owners");
            for (int i = 0; owners != null && i < owners.length(); i++) {
              JSONObject owner = owners.optJSONObject(i);
              int a = owner.optInt("account");
              if (!CenterIndex.accountAllowed(a, owner.optLong("owner"))) scope.excluded.add(a);
            }
            long now = System.currentTimeMillis() / 1000;
            scope.to = job.optLong("runTo") > 0 ? job.optLong("runTo") : now;
            scope.from =
                job.optLong("runFrom") > 0
                    ? job.optLong("runFrom")
                    : job.optBoolean("rolling", true)
                        ? scope.to - job.optLong("span", 86400)
                        : job.optLong("last") > 0
                            ? job.optLong("last") / 1000
                            : scope.to - job.optLong("span", 86400);
            scope.sinceRead = false;
            job.put("runTo", scope.to).put("runFrom", scope.from);
            update(job);
            index.scope = scope;
            CenterSync sync =
                new CenterSync(
                    index,
                    syncError -> {
                      if (cancelled.get()) {
                        done.run();
                        return;
                      }
                      index.scope = scope;
                      List<CenterData.Message> messages = index.selected();
                      new Thread(
                              () -> {
                                try {
                                  if (messages.isEmpty())
                                    throw new IllegalStateException(CenterText.get("empty"));
                                  CenterReports.Report report;
                                  if (CenterIndex.settings().optBoolean("localOnly", false)) {
                                    report =
                                        local(
                                            messages, index.sources, scope, job.optString("query"));
                                  } else
                                    report =
                                        CenterAi.run(
                                            messages,
                                            index.sources,
                                            scope,
                                            job.optString("query"),
                                            true,
                                            cancelled,
                                            (a, b) -> {});
                                  if (cancelled.get()) throw new InterruptedException();
                                  CenterAssets.save(report, "report", new CenterExport.Options());
                                  if (report.retryNeeded)
                                    throw new IllegalStateException(CenterText.get("partial"));
                                  job.put("last", scope.to * 1000)
                                      .put("runTo", 0)
                                      .put("runFrom", 0)
                                      .put(
                                          "next",
                                          next(
                                              job.optInt("days", 1),
                                              job.optInt("hour", 9),
                                              job.optInt("minute", 0),
                                              job.optString("zone", TimeZone.getDefault().getID())))
                                      .put("error", "");
                                  notifyReady(
                                      report, index.sources.stream().filter(scope::allows).count());
                                } catch (Exception e) {
                                  try {
                                    job.put(
                                            "error",
                                            e.getMessage() == null
                                                ? e.getClass().getSimpleName()
                                                : e.getMessage())
                                        .put("next", System.currentTimeMillis() + 3600000);
                                  } catch (Exception ignored) {
                                  }
                                }
                                try {
                                  JSONObject latestSchedules =
                                      CenterIndex.settings().optJSONObject("schedules");
                                  JSONObject latestJob =
                                      latestSchedules == null
                                          ? null
                                          : latestSchedules.optJSONObject(job.optString("id"));
                                  if (latestJob != null && !cancelled.get()) {
                                    job.put("enabled", latestJob.optBoolean("enabled"));
                                    update(job);
                                  }
                                } catch (Exception e) {
                                  FileLog.e(e);
                                }
                                AndroidUtilities.runOnUIThread(
                                    () -> runNext(due, n + 1, cancelled, done));
                              },
                              "tp-ai-digest")
                          .start();
                    });
            if (CenterIndex.settings().optBoolean("localOnly", false)) {
              index.scope = scope;
              CenterReports.Report local =
                  local(index.selected(), index.sources, scope, job.optString("query"));
              CenterStore.IO.execute(
                  () -> {
                    try {
                      if (cancelled.get()) throw new InterruptedException();
                      CenterAssets.save(local, "report", new CenterExport.Options());
                      job.put("last", scope.to * 1000)
                          .put("runTo", 0)
                          .put("runFrom", 0)
                          .put(
                              "next",
                              next(
                                  job.optInt("days", 1),
                                  job.optInt("hour", 9),
                                  job.optInt("minute", 0),
                                  job.optString("zone", TimeZone.getDefault().getID())));
                      update(job);
                      notifyReady(local, index.sources.stream().filter(scope::allows).count());
                    } catch (Exception e) {
                      FileLog.e(e);
                    }
                    AndroidUtilities.runOnUIThread(() -> runNext(due, n + 1, cancelled, done));
                  });
            } else {
              activeSync = sync;
              sync.discoverThenLoad();
            }
          } catch (Exception e) {
            FileLog.e(e);
            runNext(due, n + 1, cancelled, done);
          }
        });
  }

  private static CenterReports.Report local(
      List<CenterData.Message> messages,
      List<CenterData.Source> sources,
      CenterData.Scope scope,
      String question)
      throws Exception {
    CenterStats stats = CenterStats.compute(messages, sources, scope, TimeZone.getDefault());
    CenterReports.Report report = new CenterReports.Report();
    report.question = question;
    report.from = scope.from;
    report.to = scope.to;
    report.indexed = report.analyzed = messages.size();
    report.complete = true;
    for (CenterData.Source source : sources)
      if (scope.allows(source) && !source.covers(scope.from, scope.to)) report.complete = false;
    report.statistics = stats.json();
    report.dataset.addAll(messages);
    report.unclear.add(CenterText.get("local"));
    for (CenterStats.Group group : stats.groups) {
      CenterReports.Claim claim = new CenterReports.Claim();
      claim.title = group.title;
      claim.text = CenterText.get("messages") + ": " + group.messages;
      for (CenterData.Message message : group.evidence) {
        String quote = message.text.substring(0, Math.min(160, message.text.length()));
        if (!quote.isEmpty()) {
          JSONObject one =
              new JSONObject()
                  .put(
                      "claims",
                      new JSONArray()
                          .put(
                              new JSONObject()
                                  .put("title", claim.title)
                                  .put("text", claim.text)
                                  .put(
                                      "evidence",
                                      new JSONArray()
                                          .put(
                                              new JSONObject()
                                                  .put("account", message.account)
                                                  .put("chat", message.dialog)
                                                  .put("message", message.id)
                                                  .put("quote", quote)))));
          report.claims.addAll(CenterReports.validate(one, messages).claims);
          break;
        }
      }
    }
    return report;
  }

  private static void notifyReady(CenterReports.Report report, long sources) {
    try {
      Context c = ApplicationLoader.applicationContext;
      NotificationManager nm =
          (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      if (Build.VERSION.SDK_INT >= 26)
        nm.createNotificationChannel(
            new NotificationChannel(
                "tp_ai_digests",
                CenterText.get("reports"),
                NotificationManager.IMPORTANCE_DEFAULT));
      Notification.Builder b =
          Build.VERSION.SDK_INT >= 26
              ? new Notification.Builder(c, "tp_ai_digests")
              : new Notification.Builder(c);
      Intent i =
          new Intent(c, org.telegram.ui.LaunchActivity.class)
              .setAction("org.telegram.aicenter")
              .putExtra("aic_screen", 7);
      PendingIntent pi =
          PendingIntent.getActivity(
              c, 72582, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      nm.notify(
          72582,
          b.setSmallIcon(org.telegram.messenger.R.drawable.notification)
              .setContentTitle(CenterText.get("title"))
              .setContentText(
                  String.format(
                      Locale.getDefault(),
                      CenterText.get("digestSummary"),
                      report.claims.size(),
                      sources))
              .setContentIntent(pi)
              .setAutoCancel(true)
              .build());
    } catch (Exception e) {
      FileLog.e(e);
    }
  }
}
