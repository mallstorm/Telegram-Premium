package org.telegram.messenger.telepremium.cloud;

import java.util.Locale;

/** Pure Cloud UI helpers (no Android dependencies): counts, sizes, MIME, errors. */
public final class TelePremiumCloudFormat {
    /** System MIME lookup by extension (MimeTypeMap on Android); may return null. */
    public interface MimeLookup { String fromExtension(String extension); }

    private TelePremiumCloudFormat() {}

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Explicit overrides first (system tables are wrong/missing for these), then system, then stored, then octet-stream. */
    public static String resolveMime(String name, String storedMime, MimeLookup lookup) {
        String ext = extension(name);
        String mapped = lookup == null || ext.isEmpty() ? null : lookup.fromExtension(ext);
        switch (ext) {
            case "apk": mapped = "application/vnd.android.package-archive"; break;
            case "rar": mapped = "application/vnd.rar"; break;
            case "7z": mapped = "application/x-7z-compressed"; break;
            case "doc": mapped = "application/msword"; break;
            case "docx": mapped = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; break;
            case "xls": mapped = "application/vnd.ms-excel"; break;
            case "xlsx": mapped = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; break;
            case "ppt": mapped = "application/vnd.ms-powerpoint"; break;
            case "pptx": mapped = "application/vnd.openxmlformats-officedocument.presentationml.presentation"; break;
            default: break;
        }
        if (mapped != null) return mapped;
        return storedMime != null && !storedMime.isEmpty() ? storedMime : "application/octet-stream";
    }

    public static String shortError(String error) {
        if (error == null || error.isEmpty()) return "неизвестная ошибка";
        return error.length() > 90 ? error.substring(0, 90) + "…" : error;
    }

    public static String compactNumber(double value) {
        if (value >= 10 || Math.abs(value - Math.rint(value)) < 0.05) return String.valueOf((int) Math.round(value));
        return String.format(Locale.US, "%.1f", value).replace('.', ',');
    }

    /** Russian plural forms: 1 файл, 2 файла, 5 файлов, 11 файлов, 21 файл. */
    public static String compactCount(int n) {
        if (n >= 1000000) return compactNumber(n / 1000000d) + " млн";
        if (n >= 1000) return compactNumber(n / 1000d) + " тыс.";
        int mod100 = n % 100, mod10 = n % 10;
        String word = mod100 >= 11 && mod100 <= 14 ? "файлов" : mod10 == 1 ? "файл" : mod10 >= 2 && mod10 <= 4 ? "файла" : "файлов";
        return n + " " + word;
    }

    public static String humanSize(long n) {
        if (n < 1024) return n + " Б";
        if (n < 1024 * 1024) return (n / 1024) + " КБ";
        if (n < 1024L * 1024 * 1024) return (n / (1024 * 1024)) + " МБ";
        return String.format(Locale.US, "%.1f", n / (1024d * 1024d * 1024d)).replace('.', ',') + " ГБ";
    }

    public static String categoryTitle(String kind) {
        if ("images".equals(kind)) return "Фото";
        if ("video".equals(kind)) return "Видео";
        if ("audio".equals(kind)) return "Аудио";
        if ("archives".equals(kind)) return "Архивы";
        if ("apk".equals(kind)) return "APK";
        if ("favorites".equals(kind)) return "Избранное";
        if ("trash".equals(kind)) return "Корзина";
        if ("all".equals(kind)) return "Все файлы";
        return "Документы";
    }
}
