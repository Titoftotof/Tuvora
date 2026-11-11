package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.backup.*
import com.nuvio.tv.core.runtime.AppRestarter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class BackupRestoreState(
    val busy: Boolean = false,
    val notice: String? = null,
    val preview: RestoreService.PreviewResult.Ready? = null,
    val restartRequired: Boolean = false
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: ConfigurationBackupService,
    private val restarter: AppRestarter
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupRestoreState())
    val state = mutable.asStateFlow()
    private var pendingSource: Uri? = null
    private var pendingPassword: String? = null

    fun clear() {
        if (state.value.busy || state.value.restartRequired) return
        pendingPassword = null
        pendingSource = null
        mutable.value = BackupRestoreState()
    }

    fun pickerUnavailable() {
        mutable.value = state.value.copy(notice = context.getString(com.nuvio.tv.R.string.backup_picker_missing))
    }

    fun export(uri: Uri, password: String) = runOperation {
        val result = context.contentResolver.openOutputStream(uri, "wt")?.use { service.exportBackup(password, it) }
            ?: error("Unable to open output")
        when (result) {
            is BackupService.BackupResult.Success -> mutable.value = BackupRestoreState(notice = context.getString(com.nuvio.tv.R.string.backup_export_success))
            is BackupService.BackupResult.Failed -> throw result.cause
            is BackupService.BackupResult.PasswordTooShort -> error("PASSWORD_TOO_SHORT")
        }
    }

    fun exportFile(directory: File, password: String) = runOperation {
        val name = "Tuvora-${System.currentTimeMillis()}.tuvora-backup"
        val target = File(directory, name)
        val tmp = File(directory, "$name.tmp")
        try {
            val result = tmp.outputStream().use { service.exportBackup(password, it) }
            when (result) {
                is BackupService.BackupResult.Failed -> throw result.cause
                is BackupService.BackupResult.PasswordTooShort -> error("PASSWORD_TOO_SHORT")
                is BackupService.BackupResult.Success -> check(tmp.renameTo(target))
            }
            mutable.value = BackupRestoreState(notice = context.getString(com.nuvio.tv.R.string.backup_file_saved, target.absolutePath))
        } finally { tmp.delete() }
    }

    fun preview(uri: Uri, password: String) = runOperation {
        val result = open(uri).use { service.previewBackup(it, password) }
        if (result is RestoreService.PreviewResult.Ready) {
            pendingSource = uri
            pendingPassword = password
            mutable.value = BackupRestoreState(preview = result)
        } else {
            pendingPassword = null
            pendingSource = null
            mutable.value = BackupRestoreState(notice = context.getString(
                if (result is RestoreService.PreviewResult.WrongPassword) com.nuvio.tv.R.string.backup_wrong_password
                else com.nuvio.tv.R.string.backup_invalid_file))
        }
    }

    fun restore() {
        val source = pendingSource ?: return
        val password = pendingPassword ?: return
        val fingerprint = state.value.preview?.archiveSha256 ?: return
        runOperation {
            when (val result = open(source).use { service.applyRestore(it, password, fingerprint) }) {
                RestoreService.RestoreOutcome.Success -> {
                    pendingPassword = null
                    pendingSource = null
                    mutable.value = BackupRestoreState(restartRequired = true)
                }
                is RestoreService.RestoreOutcome.Failed -> throw result.cause
            }
        }
    }

    fun resumeSync() = runOperation {
        com.nuvio.tv.core.backup.RestoreSyncHold.release(context)
        check(restarter.restart())
    }

    fun restart() { restarter.restart() }

    private fun open(uri: Uri): java.io.InputStream =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else requireNotNull(context.contentResolver.openInputStream(uri))

    private fun runOperation(block: suspend () -> Unit) {
        if (state.value.busy || state.value.restartRequired) return
        mutable.value = state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val message = when {
                    e.message == "SIGN_OUT_REQUIRED" -> context.getString(com.nuvio.tv.R.string.backup_sign_out)
                    e.message?.startsWith("PIN_REQUIRED:") == true ->
                        context.getString(com.nuvio.tv.R.string.backup_pin_required, e.message!!.substringAfter(':'))
                    e.message == "BACKUP_CHANGED" -> context.getString(com.nuvio.tv.R.string.backup_invalid_file)
                    e.message == "PLAYLIST_TOO_LARGE" -> context.getString(com.nuvio.tv.R.string.backup_playlist_limit)
                    e.message == "PASSWORD_TOO_SHORT" -> context.getString(com.nuvio.tv.R.string.backup_password_hint)
                    else -> context.getString(com.nuvio.tv.R.string.backup_operation_failed)
                }
                mutable.value = state.value.copy(busy = false, notice = message)
            } finally {
                if (state.value.busy) mutable.value = state.value.copy(busy = false)
            }
        }
    }
}
