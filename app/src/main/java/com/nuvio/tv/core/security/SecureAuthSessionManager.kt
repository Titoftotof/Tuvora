package com.nuvio.tv.core.security

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Migrates the SDK session only after a verified encrypted write. Never exports sessions. */
class SecureAuthSessionManager(
    private val store: SecureCredentialStore,
    private val backendUrl: String,
    private val legacyOverride: SessionManager? = null
) : SessionManager {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val ref = java.security.MessageDigest.getInstance("SHA-256")
        .digest(backendUrl.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val legacy by lazy {
        val key = "sb-" + backendUrl.removeSuffix("/").replace('/', '-').replace('.', '-') + "-session"
        legacyOverride ?: SettingsSessionManager(key = key)
    }
    override suspend fun saveSession(session: UserSession) = mutex.withLock { persist(session) }

    private suspend fun persist(session: UserSession) {
        // A late auto-refresh must not recreate a session after workspace sign-out.
        if (com.nuvio.tv.core.network.BackendWorkspaceStore.switching) return
        val encoded = json.encodeToString(UserSession.serializer(), session)
        store.write(0, "main_auth", ref, encoded)
        check((store.read(0, "main_auth", ref) as? SecureCredentialStore.ReadResult.Success)?.value == encoded)
        legacy.deleteSession()
    }
    override suspend fun loadSession(): UserSession = mutex.withLock { when (val result = store.read(0, "main_auth", ref)) {
        is SecureCredentialStore.ReadResult.Success -> json.decodeFromString(UserSession.serializer(), result.value)
        SecureCredentialStore.ReadResult.NotFound -> legacy.loadSession().also { persist(it) }
        else -> throw IOException("Stored session is temporarily unreadable")
    } }
    override suspend fun deleteSession() = mutex.withLock {
        store.delete(0, "main_auth", ref)
        legacy.deleteSession()
    }
}
