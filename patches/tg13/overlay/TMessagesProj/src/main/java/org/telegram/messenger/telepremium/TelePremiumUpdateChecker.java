package org.telegram.messenger.telepremium;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * TelePremium's own update channel (Telegram's updater is disabled for the fork).
 *
 * Source: a public GitHub repository ("owner/repo", latest release must contain the APK and
 * telepremium-update.json written by scripts/make_update_manifest.py) or a direct HTTPS URL
 * of such a JSON manifest. The downloaded APK is installed only when its SHA-256 matches the
 * manifest AND it is signed by the same certificate as the running app.
 */
public final class TelePremiumUpdateChecker {
    public static final String MANIFEST_ASSET = "telepremium-update.json";
    private static final String PREFS = "telepremium_updates";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_AUTO = "auto";
    private static final long AUTO_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int TIMEOUT_MS = 20_000;
    private static volatile boolean busy;

    public static final class Release {
        public int versionCode;
        public String versionName;
        public String tag;
        public String apkUrl;
        public long size;
        public String sha256;
        public String certSha256;
    }

    public interface CheckCallback { void onResult(Release newer, String error); }
    public interface ProgressCallback { void onProgress(long done, long total); }
    public interface DownloadCallback { void onDone(File apk, String error); }

    private TelePremiumUpdateChecker() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getSource() { return prefs().getString(KEY_SOURCE, ""); }
    public static void setSource(String source) { prefs().edit().putString(KEY_SOURCE, source == null ? "" : source.trim()).apply(); }
    public static boolean isConfigured() { return !TextUtils.isEmpty(getSource()); }
    public static boolean isAutoCheck() { return prefs().getBoolean(KEY_AUTO, true); }
    public static void setAutoCheck(boolean value) { prefs().edit().putBoolean(KEY_AUTO, value).apply(); }
    public static long getLastCheck() { return prefs().getLong(KEY_LAST_CHECK, 0L); }

    public static int installedVersionCode() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Called from LaunchActivity.checkAppUpdate: silent check at most once a day. */
    public static void checkFromLaunch(Activity activity, boolean force, Runnable onFinished) {
        if (!isConfigured() || (!force && (!isAutoCheck() || System.currentTimeMillis() - getLastCheck() < AUTO_INTERVAL_MS))) {
            if (onFinished != null) onFinished.run();
            return;
        }
        check((newer, error) -> {
            if (onFinished != null) onFinished.run();
            if (newer != null && activity != null && !activity.isFinishing()) {
                org.telegram.ui.TelePremiumUpdateUi.offer(activity, newer);
            }
        });
    }

