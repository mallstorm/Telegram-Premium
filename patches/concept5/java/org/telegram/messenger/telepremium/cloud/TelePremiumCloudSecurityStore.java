package org.telegram.messenger.telepremium.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

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

    public static boolean hasBiometricCredential(Context context) { return prefs(context).getBoolean(KEY_BIOMETRIC, false); }
    public static boolean hasUnlockMethod(Context context) { return hasPattern(context) || hasBiometricCredential(context); }
    public static boolean isProtectionEnabled(Context context) { return prefs(context).getBoolean(KEY_ENABLED, false) && hasUnlockMethod(context); }
    public static void setProtectionEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled && hasUnlockMethod(context)).apply();
        if (!enabled) TelePremiumCloudSecuritySession.lockNow();
    }
    public static boolean isWholeCloudLocked(Context context) { return isProtectionEnabled(context) && prefs(context).getBoolean(KEY_WHOLE, true); }
    public static void setWholeCloudLocked(Context context, boolean locked) { prefs(context).edit().putBoolean(KEY_WHOLE, locked).apply(); TelePremiumCloudSecuritySession.lockNow(); }
    public static boolean isBiometricEnabled(Context context) { return isProtectionEnabled(context) && hasBiometricCredential(context); }
    public static void setBiometricEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled);
        if (enabled) editor.putBoolean(KEY_ENABLED, true); else if (!hasPattern(context)) editor.putBoolean(KEY_ENABLED, false);
        editor.apply(); TelePremiumCloudSecuritySession.lockNow();
    }
    public static int getAutoLockSeconds(Context context) { return Math.max(15, prefs(context).getInt(KEY_AUTO_LOCK, 60)); }
    public static void setAutoLockSeconds(Context context, int seconds) { prefs(context).edit().putInt(KEY_AUTO_LOCK, Math.max(15, seconds)).apply(); TelePremiumCloudSecuritySession.lockNow(); }
    public static boolean hasPattern(Context context) {
        SharedPreferences p = prefs(context);
        return !TextUtils.isEmpty(p.getString(KEY_PATTERN_HASH, null)) && !TextUtils.isEmpty(p.getString(KEY_PATTERN_SALT, null));
    }
    public static boolean setPattern(Context context, String pattern) {
        if (!isValidPattern(pattern)) return false;
        try {
            byte[] salt = new byte[24]; new SecureRandom().nextBytes(salt);
            String saltString = Base64.encodeToString(salt, Base64.NO_WRAP);
            String hash = hash(pattern, saltString);
            prefs(context).edit().putString(KEY_PATTERN_SALT, saltString).putString(KEY_PATTERN_HASH, hash).putBoolean(KEY_ENABLED, true)
                    .putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply();
            TelePremiumCloudSecuritySession.lockNow(); return true;
        } catch (Throwable ignore) { return false; }
    }
    public static boolean verifyPattern(Context context, String pattern) {
        if (!hasPattern(context) || !isValidPattern(pattern) || remainingLockoutMs(context) > 0) return false;
        SharedPreferences p = prefs(context);
        boolean ok = constantTimeEquals(p.getString(KEY_PATTERN_HASH, ""), hash(pattern, p.getString(KEY_PATTERN_SALT, "")));
        if (ok) p.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply(); else registerFailure(context);
        return ok;
    }
    public static void clearPattern(Context context) {
        SharedPreferences p = prefs(context); boolean keepProtection = p.getBoolean(KEY_BIOMETRIC, false);
        p.edit().remove(KEY_PATTERN_HASH).remove(KEY_PATTERN_SALT).putBoolean(KEY_ENABLED, keepProtection).putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply();
        TelePremiumCloudSecuritySession.lockNow();
    }
    public static long remainingLockoutMs(Context context) { return Math.max(0L, prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()); }
    private static void registerFailure(Context context) {
        SharedPreferences p = prefs(context); int failures = p.getInt(KEY_FAILURES, 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt(KEY_FAILURES, failures);
        if (failures >= 5) e.putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + 30_000L);
        e.apply();
    }
    public static boolean isCategoryLocked(Context context, String category) {
        if (!isProtectionEnabled(context) || isWholeCloudLocked(context)) return false;
        if (CATEGORY_ALL.equals(category)) return anyCategoryLocked(context);
        if (CATEGORY_FAVORITES.equals(category) || CATEGORY_TRASH.equals(category))
            return prefs(context).getBoolean(PREFIX_CATEGORY + category, false) || anyMediaCategoryLocked(context);
        return prefs(context).getBoolean(PREFIX_CATEGORY + category, false);
    }
    public static void setCategoryLocked(Context context, String category, boolean locked) { prefs(context).edit().putBoolean(PREFIX_CATEGORY + category, locked).apply(); TelePremiumCloudSecuritySession.lockNow(); }
    public static boolean anyCategoryLocked(Context context) {
        if (!isProtectionEnabled(context)) return false;
        for (String category : CATEGORIES) if (prefs(context).getBoolean(PREFIX_CATEGORY + category, false)) return true;
        return false;
    }
    private static boolean anyMediaCategoryLocked(Context context) {
        String[] media = {CATEGORY_IMAGES, CATEGORY_VIDEO, CATEGORY_DOCUMENTS, CATEGORY_AUDIO, CATEGORY_ARCHIVES, CATEGORY_APK};
        for (String category : media) if (prefs(context).getBoolean(PREFIX_CATEGORY + category, false)) return true;
        return false;
    }
    public static boolean requiresCloudUnlock(Context context) { return isWholeCloudLocked(context) && !TelePremiumCloudSecuritySession.isUnlocked(context); }
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
        for (int i = 0; i < pattern.length(); i++) { int v = pattern.charAt(i) - '0'; if (v < 0 || v > 8 || used[v]) return false; used[v] = true; }
        return true;
    }
    private static String hash(String pattern, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8)); digest.update((byte) ':');
            return Base64.encodeToString(digest.digest(pattern.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Throwable e) { return ""; }
    }
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length, n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
