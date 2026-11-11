package com.nuvio.tv.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.*
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Local cache/file encryption. File records are bounded and individually authenticated. */
class LocalSecretCodec internal constructor(
    private val suppliedKey: SecretKey?,
    private val context: android.content.Context? = null
) {
    constructor(context: android.content.Context) : this(null, context.applicationContext)
    companion object {
        private const val ALIAS = "com.tuvora.cache.v1"
        private const val PREFIX = "__tuvora_cache_v1__:"
        private const val DATA_PREFIX = "__tuvora_cache_v2__:"
        private const val DATA_KEY_PREFS = "local_cache_keys"
        @Volatile private var cachedDataKey: SecretKey? = null
        private val MAGIC = byteArrayOf(84, 86, 83, 70, 1)
        private val keyLock = Any()
        @Volatile private var cached: SecretKey? = null
    }
    private fun key(create: Boolean = true): SecretKey = suppliedKey ?: cached ?: synchronized(keyLock) {
        cached ?: run {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(ALIAS, null) as? SecretKey
            if (existing == null && !create) throw java.security.UnrecoverableKeyException("Cache encryption key is missing")
            (existing ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
                generateKey()
            }).also { cached = it }
        }
    }
    private fun encrypt(bytes: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(bytes)
    }
    private fun decrypt(bytes: ByteArray, aad: ByteArray): ByteArray {
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
    // One Keystore unwrap per process, rather than a Binder round trip per catalog cell.
    // Only the wrapped random key is persisted; older v1 cells retain their original reader.
    private fun dataKey(create: Boolean): SecretKey = suppliedKey ?: cachedDataKey ?: synchronized(keyLock) {
        cachedDataKey ?: run {
            val prefs = checkNotNull(context).getSharedPreferences(DATA_KEY_PREFS, android.content.Context.MODE_PRIVATE)
            val wrapped = prefs.getString("wrapped_data_key", null)
            val aad = "cache-data-key-v2".toByteArray()
            val raw = if (wrapped != null) {
                decrypt(Base64.decode(wrapped, Base64.NO_WRAP), aad)
            } else {
                if (!create) throw java.security.UnrecoverableKeyException("Cache data key is missing")
                ByteArray(32).also { bytes ->
                    java.security.SecureRandom().nextBytes(bytes)
                    val encoded = Base64.encodeToString(encrypt(bytes, aad), Base64.NO_WRAP)
                    check(prefs.edit().putString("wrapped_data_key", encoded).commit()) { "Cannot persist cache data key" }
                }
            }
            try {
                require(raw.size == 32)
                javax.crypto.spec.SecretKeySpec(raw, "AES").also { cachedDataKey = it }
            } finally { raw.fill(0) }
        }
    }

    fun seal(value: String?): String? = value?.let {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(create = true))
        cipher.updateAAD("cache-string-v2".toByteArray())
        DATA_PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(it.toByteArray()), Base64.NO_WRAP)
    }
    fun open(value: String?): String? = value?.let {
        when {
            it.startsWith(DATA_PREFIX) -> {
                val bytes = Base64.decode(it.removePrefix(DATA_PREFIX), Base64.NO_WRAP)
                require(bytes.size >= 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, dataKey(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.updateAAD("cache-string-v2".toByteArray())
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
            }
            it.startsWith(PREFIX) ->
                decrypt(Base64.decode(it.removePrefix(PREFIX), Base64.NO_WRAP), "cache-string".toByteArray()).toString(Charsets.UTF_8)
            else -> it
        }
    }

    /** Writes a random file id and authenticates index, final marker and file id in every record. */
    fun writeEncrypted(input: InputStream, output: OutputStream) {
        val data = DataOutputStream(output)
        data.write(MAGIC)
        val fileId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        data.write(fileId)
        var index = 0
        val buffer = ByteArray(64 * 1024)
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            val aad = fileId + java.nio.ByteBuffer.allocate(8).putInt(index++).putInt(count).array()
            val encrypted = encrypt(buffer.copyOf(count), aad)
            data.writeInt(count)
            data.write(encrypted)
            if (count == 0) break
        }
        data.flush()
        buffer.fill(0)
    }
    fun readEncrypted(input: InputStream): InputStream {
        val buffered = input.buffered()
        buffered.mark(MAGIC.size)
        val magic = ByteArray(MAGIC.size)
        val n = buffered.read(magic)
        if (n != MAGIC.size || !magic.contentEquals(MAGIC)) {
            val start = magic.copyOf(n.coerceAtLeast(0)).toString(Charsets.ISO_8859_1)
            require(n <= 0 || start.startsWith("#") || start.startsWith("[") || start.startsWith("{") ||
                start.startsWith("http") || (n >= 3 && magic[0] == 0xef.toByte() && magic[1] == 0xbb.toByte() && magic[2] == 0xbf.toByte()) ||
                (n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte())) {
                "Truncated or unsupported encrypted file"
            }
            buffered.reset()
            return buffered // legacy: caller migrates atomically before exposing it
        }
        val data = DataInputStream(buffered)
        val fileId = ByteArray(16).also { data.readFully(it) }
        return object : InputStream() {
            var record = ByteArrayInputStream(byteArrayOf())
            var index = 0
            var done = false
            private fun next(): Boolean {
                if (done) return false
                val count = data.readInt()
                require(count in 0..65536) { "Invalid encrypted file record" }
                val bytes = ByteArray(count + 28).also { data.readFully(it) }
                val aad = fileId + java.nio.ByteBuffer.allocate(8).putInt(index++).putInt(count).array()
                val plain = decrypt(bytes, aad)
                if (count == 0) {
                    check(data.read() == -1) { "Trailing encrypted file data" }
                    done = true
                    return false
                }
                record = ByteArrayInputStream(plain)
                return true
            }
            override fun read(): Int {
                while (record.available() == 0) if (!next()) return -1
                return record.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (record.available() == 0) if (!next()) return -1
                return record.read(b, off, len)
            }
            override fun close() { data.close() }
        }
    }
    fun isEncrypted(file: File): Boolean = file.inputStream().use {
        val magic = ByteArray(MAGIC.size)
        it.read(magic) == magic.size && magic.contentEquals(MAGIC)
    }
    fun migrate(file: File) {
        if (!file.exists() || isEncrypted(file)) return
        val tmp = File(file.parentFile, file.name + ".secure_tmp")
        try {
            readEncrypted(file.inputStream()).use { input ->
                FileOutputStream(tmp).use { output -> writeEncrypted(input, output); output.fd.sync() }
            }
            // Verify every authenticated record before replacing the legacy bytes.
            readEncrypted(tmp.inputStream()).use { input -> input.copyTo(object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }) }
            check(tmp.renameTo(file)) { "Cannot install encrypted file" }
        } finally { tmp.delete() }
    }
}
