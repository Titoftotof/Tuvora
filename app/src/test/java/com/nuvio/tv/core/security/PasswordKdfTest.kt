package com.nuvio.tv.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PasswordKdfTest {
    @Test fun `API 24 fallback matches JCA including Unicode passwords`() {
        for (password in listOf("password", "mot de passe sécurisé")) {
            for (iterations in listOf(1, 2, 4096)) {
                val salt = "portable salt".toByteArray()
                assertArrayEquals(PasswordKdf.derive(password, salt, iterations),
                    PasswordKdf.deriveWithMac(password, salt, iterations))
            }
        }
    }
}
