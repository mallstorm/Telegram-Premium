package org.telegram.messenger.telepremium.aicenter;

import android.app.job.*;

public final class CenterBackgroundJob extends JobService {
  @Override
  public boolean onStartJob(JobParameters params) {
    org.telegram.messenger.ApplicationLoader.postInitApplication();
    CenterTaskService.startWorker(() -> jobFinished(params, false));
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    CenterTaskService.stopWork();
    return true;
  }
}
