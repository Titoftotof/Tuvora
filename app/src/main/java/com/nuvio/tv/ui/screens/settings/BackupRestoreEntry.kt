@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import java.io.File

@Composable
internal fun BackupRestoreEntry(viewModel: BackupRestoreViewModel = hiltViewModel()) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = NuvioTheme.colors.TextPrimary,
        unfocusedTextColor = NuvioTheme.colors.TextPrimary,
        cursorColor = NuvioTheme.colors.FocusRing,
        focusedBorderColor = NuvioTheme.colors.FocusRing,
        unfocusedBorderColor = NuvioTheme.colors.TextTertiary
    )
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncHeld by com.nuvio.tv.core.backup.RestoreSyncHold.state.collectAsStateWithLifecycle()
    var confirmSync by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var exportMode by remember { mutableStateOf(true) }
    var browsing by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf<File?>(null) }
    val roots = remember { (context.getExternalFilesDirs(null).filterNotNull() +
        listOf(File(context.filesDir, "backups"), File(context.filesDir, "backup_safety"), File("/storage"))).distinctBy { it.absolutePath } }
    val files by produceState<List<File>>(emptyList(), directory, browsing) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            File(context.filesDir, "backups").mkdirs()
            (directory?.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || it.extension == "tuvora-backup") }
                ?: roots).sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { viewModel.export(uri, password); password = ""; confirmation = "" }
    }
    val select = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { viewModel.preview(uri, password); password = ""; confirmation = "" }
    }
    SettingsActionRow(
        title = stringResource(R.string.backup_title),
        subtitle = stringResource(R.string.backup_subtitle),
        onClick = { viewModel.clear(); password = ""; confirmation = ""; open = true }
    )
    if (syncHeld) SettingsActionRow(
        title = stringResource(R.string.backup_resume_sync),
        subtitle = stringResource(R.string.backup_sync_held),
        onClick = { viewModel.clear(); confirmSync = true }
    )
    if (confirmSync) NuvioDialog(
        title = stringResource(R.string.backup_resume_sync),
        onDismiss = { if (!state.busy) confirmSync = false }, width = 620.dp
    ) {
        Text(stringResource(R.string.backup_resume_sync_warning))
        state.notice?.let { Text(it) }
        Button(onClick = { viewModel.resumeSync() }, enabled = !state.busy) {
            Text(stringResource(R.string.backup_resume_sync))
        }
        Button(onClick = { confirmSync = false }, enabled = !state.busy) {
            Text(stringResource(R.string.profile_cancel))
        }
    }
    if (!open) return
    NuvioDialog(
        title = stringResource(R.string.backup_title), width = 700.dp,
        onDismiss = { if (!state.busy && !state.restartRequired) { open = false; password = ""; confirmation = ""; viewModel.clear() } }
    ) {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                state.notice?.let { Text(it) }
                if (state.busy) Text(stringResource(R.string.backup_working))
            }
            if (state.restartRequired) {
                item {
                    Text(stringResource(R.string.backup_restart_hint))
                    Button(onClick = { viewModel.restart() }) { Text(stringResource(R.string.backup_restart)) }
                }
            } else if (state.preview != null) {
                item {
                    val preview = state.preview!!
                    Text(stringResource(R.string.backup_preview, preview.profileCount, preview.iptvConnectionCount))
                    Text(preview.profileNames.joinToString(", "))
                    Text(stringResource(R.string.backup_replace_warning))
                    Button(onClick = { viewModel.restore() }, enabled = !state.busy) {
                        Text(stringResource(R.string.backup_confirm_replace))
                    }
                    Button(onClick = { viewModel.clear() }, enabled = !state.busy) { Text(stringResource(R.string.profile_cancel)) }
                }
            } else if (browsing) {
                item {
                    Text(directory?.absolutePath ?: stringResource(R.string.backup_choose_folder))
                    Button(onClick = { directory = null; browsing = false }) { Text(stringResource(R.string.profile_cancel)) }
                    if (directory != null) {
                        Button(onClick = { directory = directory?.parentFile?.takeIf { parent -> roots.any { parent.absolutePath.startsWith(it.absolutePath) } } }) {
                            Text(stringResource(R.string.backup_parent))
                        }
                        if (exportMode) Button(
                            enabled = !state.busy && directory!!.canWrite(),
                            onClick = { viewModel.exportFile(directory!!, password); password = ""; confirmation = ""; browsing = false }
                        ) { Text(stringResource(R.string.backup_save_here)) }
                    }
                }
                items(files, key = { it.absolutePath }) { file ->
                    Button(enabled = !state.busy, onClick = {
                        if (file.isDirectory) directory = file
                        else { viewModel.preview(Uri.fromFile(file), password); password = ""; browsing = false }
                    }) { Text(file.name.ifBlank { file.absolutePath }) }
                }
            } else {
                item {
                    Text(stringResource(R.string.backup_password_hint))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { exportMode = true }, enabled = !state.busy) { Text(stringResource(R.string.backup_export)) }
                        Button(onClick = { exportMode = false }, enabled = !state.busy) { Text(stringResource(R.string.backup_import)) }
                    }
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        colors = fieldColors,
                        textStyle = androidx.tv.material3.MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
                        label = { Text(stringResource(R.string.backup_password)) }, enabled = !state.busy
                    )
                    if (exportMode) OutlinedTextField(
                        value = confirmation, onValueChange = { confirmation = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        colors = fieldColors,
                        textStyle = androidx.tv.material3.MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
                        label = { Text(stringResource(R.string.backup_password_confirm)) }, enabled = !state.busy
                    )
                    val valid = password.length >= 12 && (!exportMode || password == confirmation)
                    Button(enabled = valid && !state.busy, onClick = {
                        try {
                            if (exportMode) create.launch("Tuvora-${System.currentTimeMillis()}.tuvora-backup")
                            else select.launch(arrayOf("*/*"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            viewModel.pickerUnavailable(); directory = null; browsing = true
                        }
                    }) { Text(stringResource(R.string.backup_choose_document)) }
                    Button(enabled = valid && !state.busy, onClick = { directory = null; browsing = true }) {
                        Text(stringResource(R.string.backup_browse_files))
                    }
                }
            }
        }
    }
}
