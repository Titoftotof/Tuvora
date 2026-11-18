@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.core.picture.AspectMode

/** Modal focus/Back ownership, with the picture left visible for immediate comparison. */
@Composable
internal fun AspectModePickerDialog(
    currentMode: AspectMode,
    onSelectMode: (AspectMode) -> Unit,
    onApplyToAll: () -> Unit,
    onDismiss: () -> Unit,
    tunnelingEnabled: Boolean = false,
) {
    val modes = if (tunnelingEnabled) listOf(AspectMode.ORIGINAL, AspectMode.STRETCH) else AspectMode.entries
    val initialFocus = remember { FocusRequester() }
    // Keep the initial requester on the same row when selection changes.
    val initiallySelected = remember { currentMode.takeIf { it in modes } ?: modes.first() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(360.dp).fillMaxHeight()
                    .background(Color(0xED142432))
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Text(
                    stringResource(R.string.player_more_aspect_ratio),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    modes.forEach { mode ->
                        AspectModeRow(
                            label = stringResource(mode.labelResId),
                            selected = currentMode == mode,
                            onClick = { onSelectMode(mode) },
                            modifier = if (mode == initiallySelected) Modifier.focusRequester(initialFocus) else Modifier
                        )
                    }
                }
                if (tunnelingEnabled) {
                    Text(
                        stringResource(R.string.player_aspect_tunneling_modes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                AspectModeRow(
                    label = stringResource(R.string.player_aspect_apply_all),
                    selected = false,
                    onClick = onApplyToAll,
                    isMode = false
                )
            }
        }
        LaunchedEffect(Unit) { initialFocus.requestFocus() }
    }
}

@Composable
private fun AspectModeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMode: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val foreground = if (focused) Color(0xFF142432) else Color.White
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                if (isMode) {
                    role = Role.RadioButton
                    this.selected = selected
                }
            },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(6.dp)),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isMode) Icon(
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(24.dp)
            )
            Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground)
        }
    }
}
