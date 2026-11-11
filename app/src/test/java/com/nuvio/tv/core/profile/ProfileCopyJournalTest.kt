package com.nuvio.tv.core.profile

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileCopyJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = mockk<Context> { every { filesDir } returns temp.root }
    private fun put(path: String, value: String) = File(temp.root, path).apply { parentFile!!.mkdirs(); writeText(value) }
    @Test fun `an interrupted profile copy restores both preferences and their recovery shadow`() {
        put("datastore/xtream_accounts_p2.preferences_pb", "partial copy")
        put("profile_copy_recovery/originals/xtream_accounts_p2.preferences_pb", "original encrypted preferences")
        put("profile_copy_recovery/pending", "1")
        ProfileCopyJournal.recover(context)
        assertEquals("original encrypted preferences", File(temp.root, "datastore/xtream_accounts_p2.preferences_pb").readText())
        assertEquals("original encrypted preferences", File(temp.root, "datastore/xtream_accounts_p2.preferences_pb.bak").readText())
        assertFalse(File(temp.root, "profile_copy_recovery").exists())
        ProfileCopyJournal.recover(context)
    }
    @Test fun `a completed copy is not rolled back if journal cleanup was interrupted`() {
        put("datastore/xtream_accounts_p2.preferences_pb", "completed copy")
        put("profile_copy_recovery/originals/xtream_accounts_p2.preferences_pb", "old")
        put("profile_copy_recovery/pending", "1")
        put("profile_copy_recovery/committed", "1")
        ProfileCopyJournal.recover(context)
        assertEquals("completed copy", File(temp.root, "datastore/xtream_accounts_p2.preferences_pb").readText())
        assertFalse(File(temp.root, "profile_copy_recovery").exists())
    }
}
