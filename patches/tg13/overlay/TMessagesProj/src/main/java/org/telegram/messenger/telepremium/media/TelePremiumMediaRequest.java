package org.telegram.messenger.telepremium.media;

import org.telegram.messenger.AndroidUtilities;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;

/** Cancellation owns the HTTP connections as well as the queued job. Never blocks the UI. */
public final class TelePremiumMediaRequest {
    public interface Callback<T> { void complete(T value, Throwable error); }
    private static final ThreadLocal<TelePremiumMediaRequest> CURRENT = new ThreadLocal<>();
    private static final ExecutorService CLOSER = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "tp-media-cancel"); thread.setDaemon(true); return thread;
    });
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private final ExecutorService executor;
    private volatile boolean cancelled;
    private FutureTask<?> future;
    private TelePremiumMediaRequest(ExecutorService executor) { this.executor = executor; }

    public static <T> TelePremiumMediaRequest start(ExecutorService executor, Callable<T> work, Callback<T> callback) {
        TelePremiumMediaRequest request = new TelePremiumMediaRequest(executor);
        request.future = new FutureTask<Void>(() -> {
            if (request.cancelled) return null;
            CURRENT.set(request); T result = null; Throwable error = null;
            try { checkCancelled(); result = work.call(); }
            catch (Throwable t) { error = t; }
            finally { CURRENT.remove(); request.closeConnections(); }
            final T value = result; final Throwable failure = error;
            if (!request.cancelled) AndroidUtilities.runOnUIThread(() -> {
                if (!request.cancelled) callback.complete(value, failure);
            });
            return null;
        });
        executor.execute(request.future);
        return request;
    }
    public boolean isCancelled() { return cancelled; }
    public void cancel() {
        cancelled = true;
        if (future != null) {
            future.cancel(true);
            if (executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) executor).remove(future);
        }
        closeConnections();
    }
    private void closeConnections() {
        final Set<HttpURLConnection> copy;
        synchronized (connections) { copy = new HashSet<>(connections); connections.clear(); }
        for (HttpURLConnection connection : copy) CLOSER.execute(connection::disconnect);
    }
    public static void checkCancelled() throws InterruptedIOException {
        TelePremiumMediaRequest request = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || (request != null && request.cancelled))
            throw new InterruptedIOException("Media request cancelled");
    }
    public static HttpURLConnection open(URL url) throws java.io.IOException {
        checkCancelled();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(org.telegram.messenger.telepremium.xray.XrayRuntime.mediaProxy());
        TelePremiumMediaRequest request = CURRENT.get();
        if (request != null) synchronized (request.connections) {
            if (request.cancelled) { CLOSER.execute(connection::disconnect); throw new InterruptedIOException("Media request cancelled"); }
            request.connections.add(connection);
        }
        return connection;
    }
    public static void disconnect(HttpURLConnection connection) {
        if (connection == null) return;
        TelePremiumMediaRequest request = CURRENT.get();
        if (request != null) synchronized (request.connections) { request.connections.remove(connection); }
        connection.disconnect();
    }
}
