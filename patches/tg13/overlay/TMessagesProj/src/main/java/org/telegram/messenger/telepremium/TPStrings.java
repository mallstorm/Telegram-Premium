package org.telegram.messenger.telepremium;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

/**
 * TelePremium UI strings. Telegram 13 strips all locale resources from the APK (localeFilters "zz")
 * and translates its own strings from server language packs, which never contain TelePremium keys.
 * English lives in res/values (TP_*), Russian in assets/telepremium/strings_ru.json; the language
 * follows the language chosen inside Telegram, not the system locale.
 */
public final class TPStrings {
    private static final String RU_ASSET = "telepremium/strings_ru.json";
    private static volatile HashMap<String, String> ru;

    private TPStrings() {}

    /**
     * @param key the resource name (passed explicitly: release builds may shorten resource names,
     *            so it cannot be derived from {@code res} at runtime)
     */
    public static String get(String key, int res) {
        Context context = ApplicationLoader.applicationContext;
        try {
            if (isRussian()) {
                String value = russian(context).get(key);
                if (value != null) return value;
            }
            return context.getString(res);
        } catch (Exception e) {
            FileLog.e(e);
            return LocaleController.getString(res);
        }
    }

    public static String format(String key, int res, Object... args) {
        try {
            return String.format(Locale.getDefault(), get(key, res), args);
        } catch (Exception e) {
            FileLog.e(e);
            return get(key, res);
        }
    }

    private static boolean isRussian() {
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            String code = info != null ? info.shortName : null;
            if (TextUtils.isEmpty(code)) {
                Locale locale = LocaleController.getInstance().getCurrentLocale();
                code = locale != null ? locale.getLanguage() : null;
            }
            return code != null && code.toLowerCase(Locale.ROOT).startsWith("ru");
        } catch (Exception e) {
            return false;
        }
    }

    private static HashMap<String, String> russian(Context context) {
        HashMap<String, String> map = ru;
        if (map != null) return map;
        synchronized (TPStrings.class) {
            if (ru != null) return ru;
            map = new HashMap<>();
            try (InputStream in = context.getAssets().open(RU_ASSET); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                JSONObject json = new JSONObject(out.toString("UTF-8"));
                for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                    String key = it.next();
                    map.put(key, json.getString(key));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            ru = map;
            return map;
        }
    }
}
