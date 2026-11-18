package com.nuvio.tv.core.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.security.KeystoreSecureCredentialStore
import com.nuvio.tv.core.security.SecureCredentialStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackendWorkspaceStorageTest {
    @Test fun legacyPersonalFilesAreAdoptedWithoutMovingOrReplacingThem() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test_legacy_" + UUID.randomUUID()
        val file = java.io.File(base.filesDir, name)
        try {
            file.writeText("unsynced personal data")
            val legacy = BackendWorkspaceStore.contextFor(base,
                BackendWorkspaceStore.Workspace("hosted", "legacy_test", true))
            assertEquals(base.filesDir, legacy.filesDir)
            assertEquals(file.canonicalPath, java.io.File(legacy.filesDir, name).canonicalPath)
            assertEquals("unsynced personal data", java.io.File(legacy.filesDir, name).readText())
        } finally { file.delete() }
    }

    @Test fun filesPreferencesDatabasesAndSecretsAreSeparatedAndSurviveReopening() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val a = BackendWorkspaceStore.Workspace("hosted", "test_a_$suffix", false)
        val b = BackendWorkspaceStore.Workspace("nuvio", "test_b_$suffix", false)
        val first = BackendWorkspaceStore.contextFor(base, a)
        val second = BackendWorkspaceStore.contextFor(base, b)
        val prefs = "secure_credentials_test_$suffix"
        try {
            java.io.File(first.filesDir, "playlist").writeText("personal")
            first.getSharedPreferences("test_$suffix", Context.MODE_PRIVATE).edit().putString("profile_1", "personal").commit()
            first.openOrCreateDatabase("test.db", 0, null).use {
                it.execSQL("CREATE TABLE accounts (name TEXT)")
                it.execSQL("INSERT INTO accounts VALUES ('personal')")
            }
            assertFalse(java.io.File(second.filesDir, "playlist").exists())
            assertNull(second.getSharedPreferences("test_$suffix", Context.MODE_PRIVATE).getString("profile_1", null))
            assertNotEquals(first.getDatabasePath("test.db"), second.getDatabasePath("test.db"))
            assertFalse(second.getDatabasePath("test.db").exists())
            val storeA = KeystoreSecureCredentialStore(first)
            val storeB = KeystoreSecureCredentialStore(second)
            storeA.write(1, "test_$suffix", "ref", "password")
            assertEquals(SecureCredentialStore.ReadResult.NotFound, storeB.read(1, "test_$suffix", "ref"))
            val envelope = first.getSharedPreferences(prefs, 0).getString("v1.1.ref", null)!!
            second.getSharedPreferences(prefs, 0).edit().putString("v1.1.ref", envelope).commit()
            assertTrue(storeB.read(1, "test_$suffix", "ref") is SecureCredentialStore.ReadResult.Corrupted)
            val reopened = BackendWorkspaceStore.contextFor(base, a)
            assertEquals("personal", java.io.File(reopened.filesDir, "playlist").readText())
            assertEquals(SecureCredentialStore.ReadResult.Success("password"),
                KeystoreSecureCredentialStore(reopened).read(1, "test_$suffix", "ref"))
        } finally {
            for (ctx in listOf(first, second)) {
                ctx.deleteSharedPreferences(prefs)
                ctx.deleteSharedPreferences("test_$suffix")
                ctx.deleteDatabase("test.db")
                ctx.filesDir.parentFile!!.deleteRecursively()
            }
        }
    }
}
