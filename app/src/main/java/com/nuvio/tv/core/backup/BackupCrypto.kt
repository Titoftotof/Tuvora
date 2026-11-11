package com.nuvio.tv.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitives for `.tuvora-backup` container files.
 *
 * Algorithm:
 *  - Key derivation: PBKDF2-HMAC-SHA256 (600,000 iterations, 16-byte salt, 256-bit output).
 *  - Encryption: AES-256-GCM (12-byte nonce, 128-bit authentication tag).
 *  - Authenticated data: The entire 40-byte header is verified as AAD.
 */
object BackupCrypto {

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray = ByteArray(BackupFormat.SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    fun generateNonce(): ByteArray = ByteArray(BackupFormat.NONCE_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val bytes = com.nuvio.tv.core.security.PasswordKdf.derive(password, salt, iterations)
        return try { SecretKeySpec(bytes, KEY_ALGORITHM) } finally { bytes.fill(0) }
    }

    /**
     * Encrypts [plaintext] under [header]. The header's byte representation is used as AAD.
     * Returns ciphertext with the 16-byte GCM tag appended.
     */
    fun encrypt(plaintext: ByteArray, password: String, header: BackupFormat.Header): ByteArray {
        val key = deriveKey(password, header.salt, header.iterations)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BackupFormat.GCM_TAG_LENGTH_BITS, header.nonce))
        cipher.updateAAD(header.toBytes())
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts [ciphertext] under [header] and [password].
     * @throws javax.crypto.AEADBadTagException if password is incorrect or data was tampered with.
     */
    fun decrypt(ciphertext: ByteArray, password: String, header: BackupFormat.Header): ByteArray {
        val key = deriveKey(password, header.salt, header.iterations)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BackupFormat.GCM_TAG_LENGTH_BITS, header.nonce))
        cipher.updateAAD(header.toBytes())
        return cipher.doFinal(ciphertext)
    }
}
