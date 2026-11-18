@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.core.network.SYNC_BACKEND_NUVIO_ID
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** Available in production too. Historical name retained for existing call sites. */
@Composable
fun DebugSyncBackendSwitchCard(
    uiState: AccountUiState,
    requireConfirmation: Boolean,
    modifier: Modifier = Modifier,
    onSwitchBackend: () -> Unit,
) {
    if (!uiState.debugBackendSwitchEnabled) return
    var confirm by remember { mutableStateOf(false) }
    val official = uiState.syncBackendId == SYNC_BACKEND_NUVIO_ID
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.workspace_separate), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { confirm = true }, enabled = !official && !uiState.isDebugBackendSwitching) {
                Text(stringResource(R.string.workspace_official) + if (official) " ✓" else "")
            }
            Button(onClick = { confirm = true }, enabled = official && !uiState.isDebugBackendSwitching) {
                Text(stringResource(R.string.workspace_personal) + if (!official) " ✓" else "")
            }
        }
        Text(stringResource(R.string.workspace_credentials_notice), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary)
        uiState.debugBackendSwitchError?.let {
            Text(it, color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodySmall)
        }
        if (uiState.isDebugBackendSwitching) Text(stringResource(R.string.workspace_switching))
    }
    // Confirmation is mandatory even from the sign-in screen: local-only playlists may exist.
    if (confirm) NuvioDialog(
        onDismiss = { confirm = false },
        title = stringResource(R.string.workspace_confirm_title),
        subtitle = stringResource(R.string.workspace_confirm_message),
        suppressFirstKeyUp = false,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = { confirm = false; onSwitchBackend() }, enabled = !uiState.isDebugBackendSwitching) {
                Text(stringResource(R.string.action_switch))
            }
        }
    }
}
