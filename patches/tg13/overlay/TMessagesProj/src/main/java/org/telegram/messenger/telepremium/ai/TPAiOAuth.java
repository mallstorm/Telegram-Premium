package org.telegram.messenger.telepremium.ai;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Browser sign-in with OAuth 2.0 Authorization Code + PKCE (S256) and a loopback callback
 * (127.0.0.1, random port) for gateways that issue an API key to third-party apps:
 * OpenRouter and Polza.AI. The verifier never leaves the device until the code exchange.
 */
public final class TPAiOAuth {
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    public interface Listener {
        void onKey(String apiKey);
        void onError(String error);
    }

    public static final class Session {
        public final String authUrl;
        final String providerId;
        final String verifier;
        final String state;
        final String callbackUrl;
        final ServerSocket server;
        volatile boolean cancelled;

        Session(String providerId, String authUrl, String verifier, String state, String callbackUrl, ServerSocket server) {
            this.providerId = providerId;
            this.authUrl = authUrl;
            this.verifier = verifier;
            this.state = state;
            this.callbackUrl = callbackUrl;
            this.server = server;
        }

        public void cancel() {
            cancelled = true;
            try { server.close(); } catch (Exception ignore) {}
        }
    }

    private TPAiOAuth() {}

    public static boolean supports(String providerId) {
        return TPAiProvider.OPENROUTER_ACCOUNT.equals(providerId) || TPAiProvider.POLZA_ACCOUNT.equals(providerId);
    }

    /** Opens the loopback listener and returns the URL to show in the browser. */
    public static Session start(String providerId, Listener listener) throws IOException {
        if (!supports(providerId)) throw new IOException("Вход через аккаунт не поддерживается");
        String verifier = randomBase64Url(48);
        if (verifier.length() > 64) verifier = verifier.substring(0, 64);
        String challenge = challenge(verifier);
        String state = randomHex(16);
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
        String callback = "http://127.0.0.1:" + server.getLocalPort() + "/callback";
        String url;
        if (TPAiProvider.OPENROUTER_ACCOUNT.equals(providerId)) {
            url = "https://openrouter.ai/auth?callback_url=" + enc(callback)
                    + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
        } else {
            url = "https://polza.ai/api/auth/authorize?response_type=code&callback_url=" + enc(callback)
                    + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256"
                    + "&state=" + enc(state) + "&app_name=TelePremium";
        }
        Session session = new Session(providerId, url, verifier, state, callback, server);
        Thread thread = new Thread(() -> await(session, listener), "tp-ai-oauth");
        thread.setDaemon(true);
        thread.start();
        return session;
    }

    private static void await(Session session, Listener listener) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            while (!session.cancelled && System.currentTimeMillis() < deadline) {
                Socket socket;
                try {
                    socket = session.server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try (Socket s = socket) {
                    Uri callback = readCallback(s);
                    if (callback == null) { page(s, 404, "Страница не найдена"); continue; }
                    String error = callback.getQueryParameter("error");
                    if (!TextUtils.isEmpty(error)) {
                        page(s, 200, "Вход отклонён. Можно закрыть вкладку.");
                        fail(listener, "Вход отклонён: " + error);
                        return;
                    }
                    String code = callback.getQueryParameter("code");
                    String returnedState = callback.getQueryParameter("state");
                    boolean stateOk = TPAiProvider.OPENROUTER_ACCOUNT.equals(session.providerId)
                            ? (returnedState == null || MessageDigest.isEqual(returnedState.getBytes(), session.state.getBytes()))
                            : returnedState != null && MessageDigest.isEqual(returnedState.getBytes(), session.state.getBytes());
                    if (TextUtils.isEmpty(code) || !stateOk) {
                        page(s, 200, "Некорректный ответ сервиса. Попробуйте ещё раз.");
                        fail(listener, "Некорректный ответ OAuth");
                        return;
                    }
                    String key = exchange(session, code);
                    page(s, 200, "TelePremium подключён. Вернитесь в приложение.");
                    AndroidUtilities.runOnUIThread(() -> listener.onKey(key));
                    return;
                }
            }
            if (!session.cancelled) fail(listener, "Истекло время ожидания входа");
        } catch (Exception e) {
            FileLog.e(e);
            if (!session.cancelled) fail(listener, e.getMessage() == null ? "Ошибка входа" : e.getMessage());
        } finally {
            try { session.server.close(); } catch (Exception ignore) {}
        }
    }

    private static String exchange(Session session, String code) throws Exception {
        JSONObject body;
        String endpoint;
        if (TPAiProvider.OPENROUTER_ACCOUNT.equals(session.providerId)) {
            endpoint = "https://openrouter.ai/api/v1/auth/keys";
            body = new JSONObject().put("code", code).put("code_verifier", session.verifier).put("code_challenge_method", "S256");
        } else {
            endpoint = "https://polza.ai/api/auth/token";
            body = new JSONObject().put("grant_type", "authorization_code").put("code", code)
                    .put("code_verifier", session.verifier).put("callback_url", session.callbackUrl);
        }
        HttpURLConnection http = (HttpURLConnection) new URL(endpoint).openConnection();
        http.setConnectTimeout(20000);
        http.setReadTimeout(30000);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", "application/json");
        http.setRequestProperty("Accept", "application/json");
        try (OutputStream out = http.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code2 = http.getResponseCode();
        String text = TPAiClient.read(code2 >= 400 ? http.getErrorStream() : http.getInputStream());
        http.disconnect();
        if (code2 >= 400) throw new IOException("Обмен кода не удался (HTTP " + code2 + ")");
        JSONObject json = new JSONObject(text);
        String key = json.optString("key", json.optString("api_key"));
        if (TextUtils.isEmpty(key)) throw new IOException("Сервис не вернул API-ключ");
        return key;
    }

    private static void fail(Listener listener, String error) {
        AndroidUtilities.runOnUIThread(() -> listener.onError(error));
    }

    private static Uri readCallback(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2 || !"GET".equals(parts[0])) return null;
        String header;
        while ((header = reader.readLine()) != null && !header.isEmpty()) { /* skip headers */ }
        Uri uri = Uri.parse("http://127.0.0.1" + parts[1]);
        return "/callback".equals(uri.getPath()) ? uri : null;
    }

    private static void page(Socket socket, int status, String message) {
        try {
            String body = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                    + "<body style=\"font-family:sans-serif;padding:32px\"><h2>" + message + "</h2></body></html>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (Exception ignore) {
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomBase64Url(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
