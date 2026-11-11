package com.nuvio.tv.core.backup

/**
 * Binary container format for `.tuvora-backup` encrypted archive files.
 *
 * Header layout (40 bytes fixed):
 *  - [0..3]   Magic identifier: "TVBK" (0x54, 0x56, 0x42, 0x4B)
 *  - [4..5]   File format version: 1 (UInt16, Big Endian)
 *  - [6..7]   KDF algorithm: 1 = PBKDF2-HMAC-SHA256 (UInt16, Big Endian)
 *  - [8..11]  KDF iterations: 600,000 (UInt32, Big Endian)
 *  - [12..27] Salt: 16 cryptographically random bytes
 *  - [28..39] Nonce / IV: 12 cryptographically random bytes (AES-GCM standard)
 *
 * Payload:
 *  - [40..N-1]  Ciphertext: AES-256-GCM encrypted JSON manifest
 *  - [N-16..N]  Auth Tag: 128-bit (16 bytes) GCM authentication tag (appended by Cipher)
 *
 * Associated Authenticated Data (AAD):
 *  The entire 40-byte header is fed as AAD to the AES-GCM cipher during both encryption
 *  and decryption. Any tampering with iterations, salt, or version immediately causes a GCM tag failure.
 */
object BackupFormat {

    val MAGIC_BYTES = byteArrayOf('T'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val CURRENT_FORMAT_VERSION: Short = 1
    const val KDF_PBKDF2_SHA256: Short = 1
    const val KDF_ITERATIONS: Int = 600_000
    const val SALT_LENGTH_BYTES = 16
    const val NONCE_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    const val HEADER_LENGTH_BYTES = 40 // 4 + 2 + 2 + 4 + 16 + 12

    const val MIN_PASSWORD_LENGTH = 12
    const val FILE_EXTENSION = "tuvora-backup"

    /** Parsed representation of the 40-byte header. */
    data class Header(
        val version: Short,
        val kdfId: Short,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray
    ) {
        init {
            require(salt.size == SALT_LENGTH_BYTES) { "Salt must be $SALT_LENGTH_BYTES bytes" }
            require(nonce.size == NONCE_LENGTH_BYTES) { "Nonce must be $NONCE_LENGTH_BYTES bytes" }
        }

        fun toBytes(): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(HEADER_LENGTH_BYTES)
            buf.put(MAGIC_BYTES)
            buf.putShort(version)
            buf.putShort(kdfId)
            buf.putInt(iterations)
            buf.put(salt)
            buf.put(nonce)
            return buf.array()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return version == other.version && kdfId == other.kdfId &&
                iterations == other.iterations && salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int {
            var r = version.toInt()
            r = 31 * r + kdfId.toInt()
            r = 31 * r + iterations
            r = 31 * r + salt.contentHashCode()
            r = 31 * r + nonce.contentHashCode()
            return r
        }
    }

    /** Parses the 40-byte header from [bytes]. Returns null if invalid magic or truncated. */
    fun parseHeader(bytes: ByteArray): Header? {
        if (bytes.size < HEADER_LENGTH_BYTES) return null
        val buf = java.nio.ByteBuffer.wrap(bytes)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC_BYTES)) return null
        val version = buf.short
        val kdfId = buf.short
        val iterations = buf.int
        val salt = ByteArray(SALT_LENGTH_BYTES)
        buf.get(salt)
        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        buf.get(nonce)
        return Header(version, kdfId, iterations, salt, nonce)
    }
}
