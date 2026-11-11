package com.nuvio.tv.core.backup

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun clearHold() { RestoreSyncHold.release(context) }
    private val context get() = mockk<Context> {
        every { filesDir } returns temp.root
        every { getDatabasePath(any()) } answers { File(temp.root, "databases/" + firstArg<String>()) }
        every { deleteDatabase(any()) } answers { File(temp.root, "databases/" + firstArg<String>()).delete() }
    }
    private fun put(path: String, text: String) = File(temp.root, path).apply { parentFile!!.mkdirs(); writeText(text) }
    @Test fun `cold install replaces stores removes stale journals and retains emergency backup`() {
        put("datastore/xtream_accounts.preferences_pb", "old encrypted account")
        put("datastore/xtream_accounts.preferences_pb.bak", "old shadow")
        put("datastore/xtream_sync_state.preferences_pb", "old pending edits")
        put("shared_prefs/media_servers.xml", "previous destination credentials")
        put("databases/iptv_content.db", "old cached URLs")
        put("cw_enrichment/inprogress_1.json", "old profile metadata")
        put("backup_safety/emergency.tuvora-backup", "encrypted emergency archive")
        put("configuration_restore/stage/datastore/xtream_accounts.preferences_pb", "new encrypted account")
        put("configuration_restore/ready", "1")
        RestoreTransaction.installOrRecover(context)
        assertEquals("new encrypted account", File(temp.root, "datastore/xtream_accounts.preferences_pb").readText())
        assertFalse(File(temp.root, "datastore/xtream_accounts.preferences_pb.bak").exists())
        assertFalse(File(temp.root, "datastore/xtream_sync_state.preferences_pb").exists())
        assertFalse(File(temp.root, "shared_prefs/media_servers.xml").exists())
        assertFalse(File(temp.root, "databases/iptv_content.db").exists())
        assertFalse(File(temp.root, "cw_enrichment").exists())
        assertTrue(File(temp.root, "backup_safety/emergency.tuvora-backup").exists())
        assertFalse(File(temp.root, "configuration_restore").exists())
        RestoreSyncHold.initialize(context)
        assertTrue(RestoreSyncHold.held)
    }
    @Test fun `interrupted installation rolls back originals shadows and absent files idempotently`() {
        val name = "datastore/xtream_accounts.preferences_pb"
        put(name, "partially installed")
        put("$name.bak", "wrong shadow")
        put("configuration_restore/old/$name", "original")
        put("configuration_restore/old/$name.bak", "original shadow")
        put("datastore/theme_settings.preferences_pb", "new but originally absent")
        put("configuration_restore/targets", "$name\ndatastore/theme_settings.preferences_pb")
        put("configuration_restore/installing", "1")
        RestoreTransaction.installOrRecover(context)
        assertEquals("original", File(temp.root, name).readText())
        assertEquals("original shadow", File(temp.root, "$name.bak").readText())
        assertFalse(File(temp.root, "datastore/theme_settings.preferences_pb").exists())
        RestoreTransaction.installOrRecover(context)
        assertEquals("original", File(temp.root, name).readText())
    }
    @Test fun `committed install is preserved even if cleanup was interrupted`() {
        put("datastore/xtream_accounts.preferences_pb", "installed")
        put("configuration_restore/committed", "1")
        put("configuration_restore/installing", "1")
        RestoreTransaction.installOrRecover(context)
        assertEquals("installed", File(temp.root, "datastore/xtream_accounts.preferences_pb").readText())
        assertFalse(File(temp.root, "configuration_restore").exists())
    }
}
