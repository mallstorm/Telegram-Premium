package org.telegram.messenger.telepremium.aicenter;

import android.app.job.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CenterDigestJob extends JobService {
  private AtomicBoolean cancellation;

  @Override
  public boolean onStartJob(JobParameters params) {
    org.telegram.messenger.ApplicationLoader.postInitApplication();
    cancellation = new AtomicBoolean();
    CenterDigests.runDue(
        cancellation,
        () -> {
          CenterDigests.schedule();
          jobFinished(params, false);
        });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    if (cancellation != null) cancellation.set(true);
    CenterDigests.cancelWork();
    return true;
  }
}
