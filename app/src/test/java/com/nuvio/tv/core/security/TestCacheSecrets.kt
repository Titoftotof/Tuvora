package com.nuvio.tv.core.security

/** Uses actual AES-GCM with a repeatable test key in JVM storage integration tests. */
object TestCacheSecrets {
    fun codec() = LocalSecretCodec(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 47 }, "AES"))
}
