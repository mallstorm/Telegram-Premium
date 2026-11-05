package org.telegram.messenger.telepremium.ai;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI provider profiles (catalog shared with OmniChat: assets/telepremium/ai_providers.json).
 * Two wire protocols cover the catalog: OpenAI-compatible Chat Completions and Anthropic Messages.
 * Account providers (OpenRouter, Polza.AI) sign in through OAuth PKCE and return an API key.
 */
public final class TPAiProvider {
    public static final String CHAT = "chat";
    public static final String ANTHROPIC = "anthropic";

    public static final String AUTH_BEARER = "bearer";
    public static final String AUTH_X_API_KEY = "x-api-key";
    public static final String AUTH_API_KEY = "api-key";
    public static final String AUTH_NONE = "none";
    public static final String AUTH_GIGACHAT = "gigachat-oauth";
    public static final String AUTH_YANDEX = "yandex-api-key";
    public static final String AUTH_OAUTH = "account-oauth";

    public static final String OPENROUTER_ACCOUNT = "openrouter-account";
    public static final String POLZA_ACCOUNT = "polza-account";
    public static final String CUSTOM = "custom";

    public final String id;
    public final String name;
    public final String category;
    public final String baseUrl;
    public final String protocol;
    public final String authType;
    public final boolean supported;

    TPAiProvider(String id, String name, String category, String baseUrl, String protocol, String authType, boolean supported) {
        this.id = id;
        this.name = name;
        this.category = category == null ? "" : category;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.protocol = protocol;
        this.authType = authType;
        this.supported = supported;
    }

    public boolean needsKey() { return !AUTH_NONE.equals(authType) && !AUTH_OAUTH.equals(authType); }
    public boolean needsEndpoint() { return TextUtils.isEmpty(baseUrl); }
    public boolean needsScope() { return AUTH_GIGACHAT.equals(authType) || AUTH_YANDEX.equals(authType); }
    public boolean isAccount() { return AUTH_OAUTH.equals(authType); }

    /** Hint for the scope field (GigaChat access scope or Yandex folder id). */
    public String scopeHint(boolean ru) {
        if (AUTH_GIGACHAT.equals(authType)) return ru ? "Область: GIGACHAT_API_PERS / B2B / CORP" : "Scope: GIGACHAT_API_PERS / B2B / CORP";
        if (AUTH_YANDEX.equals(authType)) return ru ? "ID каталога Yandex Cloud" : "Yandex Cloud folder ID";
        return "";
    }

    public String defaultModel(String scope) {
        switch (id) {
            case "openai": return "gpt-4o-mini";
            case "anthropic": return "claude-sonnet-4-5";
            case "gemini": return "gemini-2.5-flash";
            case "gigachat": return "GigaChat";
            case "yandex": return TextUtils.isEmpty(scope) ? "" : "gpt://" + scope + "/yandexgpt/latest";
            case "deepseek": return "deepseek-chat";
            case "mistral": return "mistral-small-latest";
            case "groq": return "llama-3.3-70b-versatile";
            case "xai": return "grok-3-mini";
            case OPENROUTER_ACCOUNT: case "openrouter": return "openrouter/auto";
            default: return "";
        }
    }

    private static List<TPAiProvider> cache;

    public static synchronized List<TPAiProvider> all(Context context) {
        if (cache != null) return cache;
        ArrayList<TPAiProvider> out = new ArrayList<>();
        out.add(new TPAiProvider(OPENROUTER_ACCOUNT, "OpenRouter · вход через аккаунт", "Account", "https://openrouter.ai/api/v1", CHAT, AUTH_OAUTH, true));
        out.add(new TPAiProvider(POLZA_ACCOUNT, "Polza.AI · вход через аккаунт", "Account", "https://polza.ai/api/v1", CHAT, AUTH_OAUTH, true));
        try (InputStream in = context.getAssets().open("telepremium/ai_providers.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buffer.write(b, 0, n);
            JSONArray array = new JSONArray(buffer.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < array.length(); i++) {
                JSONObject e = array.optJSONObject(i);
                if (e == null) continue;
                String id = e.optString("id");
                String auth = e.optString("auth_type", AUTH_BEARER);
                JSONArray protocols = e.optJSONArray("protocols");
                boolean chat = false, messages = false;
                for (int p = 0; protocols != null && p < protocols.length(); p++) {
                    String v = protocols.optString(p);
                    if ("chat-completions".equals(v)) chat = true;
                    if ("messages".equals(v)) messages = true;
                }
                String protocol = "anthropic".equals(id) ? ANTHROPIC : CHAT;
                boolean authOk = AUTH_BEARER.equals(auth) || AUTH_X_API_KEY.equals(auth) || AUTH_API_KEY.equals(auth)
                        || AUTH_NONE.equals(auth) || AUTH_GIGACHAT.equals(auth) || AUTH_YANDEX.equals(auth);
                boolean supported = authOk && (chat || "anthropic".equals(id));
                if (!supported) continue; // native-only APIs (Replicate, AI21) are not offered
                String base = e.isNull("base_url") ? "" : e.optString("base_url").replaceAll("/+$", "");
                String name = CUSTOM.equals(id) ? "Произвольный OpenAI-compatible" : e.optString("name", id);
                out.add(new TPAiProvider(id, name, e.optString("category"), base, protocol, auth, supported));
            }
        } catch (Exception ignore) {
        }
        boolean hasCustom = false;
        for (TPAiProvider p : out) if (CUSTOM.equals(p.id)) { hasCustom = true; break; }
        if (!hasCustom) out.add(new TPAiProvider(CUSTOM, "Произвольный OpenAI-compatible", "Custom", "", CHAT, AUTH_BEARER, true));
        cache = Collections.unmodifiableList(out);
        return cache;
    }

    public static TPAiProvider find(Context context, String id) {
        for (TPAiProvider p : all(context)) if (p.id.equals(id)) return p;
        return null;
    }
}
