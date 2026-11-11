package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.home.*
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun IptvHomeSettingsEntry(viewModel: IptvHomeSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var providerId by remember(state.profileId) { mutableStateOf<String?>(null) }
    var contentType by remember(state.profileId) { mutableStateOf<IptvHomeContentType?>(null) }
    var ordering by remember(state.profileId) { mutableStateOf(false) }
    var filter by remember(providerId, contentType) { mutableStateOf("") }
    LaunchedEffect(state.profileId) { open = false }
    val provider = state.accounts.firstOrNull { it.id == providerId }
    val type = contentType
    LaunchedEffect(open, provider?.id, type) {
        if (open && provider != null && type != null && !ordering) viewModel.load(provider.id, type)
    }
    val enabledRows = state.selections.filter { it.enabled }
    SettingsActionRow(
        title = stringResource(R.string.iptv_home_categories_title),
        subtitle = stringResource(R.string.iptv_home_categories_hint),
        value = enabledRows.size.toString(),
        onClick = { providerId = null; contentType = null; ordering = false; open = true }
    )
    if (!open) return
    val back = {
        when {
            ordering -> ordering = false
            contentType != null -> contentType = null
            providerId != null -> providerId = null
            else -> open = false
        }
    }
    NuvioDialog(
        onDismiss = back,
        title = stringResource(R.string.iptv_home_categories_title),
        subtitle = provider?.name ?: stringResource(R.string.iptv_home_categories_hint),
        width = 650.dp
    ) {
        if (state.saveFailed) Text(stringResource(R.string.iptv_home_save_failed), color = NuvioTheme.colors.TextPrimary)
        when {
            !state.ready -> Text(stringResource(R.string.iptv_home_loading), color = NuvioTheme.colors.TextPrimary)
            ordering -> {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(enabledRows, key = { it.rowKey }) { row ->
                        Column {
                            Text(row.categoryName + " · " + row.providerName + " · " + stringResource(
                                if (row.contentType == IptvHomeContentType.MOVIES) R.string.iptv_home_movies else R.string.iptv_home_series
                            ), color = NuvioTheme.colors.TextPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.move(row, -1) }, enabled = row != enabledRows.firstOrNull()) {
                                    Text(stringResource(R.string.iptv_home_move_up))
                                }
                                Button(onClick = { viewModel.move(row, 1) }, enabled = row != enabledRows.lastOrNull()) {
                                    Text(stringResource(R.string.iptv_home_move_down))
                                }
                                Button(onClick = { viewModel.toggle(row) }) { Text(stringResource(R.string.iptv_home_remove)) }
                            }
                        }
                    }
                }
            }
            provider == null -> {
                if (enabledRows.isNotEmpty()) Button(onClick = { ordering = true }) {
                    Text(stringResource(R.string.iptv_home_order))
                }
                if (state.accounts.isEmpty()) Text(stringResource(R.string.iptv_home_no_provider), color = NuvioTheme.colors.TextPrimary)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.accounts, key = { it.id }) { account ->
                        SettingsActionRow(account.name, null, onClick = { providerId = account.id })
                    }
                }
            }
            type == null -> {
                IptvHomeContentType.entries.forEach { choice ->
                    SettingsActionRow(
                        stringResource(if (choice == IptvHomeContentType.MOVIES) R.string.iptv_home_movies else R.string.iptv_home_series),
                        null, onClick = { contentType = choice }
                    )
                }
            }
            state.loading || state.categoryOwner != "${provider.id}|${type.key}" -> Text(stringResource(R.string.iptv_home_loading), color = NuvioTheme.colors.TextPrimary)
            state.loadFailed -> {
                Text(stringResource(R.string.iptv_settings_load_failed), color = NuvioTheme.colors.TextPrimary)
                Button(onClick = { viewModel.load(provider.id, type) }) { Text(stringResource(R.string.action_retry)) }
            }
            else -> {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.iptv_home_filter), color = NuvioTheme.colors.TextSecondary) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NuvioTheme.colors.TextPrimary,
                        unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                        cursorColor = NuvioTheme.colors.FocusRing,
                        focusedBorderColor = NuvioTheme.colors.FocusRing,
                        unfocusedBorderColor = NuvioTheme.colors.TextTertiary
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val categories = state.categories.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                if (categories.isEmpty()) Text(stringResource(R.string.iptv_home_empty), color = NuvioTheme.colors.TextPrimary)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories, key = { it.id }) { category ->
                        val row = IptvHomeCategorySelection(provider.id, provider.name, type, category.id, category.name)
                        val checked = state.selections.any { it.rowKey == row.rowKey && it.enabled }
                        Button(
                            onClick = { viewModel.toggle(row) },
                            modifier = Modifier.fillMaxWidth().semantics { selected = checked },
                            scale = ButtonDefaults.scale(focusedScale = 1f)
                        ) { Text((if (checked) "✓  " else "☐  ") + category.name) }
                    }
                }
            }
        }
        Button(onClick = back) { Text(stringResource(R.string.iptv_home_back)) }
    }
}
