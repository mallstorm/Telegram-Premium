package org.telegram.messenger.telepremium.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal AI client for TelePremium features: OpenAI-compatible Chat Completions (covers OpenAI,
 * Gemini, OpenRouter, DeepSeek, Mistral, Groq, xAI, Yandex AI Studio, GigaChat, Ollama, LM Studio…)
 * and Anthropic Messages. Blocking calls run on a private executor; callbacks come on the UI thread.
 */
public final class TPAiClient {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tp-ai");
        t.setDaemon(true);
        return t;
    });

    public interface Callback<T> { void onResult(T result, String error); }

    private TPAiClient() {}

    public static <T> void async(java.util.concurrent.Callable<T> work, Callback<T> callback) {
        EXECUTOR.execute(() -> {
            T result = null;
            String error = null;
            try {
                result = work.call();
            } catch (Throwable t) {
                FileLog.e(t);
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            T r = result;
            String e = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(r, e));
        });
    }

    // ------------------------------------------------------------------ completion

    /** One-shot completion: a system instruction and a user message, returns the answer text. */
    public static String complete(TPAiStore.Connection c, String system, String user) throws IOException {
        if (c == null) throw new IOException("Нет подключения к ИИ");
        if (TextUtils.isEmpty(c.model)) throw new IOException("В подключении не выбрана модель");
        if (TPOmni.AUTH.equals(c.authType)) return TPOmni.complete(c, system, user);
        String key = TPAiStore.getSecret(c.id);
        try {
            if (TPAiProvider.ANTHROPIC.equals(c.protocol)) {
                JSONObject body = new JSONObject()
                        .put("model", c.model)
                        .put("max_tokens", 8192)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
                if (!TextUtils.isEmpty(system)) body.put("system", system);
                JSONObject json = request(c, key, "POST", "messages", body.toString());
                JSONArray content = json.optJSONArray("content");
                StringBuilder out = new StringBuilder();
                for (int i = 0; content != null && i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) out.append(part.optString("text"));
                }
                if (out.length() == 0) throw new IOException("Модель вернула пустой ответ");
                return out.toString();
            }
            JSONArray messages = new JSONArray();
            if (!TextUtils.isEmpty(system)) messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", user));
            JSONObject body = new JSONObject().put("model", c.model).put("messages", messages).put("stream", false);
            JSONObject json = request(c, key, "POST", "chat/completions", body.toString());
            JSONArray choices = json.optJSONArray("choices");
            JSONObject message = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0).optJSONObject("message");
            if (message == null) throw new IOException("Модель вернула пустой ответ");
            Object content = message.opt("content");
            String text;
            if (content instanceof JSONArray) {
                StringBuilder out = new StringBuilder();
                JSONArray parts = (JSONArray) content;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) out.append(part.optString("text"));
                }
                text = out.toString();
            } else {
                text = content == null || JSONObject.NULL.equals(content) ? "" : content.toString();
            }
            if (TextUtils.isEmpty(text.trim())) throw new IOException("Модель вернула пустой ответ");
            return text;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Explicit, user-authorized image analysis for providers supporting the public
     * OpenAI Chat Completions vision or Anthropic Messages image content formats.
     *
     * Subscription-proxy transports are deliberately excluded: they have different
     * media APIs and must never receive image bytes through a text-only fallback.
     * No API token, arbitrary URL or media path is included in the user payload.
     */
    public static String completeVision(TPAiStore.Connection c, String system, String user,
                                        List<byte[]> jpegImages) throws IOException {
        if (c == null || TextUtils.isEmpty(c.model)) throw new IOException("Выберите AI-подключение с поддержкой изображений");
        if (TPOmni.AUTH.equals(c.authType)) {
            return TPOmni.completeVision(c, system, user, jpegImages);
        }
        if (jpegImages == null || jpegImages.isEmpty() || jpegImages.size() > 5)
            throw new IOException("Для анализа выберите от 1 до 5 изображений");
        if (!TPAiProvider.CHAT.equals(c.protocol) && !TPAiProvider.ANTHROPIC.equals(c.protocol))
            throw new IOException("Выбранный API-протокол не поддерживает этот формат Vision");
        long size = 0;
        for (byte[] bytes : jpegImages) {
            if (bytes == null || bytes.length == 0 || bytes.length > 1_400_000)
                throw new IOException("Размер одного JPEG должен быть не более 1.4 МБ");
            size += bytes.length;
        }
        if (size > 5_000_000) throw new IOException("Слишком много данных изображений");

        try {
            String key = TPAiStore.getSecret(c.id);
            JSONArray parts = new JSONArray();
            if (TPAiProvider.ANTHROPIC.equals(c.protocol)) {
                parts.put(new JSONObject().put("type", "text").put("text", user));
                for (byte[] image : jpegImages) {
                    JSONObject source = new JSONObject().put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP));
                    parts.put(new JSONObject().put("type", "image").put("source", source));
                }
                JSONObject body = new JSONObject().put("model", c.model).put("max_tokens", 4096)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", parts)));
                if (!TextUtils.isEmpty(system)) body.put("system", system);
                JSONObject response = request(c, key, "POST", "messages", body.toString());
                JSONArray items = response.optJSONArray("content");
                StringBuilder out = new StringBuilder();
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null && "text".equals(item.optString("type"))) out.append(item.optString("text"));
                }
                if (out.length() == 0) throw new IOException("Vision-модель вернула пустой ответ");
                return out.toString();
            }

            parts.put(new JSONObject().put("type", "text").put("text", user));
            for (byte[] image : jpegImages) {
                String data = "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                        image, android.util.Base64.NO_WRAP);
                parts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", data).put("detail", "high")));
            }
            JSONArray messages = new JSONArray();
            if (!TextUtils.isEmpty(system))
                messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", parts));
            JSONObject payload = new JSONObject().put("model", c.model).put("stream", false)
                    .put("messages", messages);
            JSONObject response = request(c, key, "POST", "chat/completions", payload.toString());
            JSONArray choices = response.optJSONArray("choices");
            JSONObject msg = choices != null && choices.length() != 0 ? choices.optJSONObject(0).optJSONObject("message") : null;
            if (msg == null) throw new IOException("Vision-модель вернула пустой ответ");
            Object value = msg.opt("content");
            String result = value == null || JSONObject.NULL.equals(value) ? "" : String.valueOf(value);
            if (TextUtils.isEmpty(result.trim())) throw new IOException("Vision-модель вернула пустой ответ");
            return result;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Ошибка обработки изображений: " + exception.getMessage(), exception);
        }
    }

    /** Available model ids of the connection (sorted). */
    public static List<String> models(TPAiStore.Connection c) throws IOException {
        if (TPOmni.AUTH.equals(c.authType)) return TPOmni.models(c);
        String key = TPAiStore.getSecret(c.id);
        return models(c, key);
    }

    public static List<String> models(TPAiStore.Connection c, String key) throws IOException {
        try {
            JSONObject json = request(c, key, "GET", "models", null);
            JSONArray data = json.optJSONArray("data");
            if (data == null) data = json.optJSONArray("models");
            ArrayList<String> out = new ArrayList<>();
            for (int i = 0; data != null && i < data.length(); i++) {
                Object item = data.opt(i);
                String id = item instanceof JSONObject ? ((JSONObject) item).optString("id", ((JSONObject) item).optString("name")) : String.valueOf(item);
                if (id.startsWith("models/")) id = id.substring(7);
                if (item instanceof JSONObject && isZeroPricing(((JSONObject) item).optJSONObject("pricing"))) FREE_HINTS.add(base(c) + "|" + id);
                if (!TextUtils.isEmpty(id) && !out.contains(id)) out.add(id);
            }
            Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ access tiers

    private static final java.util.Set<String> FREE_HINTS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static String base(TPAiStore.Connection c) {
        return c.baseUrl == null ? "" : c.baseUrl.trim().replaceAll("/+$", "");
    }

    private static boolean isZeroPricing(JSONObject pricing) {
        if (pricing == null) return false;
        try {
            String p = pricing.optString("prompt", ""), cpl = pricing.optString("completion", "");
            return !p.isEmpty() && !cpl.isEmpty() && Double.parseDouble(p) == 0d && Double.parseDouble(cpl) == 0d;
        } catch (Exception e) {
            return false;
        }
    }

    /** Free / subscription / paid for a model of a connection (see TPOmni.tier). */
    public static int tier(TPAiStore.Connection c, String model) {
        int t = TPOmni.tier(c.providerId, c.authType, model);
        if (t != TPOmni.TIER_FREE && model != null && (FREE_HINTS.contains(base(c) + "|" + model) || model.endsWith(":free"))) t = TPOmni.TIER_FREE;
        if (t == TPOmni.TIER_UNKNOWN) t = TPOmni.TIER_PAID; // API keys are billed per token
        return t;
    }

    public static String tierLabel(int tier, boolean ru) {
        switch (tier) {
            case TPOmni.TIER_FREE: return ru ? "Бесплатная" : "Free";
            case TPOmni.TIER_SUBSCRIPTION: return ru ? "По подписке" : "Subscription";
            default: return ru ? "Платная" : "Paid";
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static JSONObject request(TPAiStore.Connection c, String key, String method, String path, String body) throws Exception {
        String base = c.baseUrl == null ? "" : c.baseUrl.trim().replaceAll("/+$", "");
        if (TextUtils.isEmpty(base)) throw new IOException("Не указан адрес API");
        URL url = new URL(base + "/" + path);
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        if (http instanceof HttpsURLConnection && isRussianCaHost(url.getHost())) {
            SSLSocketFactory factory = russianTrustFactory();
            if (factory != null) ((HttpsURLConnection) http).setSSLSocketFactory(factory);
        }
        http.setConnectTimeout(20000);
        http.setReadTimeout(180000);
        http.setRequestMethod(method);
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("User-Agent", "TelePremium");
        authorize(http, c, key);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            http.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = http.getOutputStream()) { out.write(bytes); }
        }
        int code = http.getResponseCode();
        String text = read(code >= 400 ? http.getErrorStream() : http.getInputStream());
        http.disconnect();
        if (code >= 400) throw new IOException(errorText(code, text));
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("Некорректный ответ сервера (HTTP " + code + ")");
        }
    }

    private static void authorize(HttpURLConnection http, TPAiStore.Connection c, String key) throws Exception {
        String auth = c.authType == null ? TPAiProvider.AUTH_BEARER : c.authType;
        if (TPAiProvider.ANTHROPIC.equals(c.protocol)) {
            http.setRequestProperty("x-api-key", key);
            http.setRequestProperty("anthropic-version", "2023-06-01");
            return;
        }
        switch (auth) {
            case TPAiProvider.AUTH_NONE:
                if (!TextUtils.isEmpty(key)) http.setRequestProperty("Authorization", "Bearer " + key);
                break;
            case TPAiProvider.AUTH_API_KEY:
                http.setRequestProperty("api-key", key);
                break;
            case TPAiProvider.AUTH_X_API_KEY:
                http.setRequestProperty("x-api-key", key);
                break;
            case TPAiProvider.AUTH_YANDEX:
                if (TextUtils.isEmpty(c.scope)) throw new IOException("Укажите ID каталога Yandex Cloud");
                http.setRequestProperty("Authorization", "Api-Key " + key);
                http.setRequestProperty("OpenAI-Project", c.scope);
                break;
            case TPAiProvider.AUTH_GIGACHAT:
                http.setRequestProperty("Authorization", "Bearer " + gigaChatToken(key, c.scope));
                break;
            default:
                if (TextUtils.isEmpty(key)) throw new IOException("Не указан API-ключ");
                http.setRequestProperty("Authorization", "Bearer " + key);
        }
        String host = http.getURL().getHost();
        if (host != null && (host.endsWith("openrouter.ai") || host.endsWith("polza.ai"))) {
            http.setRequestProperty("HTTP-Referer", "https://github.com/mallstorm/Telegram-Premium");
            http.setRequestProperty("X-Title", "TelePremium");
        }
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String errorText(int code, String raw) {
        String detail = null;
        try {
            JSONObject json = new JSONObject(raw);
            Object error = json.opt("error");
            if (error instanceof JSONObject) detail = ((JSONObject) error).optString("message");
            else if (error instanceof String) detail = (String) error;
            if (TextUtils.isEmpty(detail)) detail = json.optString("message");
        } catch (Exception ignore) {
        }
        if (TextUtils.isEmpty(detail)) detail = raw == null ? "" : raw.trim();
        if (detail.length() > 300) detail = detail.substring(0, 300);
        String prefix = code == 401 || code == 403 ? "Доступ запрещён — проверьте ключ"
                : code == 404 ? "Не найдено — проверьте адрес и модель"
                : code == 429 ? "Слишком много запросов или закончился лимит"
                : "Ошибка сервера";
        return prefix + " (HTTP " + code + ")" + (TextUtils.isEmpty(detail) ? "" : ": " + detail);
    }

    // ------------------------------------------------------------------ GigaChat

    private static final Map<String, String[]> GIGA_TOKENS = new HashMap<>();

    private static synchronized String gigaChatToken(String authorizationKey, String scope) throws Exception {
        if (TextUtils.isEmpty(authorizationKey)) throw new IOException("Введите ключ авторизации GigaChat");
        if (TextUtils.isEmpty(scope)) scope = "GIGACHAT_API_PERS";
        String cacheKey = scope + ":" + authorizationKey.hashCode();
        String[] cached = GIGA_TOKENS.get(cacheKey);
        if (cached != null && Long.parseLong(cached[1]) - 90_000L > System.currentTimeMillis()) return cached[0];
        URL url = new URL("https://ngw.devices.sberbank.ru:9443/api/v2/oauth");
        HttpsURLConnection http = (HttpsURLConnection) url.openConnection();
        SSLSocketFactory factory = russianTrustFactory();
        if (factory != null) http.setSSLSocketFactory(factory);
        http.setConnectTimeout(20000);
        http.setReadTimeout(30000);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        String basic = authorizationKey.trim();
        if (basic.regionMatches(true, 0, "Basic ", 0, 6)) basic = basic.substring(6);
        http.setRequestProperty("Authorization", "Basic " + basic);
        http.setRequestProperty("RqUID", UUID.randomUUID().toString());
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = http.getOutputStream()) {
            out.write(("scope=" + URLEncoder.encode(scope, "UTF-8")).getBytes(StandardCharsets.UTF_8));
        }
        int code = http.getResponseCode();
        String text = read(code >= 400 ? http.getErrorStream() : http.getInputStream());
        http.disconnect();
        if (code >= 400) throw new IOException("GigaChat: не удалось получить токен (HTTP " + code + "), проверьте ключ и область");
        JSONObject json = new JSONObject(text);
        String token = json.optString("access_token");
        if (TextUtils.isEmpty(token)) throw new IOException("GigaChat не вернул токен");
        long expiry = json.optLong("expires_at", 0);
        if (expiry > 0 && expiry < 10_000_000_000L) expiry *= 1000L;
        if (expiry <= 0) expiry = System.currentTimeMillis() + 29 * 60_000L;
        GIGA_TOKENS.put(cacheKey, new String[]{token, String.valueOf(expiry)});
        return token;
    }

    private static boolean isRussianCaHost(String host) {
        return host != null && (host.endsWith("giga.chat") || host.endsWith("sberbank.ru"));
    }

    private static SSLSocketFactory russianFactory;

    /** System trust plus the Russian Trusted Root CA (required by GigaChat endpoints). */
    private static synchronized SSLSocketFactory russianTrustFactory() {
        if (russianFactory != null) return russianFactory;
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            system.init((KeyStore) null);
            int index = 0;
            for (TrustManager tm : system.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    for (X509Certificate cert : ((X509TrustManager) tm).getAcceptedIssuers()) store.setCertificateEntry("sys" + (index++), cert);
                }
            }
            try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("telepremium/russian_trusted_root_ca.pem")) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                int n = 0;
                for (java.security.cert.Certificate cert : cf.generateCertificates(in)) store.setCertificateEntry("ru" + (n++), cert);
            }
            TrustManagerFactory combined = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            combined.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, combined.getTrustManagers(), null);
            russianFactory = context.getSocketFactory();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return russianFactory;
    }
}
