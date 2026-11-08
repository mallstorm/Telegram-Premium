package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only subset of Android's org.json.JSONObject with the same semantics for the
 * methods TelePremium uses: put(name, null) removes the mapping, opt* return fallbacks.
 */
public class JSONObject {
    public static final Object NULL = new Object();
    private final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String json) throws JSONException {
        Parser p = new Parser(json);
        Object v = p.value();
        p.skipWs();
        if (!(v instanceof JSONObject) || p.pos != json.length()) throw new JSONException("Not a JSON object");
        map.putAll(((JSONObject) v).map);
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) throw new JSONException("Names must be non-null");
        if (value == null) map.remove(name); else map.put(name, value);
        return this;
    }
    public JSONObject put(String name, long value) throws JSONException { return put(name, (Object) value); }
    public JSONObject put(String name, int value) throws JSONException { return put(name, (Object) value); }
    public JSONObject put(String name, boolean value) throws JSONException { return put(name, (Object) value); }
    public JSONObject put(String name, double value) throws JSONException { return put(name, (Object) value); }

    public java.util.Iterator<String> keys() {return map.keySet().iterator();}
    public boolean has(String name) { return map.containsKey(name); }
    public Object opt(String name) { return map.get(name); }

    public String getString(String name) throws JSONException {
        Object v = map.get(name);
        if (v == null) throw new JSONException("No value for " + name);
        return String.valueOf(v);
    }
    public int getInt(String name) throws JSONException { return (int) getLong(name); }
    public long getLong(String name) throws JSONException {
        if (!map.containsKey(name)) throw new JSONException("Missing " + name);
        Object v = map.get(name);
        try { return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v)); }
        catch (RuntimeException e) { throw new JSONException("Not a number " + name); }
    }
    public JSONArray getJSONArray(String name) throws JSONException {
        Object v = map.get(name); if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Not an array " + name);
    }
    public String optString(String name) { return optString(name, ""); }
    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return v == null ? fallback : String.valueOf(v);
    }
    public long optLong(String name) { return optLong(name, 0L); }
    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return v == null ? fallback : Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return fallback; }
    }
    public int optInt(String name) { return optInt(name, 0); }
    public int optInt(String name, int fallback) { return (int) optLong(name, fallback); }
    public boolean optBoolean(String name) { return optBoolean(name, false); }
    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) return (Boolean) v;
        return v == null ? fallback : Boolean.parseBoolean(String.valueOf(v));
    }
    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }
    public JSONObject getJSONObject(String name) throws JSONException {JSONObject value=optJSONObject(name);if(value==null)throw new JSONException("Not an object: "+name);return value;}
    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            write(sb, e.getKey());
            sb.append(':');
            write(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null || v == NULL) { sb.append("null"); return; }
        if (v instanceof Number || v instanceof Boolean || v instanceof JSONObject || v instanceof JSONArray) { sb.append(v); return; }
        String s = String.valueOf(v);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }

        void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object value() throws JSONException {
            skipWs();
            if (pos >= s.length()) throw new JSONException("Unexpected end");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String num = s.substring(start, pos);
            if (num.isEmpty()) throw new JSONException("Unexpected character at " + start);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            long l = Long.parseLong(num);
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
        }

        JSONObject object() throws JSONException {
            JSONObject o = new JSONObject();
            pos++;
            skipWs();
            if (s.charAt(pos) == '}') { pos++; return o; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                if (s.charAt(pos++) != ':') throw new JSONException("Expected ':'");
                o.put(key, value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') return o;
                if (c != ',') throw new JSONException("Expected ',' or '}'");
            }
        }

        JSONArray array() throws JSONException {
            JSONArray a = new JSONArray();
            pos++;
            skipWs();
            if (s.charAt(pos) == ']') { pos++; return a; }
            while (true) {
                a.put(value());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') return a;
                if (c != ',') throw new JSONException("Expected ',' or ']'");
            }
        }

        String string() throws JSONException {
            if (s.charAt(pos) != '"') throw new JSONException("Expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; break;
                    default: sb.append(e);
                }
            }
        }
    }
}
