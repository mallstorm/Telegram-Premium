package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Sound settings of the TelePremium player: system equalizer (bands + presets), bass boost,
 * virtualizer, loudness enhancer on the player's audio session, plus stereo balance / mono
 * applied by {@link TelePremiumBalanceAudioProcessor}. Settings persist across launches.
 */
public final class TelePremiumAudioEffects {
    private static final String PREFS = "telepremium_audio_fx";
    public static final int PRESET_CUSTOM = -1;

    private static volatile float leftGain = 1f, rightGain = 1f;
    private static volatile boolean mono;

    private static int sessionId;
    private static Equalizer equalizer;
    private static BassBoost bassBoost;
    private static Virtualizer virtualizer;
    private static LoudnessEnhancer loudness;

    private TelePremiumAudioEffects() {}

    static {
        applyBalance(prefs().getInt("balance", 0), prefs().getBoolean("mono", false));
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- balance / mono (PCM processor) ----

    static float leftGain() { return leftGain; }
    static float rightGain() { return rightGain; }
    static boolean isMono() { return mono; }

    /** @param balance -100 (left only) .. 0 .. 100 (right only) */
    public static void setBalance(int balance) {
        balance = Math.max(-100, Math.min(100, balance));
        prefs().edit().putInt("balance", balance).apply();
        applyBalance(balance, mono);
    }

    public static int getBalance() { return prefs().getInt("balance", 0); }

    public static void setMono(boolean value) {
        prefs().edit().putBoolean("mono", value).apply();
        applyBalance(getBalance(), value);
    }

    public static boolean getMono() { return prefs().getBoolean("mono", false); }

    private static void applyBalance(int balance, boolean monoValue) {
        leftGain = balance > 0 ? (100 - balance) / 100f : 1f;
        rightGain = balance < 0 ? (100 + balance) / 100f : 1f;
        mono = monoValue;
    }

    // ---- session effects ----

    /** Binds the effects to a new player audio session and re-applies saved settings. */
    public static synchronized void attach(int audioSessionId) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return;
        release();
        sessionId = audioSessionId;
        try {
            equalizer = new Equalizer(0, audioSessionId);
        } catch (Throwable e) {
            FileLog.e(e);
            equalizer = null;
        }
        try {
            bassBoost = new BassBoost(0, audioSessionId);
        } catch (Throwable e) {
            FileLog.e(e);
            bassBoost = null;
        }
        try {
            virtualizer = new Virtualizer(0, audioSessionId);
        } catch (Throwable e) {
            FileLog.e(e);
            virtualizer = null;
        }
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                loudness = new LoudnessEnhancer(audioSessionId);
            } catch (Throwable e) {
                FileLog.e(e);
                loudness = null;
            }
        }
        applySaved();
    }

    public static synchronized void release() {
        try { if (equalizer != null) equalizer.release(); } catch (Throwable e) { FileLog.e(e); }
        try { if (bassBoost != null) bassBoost.release(); } catch (Throwable e) { FileLog.e(e); }
        try { if (virtualizer != null) virtualizer.release(); } catch (Throwable e) { FileLog.e(e); }
        try { if (loudness != null) loudness.release(); } catch (Throwable e) { FileLog.e(e); }
        equalizer = null;
        bassBoost = null;
        virtualizer = null;
        loudness = null;
        sessionId = 0;
    }

    public static synchronized boolean isAttached() { return equalizer != null; }

    private static void applySaved() {
        SharedPreferences p = prefs();
        try {
            if (equalizer != null) {
                int preset = p.getInt("eq_preset", PRESET_CUSTOM);
                if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                    equalizer.usePreset((short) preset);
                } else {
                    for (short b = 0; b < equalizer.getNumberOfBands(); b++) {
                        equalizer.setBandLevel(b, (short) p.getInt("eq_band_" + b, 0));
                    }
                }
                equalizer.setEnabled(p.getBoolean("eq_enabled", false));
            }
            if (bassBoost != null && bassBoost.getStrengthSupported()) {
                int strength = p.getInt("bass", 0);
                bassBoost.setStrength((short) strength);
                bassBoost.setEnabled(strength > 0);
            }
            if (virtualizer != null && virtualizer.getStrengthSupported()) {
                int strength = p.getInt("virtualizer", 0);
                virtualizer.setStrength((short) strength);
                virtualizer.setEnabled(strength > 0);
            }
            if (loudness != null && Build.VERSION.SDK_INT >= 19) {
                int gain = p.getInt("loudness", 0);
                loudness.setTargetGain(gain);
                loudness.setEnabled(gain > 0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---- equalizer ----

    public static synchronized boolean isEqEnabled() { return prefs().getBoolean("eq_enabled", false); }

    public static synchronized void setEqEnabled(boolean enabled) {
        prefs().edit().putBoolean("eq_enabled", enabled).apply();
        try { if (equalizer != null) equalizer.setEnabled(enabled); } catch (Throwable e) { FileLog.e(e); }
    }

    public static synchronized int getBandCount() {
        try { return equalizer == null ? 0 : equalizer.getNumberOfBands(); } catch (Throwable e) { return 0; }
    }

    /** {min, max} in millibels. */
    public static synchronized int[] getBandRange() {
        try {
            short[] r = equalizer.getBandLevelRange();
            return new int[]{r[0], r[1]};
        } catch (Throwable e) {
            return new int[]{-1500, 1500};
        }
    }

    /** Center frequency in Hz. */
    public static synchronized int getBandFrequency(int band) {
        try { return equalizer.getCenterFreq((short) band) / 1000; } catch (Throwable e) { return 0; }
    }

    public static synchronized int getBandLevel(int band) {
        try { return equalizer.getBandLevel((short) band); } catch (Throwable e) { return prefs().getInt("eq_band_" + band, 0); }
    }

    public static synchronized void setBandLevel(int band, int level) {
        prefs().edit().putInt("eq_band_" + band, level).putInt("eq_preset", PRESET_CUSTOM).apply();
        try { if (equalizer != null) equalizer.setBandLevel((short) band, (short) level); } catch (Throwable e) { FileLog.e(e); }
    }

    public static synchronized String[] getPresetNames() {
        try {
            if (equalizer == null) return new String[0];
            String[] names = new String[equalizer.getNumberOfPresets()];
            for (short i = 0; i < names.length; i++) names[i] = equalizer.getPresetName(i);
            return names;
        } catch (Throwable e) {
            return new String[0];
        }
    }

    public static synchronized int getPreset() { return prefs().getInt("eq_preset", PRESET_CUSTOM); }

    public static synchronized void usePreset(int preset) {
        SharedPreferences.Editor editor = prefs().edit().putInt("eq_preset", preset);
        try {
            if (equalizer != null && preset >= 0) {
                equalizer.usePreset((short) preset);
                for (short b = 0; b < equalizer.getNumberOfBands(); b++) editor.putInt("eq_band_" + b, equalizer.getBandLevel(b));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        editor.apply();
    }

    // ---- bass / virtualizer / loudness (0..1000 strength, loudness in mB) ----

    public static int getBass() { return prefs().getInt("bass", 0); }
    public static int getVirtualizer() { return prefs().getInt("virtualizer", 0); }
    public static int getLoudness() { return prefs().getInt("loudness", 0); }

    public static synchronized void setBass(int strength) {
        prefs().edit().putInt("bass", strength).apply();
        try {
            if (bassBoost != null) { bassBoost.setStrength((short) strength); bassBoost.setEnabled(strength > 0); }
        } catch (Throwable e) { FileLog.e(e); }
    }

    public static synchronized void setVirtualizer(int strength) {
        prefs().edit().putInt("virtualizer", strength).apply();
        try {
            if (virtualizer != null) { virtualizer.setStrength((short) strength); virtualizer.setEnabled(strength > 0); }
        } catch (Throwable e) { FileLog.e(e); }
    }

    public static synchronized void setLoudness(int gainMb) {
        prefs().edit().putInt("loudness", gainMb).apply();
        try {
            if (loudness != null && Build.VERSION.SDK_INT >= 19) { loudness.setTargetGain(gainMb); loudness.setEnabled(gainMb > 0); }
        } catch (Throwable e) { FileLog.e(e); }
    }

    public static synchronized void resetAll() {
        prefs().edit().clear().apply();
        applyBalance(0, false);
        try {
            if (equalizer != null) {
                for (short b = 0; b < equalizer.getNumberOfBands(); b++) equalizer.setBandLevel(b, (short) 0);
                equalizer.setEnabled(false);
            }
        } catch (Throwable e) { FileLog.e(e); }
        setBass(0);
        setVirtualizer(0);
        setLoudness(0);
    }
}
