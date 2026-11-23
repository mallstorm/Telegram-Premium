package org.telegram.messenger.telepremium.cloud.vault;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Which secrets may protect a folder, and how they are turned into the KDF input.
 * The kind is mixed into the secret, so a PIN "123456" and a password "123456" can never collide.
 */
public final class VaultSecretPolicy {
    public enum Kind {
        PIN('N'), PASSWORD('W'), PATTERN('P');
        final char tag;
        Kind(char tag) { this.tag = tag; }
    }

    public enum Verdict { OK, TOO_SHORT, TOO_LONG, INVALID_CHARS, TRIVIAL, WEAK_BUT_ALLOWED }

    private static final Set<String> COMMON_PINS = new HashSet<>(Arrays.asList(
            "123456", "654321", "111111", "000000", "121212", "123123", "112233", "159753",
            "222222", "333333", "444444", "555555", "666666", "777777", "888888", "999999",
            "696969", "101010", "123321", "1234567", "12345678", "123456789", "1234567890",
            "000111", "123654", "147258", "147852", "789456", "135790", "246810", "202020"));

    private VaultSecretPolicy() {}

    public static Verdict check(Kind kind, String raw) {
        if (kind == null || raw == null) return Verdict.TOO_SHORT;
        switch (kind) {
            case PIN: return checkPin(raw);
            case PASSWORD: return checkPassword(raw);
            default: return checkPattern(raw);
        }
    }

    public static boolean isAcceptable(Verdict v) { return v == Verdict.OK || v == Verdict.WEAK_BUT_ALLOWED; }

    /** KDF input; the caller must zero the returned array. */
    public static char[] toSecret(Kind kind, String raw) {
        char[] out = new char[raw.length() + 2];
        out[0] = kind.tag;
        out[1] = ':';
        raw.getChars(0, raw.length(), out, 2);
        return out;
    }

    private static Verdict checkPin(String pin) {
        if (pin.length() < 6) return Verdict.TOO_SHORT;
        if (pin.length() > 32) return Verdict.TOO_LONG;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return Verdict.INVALID_CHARS;
        if (COMMON_PINS.contains(pin) || sameDigit(pin) || sequence(pin) || repeatedBlock(pin)) return Verdict.TRIVIAL;
        return Verdict.OK;
    }

    private static Verdict checkPassword(String pw) {
        if (pw.length() < 10) return Verdict.TOO_SHORT;
        if (pw.length() > 128) return Verdict.TOO_LONG;
        boolean lower = false, upper = false, digit = false, other = false;
        Set<Character> distinct = new HashSet<>();
        for (int i = 0; i < pw.length(); i++) {
            char c = pw.charAt(i);
            distinct.add(c);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else other = true;
        }
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (distinct.size() < 5) return Verdict.TRIVIAL;
        if (classes < 2 && pw.length() < 14) return Verdict.TRIVIAL;
        return Verdict.OK;
    }

    /** Pattern = digits 0-8, each used once (same encoding as the existing pattern view). */
    private static Verdict checkPattern(String p) {
        if (p.length() < 5) return Verdict.TOO_SHORT;
        if (p.length() > 9) return Verdict.TOO_LONG;
        boolean[] used = new boolean[9];
        for (int i = 0; i < p.length(); i++) {
            int v = p.charAt(i) - '0';
            if (v < 0 || v > 8 || used[v]) return Verdict.INVALID_CHARS;
            used[v] = true;
        }
        // At most ~10^6 combinations: usable, but clearly weaker than a PIN or password.
        return Verdict.WEAK_BUT_ALLOWED;
    }

    private static boolean sameDigit(String s) {
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != s.charAt(0)) return false;
        return true;
    }

    private static boolean sequence(String s) {
        boolean up = true, down = true;
        for (int i = 1; i < s.length(); i++) {
            int d = s.charAt(i) - s.charAt(i - 1);
            if (d != 1 && !(s.charAt(i - 1) == '9' && s.charAt(i) == '0')) up = false;
            if (d != -1 && !(s.charAt(i - 1) == '0' && s.charAt(i) == '9')) down = false;
        }
        return up || down;
    }

    private static boolean repeatedBlock(String s) {
        for (int block = 1; block <= s.length() / 2; block++) {
            if (s.length() % block != 0) continue;
            boolean all = true;
            for (int i = block; i < s.length() && all; i++) all = s.charAt(i) == s.charAt(i - block);
            if (all) return true;
        }
        return false;
    }
}
