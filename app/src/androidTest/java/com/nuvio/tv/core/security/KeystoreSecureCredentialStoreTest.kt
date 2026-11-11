package com.nuvio.tv.core.security

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecureCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val alias = "test.credentials.$suffix"
    private val service = "test_$suffix"
    private val prefs get() = context.getSharedPreferences("secure_credentials_$service", Context.MODE_PRIVATE)
    private val store get() = KeystoreSecureCredentialStore(context, alias)

    @After fun cleanUp() {
        prefs.edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun encryptionUsesAes256UniqueTwelveByteNoncesAndSurvivesRecreation() = runBlocking {
        store.write(1, service, "ref", "private password")
        val first = prefs.getString("v1.1.ref", null)!!
        assertFalse(first.contains("private password"))
        assertEquals(12, Base64.decode(first.split('.')[1], Base64.NO_WRAP).size)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key as javax.crypto.SecretKey, android.security.keystore.KeyInfo::class.java)
                as android.security.keystore.KeyInfo
        assertEquals(256, info.keySize)
        store.write(1, service, "ref", "private password")
        assertNotEquals(first, prefs.getString("v1.1.ref", null))
        assertEquals(SecureCredentialStore.ReadResult.Success("private password"), store.read(1, service, "ref"))
    }

    @Test fun aadRejectsReplayUnderAnotherProfileServiceOrReference() = runBlocking {
        val envelope = store.seal(1, service, "ref", "secret")
        assertTrue(store.unseal(2, service, "ref", envelope) is SecureCredentialStore.ReadResult.Corrupted)
        assertTrue(store.unseal(1, "other", "ref", envelope) is SecureCredentialStore.ReadResult.Corrupted)
        assertTrue(store.unseal(1, service, "other", envelope) is SecureCredentialStore.ReadResult.Corrupted)
        assertEquals(SecureCredentialStore.ReadResult.Success("secret"), store.unseal(1, service, "ref", envelope))
    }

    @Test fun corruptedCiphertextIsPreserved() = runBlocking {
        store.write(1, service, "ref", "secret")
        val parts = prefs.getString("v1.1.ref", null)!!.split('.')
        val bytes = Base64.decode(parts[2], Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val damaged = "${parts[0]}.${parts[1]}.${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        prefs.edit().putString("v1.1.ref", damaged).commit()
        assertTrue(store.read(1, service, "ref") is SecureCredentialStore.ReadResult.Corrupted)
        assertEquals(damaged, prefs.getString("v1.1.ref", null))
    }

    @Test fun persistenceFailureIsReportedInsteadOfAcknowledged() = runBlocking {
        val realPreferences = prefs
        val failingPreferences = object : android.content.SharedPreferences by realPreferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val realEditor = realPreferences.edit()
                return object : android.content.SharedPreferences.Editor by realEditor {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        realEditor.putString(key, value)
                        return this
                    }
                    override fun remove(key: String): android.content.SharedPreferences.Editor {
                        realEditor.remove(key)
                        return this
                    }
                    override fun commit(): Boolean = false
                }
            }
        }
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = failingPreferences
        }
        val failingStore = KeystoreSecureCredentialStore(failingContext, alias)
        assertTrue(runCatching { failingStore.write(1, service, "ref", "secret") }.exceptionOrNull() is java.io.IOException)
        assertNull(realPreferences.getString("v1.1.ref", null))
        store.write(1, service, "ref", "secret")
        assertTrue(runCatching { failingStore.delete(1, service, "ref") }.exceptionOrNull() is java.io.IOException)
        assertEquals(SecureCredentialStore.ReadResult.Success("secret"), store.read(1, service, "ref"))
    }

    @Test fun missingKeyDoesNotCreateReplacementOrDeleteCiphertext() = runBlocking {
        store.write(1, service, "ref", "secret")
        val encrypted = prefs.getString("v1.1.ref", null)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        assertTrue(store.read(1, service, "ref") is SecureCredentialStore.ReadResult.KeyUnavailable)
        assertEquals(encrypted, prefs.getString("v1.1.ref", null))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }
}
