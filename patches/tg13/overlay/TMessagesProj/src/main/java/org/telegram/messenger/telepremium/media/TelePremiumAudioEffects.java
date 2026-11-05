package org.telegram.messenger.telepremium.media;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.Locale;

/**
 * Sound settings of the TelePremium player. All processing is done by TelePremium itself
 * ({@link TelePremiumBalanceAudioProcessor}, a PCM DSP inside the player), so it works the
 * same on every phone: the system audiofx equalizer is ignored or overridden on many
 * firmwares (MIUI, One UI, Dirac/Dolby), which is why presets used to sound identical.
 *
 * <p>10-band graphic EQ with presets, preamp, bass and treble shelves, stereo width,
 * balance, mono and a soft limiter. Changes apply live: the processor re-reads the settings
 * whenever {@link #version()} changes.</p>
 */
public final class TelePremiumAudioEffects {
    private static final String PREFS = "telepremium_audio_dsp";
    public static final int PRESET_CUSTOM = -1;
    public static final int BANDS = 10;
    public static final float MAX_DB = 12f;
    /** Center frequencies of the graphic EQ, Hz (one octave apart). */
    public static final int[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    /** Preset names (Russian UI shows {@link #presetNamesRu}). */
    private static final String[] PRESET_NAMES_EN = {"Flat", "Rock", "Pop", "Jazz", "Classical", "Dance", "Hip-hop", "Electronic",
            "Bass boost", "Vocal", "Acoustic", "Metal", "Treble boost", "Soft", "Loudness", "Night"};
    private static final String[] PRESET_NAMES_RU = {"Обычный", "Рок", "Поп", "Джаз", "Классика", "Танцевальная", "Хип-хоп", "Электроника",
            "Бас+", "Вокал", "Акустика", "Метал", "Высокие+", "Мягкий", "Громкость", "Ночной"};
    private static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {5, 4, 3, 1, -1, -1, 1, 3, 4, 5},
            {-1, 1, 3, 4, 3, 1, -1, -1, 1, 2},
            {3, 2, 1, 2, -1, -1, 0, 1, 2, 3},
            {4, 3, 2, 1, -1, -1, 0, 2, 3, 4},
            {6, 5, 3, 0, 0, -1, 0, 2, 4, 4},
            {6, 5, 2, 3, -1, -1, 1, 0, 2, 3},
            {5, 4, 1, 0, -2, 2, 1, 1, 4, 5},
            {8, 7, 5, 3, 1, 0, 0, 0, 0, 0},
            {-2, -2, -1, 1, 4, 4, 3, 1, 0, -1},
            {4, 3, 2, 1, 1, 1, 2, 3, 3, 2},
            {5, 4, 3, 0, -2, -2, 0, 3, 5, 6},
            {0, 0, 0, 0, 0, 1, 3, 5, 6, 7},
            {-2, -1, 0, 2, 3, 2, 0, -1, -2, -3},
            {6, 4, 1, 0, -1, 0, 0, 1, 4, 5},
            {-3, -2, 0, 1, 2, 2, 1, 0, -2, -4},
    };

    private static volatile int version;
    private static volatile boolean eqEnabled;
    private static final float[] bands = new float[BANDS];
    private static volatile float preampDb, bassDb, trebleDb;
    private static volatile int widthPercent = 100;
    private static volatile int balance;
    private static volatile boolean mono, limiter = true, skipSilence;
    private static volatile int preset = PRESET_CUSTOM;

    static {
        SharedPreferences p = prefs();
        eqEnabled = p.getBoolean("eq_enabled", false);
        for (int i = 0; i < BANDS; i++) bands[i] = p.getFloat("band_" + i, 0f);
        preampDb = p.getFloat("preamp", 0f);
        bassDb = p.getFloat("bass", 0f);
        trebleDb = p.getFloat("treble", 0f);
        widthPercent = p.getInt("width", 100);
        balance = p.getInt("balance", 0);
        mono = p.getBoolean("mono", false);
        limiter = p.getBoolean("limiter", true);
        skipSilence = p.getBoolean("skip_silence", false);
        preset = p.getInt("preset", PRESET_CUSTOM);
    }

    private TelePremiumAudioEffects() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void changed() { version++; }

    static int version() { return version; }

    // ---- equalizer ----

