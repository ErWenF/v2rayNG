package com.v2ray.ang.handler

import com.v2ray.ang.dto.Po0Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class Po0SettingsStoreTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun credentialsAndStandalonePreferencesRoundTripWithoutPlaintext() {
        val settings = Po0Settings(true, listOf("pgnfw_first@0", "pgnfw_second"), 600, true, true)
        val codec = Po0SettingsCodec(key())
        val encrypted = codec.encode(settings)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("pgnfw_"))
        assertEquals(settings, codec.decode(encrypted))
        assertNotEquals(encrypted.toList(), codec.encode(settings).toList())
    }

    @Test
    fun tamperedCredentialsAndAnotherDeviceKeyAreRejected() {
        val codec = Po0SettingsCodec(key())
        val encrypted = codec.encode(Po0Settings(tokens = listOf("pgnfw_example")))
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
        assertThrows(IllegalArgumentException::class.java) { normalizePo0Settings(Po0Settings(tokens = listOf("bad"))) }
        assertThrows(IllegalArgumentException::class.java) { normalizePo0Settings(Po0Settings(tokens = listOf("pgnfw_a,pgnfw_b"))) }
        assertThrows(IllegalArgumentException::class.java) { normalizePo0Settings(Po0Settings(tokens = listOf("pgnfw_a@5"))) }
        assertEquals(listOf("pgnfw_a"), normalizePo0Settings(Po0Settings(tokens = listOf("pgnfw_a", "pgnfw_a"))).tokens)
        assertEquals(600, Po0Settings().intervalSeconds)
    }
}
