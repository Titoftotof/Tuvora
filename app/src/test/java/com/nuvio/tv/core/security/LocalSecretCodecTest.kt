package com.nuvio.tv.core.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayOutputStream

class LocalSecretCodecTest {
    private val codec = LocalSecretCodec(SecretKeySpec(ByteArray(32) { 31 }, "AES"))
    private fun encrypt(bytes: ByteArray) = ByteArrayOutputStream().also { codec.writeEncrypted(bytes.inputStream(), it) }.toByteArray()
    @Test fun `encrypted records round trip empty boundary and multirecord payloads with unique nonces`() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 200000)) {
            val source = ByteArray(size) { (it % 251).toByte() }
            val a = encrypt(source)
            val b = encrypt(source)
            assertFalse(a.contentEquals(b))
            assertArrayEquals(source, codec.readEncrypted(a.inputStream()).use { it.readBytes() })
        }
    }
    @Test fun `modified truncated appended and reordered records are rejected`() {
        val encrypted = encrypt(ByteArray(150000) { 42 })
        val modified = encrypted.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() }
        val firstRecordSize = 4 + 65536 + 28
        val reordered = encrypted.copyOf().apply {
            encrypted.copyInto(this, 21, 21 + firstRecordSize, 21 + 2 * firstRecordSize)
            encrypted.copyInto(this, 21 + firstRecordSize, 21, 21 + firstRecordSize)
        }
        for (invalid in listOf(modified, encrypted.copyOf(encrypted.size - 1), encrypted + byteArrayOf(1), reordered)) {
            assertTrue(runCatching { codec.readEncrypted(invalid.inputStream()).use { it.readBytes() } }.isFailure)
        }
    }
    @Test fun `a different device key cannot decode file records`() {
        val encrypted = encrypt("private credentials".toByteArray())
        val differentDevice = LocalSecretCodec(SecretKeySpec(ByteArray(32) { 12 }, "AES"))
        assertTrue(runCatching { differentDevice.readEncrypted(encrypted.inputStream()).use { it.readBytes() } }.isFailure)
    }
    @Test fun `legacy plaintext remains readable for transactional migration`() {
        val legacy = "#EXTM3U\nhttps://provider/live/user/password/1.ts"
        assertEquals(legacy, codec.readEncrypted(legacy.byteInputStream()).bufferedReader().use { it.readText() })
    }
}
