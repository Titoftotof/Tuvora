@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileCopyOptions
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
internal fun ProfileSettingsSourceDialog(
    profiles: List<UserProfile>,
    selectedSourceProfileId: Int?,
    options: ProfileCopyOptions,
    onDismiss: () -> Unit,
    onConfirm: (Int?, ProfileCopyOptions) -> Unit
) {
    var source by remember { mutableStateOf(selectedSourceProfileId) }
    var selected by remember { mutableStateOf(options) }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.profile_copy_settings_setup_title), width = 620.dp, scrollable = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.profile_copy_settings_start_fresh), source == null) { source = null }
            profiles.forEach { profile ->
                Choice(profile.name, source == profile.id) { source = profile.id }
            }
            if (source != null) CopyOptions(selected, true) { selected = it }
        }
        Text(stringResource(R.string.profile_copy_exclusions_v2))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss) { Text(stringResource(R.string.profile_cancel)) }
            Button(onClick = { onConfirm(source, selected) }) { Text(stringResource(R.string.profile_copy_settings_apply)) }
        }
    }
}

@Composable
internal fun CopyProfileSettingsDialog(
    profiles: List<UserProfile>,
    targetProfile: UserProfile,
    isCopying: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCopy: (Int, ProfileCopyOptions) -> Unit
) {
    val sources = profiles.filterNot { it.id == targetProfile.id }
    var source by remember(targetProfile.id) { mutableStateOf(sources.firstOrNull { it.isPrimary }?.id ?: sources.firstOrNull()?.id) }
    var options by remember(targetProfile.id) { mutableStateOf(ProfileCopyOptions.DEFAULT) }
    NuvioDialog(
        onDismiss = { if (!isCopying) onDismiss() },
        title = stringResource(R.string.profile_copy_settings_title, targetProfile.name),
        width = 620.dp,
        scrollable = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { profile ->
                Choice(profile.name, source == profile.id, !isCopying) { source = profile.id }
            }
            CopyOptions(options, !isCopying, !targetProfile.usesPrimaryAddons) { options = it }
            if (targetProfile.usesPrimaryAddons) Text(stringResource(R.string.profile_copy_addons_shared))
        }
        Text(stringResource(R.string.profile_copy_exclusions_v2))
        errorMessage?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss, enabled = !isCopying) { Text(stringResource(R.string.profile_cancel)) }
            Button(
                enabled = source != null && options.hasAnySelection && !isCopying,
                onClick = { source?.let { onCopy(it, options) } }
            ) { Text(stringResource(if (isCopying) R.string.profile_copy_settings_copying else R.string.profile_copy_settings_action)) }
        }
    }
}

@Composable
private fun CopyOptions(value: ProfileCopyOptions, enabled: Boolean, addonsEnabled: Boolean = true, onChange: (ProfileCopyOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice(stringResource(R.string.profile_copy_addons), value.copyAddons, enabled && addonsEnabled) { onChange(value.copy(copyAddons = !value.copyAddons)) }
        Choice(stringResource(R.string.profile_copy_iptv), value.copyIptv, enabled) { onChange(value.copy(copyIptv = !value.copyIptv)) }
        Choice(stringResource(R.string.profile_copy_other_connections), value.copyOtherConnections, enabled) { onChange(value.copy(copyOtherConnections = !value.copyOtherConnections)) }
        Choice(stringResource(R.string.profile_copy_preferences), value.copySettings, enabled) { onChange(value.copy(copySettings = !value.copySettings)) }
        Choice(stringResource(R.string.profile_copy_favorites), value.copyFavorites, enabled) { onChange(value.copy(copyFavorites = !value.copyFavorites)) }
        Choice(stringResource(R.string.profile_copy_history), value.copyHistory, enabled) { onChange(value.copy(copyHistory = !value.copyHistory)) }
    }
}

@Composable
private fun Choice(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text((if (checked) "✓  " else "☐  ") + label)
    }
}
