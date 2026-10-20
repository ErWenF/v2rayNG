package com.v2ray.ang.handler

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.util.parsePo0Tokens
import com.v2ray.ang.util.validatePo0Tokens
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Po0 credentials have a device-bound lifetime, separate from portable proxy profiles. */
object Po0SettingsStore {
    private const val CONFIG_KEY = "pref_po0_encrypted_config_v1"
    private const val STATUS_KEY = "pref_po0_status_v1"
    private const val KEY_ALIAS = "${BuildConfig.APPLICATION_ID}.po0.credentials.v1"

    // Callers own the IO dispatcher; neither Keystore nor MMKV is accessed by a composable.
    fun read(): Po0Settings {
        val encoded = MmkvManager.decodeSettingsString(CONFIG_KEY) ?: return Po0Settings()
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Po0 device key unavailable")
        return Po0SettingsCodec(key).decode(Base64.decode(encoded, Base64.NO_WRAP))
    }

    fun save(settings: Po0Settings) {
        val normalized = normalizePo0Settings(settings)
        val encoded = Po0SettingsCodec(getOrCreateKey()).encode(normalized)
        // One record replaces the entire configuration; a failed write cannot leave partial flags/tokens.
        check(MmkvManager.encodeSettings(CONFIG_KEY, Base64.encodeToString(encoded, Base64.NO_WRAP))) {
            "Po0 settings write failed"
        }
    }

    fun readStatus(): Po0RunStatus {
        val json = MmkvManager.decodeSettingsString(STATUS_KEY) ?: return Po0RunStatus()
        return try {
            Gson().fromJson(json, Po0RunStatus::class.java) ?: Po0RunStatus()
        } catch (_: RuntimeException) {
            Po0RunStatus()
        }
    }

    fun writeStatus(status: Po0RunStatus) {
        check(MmkvManager.encodeSettings(STATUS_KEY, Gson().toJson(status))) { "Po0 status write failed" }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }
}

internal fun normalizePo0Settings(settings: Po0Settings): Po0Settings {
    require(settings.intervalSeconds in 1..3600) { "Invalid Po0 interval" }
    require(settings.tokens.all { token ->
        token == token.trim() && parsePo0Tokens(token) == listOf(token) && validatePo0Tokens(token)
    }) { "Invalid Po0 credentials" }
    val tokens = settings.tokens.distinct()
    require(!settings.enabled || tokens.isNotEmpty()) { "Po0 credentials required" }
    return settings.copy(tokens = tokens)
}

/** Pure codec so authenticated encryption and configuration validation can be tested on the JVM. */
internal class Po0SettingsCodec(private val key: SecretKey) {
    fun encode(settings: Po0Settings): ByteArray {
        val normalized = normalizePo0Settings(settings)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val json = Gson().toJson(normalized).toByteArray(Charsets.UTF_8)
        return byteArrayOf(VERSION, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(json)
    }

    fun decode(bytes: ByteArray): Po0Settings {
        require(bytes.size >= 2 + IV_SIZE + TAG_SIZE && bytes[0] == VERSION && bytes[1].toInt() == IV_SIZE) {
            "Invalid Po0 encrypted configuration"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(2, 2 + IV_SIZE)))
        cipher.updateAAD(AAD)
        val json = cipher.doFinal(bytes.copyOfRange(2 + IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
        val root = JsonParser.parseString(json).asJsonObject
        require(listOf("enabled", "tokens", "intervalSeconds", "networkChangeEnabled").all(root::has)) {
            "Incomplete Po0 configuration"
        }
        return normalizePo0Settings(Gson().fromJson(root, Po0Settings::class.java))
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        val AAD = "v2rayNG.Po0.settings.v1".toByteArray(Charsets.UTF_8)
    }
}
