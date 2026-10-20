package com.v2ray.ang.handler

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import com.v2ray.ang.util.parsePo0Tokens
import com.v2ray.ang.util.po0ServerId
import com.v2ray.ang.util.validatePo0Tokens
import java.security.KeyStore
import java.security.MessageDigest
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
            .copy(storageIdentity = po0ConfigurationIdentity(encoded))
    }

    fun save(settings: Po0Settings) {
        val normalized = normalizePo0Settings(settings)
        val encoded = Po0SettingsCodec(getOrCreateKey()).encode(normalized)
        // One record replaces the entire configuration; a failed write cannot leave partial flags/tokens.
        check(MmkvManager.encodeSettings(CONFIG_KEY, Base64.encodeToString(encoded, Base64.NO_WRAP))) {
            "Po0 settings write failed"
        }
    }

    /** A missed IPC notification must not keep an obsolete credential batch running forever. */
    internal fun isCurrentConfiguration(settings: Po0Settings): Boolean =
        po0ConfigurationMatches(settings.storageIdentity, MmkvManager.decodeSettingsString(CONFIG_KEY))

    /** Explicit user recovery only. Proxy profiles and other settings are never changed. */
    @Synchronized
    fun reset(onCleared: () -> Unit = {}) = resetPo0Records(
        CONFIG_KEY, STATUS_KEY,
        MmkvManager::decodeSettingsString,
        { key, value -> MmkvManager.encodeSettings(key, value) },
        { onCleared(); keyStore().deleteEntry(KEY_ALIAS) }
    )

    fun readStatus(): Po0RunStatus {
        val json = MmkvManager.decodeSettingsString(STATUS_KEY) ?: return Po0RunStatus()
        return try {
            val status = Gson().fromJson(json, Po0RunStatus::class.java) ?: return Po0RunStatus()
            if (po0StatusMatchesConfiguration(status, MmkvManager.decodeSettingsString(CONFIG_KEY))) status
            else Po0RunStatus()
        } catch (_: RuntimeException) {
            Po0RunStatus()
        }
    }

    fun writeStatus(status: Po0RunStatus) {
        if (status.configurationId.isNotEmpty() &&
            !po0StatusMatchesConfiguration(status, MmkvManager.decodeSettingsString(CONFIG_KEY))) return
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

/** Each encrypted save has a random IV, so its digest identifies one saved configuration generation. */
internal fun po0ConfigurationIdentity(encodedConfig: String): String = MessageDigest.getInstance("SHA-256")
    .digest(encodedConfig.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun po0ConfigurationMatches(identity: String, encodedConfig: String?): Boolean =
    identity.isNotEmpty() && encodedConfig != null && identity == po0ConfigurationIdentity(encodedConfig)

/** Read-side validation also rejects an old writer that passed its check just before a reset/save. */
internal fun po0StatusMatchesConfiguration(status: Po0RunStatus, encodedConfig: String?): Boolean =
    encodedConfig != null &&
        (status.configurationId.isEmpty() || status.configurationId == po0ConfigurationIdentity(encodedConfig))

internal fun normalizePo0Settings(settings: Po0Settings, allowServerConflicts: Boolean = false): Po0Settings {
    require(settings.intervalSeconds in 1..3600) { "Invalid Po0 interval" }
    require(settings.tokenEntries.all { (token) ->
        token == token.trim() && parsePo0Tokens(token) == listOf(token) && validatePo0Tokens(token)
    }) { "Invalid Po0 credentials" }
    val entries = settings.tokenEntries.map { it.copy(name = it.name.trim()) }.distinctBy(Po0TokenEntry::token)
    require(allowServerConflicts || entries.map { po0ServerId(it.token) }.distinct().size == entries.size) {
        "Conflicting Po0 server modes"
    }
    require(!settings.enabled || entries.isNotEmpty()) { "Po0 credentials required" }
    return settings.copy(tokenEntries = entries)
}

/** Restore both records if a reset step fails; never leave only half of a configuration reset. */
internal fun resetPo0Records(
    configKey: String,
    statusKey: String,
    read: (String) -> String?,
    write: (String, String?) -> Boolean,
    deleteKey: () -> Unit,
) {
    val previousConfig = read(configKey)
    val previousStatus = read(statusKey)
    try {
        check(write(configKey, null)) { "Po0 credentials reset failed" }
        check(write(statusKey, null)) { "Po0 status reset failed" }
        // Delete the device key last, after both credential-owned records have been removed.
        deleteKey()
    } catch (error: Exception) {
        val configRestored = runCatching { write(configKey, previousConfig) }.getOrDefault(false)
        val statusRestored = runCatching { write(statusKey, previousStatus) }.getOrDefault(false)
        if (!configRestored || !statusRestored) error.addSuppressed(IllegalStateException("Po0 reset rollback failed"))
        throw error
    }
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
        require(listOf("enabled", "intervalSeconds", "networkChangeEnabled").all(root::has) &&
            (root.has("tokenEntries") || root.has("tokens"))) {
            "Incomplete Po0 configuration"
        }
        // The preceding release stored tokens without names. Migrate inside the same encrypted record.
        val entries = if (root.has("tokenEntries")) {
            val encodedEntries = root.get("tokenEntries")
            require(encodedEntries.isJsonArray) { "Invalid Po0 credential entries" }
            encodedEntries.asJsonArray.map { encodedEntry ->
                require(encodedEntry.isJsonObject) { "Invalid Po0 credential entry" }
                val entry = encodedEntry.asJsonObject
                val token = entry.get("token")
                val name = entry.get("name")
                val paused = entry.get("paused")
                require(token?.isJsonPrimitive == true && token.asJsonPrimitive.isString &&
                    (name == null || name.isJsonPrimitive && name.asJsonPrimitive.isString) &&
                    (paused == null || paused.isJsonPrimitive && paused.asJsonPrimitive.isBoolean)) {
                    "Invalid Po0 credential entry"
                }
                Po0TokenEntry(token.asString, name?.asString.orEmpty(), paused?.asBoolean ?: false)
            }
        } else {
            val encodedTokens = root.get("tokens")
            require(encodedTokens.isJsonArray) { "Invalid Po0 credentials" }
            encodedTokens.asJsonArray.flatMap { token ->
                require(token.isJsonPrimitive && token.asJsonPrimitive.isString) { "Invalid Po0 credential" }
                // The previous parser could persist Chinese-separated credentials as one token.
                // Split those records during migration so affected users can load and edit them.
                val tokens = parsePo0Tokens(token.asString)
                require(tokens.isNotEmpty() && validatePo0Tokens(token.asString)) { "Invalid Po0 credential" }
                tokens.map { Po0TokenEntry(it) }
            }
        }
        val settings = Gson().fromJson(root, Po0Settings::class.java)
        // Released versions allowed the same server with different @ modes. Keep every entry
        // editable on load; only a new save must resolve those conflicting maintenance targets.
        return normalizePo0Settings(settings.copy(tokenEntries = entries), allowServerConflicts = true)
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        val AAD = "v2rayNG.Po0.settings.v1".toByteArray(Charsets.UTF_8)
    }
}
