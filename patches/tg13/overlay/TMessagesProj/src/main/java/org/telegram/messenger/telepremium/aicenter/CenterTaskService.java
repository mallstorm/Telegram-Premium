package org.telegram.messenger.telepremium.aicenter;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.messenger.telepremium.TelePremiumLocalTranscriber;

/** Durable user-authorized queue. No checkpoint advances before its result is committed. */
public final class CenterTaskService extends Service {
  private static final int NOTIFICATION = 7258, JOB = 72581;
  private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> FINISHED =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean> EXTERNAL =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static volatile String activeJob = "";
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static volatile AtomicBoolean cancellation = new AtomicBoolean();

  public static void enqueue(List<CenterData.Message> selected, String operation) {
    enqueue(selected, operation, "");
  }

  public static void enqueue(List<CenterData.Message> selected, String operation, String question) {
    List<CenterData.Message> copy = new ArrayList<>(selected);
    CenterStore.IO.execute(
        () -> {
          try {
            for (CenterData.Message m : copy) {
              CenterMedia.check(m);
              String signature =
                  question
                      + "|"
                      + operation
                      + "|"
                      + m.qualified()
                      + "|"
                      + m.transport.hashCode()
                      + "|"
                      + m.localUri
                      + "|"
                      + CenterIndex.settings().optString("language", "auto")
                      + "|"
                      + (operation.equals("cloudAsr")
                          ? CenterAi.model("speechModel").id
                              + CenterIndex.settings().optString("asrModel", "whisper-1")
                          : "");
              boolean existing = false;
              for (JSONObject old : CenterStore.get().list(m.account, m.owner, "job"))
                if (signature.equals(old.optString("signature"))
                    && !"cancelled".equals(old.optString("state"))) {
                  existing = true;
                  if ("error".equals(old.optString("state"))
                      || "paused".equals(old.optString("state"))) {
                    old.put("state", "queued").put("retryAt", 0);
                    CenterStore.get().put(m.account, m.owner, "job", old.optString("id"), old);
                  }
                  break;
                }
              if (existing) continue;
              String id = UUID.randomUUID().toString();
              JSONObject job =
                  new JSONObject()
                      .put("id", id)
                      .put("signature", signature)
                      .put("op", operation)
                      .put("language", CenterIndex.settings().optString("language", "auto"))
                      .put("speechModel", CenterIndex.settings().optString("asrModel", "whisper-1"))
                      .put("query", question)
                      .put("account", m.account)
                      .put("owner", m.owner)
                      .put("message", m.json())
                      .put("state", "queued")
                      .put("cursor", 0)
                      .put("percent", 0)
                      .put("created", System.currentTimeMillis())
                      .put("accounts", CenterAi.accounts(Collections.singletonList(m)));
              CenterStore.get().put(m.account, m.owner, "job", id, job);
            }
            AndroidUtilities.runOnUIThread(CenterTaskService::startManual);
          } catch (Exception e) {
            FileLog.e(e);
          }
        });
  }

  public static void startManual() {
    Context c = ApplicationLoader.applicationContext;
    Intent i = new Intent(c, CenterTaskService.class);
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
    else c.startService(i);
    schedule();
  }

