package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class TelePremiumCloudSecurityStore {
    private static final String PREFS = "telepremium_cloud_security_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WHOLE = "whole_cloud";
    private static final String KEY_BIOMETRIC = "biometric";
    private static final String KEY_PATTERN_HASH = "pattern_hash";
    private static final String KEY_PATTERN_SALT = "pattern_salt";
    private static final String KEY_AUTO_LOCK = "auto_lock_seconds";
    private static final String KEY_FAILURES = "pattern_failures";
    private static final String KEY_LOCKOUT_UNTIL = "pattern_lockout_until";
    private static final String KEY_LOCKOUT_LEVEL = "pattern_lockout_level";
    /** Escalating lockout after every 5 wrong patterns: 30 s, 5 min, 15 min, then 1 h. */
    private static final long[] LOCKOUT_STEPS_MS = {30_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L};
    private static final int FAILURES_PER_LOCKOUT = 5;
    private static final String PREFIX_CATEGORY = "category_";

    public static final String CATEGORY_IMAGES = "images";
    public static final String CATEGORY_VIDEO = "video";
    public static final String CATEGORY_DOCUMENTS = "documents";
    public static final String CATEGORY_AUDIO = "audio";
    public static final String CATEGORY_ARCHIVES = "archives";
    public static final String CATEGORY_APK = "apk";
    public static final String CATEGORY_FAVORITES = "favorites";
    public static final String CATEGORY_TRASH = "trash";
    public static final String CATEGORY_ALL = "all";

    private static final String[] CATEGORIES = {
            CATEGORY_IMAGES, CATEGORY_VIDEO, CATEGORY_DOCUMENTS, CATEGORY_AUDIO,
            CATEGORY_ARCHIVES, CATEGORY_APK, CATEGORY_FAVORITES, CATEGORY_TRASH
    };

    private TelePremiumCloudSecurityStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasBiometricCredential(Context context) {
        return prefs(context).getBoolean(KEY_BIOMETRIC, false);
    }

    public static boolean hasUnlockMethod(Context context) {
        return hasPattern(context) || hasBiometricCredential(context);
    }

    public static boolean isProtectionEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false) && hasUnlockMethod(context);
    }

    public static void setProtectionEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled && hasUnlockMethod(context)).apply();
        if (!enabled) TelePremiumCloudSecuritySession.lockNow();
    }

    public static boolean isWholeCloudLocked(Context context) {
        return isProtectionEnabled(context) && prefs(context).getBoolean(KEY_WHOLE, true);
    }

    public static void setWholeCloudLocked(Context context, boolean locked) {
        prefs(context).edit().putBoolean(KEY_WHOLE, locked).apply();
        TelePremiumCloudSecuritySession.lockNow();
    }

    public static boolean isBiometricEnabled(Context context) {
        return isProtectionEnabled(context) && hasBiometricCredential(context);
    }

    public static void setBiometricEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled);
        if (enabled) {
            editor.putBoolean(KEY_ENABLED, true);
        } else if (!hasPattern(context)) {
            editor.putBoolean(KEY_ENABLED, false);
        }
        editor.apply();
        TelePremiumCloudSecuritySession.lockNow();
    }

    public static int getAutoLockSeconds(Context context) {
        return Math.max(15, prefs(context).getInt(KEY_AUTO_LOCK, 60));
    }

    public static void setAutoLockSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_AUTO_LOCK, Math.max(15, seconds)).apply();
        TelePremiumCloudSecuritySession.lockNow();
    }

    public static boolean hasPattern(Context context) {
        SharedPreferences p = prefs(context);
        return !TextUtils.isEmpty(p.getString(KEY_PATTERN_HASH, null)) && !TextUtils.isEmpty(p.getString(KEY_PATTERN_SALT, null));
    }

    public static boolean setPattern(Context context, String pattern) {
        if (!isValidPattern(pattern)) return false;
        try {
            byte[] salt = new byte[24];
            new SecureRandom().nextBytes(salt);
            String saltString = Base64.encodeToString(salt, Base64.NO_WRAP);
            String hash = hashV2(pattern, saltString);
            if (TextUtils.isEmpty(hash)) return false;
            prefs(context).edit()
                    .putString(KEY_PATTERN_SALT, saltString)
                    .putString(KEY_PATTERN_HASH, hash)
                    .putBoolean(KEY_ENABLED, true)
                    .putInt(KEY_FAILURES, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .putInt(KEY_LOCKOUT_LEVEL, 0)
                    .commit();
            TelePremiumCloudSecuritySession.lockNow();
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static boolean verifyPattern(Context context, String pattern) {
        if (!hasPattern(context) || !isValidPattern(pattern) || remainingLockoutMs(context) > 0) return false;
        SharedPreferences p = prefs(context);
        String salt = p.getString(KEY_PATTERN_SALT, "");
        String expected = p.getString(KEY_PATTERN_HASH, "");
        boolean legacy = !expected.startsWith(HASH_V2_PREFIX);
        boolean ok = constantTimeEquals(expected, legacy ? hashLegacy(pattern, salt) : hashV2Matching(pattern, salt, expected));
        if (ok) {
            SharedPreferences.Editor editor = p.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).putInt(KEY_LOCKOUT_LEVEL, 0);
            if (legacy) {
                // Transparent upgrade: legacy single-pass SHA-256 -> PBKDF2 on the first successful unlock.
                String upgraded = hashV2(pattern, salt);
                if (!TextUtils.isEmpty(upgraded)) editor.putString(KEY_PATTERN_HASH, upgraded);
            }
            editor.apply();
        } else {
            registerFailure(context);
        }
        return ok;
    }

    public static void clearPattern(Context context) {
        SharedPreferences p = prefs(context);
        boolean keepProtection = p.getBoolean(KEY_BIOMETRIC, false);
        p.edit()
                .remove(KEY_PATTERN_HASH)
                .remove(KEY_PATTERN_SALT)
                .putBoolean(KEY_ENABLED, keepProtection)
                .putInt(KEY_FAILURES, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .apply();
        TelePremiumCloudSecuritySession.lockNow();
    }

    public static long remainingLockoutMs(Context context) {
        long until = prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    private static void registerFailure(Context context) {
        SharedPreferences p = prefs(context);
        int failures = p.getInt(KEY_FAILURES, 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt(KEY_FAILURES, failures);
        if (failures >= FAILURES_PER_LOCKOUT) {
            int level = Math.max(0, p.getInt(KEY_LOCKOUT_LEVEL, 0));
            long duration = LOCKOUT_STEPS_MS[Math.min(level, LOCKOUT_STEPS_MS.length - 1)];
            e.putInt(KEY_FAILURES, 0)
                    .putInt(KEY_LOCKOUT_LEVEL, level + 1)
                    .putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + duration);
        }
        e.commit();
    }

    /** Remaining wrong attempts before the next lockout. */
    public static int attemptsBeforeLockout(Context context) {
        return Math.max(1, FAILURES_PER_LOCKOUT - prefs(context).getInt(KEY_FAILURES, 0));
    }

    /**
     * PBKDF2 takes noticeable time on low-end phones, so pattern checks run off the UI thread.
     * The callback is delivered on the UI thread.
     */
    public static void verifyPatternAsync(Context context, String pattern, org.telegram.messenger.Utilities.Callback<Boolean> callback) {
        final Context app = context.getApplicationContext();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            boolean ok = verifyPattern(app, pattern);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.run(ok));
        });
    }

    public static void setPatternAsync(Context context, String pattern, org.telegram.messenger.Utilities.Callback<Boolean> callback) {
        final Context app = context.getApplicationContext();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            boolean ok = setPattern(app, pattern);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.run(ok));
        });
    }

    public static boolean isCategoryLocked(Context context, String category) {
        if (!isProtectionEnabled(context) || isWholeCloudLocked(context)) return false;
        if (CATEGORY_ALL.equals(category)) return anyCategoryLocked(context);
        if (CATEGORY_FAVORITES.equals(category) || CATEGORY_TRASH.equals(category)) {
            return prefs(context).getBoolean(PREFIX_CATEGORY + category, false) || anyMediaCategoryLocked(context);
        }
        return prefs(context).getBoolean(PREFIX_CATEGORY + category, false);
    }

    public static void setCategoryLocked(Context context, String category, boolean locked) {
        prefs(context).edit().putBoolean(PREFIX_CATEGORY + category, locked).apply();
        TelePremiumCloudSecuritySession.lockNow();
    }

    public static boolean anyCategoryLocked(Context context) {
        if (!isProtectionEnabled(context)) return false;
        for (String category : CATEGORIES) {
            if (prefs(context).getBoolean(PREFIX_CATEGORY + category, false)) return true;
        }
        return false;
    }

    private static boolean anyMediaCategoryLocked(Context context) {
        String[] media = {CATEGORY_IMAGES, CATEGORY_VIDEO, CATEGORY_DOCUMENTS, CATEGORY_AUDIO, CATEGORY_ARCHIVES, CATEGORY_APK};
        for (String category : media) {
            if (prefs(context).getBoolean(PREFIX_CATEGORY + category, false)) return true;
        }
        return false;
    }

    public static boolean requiresCloudUnlock(Context context) {
        return isWholeCloudLocked(context) && !TelePremiumCloudSecuritySession.isUnlocked(context);
    }

    public static boolean requiresCategoryUnlock(Context context, String category) {
        if (!isProtectionEnabled(context) || TelePremiumCloudSecuritySession.isUnlocked(context)) return false;
        return isWholeCloudLocked(context) || isCategoryLocked(context, category);
    }

    public static boolean isNodeProtected(Context context, CloudNode node) {
        if (node == null || node.type != CloudNode.TYPE_FILE || !isProtectionEnabled(context)) return false;
        return isWholeCloudLocked(context) || isCategoryLocked(context, categoryForNode(node));
    }

    public static String categoryForNode(CloudNode node) {
        if (node == null) return CATEGORY_DOCUMENTS;
        String name = node.name == null ? "" : node.name.toLowerCase();
        String mime = node.mimeType == null ? "" : node.mimeType.toLowerCase();
        if (mime.startsWith("image/")) return CATEGORY_IMAGES;
        if (mime.startsWith("video/")) return CATEGORY_VIDEO;
        if (mime.startsWith("audio/")) return CATEGORY_AUDIO;
        if (name.endsWith(".apk")) return CATEGORY_APK;
        if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") || name.endsWith(".tar") || name.endsWith(".gz")) return CATEGORY_ARCHIVES;
        return CATEGORY_DOCUMENTS;
    }

    public static String categoryLabel(String category) {
        if (CATEGORY_IMAGES.equals(category)) return "Фото";
        if (CATEGORY_VIDEO.equals(category)) return "Видео";
        if (CATEGORY_AUDIO.equals(category)) return "Аудио";
        if (CATEGORY_ARCHIVES.equals(category)) return "Архивы";
        if (CATEGORY_APK.equals(category)) return "APK";
        if (CATEGORY_FAVORITES.equals(category)) return "Избранное";
        if (CATEGORY_TRASH.equals(category)) return "Корзина";
        if (CATEGORY_ALL.equals(category)) return "Все файлы";
        return "Документы";
    }

    private static boolean isValidPattern(String pattern) {
        if (pattern == null || pattern.length() < 4 || pattern.length() > 9) return false;
        boolean[] used = new boolean[9];
        for (int i = 0; i < pattern.length(); i++) {
            int v = pattern.charAt(i) - '0';
            if (v < 0 || v > 8 || used[v]) return false;
            used[v] = true;
        }
        return true;
    }

    // Format: p2$<algorithm>$<iterations>$<base64 derived key>
    private static final String HASH_V2_PREFIX = "p2$";
    private static final int PBKDF2_ITERATIONS = 60_000;
    private static final int PBKDF2_KEY_BITS = 256;

    private static String hashV2(String pattern, String salt) {
        String algorithm = Build.VERSION.SDK_INT >= 26 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        return pbkdf2(pattern, salt, algorithm, PBKDF2_ITERATIONS);
    }

    private static String hashV2Matching(String pattern, String salt, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return "";
        try {
            int iterations = Integer.parseInt(parts[2]);
            if (iterations < 1_000 || iterations > 5_000_000) return "";
            return pbkdf2(pattern, salt, parts[1], iterations);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String pbkdf2(String pattern, String salt, String algorithm, int iterations) {
        char[] secret = pattern.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(secret, salt.getBytes(StandardCharsets.UTF_8), iterations, PBKDF2_KEY_BITS);
        try {
            byte[] value = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
            String encoded = Base64.encodeToString(value, Base64.NO_WRAP);
            Arrays.fill(value, (byte) 0);
            return HASH_V2_PREFIX + algorithm + "$" + iterations + "$" + encoded;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return "";
        } finally {
            spec.clearPassword();
            Arrays.fill(secret, '\0');
        }
    }

    private static String hashLegacy(String pattern, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            byte[] value = digest.digest(pattern.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(value, Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