    public static boolean isEqEnabled() { return eqEnabled; }

    public static void setEqEnabled(boolean enabled) {
        eqEnabled = enabled;
        prefs().edit().putBoolean("eq_enabled", enabled).apply();
        changed();
    }

    public static float getBand(int band) { return bands[band]; }

    public static float[] getBands() {
        synchronized (bands) { return bands.clone(); }
    }

    /** Moving a slider switches the equalizer on and marks the preset as custom. */
    public static void setBand(int band, float db) {
        db = clamp(db, -MAX_DB, MAX_DB);
        synchronized (bands) { bands[band] = db; }
        preset = PRESET_CUSTOM;
        eqEnabled = true;
        prefs().edit().putFloat("band_" + band, db).putInt("preset", PRESET_CUSTOM).putBoolean("eq_enabled", true).apply();
        changed();
    }

    public static String[] getPresetNames() {
        return org.telegram.messenger.telepremium.TPStrings.isRussian() ? PRESET_NAMES_RU.clone() : PRESET_NAMES_EN.clone();
    }

    public static int getPreset() { return preset; }

    /** Selecting a preset always enables the equalizer (the old UI left it off, so presets did nothing). */
    public static void usePreset(int index) {
        if (index < 0 || index >= PRESETS.length) return;
        SharedPreferences.Editor e = prefs().edit();
        synchronized (bands) {
            for (int i = 0; i < BANDS; i++) {
                bands[i] = PRESETS[index][i];
                e.putFloat("band_" + i, bands[i]);
            }
        }
        preset = index;
        eqEnabled = true;
        e.putInt("preset", index).putBoolean("eq_enabled", true).apply();
        changed();
    }

    // ---- tone ----

    public static float getPreamp() { return preampDb; }
    public static float getBass() { return bassDb; }
    public static float getTreble() { return trebleDb; }

    public static void setPreamp(float db) { preampDb = clamp(db, -MAX_DB, MAX_DB); prefs().edit().putFloat("preamp", preampDb).apply(); changed(); }
    public static void setBass(float db) { bassDb = clamp(db, -MAX_DB, MAX_DB); prefs().edit().putFloat("bass", bassDb).apply(); changed(); }
    public static void setTreble(float db) { trebleDb = clamp(db, -MAX_DB, MAX_DB); prefs().edit().putFloat("treble", trebleDb).apply(); changed(); }

    // ---- stereo ----

    /** 0 = mono image, 100 = original, 200 = extra wide. */
    public static int getWidth() { return widthPercent; }
    public static void setWidth(int percent) { widthPercent = Math.max(0, Math.min(200, percent)); prefs().edit().putInt("width", widthPercent).apply(); changed(); }

    /** -100 (left only) .. 0 .. 100 (right only). */
    public static int getBalance() { return balance; }
    public static void setBalance(int value) { balance = Math.max(-100, Math.min(100, value)); prefs().edit().putInt("balance", balance).apply(); changed(); }

    public static boolean getMono() { return mono; }
    public static void setMono(boolean value) { mono = value; prefs().edit().putBoolean("mono", value).apply(); changed(); }

    public static boolean isLimiter() { return limiter; }
    public static void setLimiter(boolean value) { limiter = value; prefs().edit().putBoolean("limiter", value).apply(); changed(); }

    // ---- playback ----

    public static boolean isSkipSilence() { return skipSilence; }
    public static void setSkipSilence(boolean value) {
        skipSilence = value;
        prefs().edit().putBoolean("skip_silence", value).apply();
        TelePremiumPlaybackController.getInstance().applySkipSilence();
    }

    public static void resetAll() {
        prefs().edit().clear().apply();
        synchronized (bands) { for (int i = 0; i < BANDS; i++) bands[i] = 0; }
        eqEnabled = false;
        preampDb = bassDb = trebleDb = 0;
        widthPercent = 100;
        balance = 0;
        mono = false;
        limiter = true;
        preset = PRESET_CUSTOM;
        changed();
    }

    /** True when anything changes the sound (used to show "On" in the settings). */
    public static boolean isActive() {
        return eqEnabled || bassDb != 0 || trebleDb != 0 || widthPercent != 100 || balance != 0 || mono;
    }

    public static String formatDb(float db) {
        return String.format(Locale.ROOT, "%+.1f dB", db);
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