  public static void schedule() {
    CenterStore.IO.execute(
        () -> {
          try {
            JobScheduler scheduler =
                (JobScheduler)
                    ApplicationLoader.applicationContext.getSystemService(
                        Context.JOB_SCHEDULER_SERVICE);
            boolean pending = false;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
              if (UserConfig.getInstance(a).isClientActivated())
                for (JSONObject job :
                    CenterStore.get().list(a, UserConfig.getInstance(a).getClientUserId(), "job"))
                  if (job.optString("state").equals("queued")
                      || job.optString("state").equals("error")
                      || job.optString("state").equals("running")) pending = true;
            if (!pending) {
              scheduler.cancel(JOB);
              return;
            }
            JobInfo.Builder b =
                new JobInfo.Builder(
                        JOB,
                        new ComponentName(
                            ApplicationLoader.applicationContext, CenterBackgroundJob.class))
                    .setPersisted(true)
                    .setMinimumLatency(60000)
                    .setRequiredNetworkType(
                        CenterIndex.settings().optBoolean("wifi", true)
                            ? JobInfo.NETWORK_TYPE_UNMETERED
                            : JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresCharging(CenterIndex.settings().optBoolean("charging", false));
            scheduler.schedule(b.build());
          } catch (Exception e) {
            FileLog.e(e);
          }
        });
  }

  public static void stopWork() {
    cancellation.set(true);
    for (AtomicBoolean token : EXTERNAL.values()) token.set(true);
  }

  public static void change(JSONObject job, String state) {
    CenterStore.IO.execute(
        () -> {
          try {
            job.put("state", state).put("retryAt", 0);
            CenterStore.get()
                .put(job.optInt("account"), job.optLong("owner"), "job", job.optString("id"), job);
            AtomicBoolean external = EXTERNAL.get(job.optString("id"));
            if (external != null && !state.equals("queued")) external.set(true);
            if (job.optString("id").equals(activeJob) && !state.equals("queued"))
              cancellation.set(true);
            schedule();
          } catch (Exception e) {
            FileLog.e(e);
          }
        });
  }

  public static void startWorker(Runnable finished) {
    FINISHED.add(finished);
    if (!RUNNING.compareAndSet(false, true)) return;
    cancellation = new AtomicBoolean();
    final AtomicBoolean cancel = cancellation;
    new Thread(
            () -> {
              try {
                recover();
                while (!cancel.get()) {
                  JSONObject job = next();
                  if (job == null) {
                    if (!EXTERNAL.isEmpty()) {
                      Thread.sleep(250);
                      continue;
                    }
                    break;
                  }
                  activeJob = job.optString("id");
                  int a = job.optInt("account");
                  long owner = job.optLong("owner");
                  try {
                    job.put("state", "running");
                    save(job);
                    notifyProgress(job.optInt("percent"));
                    run(job, cancel);
                    JSONObject requested =
                        CenterStore.get().get(a, owner, "job", job.optString("id"));
                    String requestedState =
                        requested == null ? "cancelled" : requested.optString("state");
                    job.put(
                        "state",
                        requestedState.equals("cancelled")
                            ? "cancelled"
                            : cancel.get() ? "paused" : "done");
                    job.put("error", "");
                    save(job);
                  } catch (Exception e) {
                    CenterNetwork.unbind();
                    if (UserConfig.getInstance(a).isClientActivated()
                        && UserConfig.getInstance(a).getClientUserId() == owner) {
                      int tries = job.optInt("attempts") + 1;
                      job.put("state", cancel.get() ? "paused" : "error")
                          .put(
                              "error",
                              e.getMessage() == null
                                  ? e.getClass().getSimpleName()
                                  : e.getMessage())
                          .put("attempts", tries)
                          .put(
                              "retryAt",
                              System.currentTimeMillis()
                                  + Math.min(3600000L, 30000L * (1L << Math.min(tries, 6))));
                      save(job);
                    }
                  }
                }
              } catch (Exception e) {
                FileLog.e(e);
              } finally {
                activeJob = "";
                RUNNING.set(false);
                schedule();
                try {
                  ((NotificationManager)
                          ApplicationLoader.applicationContext.getSystemService(
                              Context.NOTIFICATION_SERVICE))
                      .cancel(NOTIFICATION);
                } catch (Exception ignored) {
                }
                for (Runnable listener : FINISHED) AndroidUtilities.runOnUIThread(listener);
                FINISHED.clear();
              }
            },
            "tp-ai-center-work")
        .start();
  }

  private static void recover() throws Exception {
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated()) {
        long o = UserConfig.getInstance(a).getClientUserId();
        for (JSONObject job : CenterStore.get().list(a, o, "job"))
          if ("running".equals(job.optString("state"))
              && !EXTERNAL.containsKey(job.optString("id"))) {
            job.put("state", job.optBoolean("ephemeral") ? "cancelled" : "queued");
            save(job);
          }
      }
  }

