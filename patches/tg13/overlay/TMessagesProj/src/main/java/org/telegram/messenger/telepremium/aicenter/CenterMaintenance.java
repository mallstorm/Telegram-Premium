package org.telegram.messenger.telepremium.aicenter;

import android.app.job.*;
import android.content.*;
import org.telegram.messenger.*;

/** Best-effort idle cache timer; does not require a network or a running foreground service. */
public final class CenterMaintenance extends JobService {
  private static final int JOB = 72583;

  public static void schedule() {
    try {
      JobScheduler scheduler =
          (JobScheduler)
              ApplicationLoader.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (CenterStore.get().bytes() == 0) {
        scheduler.cancel(JOB);
        return;
      }
      boolean pending = false;
      for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == JOB) pending = true;
      if (!pending)
        scheduler.schedule(
            new JobInfo.Builder(
                    JOB,
                    new ComponentName(
                        ApplicationLoader.applicationContext, CenterMaintenance.class))
                .setPersisted(true)
                .setPeriodic(6 * 3600000L)
                .setRequiresDeviceIdle(true)
                .build());
    } catch (Exception e) {
      FileLog.e(e);
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    ApplicationLoader.postInitApplication();
    CenterStore.IO.execute(
        () -> {
          try {
            CenterStore.get().prune();
          } finally {
            jobFinished(params, false);
          }
        });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false;
  }
}
