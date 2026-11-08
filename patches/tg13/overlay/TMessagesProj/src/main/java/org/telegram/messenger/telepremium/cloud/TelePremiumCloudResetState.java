package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;

/** Durable reset barrier. Never expose SharedPreferences' uncommitted in-memory changes. */
public final class TelePremiumCloudResetState {
    public static final String DELETING = "deleting", CLEARING = "clearing", CREATING = "creating";
    public static final String MESSAGE = "Пересоздание облака не завершено. Откройте «Защита облака» и продолжите";
    private TelePremiumCloudResetState() { }
    private static Context owner;
    private static Snapshot durable;

    private static final class Snapshot {
        final long epoch;
        final String phase, operation, plan;
        Snapshot(long epoch, String phase, String operation, String plan) {
            this.epoch = epoch; this.phase = phase; this.operation = operation; this.plan = plan;
        }
    }
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_cloud_reset_v1", 0);
    }
    private static synchronized Snapshot state() {
        Context current = ApplicationLoader.applicationContext;
        if (durable == null || owner != current) {
            SharedPreferences p = prefs();
            durable = new Snapshot(p.getLong("epoch", 0), p.getString("phase", ""),
                    p.getString("operation", ""), p.getString("plan", null));
            owner = current;
        }
        return durable;
    }
    private static void persist(Snapshot next, boolean completed) throws Exception {
        // commit(false) still changes Android's in-memory SharedPreferences map.
        // Keep our last durable snapshot until a full replacement has really reached disk.
        SharedPreferences.Editor edit = prefs().edit().putLong("epoch", next.epoch)
                .putString("phase", next.phase).putString("operation", next.operation);
        if (next.plan == null) edit.remove("plan"); else edit.putString("plan", next.plan);
        if (completed) edit.putString("completed", next.operation);
        if (!edit.commit()) throw new IllegalStateException("Не удалось сохранить этап пересоздания");
        durable = next;
    }
    public static long epoch() { return state().epoch; }
    public static String phase() { return state().phase; }
    public static boolean pending() { return !state().phase.isEmpty(); }
    public static boolean allowed(long epoch) {
        Snapshot s = state();
        return epoch == s.epoch && (s.phase.isEmpty() || CREATING.equals(s.phase));
    }
    public static String operation() { return state().operation; }
    public static String plan() { return state().plan; }
    public static synchronized void begin(String id, String plan) throws Exception {
        Snapshot s = state();
        if (!s.phase.isEmpty()) throw new IllegalStateException(MESSAGE);
        persist(new Snapshot(s.epoch + 1, DELETING, id, plan), false);
    }
    public static synchronized void save(String id, String phase, String plan, boolean advanceEpoch) throws Exception {
        Snapshot s = state();
        if (s.phase.isEmpty() || !s.operation.equals(id)) throw new IllegalStateException("План пересоздания изменился");
        persist(new Snapshot(s.epoch + (advanceEpoch ? 1 : 0), phase, id, plan), false);
    }
    public static synchronized void complete(String id) throws Exception {
        Snapshot s = state();
        if (!s.operation.equals(id) || !CREATING.equals(s.phase)) throw new IllegalStateException("Неверный этап пересоздания");
        persist(new Snapshot(s.epoch, "", id, null), true);
    }
    public static boolean mayRotate(String id) {
        Snapshot s = state();
        return id != null && id.equals(s.operation) && CLEARING.equals(s.phase);
    }
}