    public static void check(CheckCallback callback) {
        String source = getSource();
        if (TextUtils.isEmpty(source)) { callback.onResult(null, "Источник обновлений не задан"); return; }
        Utilities.globalQueue.postRunnable(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchRelease(source);
                prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                if (release.versionCode <= installedVersionCode()) release = null;
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final Release result = release;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result, err));
        });
    }

    static Release fetchRelease(String source) throws Exception {
        String manifestUrl;
        JSONObject githubRelease = null;
        if (source.startsWith("https://")) {
            manifestUrl = source;
        } else if (source.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            githubRelease = new JSONObject(readText("https://api.github.com/repos/" + source + "/releases/latest"));
            manifestUrl = assetUrl(githubRelease, MANIFEST_ASSET);
            if (manifestUrl == null) throw new IllegalStateException("В релизе нет " + MANIFEST_ASSET);
        } else {
            throw new IllegalArgumentException("Источник: owner/repo или https://…/telepremium-update.json");
        }
        JSONObject m = new JSONObject(readText(manifestUrl));
        Release r = new Release();
        r.versionCode = m.getInt("versionCode");
        r.versionName = m.optString("versionName", "");
        r.tag = m.optString("tag", r.versionName);
        r.size = m.optLong("size", -1);
        r.sha256 = m.getString("sha256").toLowerCase(Locale.US);
        r.certSha256 = m.optString("certSha256", "").toLowerCase(Locale.US);
        String apk = m.getString("apk");
        if (githubRelease != null) {
            r.apkUrl = assetUrl(githubRelease, apk);
        } else {
            r.apkUrl = apk.startsWith("https://") ? apk : manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1) + apk;
        }
        if (r.apkUrl == null || !r.apkUrl.startsWith("https://")) throw new IllegalStateException("APK не найден в релизе");
        if (!r.sha256.matches("[0-9a-f]{64}")) throw new IllegalStateException("Некорректный SHA-256 в манифесте");
        return r;
    }

    private static String assetUrl(JSONObject release, String name) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (name.equals(a.optString("name"))) return a.optString("browser_download_url", null);
        }
        return null;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TelePremium-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/json, */*");
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IllegalStateException("HTTP " + code + " для " + Uri.parse(url).getHost());
        return c;
    }

    private static String readText(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) throw new IllegalStateException("Ответ слишком большой");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    public static File updatesDir() {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "tp_updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void download(Release release, ProgressCallback progress, DownloadCallback callback) {
        if (busy) { callback.onDone(null, "Загрузка уже идёт"); return; }
        busy = true;
        Utilities.globalQueue.postRunnable(() -> {
            File target = new File(updatesDir(), "TelePremium-" + release.versionCode + ".apk");
            String error = null;
            try {
                File[] old = updatesDir().listFiles();
                if (old != null) for (File f : old) if (!f.equals(target)) f.delete();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpURLConnection c = open(release.apkUrl);
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : release.size;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[256 * 1024];
                    long done = 0, lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        done += n;
                        if (progress != null && done - lastReport > 512 * 1024) {
                            lastReport = done;
                            final long d = done;
                            AndroidUtilities.runOnUIThread(() -> progress.onProgress(d, total));
                        }
                    }
                } finally {
                    c.disconnect();
                }
                String actual = hex(digest.digest());
                if (!actual.equals(release.sha256)) throw new SecurityException("SHA-256 не совпадает: файл повреждён или подменён");
                String apkCert = archiveCertSha256(target);
                String ownCert = installedCertSha256();
                if (apkCert == null || ownCert == null || !apkCert.equals(ownCert)) {
                    throw new SecurityException("APK подписан другим ключом — установка поверх текущей версии невозможна");
                }
                if (!TextUtils.isEmpty(release.certSha256) && !release.certSha256.equals(apkCert)) {
                    throw new SecurityException("Подпись APK не совпадает с манифестом");
                }
            } catch (Exception e) {
                FileLog.e(e);
                target.delete();
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                busy = false;
            }
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> callback.onDone(err == null ? target : null, err));
        });
    }

    public static void install(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    public static boolean canRequestInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    public static String installedCertSha256() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return certSha256(signatures(ctx.getPackageManager(), ctx.getPackageName(), null));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String archiveCertSha256(File apk) {
        try {
            return certSha256(signatures(ApplicationLoader.applicationContext.getPackageManager(), null, apk));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageManager pm, String packageName, File archive) throws Exception {
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= 28) {
            int flags = PackageManager.GET_SIGNING_CERTIFICATES;
            info = archive != null ? pm.getPackageArchiveInfo(archive.getAbsolutePath(), flags) : pm.getPackageInfo(packageName, flags);
            if (info == null || info.signingInfo == null) return null;
            return info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        }
        int flags = PackageManager.GET_SIGNATURES;
        info = archive != null ? pm.getPackageArchiveInfo(archive.getAbsolutePath(), flags) : pm.getPackageInfo(packageName, flags);
        return info == null ? null : info.signatures;
    }

    private static String certSha256(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) return null;
        // The current signer is the last entry of the rotation history.
        Signature current = signatures[signatures.length - 1];
        return hex(MessageDigest.getInstance("SHA-256").digest(current.toByteArray()));
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
