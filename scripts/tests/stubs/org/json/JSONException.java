package org.json;

/** Test-only stand-in for Android's org.json (the JDK has none). */
public class JSONException extends Exception {
    public JSONException(String message) { super(message); }
}