  private static JSONObject next() throws Exception {
    JSONObject best = null;
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated()) {
        long o = UserConfig.getInstance(a).getClientUserId();
        for (JSONObject job : CenterStore.get().list(a, o, "job")) {
          String s = job.optString("state");
          if ((s.equals("queued")
                  || s.equals("error") && job.optLong("retryAt") <= System.currentTimeMillis())
              && (best == null || job.optLong("created") < best.optLong("created"))) best = job;
        }
      }
    return best;
  }

  private static void save(JSONObject job) throws Exception {
    CenterStore.get()
        .put(job.optInt("account"), job.optLong("owner"), "job", job.optString("id"), job);
  }

  private static void checkpoint(JSONObject job, long cursor, int percent) throws Exception {
    job.put("cursor", cursor).put("percent", percent);
    save(job);
    notifyProgress(percent);
  }

  private static void run(JSONObject job, AtomicBoolean cancel) throws Exception {
    if (job.optString("op").equals("report")) {
      retryReport(job, cancel);
      return;
    }
    CenterNetwork.bind(cancel);
    CenterData.Message initial = CenterData.Message.parse(job.getJSONObject("message"));
    JSONObject stored =
        CenterStore.get().get(initial.account, initial.owner, "message", initial.qualified());
    CenterData.Message m = stored == null ? initial : CenterData.Message.parse(stored);
    CenterMedia.check(m);
    File file = null;
    try {
      String op = job.optString("op");
      file = CenterMedia.file(m, cancel);
      m.contentHash = CenterMedia.contentHash(file, cancel);
      String cacheQuery =
          op.equals("transcribe") || op.equals("cloudAsr")
              ? job.optString("language", "auto") + "|" + job.optString("speechModel", "whisper-1")
              : job.optString("query");
      if (CenterMedia.cached(op, m, cacheQuery)) {
        checkpoint(job, job.optLong("cursor"), 100);
        return;
      }
      if (op.equals("describe")) {
        if (m.frameTimes.isEmpty() && m.pages == 0) {
          if (m.kind.equals("photo") || m.kind.equals("image")) CenterMedia.image(m, file, false);
          else if (m.kind.equals("video") || m.kind.equals("round"))
            CenterMedia.frames(m, file, 0, cancel, (cursor, p) -> checkpoint(job, cursor, p));
          else if (m.kind.equals("pdf"))
            CenterMedia.document(m, file, 0, cancel, (cursor, p) -> checkpoint(job, cursor, p));
        }
        CenterMedia.describe(m, job.optString("query"));
        CenterMedia.cache(op, m, cacheQuery);
        checkpoint(job, job.optLong("cursor"), 100);
        return;
      }
      if ((op.equals("download") || op.equals("ocr"))
          && (m.kind.equals("photo") || m.kind.equals("image")))
        CenterMedia.image(m, file, op.equals("ocr"));
      else if (op.equals("frames"))
        CenterMedia.frames(
            m, file, job.optLong("cursor"), cancel, (cursor, p) -> checkpoint(job, cursor, p));
      else if (op.equals("document"))
        CenterMedia.document(
            m, file, job.optLong("cursor"), cancel, (cursor, p) -> checkpoint(job, cursor, p));
      else if (op.equals("transcribe") || op.equals("cloudAsr")) {
        android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
        try {
          metadata.setDataSource(file.getAbsolutePath());
          m.duration =
              Long.parseLong(
                  metadata.extractMetadata(
                      android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
        } finally {
          metadata.release();
        }
        if (m.duration > CenterIndex.settings().optInt("maxVideo", 40) * 60000L)
          throw new IOException(CenterText.get("maxVideo"));
        boolean cloud = op.equals("cloudAsr");
        long segmentLength = cloud ? 10000 : 35000;
        for (long start = job.optLong("cursor"); start < m.duration; start += segmentLength) {
          boolean cached = false;
          for (CenterTranscript.Segment segment : CenterTranscript.parse(m.transcript))
            if (segment.start == start) cached = true;
          if (cached) {
            checkpoint(
                job,
                start + segmentLength,
                (int) Math.min(100, 100 * (start + segmentLength) / m.duration));
            continue;
          }
          if (cancel.get()) throw new InterruptedException();
          CenterMedia.check(m);
          if (cloud) {
            CenterAi.requireAllowed();
            File audio =
                TelePremiumLocalTranscriber.waveSegment(
                    file,
                    m.account,
                    m.id,
                    start,
                    Math.min(start + segmentLength, m.duration),
                    cancel);
            CenterUsage.Ticket ticket = null;
            try {
              ticket = CenterUsage.reserve(10000);
              org.telegram.messenger.telepremium.ai.TPAiStore.Connection connection =
                  CenterAi.model("speechModel");
              connection = speechConnection(connection, job.optString("speechModel", "whisper-1"));
              String text =
                  org.telegram.messenger.telepremium.ai.TPAiClient.transcribe(
                      connection, audio, job.optString("language", "auto"));
              if (cancel.get()) throw new InterruptedException();
              CenterMedia.check(m);
              m.transcript +=
                  "[" + CenterTranscript.stamp(start) + "] " + text.replace('\n', ' ') + "\n";
              CenterMedia.save(m);
              checkpoint(
                  job,
                  start + segmentLength,
                  (int) Math.min(100, 100 * (start + segmentLength) / m.duration));
            } finally {
              audio.delete();
              if (ticket != null) CenterUsage.finish(ticket);
            }
            continue;
          }
          CountDownLatch wait = new CountDownLatch(1);
          String[] result = {null, null};
          TelePremiumLocalTranscriber.transcribeSegment(
              file,
              m.account,
              m.id,
              start,
              Math.min(start + segmentLength, m.duration),
              job.optString("language", "auto"),
              cancel,
              new TelePremiumLocalTranscriber.Callback() {
                public void onSuccess(String text) {
                  result[0] = text;
                  wait.countDown();
                }

                public void onError(String error) {
                  result[1] = error;
                  wait.countDown();
                }
              });
          while (!wait.await(500, TimeUnit.MILLISECONDS)) {
            if (cancel.get()) throw new InterruptedException();
          }
          if (result[1] != null) throw new IOException(result[1]);
          m.transcript +=
              "["
                  + CenterTranscript.stamp(start)
                  + "] "
                  + (result[0] == null ? "" : result[0])
                  + "\n";
          CenterMedia.save(m);
          checkpoint(
              job,
              start + segmentLength,
              (int) Math.min(100, 100 * (start + segmentLength) / m.duration));
        }
      }
      CenterMedia.cache(op, m, cacheQuery);
      checkpoint(job, job.optLong("cursor"), 100);
    } finally {
      CenterNetwork.unbind();
      if (file != null && (!m.localUri.isEmpty() || !m.cloudNode.isEmpty())) file.delete();
    }
  }

  private static org.telegram.messenger.telepremium.ai.TPAiStore.Connection speechConnection(
      org.telegram.messenger.telepremium.ai.TPAiStore.Connection c, String model) {
    org.telegram.messenger.telepremium.ai.TPAiStore.Connection copy =
        new org.telegram.messenger.telepremium.ai.TPAiStore.Connection();
    copy.id = c.id;
    copy.providerId = c.providerId;
    copy.name = c.name;
    copy.baseUrl = c.baseUrl;
    copy.protocol = c.protocol;
    copy.authType = c.authType;
    copy.scope = c.scope;
    copy.model = model;
    return copy;
  }

  public static CenterReports.Report trackReport(
      List<CenterData.Message> data,
      List<CenterData.Source> sources,
      CenterData.Scope scope,
      String query,
      boolean detailed,
      boolean ephemeral,
      AtomicBoolean cancel,
      CenterAi.Progress progress)
      throws Exception {
    if (data.isEmpty()) throw new IllegalStateException(CenterText.get("empty"));
    int account = data.get(0).account;
    long owner = data.get(0).owner;
    String id = java.util.UUID.randomUUID().toString();
    org.json.JSONArray keys = new org.json.JSONArray(), ss = new org.json.JSONArray();
    for (CenterData.Message m : data) keys.put(m.qualified());
    for (CenterData.Source s : sources) ss.put(s.json());
    JSONObject job =
        new JSONObject()
            .put("id", id)
            .put("op", "report")
            .put("account", account)
            .put("owner", owner)
            .put("keys", keys)
            .put("sources", ss)
            .put("scope", scope.json())
            .put("query", query)
            .put("detailed", detailed)
            .put("ephemeral", ephemeral)
            .put("accounts", CenterAi.accounts(data))
            .put("state", "running")
            .put("created", System.currentTimeMillis());
    EXTERNAL.put(id, cancel);
    try {
      save(job);
    } catch (Exception e) {
      EXTERNAL.remove(id);
      throw e;
    }
    AndroidUtilities.runOnUIThread(CenterTaskService::startManual);
    try {
      CenterReports.Report result =
          CenterAi.run(
              data,
              sources,
              scope,
              query,
              detailed,
              cancel,
              (done, total) -> {
                try {
                  checkpoint(job, done, 100 * done / Math.max(1, total));
                } catch (Exception e) {
                  cancel.set(true);
                }
                progress.update(done, total);
              });
      if (cancel.get()) throw new InterruptedException();
      if (!ephemeral) CenterAssets.save(result, "thread", new CenterExport.Options());
      job.put("state", result.retryNeeded ? "error" : "done")
          .put("retryAt", System.currentTimeMillis() + 60000)
          .put("percent", 100);
      save(job);
      result.dataset.clear();
      return result;
    } catch (Exception e) {
      job.put("state", cancel.get() ? "paused" : "error")
          .put("retryAt", System.currentTimeMillis() + 60000)
          .put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      if (CenterIndex.accountAllowed(account, owner)) save(job);
      throw e;
    } finally {
      EXTERNAL.remove(id);
      schedule();
    }
  }

  private static void retryReport(JSONObject job, AtomicBoolean cancel) throws Exception {
    if (job.optBoolean("ephemeral")) {
      job.put("state", "cancelled");
      save(job);
      return;
    }
    Set<String> keys = new HashSet<>();
    org.json.JSONArray selected = job.optJSONArray("keys");
    for (int i = 0; selected != null && i < selected.length(); i++) keys.add(selected.optString(i));
    List<CenterData.Message> data = new ArrayList<>();
    JSONArray owners = job.optJSONArray("accounts");
    for (int i = 0; owners != null && i < owners.length(); i++) {
      JSONObject account = owners.optJSONObject(i);
      int a = account.optInt("account");
      long owner = account.optLong("owner");
      if (!CenterIndex.accountAllowed(a, owner))
        throw new SecurityException(CenterText.get("excluded"));
      for (JSONObject row : CenterStore.get().list(a, owner, "message")) {
        CenterData.Message m = CenterData.Message.parse(row);
        if (keys.contains(m.qualified()) && !m.deleted) data.add(m);
      }
    }
    List<CenterData.Source> sources = new ArrayList<>();
    JSONArray ss = job.optJSONArray("sources");
    for (int i = 0; ss != null && i < ss.length(); i++)
      sources.add(CenterData.Source.parse(ss.optJSONObject(i)));
    CenterData.Scope scope = CenterData.Scope.parse(job.getJSONObject("scope"));
    CenterReports.Report report =
        CenterAi.run(
            data,
            sources,
            scope,
            job.optString("query"),
            job.optBoolean("detailed"),
            cancel,
            (done, total) -> {
              try {
                checkpoint(job, done, 100 * done / Math.max(1, total));
              } catch (Exception e) {
                cancel.set(true);
              }
            });
    if (cancel.get()) throw new InterruptedException();
    CenterAssets.save(report, "thread", new CenterExport.Options());
    if (report.retryNeeded) throw new java.io.IOException(CenterText.get("partial"));
  }

  private static Notification notification(int progress) {
    Context c = ApplicationLoader.applicationContext;
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26)
      nm.createNotificationChannel(
          new NotificationChannel(
              "tp_ai_center", CenterText.get("notification"), NotificationManager.IMPORTANCE_LOW));
    Notification.Builder b =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(c, "tp_ai_center")
            : new Notification.Builder(c);
    Intent open =
        new Intent(c, org.telegram.ui.LaunchActivity.class).setAction("org.telegram.aicenter");
    PendingIntent pi =
        PendingIntent.getActivity(
            c, 7258, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return b.setSmallIcon(org.telegram.messenger.R.drawable.notification)
        .setContentTitle(CenterText.get("notification"))
        .setContentText(CenterText.get("progress"))
        .setProgress(100, progress, false)
        .setContentIntent(pi)
        .setOngoing(true)
        .build();
  }

  private static void notifyProgress(int p) {
    try {
      ((NotificationManager)
              ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE))
          .notify(NOTIFICATION, notification(p));
    } catch (Exception e) {
      FileLog.e(e);
    }
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    startForeground(NOTIFICATION, notification(0));
    startWorker(
        () -> {
          stopForeground(true);
          stopSelf();
        });
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    stopWork();
    super.onDestroy();
  }

  @Override
  public void onTimeout(int startId, int type) {
    stopWork();
    stopSelf();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
