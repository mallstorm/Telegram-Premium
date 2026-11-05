package com.omnichat.app.security

import android.content.SharedPreferences

/**
 * Small encrypted-value facade for account repositories.
 *
 * It centralizes ciphertext persistence/error handling without merging credential namespaces:
 * callers still choose both SharedPreferences file and [SecretCipher] alias explicitly.
 */
class EncryptedPreferences(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher
) {
    fun read(key: String): String = runCatching {
        cipher.decrypt(prefs.getString(key, "").orEmpty())
    }.getOrDefault("")

    fun write(key: String, value: String) {
        val editor = prefs.edit()
        if (value.isBlank()) editor.remove(key)
        else editor.putString(key, cipher.encrypt(value))
        editor.apply()
    }

    fun remove(vararg keys: String) {
        prefs.edit().also { editor -> keys.forEach(editor::remove) }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
