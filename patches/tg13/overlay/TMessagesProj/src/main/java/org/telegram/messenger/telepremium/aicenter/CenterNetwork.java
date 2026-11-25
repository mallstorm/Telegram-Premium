package org.telegram.messenger.telepremium.aicenter;

import java.net.HttpURLConnection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancels only AI requests belonging to the current center task. */
public final class CenterNetwork {
  private static final ThreadLocal<AtomicBoolean> TOKEN = new ThreadLocal<>();
  private static final ScheduledExecutorService TIMER =
      Executors.newSingleThreadScheduledExecutor();

  public static void bind(AtomicBoolean token) {
    TOKEN.set(token);
    EPOCH.set(CenterIndex.generation());
  }

  private static final ThreadLocal<Long> EPOCH = new ThreadLocal<>();

  public static boolean writeAllowed() {
    AtomicBoolean token = TOKEN.get();
    Long epoch = EPOCH.get();
    return token == null || (!token.get() && (epoch == null || epoch == CenterIndex.generation()));
  }

  public static void unbind() {
    TOKEN.remove();
    EPOCH.remove();
  }

  public static ScheduledFuture<?> watch(HttpURLConnection connection) {
    AtomicBoolean token = TOKEN.get();
    if (token == null) return null;
    return TIMER.scheduleAtFixedRate(
        () -> {
          if (token.get()) connection.disconnect();
        },
        0,
        250,
        TimeUnit.MILLISECONDS);
  }
}
