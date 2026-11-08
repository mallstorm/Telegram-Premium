package org.telegram.messenger.telepremium.cloud;

import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;

/** Durable reset barrier. Epochs prevent old callbacks from repopulating a new Vault. */
public final class TelePremiumCloudResetState {
    public static final String DELETING = "deleting", CLEARING = "clearing", CREATING = "creating";
    public static final String MESSAGE = "Пересоздание облака не завершено. Откройте «Защита облака» и продолжите";
    private TelePremiumCloudResetState() { }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telepremium_cloud_reset_v1", 0);
    }

    public static long epoch() { return prefs().getLong("epoch", 0); }
    public static String phase() { return prefs().getString("phase", ""); }
    public static boolean pending() { return !phase().isEmpty(); }
    public static boolean allowed(long epoch) {
        String phase = phase();
        return epoch == epoch() && (phase.isEmpty() || CREATING.equals(phase));
    }
    public static String operation() { return prefs().getString("operation", ""); }
    public static String plan() { return prefs().getString("plan", null); }

    public static synchronized void begin(String id, String plan) throws Exception {
        if (pending()) throw new IllegalStateException(MESSAGE);
        if (!prefs().edit().putLong("epoch", epoch() + 1).putString("operation", id)
                .putString("phase", DELETING).putString("plan", plan).commit())
            throw new IllegalStateException("Не удалось сохранить план пересоздания");
    }

    public static synchronized void save(String id, String phase, String plan, boolean advanceEpoch) throws Exception {
        if (!pending() || !operation().equals(id)) throw new IllegalStateException("План пересоздания изменился");
        SharedPreferences.Editor edit = prefs().edit().putString("phase", phase).putString("plan", plan);
        if (advanceEpoch) edit.putLong("epoch", epoch() + 1);
        if (!edit.commit()) throw new IllegalStateException("Не удалось сохранить этап пересоздания");
    }

    public static synchronized void complete(String id) throws Exception {
        if (!operation().equals(id) || !CREATING.equals(phase())) throw new IllegalStateException("Неверный этап пересоздания");
        if (!prefs().edit().remove("plan").remove("phase").putString("completed", id).commit())
            throw new IllegalStateException("Не удалось сохранить завершение пересоздания");
    }

    public static boolean mayRotate(String id) {
        return id != null && id.equals(operation()) && CLEARING.equals(phase());
    }
}
