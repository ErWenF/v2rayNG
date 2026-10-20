package com.v2ray.ang.handler

import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    private fun encryptJson(secretKey: SecretKey, json: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD("v2rayNG.Po0.settings.v1".toByteArray(Charsets.UTF_8))
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }
}
