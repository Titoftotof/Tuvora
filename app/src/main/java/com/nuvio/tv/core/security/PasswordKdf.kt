package com.nuvio.tv.core.security

import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** PBKDF2-SHA256, including API 24/25 providers that do not expose the SecretKeyFactory alias. */
object PasswordKdf {
    fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0 && salt.isNotEmpty())
        val factory = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            catch (_: java.security.NoSuchAlgorithmException) { return deriveWithMac(password, salt, iterations) }
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { factory.generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    internal fun deriveWithMac(password: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        try { mac.init(SecretKeySpec(passwordBytes, "HmacSHA256")) } finally { passwordBytes.fill(0) }
        val u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        u.fill(0)
        return result
    }
}
