package com.nuvio.tv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class BackupCryptoTest {

    private val testPassword = "correct-horse-battery-staple"
    private val testPayload = "{\"test\": \"data\", \"version\": 1}".toByteArray(Charsets.UTF_8)

    private fun sampleHeader(iterations: Int = 10_000): BackupFormat.Header = BackupFormat.Header(
        version = BackupFormat.CURRENT_FORMAT_VERSION,
        kdfId = BackupFormat.KDF_PBKDF2_SHA256,
        // Use reduced iteration count in unit test for speed; production uses 600,000
        iterations = iterations,
        salt = BackupCrypto.generateSalt(),
        nonce = BackupCrypto.generateNonce()
    )

    @Test
    fun `header serialization round-trip succeeds`() {
        val original = sampleHeader()
        val bytes = original.toBytes()
        assertEquals(BackupFormat.HEADER_LENGTH_BYTES, bytes.size)

        val parsed = BackupFormat.parseHeader(bytes)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `invalid magic in header is rejected`() {
        val bytes = sampleHeader().toBytes()
        bytes[0] = 'X'.code.toByte() // corrupt magic
        assertNull(BackupFormat.parseHeader(bytes))
    }

    @Test
    fun `truncated header is rejected`() {
        val bytes = ByteArray(BackupFormat.HEADER_LENGTH_BYTES - 1)
        assertNull(BackupFormat.parseHeader(bytes))
    }

    @Test
    fun `encrypt and decrypt round-trip produces identical plaintext`() {
        val header = sampleHeader()
        val ciphertext = BackupCrypto.encrypt(testPayload, testPassword, header)

        val decrypted = BackupCrypto.decrypt(ciphertext, testPassword, header)
        assertEquals(String(testPayload, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `wrong password fails decryption with AEADBadTagException`() {
        val header = sampleHeader()
        val ciphertext = BackupCrypto.encrypt(testPayload, testPassword, header)

        try {
            BackupCrypto.decrypt(ciphertext, "wrong-password-here", header)
            org.junit.Assert.fail("Expected AEADBadTagException")
        } catch (e: Exception) {
            assertTrue("Expected AEADBadTagException but got ${e::class.java.simpleName}", e is AEADBadTagException)
        }
    }

    @Test
    fun `tampered ciphertext fails decryption`() {
        val header = sampleHeader()
        val ciphertext = BackupCrypto.encrypt(testPayload, testPassword, header)
        // Flip one bit in the ciphertext
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()

        try {
            BackupCrypto.decrypt(ciphertext, testPassword, header)
            org.junit.Assert.fail("Expected AEADBadTagException on tampered ciphertext")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException)
        }
    }

    @Test
    fun `tampered header AAD fails decryption`() {
        val header = sampleHeader()
        val ciphertext = BackupCrypto.encrypt(testPayload, testPassword, header)

        // Modify header version in AAD
        val modifiedHeader = header.copy(version = 2)
        try {
            BackupCrypto.decrypt(ciphertext, testPassword, modifiedHeader)
            org.junit.Assert.fail("Expected AEADBadTagException on modified header")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException)
        }
    }
}
