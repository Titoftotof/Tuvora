package com.nuvio.tv.core.network

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Storage selection is frozen for the lifetime of a process. Never retarget live stores. */
object BackendWorkspaceStore {
    private const val PREFS = "backend_workspaces_v1"
    private val globalPreferences = setOf(PREFS, "nuvio_sync_backend", "app_locale")
    data class Workspace(val backendId: String, val identity: String, val legacy: Boolean)
    @Volatile private var selected: Workspace? = null
    val active: Workspace get() = checkNotNull(selected) { "Workspace not initialized" }
    @Volatile var switching: Boolean = false
        private set

    fun identity(backend: SyncBackendConfig): String =
        backend.id + "_" + MessageDigest.getInstance("SHA-256")
            .digest(backend.normalizedSupabaseUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    fun initialize(base: Context) {
        if (selected != null) return
        val prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("selected")) {
            // Adopt existing files IN PLACE: no copies of live databases or encrypted envelopes.
            // Both legacy selector choices used the personal endpoint in the shipped configuration.
            val existing = File(base.filesDir, "datastore").listFiles()?.isNotEmpty() == true ||
                base.getSharedPreferences("nuvio_sync_backend", Context.MODE_PRIVATE).contains("selection_payload_v1") ||
                File(base.applicationInfo.dataDir, "shared_prefs").listFiles()
                    ?.any { it.name.startsWith("secure_credentials_") } == true
            val backend = if (existing) SyncBackendDefaults.hosted() else SyncBackendDefaults.nuvio()
            val editor = prefs.edit().putString("selected", backend.id)
            if (existing) editor.putString("legacy_identity", identity(backend))
            check(editor.commit()) { "Unable to initialize workspace" }
        }
        val id = checkNotNull(prefs.getString("selected", null))
        val backend = checkNotNull(SyncBackendDefaults.byId(id))
        val key = identity(backend)
        selected = Workspace(id, key, prefs.getString("legacy_identity", null) == key)
    }

    fun wrap(base: Context): Context {
        initialize(base)
        if (workspaceFor(base) != null) return base
        return WorkspaceContext(base, active)
    }

    /** Write only next process's selection. Existing objects and late responses stay in old space. */
    fun selectForNextLaunch(context: Context, backend: SyncBackendConfig) {
        require(backend.isUsableClientConfig())
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("selected", backend.id).commit()) { "Unable to save workspace" }
    }

    fun beginSwitch() = synchronized(com.nuvio.tv.core.profile.ConfigurationMutation) {
        check(!switching && !com.nuvio.tv.core.profile.ConfigurationMutation.active) { "Configuration operation in progress" }
        switching = true
    }
    fun cancelSwitch() { switching = false }

    fun credentialPreferences(context: Context, prefix: String): List<String> {
        val workspace = workspaceFor(context)
        val physicalPrefix = if (workspace == null || workspace.legacy) "" else "ws_${workspace.identity}_"
        return File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .map { it.name }.filter { it.startsWith(physicalPrefix + prefix) && it.endsWith(".xml") }
            .map { it.removePrefix(physicalPrefix).removeSuffix(".xml") }
    }

    fun deviceContext(context: Context): Context {
        var current = context
        while (current is ContextWrapper) {
            if (current is WorkspaceContext) return current.baseContext
            current = current.baseContext ?: return context
        }
        return context
    }

    internal fun contextFor(base: Context, workspace: Workspace): Context = WorkspaceContext(base, workspace)

    fun workspaceFor(context: Context): Workspace? {
        var current = context
        while (current is ContextWrapper) {
            if (current is WorkspaceContext) return current.workspace
            current = current.baseContext ?: return null
        }
        return null
    }

    private class WorkspaceContext(base: Context, val workspace: Workspace) : ContextWrapper(base) {
        private val prefix = if (workspace.legacy) "" else "ws_${workspace.identity}_"
        private val root = File(base.noBackupFilesDir, "backend_workspaces/${workspace.identity}")
        private fun directory(name: String) = File(root, name).apply {
            check(isDirectory || mkdirs()) { "Unable to open workspace directory" }
        }
        private fun preferenceName(name: String) = if (name in globalPreferences) name else prefix + name
        override fun getFilesDir(): File = if (workspace.legacy) super.getFilesDir() else directory("files")
        override fun getCacheDir(): File = if (workspace.legacy) super.getCacheDir() else directory("cache")
        override fun getNoBackupFilesDir(): File = if (workspace.legacy) super.getNoBackupFilesDir() else directory("no_backup")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(preferenceName(name), mode)
        override fun deleteSharedPreferences(name: String): Boolean = super.deleteSharedPreferences(preferenceName(name))
        override fun getDatabasePath(name: String): File =
            if (workspace.legacy || File(name).name == "androidx.work.workdb") super.getDatabasePath(name) else File(directory("databases"), File(name).name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory, null)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING, errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun databaseList(): Array<out String> = if (workspace.legacy) super.databaseList()
            else directory("databases").list().orEmpty()
        override fun getFileStreamPath(name: String): File = File(filesDir, name)
        override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
        override fun openFileOutput(name: String, mode: Int): FileOutputStream =
            FileOutputStream(getFileStreamPath(name), mode and Context.MODE_APPEND != 0)
        override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
        override fun fileList(): Array<out String> = filesDir.list().orEmpty()
        override fun getDir(name: String, mode: Int): File =
            if (workspace.legacy) super.getDir(name, mode) else directory("app_" + File(name).name)
        override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): Context =
            WorkspaceContext(baseContext.createConfigurationContext(overrideConfiguration), workspace)
    }
}
