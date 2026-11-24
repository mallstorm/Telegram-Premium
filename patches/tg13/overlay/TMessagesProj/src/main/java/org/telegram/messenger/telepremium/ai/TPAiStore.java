package org.telegram.messenger.telepremium.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AI connections. Metadata lives in SharedPreferences; API keys are encrypted with an
 * Android Keystore AES-GCM key (non-exportable) and never leave the device except to their own
 * endpoint.
 */
public final class TPAiStore {
    private static final String PREFS = "telepremium_ai_v1";
    private static final String KEY_LIST = "connections";
    private static final String KEY_DEFAULT = "default_id";
    private static final String ALIAS = "telepremium_ai_secrets_v1";

    public static final class Connection {
        public String id;
        public String providerId;
        public String name;
        public String baseUrl;
        public String protocol;
        public String authType;
        public String model;
        /** GigaChat scope or Yandex folder id. */
        public String scope;
        public long created;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("provider", providerId).put("name", name)
                    .put("base", baseUrl).put("protocol", protocol).put("auth", authType)
                    .put("model", model == null ? "" : model).put("scope", scope == null ? "" : scope)
                    .put("created", created);
        }

        static Connection fromJson(JSONObject o) {
            Connection c = new Connection();
            c.id = o.optString("id");
            c.providerId = o.optString("provider");
            c.name = o.optString("name");
            c.baseUrl = o.optString("base");
            c.protocol = o.optString("protocol", TPAiProvider.CHAT);
            c.authType = o.optString("auth", TPAiProvider.AUTH_BEARER);
            c.model = o.optString("model");
            c.scope = o.optString("scope");
            c.created = o.optLong("created");
            return c;
        }

        public String title() {
            return TextUtils.isEmpty(name) ? providerId : name;
        }
    }

    private TPAiStore() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Connection> list() {
        ArrayList<Connection> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) out.add(Connection.fromJson(o));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static Connection get(String id) {
        if (id == null) return null;
        for (Connection c : list()) if (id.equals(c.id)) return c;
        return null;
    }

    /** The connection used by TelePremium features (first one when none is chosen). */
    public static Connection getDefault() {
        List<Connection> all = list();
        if (all.isEmpty()) return null;
        String id = prefs().getString(KEY_DEFAULT, null);
        for (Connection c : all) if (c.id.equals(id)) return c;
        return all.get(0);
    }

    public static boolean hasAny() {
        return !list().isEmpty();
    }

    public static void setDefault(String id) {
        prefs().edit().putString(KEY_DEFAULT, id).apply();
    }

    public static synchronized Connection save(Connection connection, String secret) {
        if (TextUtils.isEmpty(connection.id)) connection.id = UUID.randomUUID().toString();
        if (connection.created == 0) connection.created = System.currentTimeMillis();
        List<Connection> all = list();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(connection.id)) { all.set(i, connection); replaced = true; break; }
        }
        if (!replaced) all.add(connection);
        write(all);
        if (secret != null) setSecret(connection.id, secret);
        if (all.size() == 1) setDefault(connection.id);
        return connection;
    }

    public static synchronized void delete(String id) {
        List<Connection> all = list();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        write(all);
        prefs().edit().remove("secret_" + id).apply();
        if (id.equals(prefs().getString(KEY_DEFAULT, null))) prefs().edit().remove(KEY_DEFAULT).apply();
    }

    private static void write(List<Connection> all) {
        JSONArray array = new JSONArray();
        try {
            for (Connection c : all) array.put(c.toJson());
        } catch (Exception ignore) {
        }
        prefs().edit().putString(KEY_LIST, array.toString()).apply();
    }

    // ------------------------------------------------------------------ secrets

    public static void setSecret(String id, String secret) {
        try {
            prefs().edit().putString("secret_" + id, encrypt(secret == null ? "" : secret)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать ключ", e);
        }
    }

    public static String getSecret(String id) {
        String raw = prefs().getString("secret_" + id, "");
        if (TextUtils.isEmpty(raw)) return "";
        try {
            return decrypt(raw);
        } catch (Exception e) {
            return "";
        }
    }

    /** Small shared values (Telegraph token, etc.), encrypted the same way. */
    public static void putPrivate(String key, String value) {
        setSecret("private_" + key, value);
    }

    public static String getPrivate(String key) {
        return getSecret("private_" + key);
    }

    /** Delete only feature-owned encrypted values; leaves AI credentials untouched. */
    public static synchronized void erasePrivatePrefix(String prefix) {
        SharedPreferences.Editor editor=prefs().edit();
        for(String key:prefs().getAll().keySet())if(key.startsWith("secret_private_"+prefix))editor.remove(key);
        if(!editor.commit())throw new IllegalStateException("Private data deletion failed");
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        if (value.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        int colon = value.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("bad secret");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(value.substring(0, colon), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(value.substring(colon + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
