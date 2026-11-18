package com.nuvio.tv.core.sync

import android.content.Context
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileCreationSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val primary = UserProfile(1, "Primary", "#000000")
    private val copied = UserProfile(2, "Copied", "#000000")

    @Before fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @After fun tearDown() = unmockkStatic(Dispatchers::class)

    @Test fun `pull waits for creation and copying to finish before publishing`() = runTest(dispatcher) {
        val h = harness()
        val finishCopy = CompletableDeferred<Unit>()
        val creating = async {
            h.service.withLocalProfileMutation {
                h.local.upsertProfile(copied)
                finishCopy.await()
            }
        }
        runCurrent()
        val pulling = async { h.service.pullFromRemote() }
        runCurrent()
        assertTrue(h.calls.isEmpty())
        finishCopy.complete(Unit)
        creating.await()
        pulling.await().getOrThrow()
        assertEquals(listOf("sync_push_profiles", "sync_pull_profiles"), h.calls)
        assertEquals(listOf(1, 2), h.local.profileSyncSnapshot().profiles.map { it.id })
    }

    @Test fun `a pull started before creation cannot remove the new profile`() = runTest(dispatcher) {
        val h = harness()
        val releaseResponse = CompletableDeferred<Unit>()
        h.beforePullResponse = { releaseResponse.await() }
        val pulling = async { h.service.pullFromRemote() }
        runCurrent()
        assertEquals(listOf("sync_pull_profiles"), h.calls)
        h.local.upsertProfile(copied)
        releaseResponse.complete(Unit)
        assertEquals(listOf(1, 2), pulling.await().getOrThrow().map { it.id })
        assertTrue(h.local.profileSyncSnapshot().pending)
    }

    @Test fun `failed upload survives restart and must succeed before downloading profiles`() = runTest(dispatcher) {
        val h = harness()
        h.failPush = true
        h.service.withLocalProfileMutation { h.local.upsertProfile(copied) }
        assertTrue(h.local.profileSyncSnapshot().pending)

        // New DataStore/service objects read the same persisted preferences.
        h.local = h.newLocal()
        h.service = h.newService()
        assertTrue(h.service.pullFromRemote().isFailure)
        assertFalse(h.calls.contains("sync_pull_profiles"))
        assertEquals(listOf(1, 2), h.local.profileSyncSnapshot().profiles.map { it.id })

        h.failPush = false
        h.calls.clear()
        h.service.pullFromRemote().getOrThrow()
        assertEquals(listOf("sync_push_profiles", "sync_pull_profiles"), h.calls)
        assertFalse(h.local.profileSyncSnapshot().pending)
        assertEquals(listOf(1, 2), h.local.profileSyncSnapshot().profiles.map { it.id })
    }

    @Test fun `upload acknowledgement does not clear edits made during the request`() = runTest(dispatcher) {
        val h = harness()
        h.local.upsertProfile(copied)
        h.beforePushResponse = { h.local.upsertProfile(copied.copy(name = "Renamed")) }
        h.service.pushToRemote().getOrThrow()
        assertTrue(h.local.profileSyncSnapshot().pending)
        assertEquals("Renamed", h.local.profileSyncSnapshot().profiles.last().name)
    }

    @Test fun `old response stays stale even after the newer local edit was acknowledged`() = runTest(dispatcher) {
        val h = harness()
        val beforeCreation = h.local.profileSyncSnapshot()
        h.local.upsertProfile(copied)
        h.local.markProfilesSynced(h.local.profileSyncSnapshot().revision)
        assertNull(h.local.replaceProfilesFromRemote(listOf(primary), beforeCreation.revision))
        assertEquals(listOf(1, 2), h.local.profileSyncSnapshot().profiles.map { it.id })
    }

    @Test fun `clean profiles still accept a deletion from another device`() = runTest(dispatcher) {
        val h = harness()
        h.local.upsertProfile(copied)
        h.local.markProfilesSynced(h.local.profileSyncSnapshot().revision)
        h.service.pullFromRemote().getOrThrow()
        assertEquals(listOf(primary), h.local.profileSyncSnapshot().profiles)
        assertFalse(h.local.profileSyncSnapshot().pending)
    }

    private suspend fun harness() = Harness().also {
        it.local.upsertProfile(primary)
        it.local.markProfilesSynced(it.local.profileSyncSnapshot().revision)
    }

    private inner class Harness {
        private val preferences = TestPreferencesStore()
        private val context = mockk<Context>(relaxed = true)
        private val moshi = Moshi.Builder().build()
        var local = newLocal()
        val calls = mutableListOf<String>()
        var failPush = false
        var beforePullResponse: suspend () -> Unit = {}
        var beforePushResponse: suspend () -> Unit = {}
        private var remote = JsonArray(listOf(buildJsonObject {
            put("profile_index", primary.id)
            put("name", primary.name)
            put("avatar_color_hex", primary.avatarColorHex)
        }))
        private val manager = mockk<ProfileManager> {
            coEvery { profileSyncSnapshot() } coAnswers { local.profileSyncSnapshot() }
            coEvery { markProfilesSynced(any()) } coAnswers { local.markProfilesSynced(firstArg()) }
            coEvery { applyRemoteProfiles(any(), any()) } coAnswers {
                local.replaceProfilesFromRemote(firstArg(), secondArg()) != null
            }
        }
        private val auth = mockk<AuthManager>(relaxed = true) {
            every { canSync } returns true
        }
        private val identity = mockk<SyncClientIdentity> {
            every { currentClientId() } returns "profile-creation-test"
        }
        private val postgrest = mockk<Postgrest> {
            every { serializer } returns KotlinXSerializer()
        }

        init {
            coEvery { postgrest.rpc("sync_pull_profiles", request = any()) } coAnswers {
                calls += "sync_pull_profiles"
                val responseSnapshot = remote.toString()
                beforePullResponse()
                PostgrestResult(responseSnapshot, Headers.Empty, postgrest)
            }
            coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
                val function = firstArg<String>()
                calls += function
                val response = when (function) {
                    "sync_push_profiles" -> {
                        if (failPush) error("Network unavailable")
                        remote = secondArg<JsonObject>().getValue("p_profiles").jsonArray
                        beforePushResponse()
                        ""
                    }
                    "sync_pull_profiles" -> {
                        val responseSnapshot = remote.toString()
                        beforePullResponse()
                        responseSnapshot
                    }
                    else -> error("Unexpected RPC: $function")
                }
                PostgrestResult(response, Headers.Empty, postgrest)
            }
        }

        var service = newService()
        fun newLocal() = ProfileDataStore(context, moshi, preferences)
        fun newService() = ProfileSyncService(auth, postgrest, manager, identity, mockk(relaxed = true))
    }
}
