package org.json;

import java.util.ArrayList;
import java.util.List;

/** Test-only subset of Android's org.json.JSONArray. */
public class JSONArray {
    final List<Object> values = new ArrayList<>();

    public JSONArray() {}

    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
    public Object opt(int index) { return index >= 0 && index < values.size() ? values.get(index) : null; }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object v = opt(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Value at " + index + " is not a JSONObject");
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            JSONObject.write(sb, values.get(i));
        }
        return sb.append(']').toString();
    }
}
