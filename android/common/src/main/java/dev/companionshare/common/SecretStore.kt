// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.common

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Store session credentials encrypted using the device's Android Keystore. */
object SecretStore {
    private const val ALIAS = "companionshare-credentials-v1"
    private const val PREFS = "companionshare"
    private const val SERVER = "server"
    private const val OWNER_ID = "owner_id"
    private const val OWNER_SECRET = "owner_secret"
    private const val VIEWER_ID = "viewer_id"
    private const val VIEWER_TOKEN = "viewer_token"
    private const val ENROLLMENT = "enrollment"

    data class Identity(val deviceId: String, val token: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28) { "invalid encrypted credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun owner(context: Context): Identity {
        val storage = prefs(context)
        val id = storage.getString(OWNER_ID, null)
        val token = storage.getString(OWNER_SECRET, null)
        if (id != null && token != null) return Identity(id, decrypt(token))
        val newId = random(16)
        val newToken = random(32)
        check(storage.edit().putString(OWNER_ID, newId)
            .putString(OWNER_SECRET, encrypt(newToken)).commit()) { "credential save failed" }
        return Identity(newId, newToken)
    }

    fun viewer(context: Context): Identity? {
        val storage = prefs(context)
        val id = storage.getString(VIEWER_ID, null) ?: return null
        val token = storage.getString(VIEWER_TOKEN, null) ?: return null
        return Identity(id, decrypt(token))
    }

    fun saveViewer(context: Context, id: String, token: String) {
        require(Regex("[A-Za-z0-9_-]{20,32}").matches(id))
        require(Regex("[A-Za-z0-9_-]{40,64}").matches(token))
        check(prefs(context).edit().putString(VIEWER_ID, id)
            .putString(VIEWER_TOKEN, encrypt(token)).commit()) { "credential save failed" }
    }

    fun clearViewer(context: Context) {
        prefs(context).edit().remove(VIEWER_ID).remove(VIEWER_TOKEN).apply()
    }

    fun enrollment(context: Context): String? =
        prefs(context).getString(ENROLLMENT, null)?.let(::decrypt)

    fun saveEnrollment(context: Context, value: String) {
        require(Regex("[A-Za-z0-9_-]{40,64}").matches(value))
        prefs(context).edit().putString(ENROLLMENT, encrypt(value)).apply()
    }

    fun clearEnrollment(context: Context) {
        prefs(context).edit().remove(ENROLLMENT).apply()
    }

    fun server(context: Context): String = prefs(context).getString(SERVER, "") ?: ""

    fun saveServer(context: Context, url: String) {
        require(url.startsWith("wss://") && url.endsWith("/ws")) { "TLS WebSocket required" }
        prefs(context).edit().putString(SERVER, url).apply()
    }

    private fun random(n: Int): String = ByteArray(n).apply { SecureRandom().nextBytes(this) }
        .let { Base64.encodeToString(it, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE) }
}
