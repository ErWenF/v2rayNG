package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class Po0SettingsStoreTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun credentialsAndStandalonePreferencesRoundTripWithoutPlaintext() {
        val settings = Po0Settings(
            true, listOf(Po0TokenEntry("pgnfw_first@0", "家庭服务器"), Po0TokenEntry("pgnfw_second", "移动网络")),
            600, true, true
        )
        val codec = Po0SettingsCodec(key())
        val encrypted = codec.encode(settings)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("pgnfw_"))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("家庭服务器"))
        assertEquals(settings, codec.decode(encrypted))
        assertNotEquals(encrypted.toList(), codec.encode(settings).toList())
    }

    @Test
    fun tamperedCredentialsAndAnotherDeviceKeyAreRejected() {
        val codec = Po0SettingsCodec(key())
        val encrypted = codec.encode(Po0Settings(tokenEntries = listOf(Po0TokenEntry("pgnfw_example"))))
        val modified = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { codec.decode(modified) }
        assertThrows(AEADBadTagException::class.java) { Po0SettingsCodec(key()).decode(encrypted) }
    }

    @Test
    fun invalidOrUnknownEnvelopeDoesNotBecomeAnEnabledDefault() {
        val codec = Po0SettingsCodec(key())
        assertThrows(IllegalArgumentException::class.java) { codec.decode(byteArrayOf()) }
        val unknown = codec.encode(Po0Settings()).apply { this[0] = 2 }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(unknown) }
    }

    @Test
    fun savingValidatesCredentialsAndIntervalBeforeReplacingAnything() {
        assertThrows(IllegalArgumentException::class.java) { normalizePo0Settings(Po0Settings(enabled = true)) }
        assertThrows(IllegalArgumentException::class.java) { normalizePo0Settings(Po0Settings(intervalSeconds = 0)) }
        assertThrows(IllegalArgumentException::class.java) { normalizePo0Settings(Po0Settings(intervalSeconds = 3601)) }
        listOf("bad", "pgnfw_a,pgnfw_b", "pgnfw_a@5", "pgnfw_a，pgnfw_b", "pgnfw_a；pgnfw_b").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizePo0Settings(Po0Settings(tokenEntries = listOf(Po0TokenEntry(token))))
            }
        }
        assertEquals(600, Po0Settings().intervalSeconds)
    }

    @Test
    fun namesAreTrimmedAndDuplicateCredentialsKeepTheirFirstNameAndPosition() {
        val normalized = normalizePo0Settings(Po0Settings(tokenEntries = listOf(
            Po0TokenEntry("pgnfw_first@0", "  家庭服务器  "),
            Po0TokenEntry("pgnfw_second", ""),
            Po0TokenEntry("pgnfw_first@0", "重复条目"),
        )))
        assertEquals(listOf(
            Po0TokenEntry("pgnfw_first@0", "家庭服务器"),
            Po0TokenEntry("pgnfw_second", ""),
        ), normalized.tokenEntries)
        assertEquals(listOf("pgnfw_first@0", "pgnfw_second"), normalized.tokens)
    }

    @Test
    fun precedingReleasedTokenArrayMigratesInOrderWithoutLosingModesOrPreferences() {
        val secretKey = key()
        val codec = Po0SettingsCodec(secretKey)
        val previousRelease = encryptJson(secretKey, """
            {"enabled":true,"tokens":["pgnfw_fifo","pgnfw_fixed@0","pgnfw_last@4"],
             "intervalSeconds":900,"networkChangeEnabled":false,"standaloneEnabled":true}
        """.trimIndent())
        val migrated = codec.decode(previousRelease)
        assertEquals(Po0Settings(
            enabled = true,
            tokenEntries = listOf(
                Po0TokenEntry("pgnfw_fifo"), Po0TokenEntry("pgnfw_fixed@0"), Po0TokenEntry("pgnfw_last@4"),
            ),
            intervalSeconds = 900,
            networkChangeEnabled = false,
            standaloneEnabled = true,
        ), migrated)
        assertEquals(migrated, codec.decode(codec.encode(migrated)))
    }

    @Test
    fun precedingFormatWithoutStandalonePreferenceKeepsItDisabled() {
        val secretKey = key()
        val settings = Po0SettingsCodec(secretKey).decode(encryptJson(secretKey, """
            {"enabled":false,"tokens":["pgnfw_fifo"],"intervalSeconds":600,"networkChangeEnabled":true}
        """.trimIndent()))
        assertFalse(settings.standaloneEnabled)
        assertEquals(listOf(Po0TokenEntry("pgnfw_fifo")), settings.tokenEntries)
    }

    @Test
    fun migrationRepairsChineseSeparatedCredentialsAcceptedByThePreviousParser() {
        val secretKey = key()
        val settings = Po0SettingsCodec(secretKey).decode(encryptJson(secretKey, """
            {"enabled":true,"tokens":["pgnfw_first，pgnfw_second；pgnfw_third@4","pgnfw_first"],
             "intervalSeconds":600,"networkChangeEnabled":true,"standaloneEnabled":false}
        """.trimIndent()))
        assertEquals(listOf("pgnfw_first", "pgnfw_second", "pgnfw_third@4"), settings.tokens)
        assertEquals(listOf("", "", ""), settings.tokenEntries.map { it.name })
    }

    @Test
    fun malformedNamedEntriesDoNotFallBackToLegacyTokensOrSilentlyLoseCredentials() {
        val secretKey = key()
        val codec = Po0SettingsCodec(secretKey)
        listOf("null", "[null]", "[{}]", "[{\"token\":null}]", "[{\"token\":\"pgnfw_a\",\"name\":null}]").forEach { entries ->
            val json = """
                {"enabled":false,"tokenEntries":$entries,"tokens":["pgnfw_legacy"],
                 "intervalSeconds":600,"networkChangeEnabled":true,"standaloneEnabled":false}
            """.trimIndent()
            assertThrows(IllegalArgumentException::class.java) { codec.decode(encryptJson(secretKey, json)) }
        }
    }

    @Test
    fun pausedStateIsEncryptedAndOlderNamedEntriesDefaultToActive() {
        val secretKey = key()
        val codec = Po0SettingsCodec(secretKey)
        val settings = Po0Settings(tokenEntries = listOf(Po0TokenEntry("pgnfw_paused", "Paused", paused = true)))
        assertEquals(settings, codec.decode(codec.encode(settings)))
        val legacy = codec.decode(encryptJson(secretKey, """
            {"enabled":false,"tokenEntries":[{"token":"pgnfw_active","name":"Active"}],
             "intervalSeconds":600,"networkChangeEnabled":true}
        """.trimIndent()))
        assertFalse(legacy.tokenEntries.single().paused)
        assertEquals(listOf("pgnfw_active"), legacy.activeTokens)
        assertTrue(settings.activeTokens.isEmpty())
    }

    @Test
    fun conflictingServerModesRemainEditableOnLoadButCannotBeSaved() {
        val secretKey = key()
        val codec = Po0SettingsCodec(secretKey)
        val legacy = codec.decode(encryptJson(secretKey, """
            {"enabled":true,"tokens":["pgnfw_same","pgnfw_same@1","pgnfw_other@4"],
             "intervalSeconds":600,"networkChangeEnabled":true}
        """.trimIndent()))
        assertEquals(listOf("pgnfw_same", "pgnfw_same@1", "pgnfw_other@4"), legacy.tokens)
        assertThrows(IllegalArgumentException::class.java) { codec.encode(legacy) }
        assertThrows(IllegalArgumentException::class.java) {
            normalizePo0Settings(legacy.copy(tokenEntries = listOf(
                Po0TokenEntry("pgnfw_same@0"), Po0TokenEntry("pgnfw_same@4", paused = true)
            )))
        }
        assertEquals(listOf("pgnfw_other@4"), normalizePo0Settings(legacy.copy(tokenEntries = legacy.tokenEntries.takeLast(1))).tokens)
    }

    @Test
    fun invalidPausedFieldIsRejectedWithoutLosingOtherEntries() {
        val secretKey = key()
        listOf("null", "\"true\"", "1").forEach { value ->
            val json = """
                {"enabled":false,"tokenEntries":[{"token":"pgnfw_a","name":"A","paused":$value}],
                 "intervalSeconds":600,"networkChangeEnabled":true}
            """.trimIndent()
            assertThrows(IllegalArgumentException::class.java) { Po0SettingsCodec(secretKey).decode(encryptJson(secretKey, json)) }
        }
    }

    @Test
    fun credentialResetRemovesOnlyModuleRecordsBeforeDeletingTheKey() {
        val records = mutableMapOf<String, String?>("config" to "encrypted", "status" to "history", "profiles" to "keep")
        var keyDeleted = false
        resetPo0Records("config", "status", records::get, { name, value -> records[name] = value; true }) {
            assertEquals(null, records["config"])
            assertEquals(null, records["status"])
            keyDeleted = true
        }
        assertTrue(keyDeleted)
        assertEquals("keep", records["profiles"])
    }

    @Test
    fun resetWriteOrKeyFailureRestoresBothRecords() {
        listOf(true, false).forEach { failWrite ->
            val records = mutableMapOf<String, String?>("config" to "encrypted", "status" to "history", "profiles" to "keep")
            var keyDeletes = 0
            assertThrows(IllegalStateException::class.java) {
                resetPo0Records("config", "status", records::get, { name, value ->
                    if (failWrite && name == "status" && value == null) false
                    else { records[name] = value; true }
                }) {
                    keyDeletes++
                    error("key unavailable")
                }
            }
            assertEquals(if (failWrite) 0 else 1, keyDeletes)
            assertEquals(mapOf("config" to "encrypted", "status" to "history", "profiles" to "keep"), records)
        }
    }

    @Test
    fun eachEncryptedSaveHasAnIdentityThatIsNotPersistedInsideCredentials() {
        val codec = Po0SettingsCodec(key())
        val settings = Po0Settings(storageIdentity = "previous-runtime-identity")
        val first = java.util.Base64.getEncoder().encodeToString(codec.encode(settings))
        val second = java.util.Base64.getEncoder().encodeToString(codec.encode(settings))
        assertTrue(po0ConfigurationIdentity(first).matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(po0ConfigurationIdentity(first), po0ConfigurationIdentity(second))
        assertFalse(Gson().toJson(settings).contains("storageIdentity"))
        assertFalse(Gson().toJson(settings).contains(settings.storageIdentity))
        assertEquals("", codec.decode(java.util.Base64.getDecoder().decode(first)).storageIdentity)
    }

    @Test
    fun aLateStatusWriteCannotReviveHistoryAfterCredentialsAreReset() {
        val records = mutableMapOf<String, String?>("config" to "encrypted-generation-one", "status" to null)
        val inFlight = Po0RunStatus(totalCount = 1, configurationId = po0ConfigurationIdentity(records.getValue("config")!!))
        assertTrue(po0StatusMatchesConfiguration(inFlight, records["config"]))
        resetPo0Records("config", "status", records::get, { name, value -> records[name] = value; true }) {}
        // A writer from another process may finish after the reset has removed both records.
        records["status"] = Gson().toJson(inFlight)
        val lateStatus = Gson().fromJson(records["status"], Po0RunStatus::class.java)
        assertFalse(po0StatusMatchesConfiguration(lateStatus, records["config"]))
    }

    @Test
    fun aLateStatusWriteCannotBecomeHistoryForReplacementCredentials() {
        val previousConfig = "encrypted-generation-one"
        val currentConfig = "encrypted-generation-two"
        val previousStatus = Po0RunStatus(configurationId = po0ConfigurationIdentity(previousConfig))
        val currentStatus = Po0RunStatus(configurationId = po0ConfigurationIdentity(currentConfig))
        assertTrue(po0StatusMatchesConfiguration(previousStatus, previousConfig))
        assertFalse(po0StatusMatchesConfiguration(previousStatus, currentConfig))
        assertTrue(po0StatusMatchesConfiguration(currentStatus, currentConfig))
    }

    @Test
    fun precedingReleasedStatusIsReadableOnlyWhileAConfigurationStillExists() {
        val previousStatus = Gson().fromJson("""{"checkedAt":123,"successCount":1,"totalCount":1}""", Po0RunStatus::class.java)
        assertEquals("", previousStatus.configurationId)
        assertTrue(po0StatusMatchesConfiguration(previousStatus, "existing-encrypted-record"))
        assertFalse(po0StatusMatchesConfiguration(previousStatus, null))
        assertFalse(po0StatusMatchesConfiguration(Po0RunStatus(), null))
    }

    @Test
    fun missedResetOrSaveNotificationCannotAuthorizeAnotherOldCredentialBatch() {
        val identity = po0ConfigurationIdentity("encrypted-generation-one")
        assertTrue(po0ConfigurationMatches(identity, "encrypted-generation-one"))
        assertFalse(po0ConfigurationMatches(identity, null))
        assertFalse(po0ConfigurationMatches(identity, "encrypted-generation-two"))
        assertFalse(po0ConfigurationMatches("", "encrypted-generation-one"))
    }

    private fun encryptJson(secretKey: SecretKey, json: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD("v2rayNG.Po0.settings.v1".toByteArray(Charsets.UTF_8))
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }
}
