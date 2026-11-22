package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.XtreamProgram
import kotlinx.coroutines.delay

/**
 * TiviMate-style two-level fullscreen live overlay:
 *  - Level 1 (1x LEFT from fullscreen): semi-transparent Channel List over the playing video,
 *    with channel number, logo, name, current programme title, live 3dp progress bar, and an
 *    adjacent compact upcoming-schedule strip for the browsed channel.
 *  - Level 2 (2x LEFT from Channel List): Groups Column opens side-by-side on the far left
 *    so the viewer can switch categories and browse channels without leaving fullscreen video.
 *
 * Browsing never tunes: only OK tunes, protecting single-connection IPTV providers.
 */
@Composable
internal fun BoxScope.LiveChannelListOverlay(
    channels: List<GuideChannel>,
    epg: Map<Int, GuideEpg>,
    playingContentId: String?,
    favoriteIds: Set<String>,
    onBrowse: (GuideChannel) -> Unit,
    onPick: (GuideChannel) -> Unit,
    onClose: () -> Unit,
    playlistName: String = "",
    categories: List<GuideCategory> = emptyList(),
    selectedCategoryId: String? = null,
    onSelectCategory: ((String) -> Unit)? = null,
    nowMs: Long = System.currentTimeMillis(),
) {
    val startIndex = remember(channels, playingContentId) {
        channels.indexOfFirst { it.contentId == playingContentId }.coerceAtLeast(0)
    }
    val channelListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0),
    )
    val startFocus = remember { FocusRequester() }
    val groupFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val latestClose by rememberUpdatedState(onClose)
    val latestSelectCategory by rememberUpdatedState(onSelectCategory)

    val canShowGroups = categories.isNotEmpty() && onSelectCategory != null
    var groupsOpen by remember { mutableStateOf(false) }
    var pendingGroupFocus by remember { mutableStateOf(false) }
    var pendingChannelFocus by remember { mutableStateOf(false) }
    var browsedChannel by remember(channels, playingContentId) {
        mutableStateOf(channels.getOrNull(startIndex) ?: channels.firstOrNull())
    }
    var pendingCategoryId by remember { mutableStateOf<String?>(null) }

    // Tiny debounce when arrowing through groups inside fullscreen overlay so fast scrolling stays 60fps.
    LaunchedEffect(pendingCategoryId) {
        val target = pendingCategoryId ?: return@LaunchedEffect
        if (target == selectedCategoryId) return@LaunchedEffect
        delay(120L)
        latestSelectCategory?.invoke(target)
        channelListState.scrollToItem(0)
    }

    val selectedCategory = remember(categories, selectedCategoryId) {
        categories.firstOrNull { it.id == selectedCategoryId }
    }
    val selectedCategoryLabel = selectedCategory?.let { cat ->
        cat.special?.labelRes?.let { stringResource(it) } ?: cat.name
    } ?: stringResource(R.string.iptv_guide_channel_list_title)

    val headerText = remember(playlistName, selectedCategoryLabel) {
        if (playlistName.isNotBlank()) "$playlistName  •  $selectedCategoryLabel" else selectedCategoryLabel
    }

    // Composed after the fullscreen BackHandler, so this one wins while the overlay is open.
    BackHandler {
        if (groupsOpen) {
            groupsOpen = false
            pendingChannelFocus = true
        } else {
            latestClose()
        }
    }
    LaunchedEffect(Unit) { startFocus.requestFocusOrFalse() }

    LaunchedEffect(pendingGroupFocus, groupsOpen) {
        if (!pendingGroupFocus || !groupsOpen) return@LaunchedEffect
        withFrameNanos { }
        val target = selectedCategoryId?.let { groupFocusRequesters[it] }
            ?: categories.firstOrNull()?.id?.let { groupFocusRequesters[it] }
        target?.requestFocusOrFalse()
        pendingGroupFocus = false
    }

    LaunchedEffect(pendingChannelFocus, groupsOpen, channels) {
        if (!pendingChannelFocus || groupsOpen) return@LaunchedEffect
        withFrameNanos { }
        startFocus.requestFocusOrFalse()
        pendingChannelFocus = false
    }

    Row(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } },
    ) {
        // Level 2: TiviMate Groups Column (side-by-side over live fullscreen video).
        if (groupsOpen && canShowGroups) {
            val selectedGroupIdx = remember(categories, selectedCategoryId) {
                categories.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
            }
            val groupListState = rememberLazyListState(
                initialFirstVisibleItemIndex = (selectedGroupIdx - 4).coerceAtLeast(0),
            )
            Column(
                modifier = Modifier
                    .width(OVERLAY_GROUPS_WIDTH)
                    .fillMaxHeight()
                    .background(TiviMateStyle.OverlayGroupsBackground)
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionRight -> {
                                if (event.type == KeyEventType.KeyDown) {
                                    groupsOpen = false
                                    pendingChannelFocus = true
                                }
                                true
                            }
                            Key.DirectionLeft -> true
                            Key.Back -> {
                                if (event.type == KeyEventType.KeyUp) {
                                    groupsOpen = false
                                    pendingChannelFocus = true
                                }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(OVERLAY_HEADER_HEIGHT)
                        .background(TiviMateStyle.HeaderBackground)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = playlistName.ifBlank { stringResource(R.string.iptv_guide_groups_title) },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TiviMateStyle.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(TiviMateStyle.Divider))
                LazyColumn(
                    state = groupListState,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(categories, key = { _, cat -> cat.id }) { _, cat ->
                        val label = cat.special?.labelRes?.let { stringResource(it) } ?: cat.name
                        val req = remember(cat.id) {
                            groupFocusRequesters.getOrPut(cat.id) { FocusRequester() }
                        }
                        OverlayGroupRow(
                            label = label,
                            selected = cat.id == selectedCategoryId,
                            focusRequester = req,
                            onFocused = { pendingCategoryId = cat.id },
                            onClick = {
                                latestSelectCategory?.invoke(cat.id)
                                groupsOpen = false
                                pendingChannelFocus = true
                            },
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(TiviMateStyle.Divider))
        }

        // Level 1: TiviMate Overlay Channel List.
        Column(
            modifier = Modifier
                .width(CHANNEL_LIST_WIDTH)
                .fillMaxHeight()
                .background(TiviMateStyle.OverlayBackground)
                .onPreviewKeyEvent { event ->
                    if (groupsOpen) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) latestClose()
                            true
                        }
                        Key.DirectionLeft -> {
                            if (canShowGroups && event.type == KeyEventType.KeyDown) {
                                groupsOpen = true
                                pendingGroupFocus = true
                            }
                            true
                        }
                        Key.Back -> {
                            if (event.type == KeyEventType.KeyUp) latestClose()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OVERLAY_HEADER_HEIGHT)
                    .background(TiviMateStyle.HeaderBackground)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TiviMateStyle.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (channels.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = channels.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TiviMateStyle.TextSecondary,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TiviMateStyle.Divider))

            LazyColumn(
                state = channelListState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(channels, key = { _, ch -> ch.contentId }) { index, channel ->
                    val channelEpg = epg[channel.streamId]
                    val currentProg = remember(channelEpg, nowMs) {
                        channelEpg?.let { GuideWindowRows.atTime(it, nowMs).now }
                    }
                    ChannelListRow(
                        number = index + 1,
                        channel = channel,
                        nowProgramme = currentProg,
                        nowMs = nowMs,
                        playing = channel.contentId == playingContentId,
                        favorite = channel.contentId in favoriteIds,
                        focusRequester = if (index == startIndex) startFocus else null,
                        onFocused = {
                            browsedChannel = channel
                            onBrowse(channel)
                        },
                        onClick = { onPick(channel) },
                    )
                }
            }
        }

        // Compact TiviMate schedule column beside the Channel List when Groups are closed.
        val scheduleChannel = if (!groupsOpen) browsedChannel else null
        val scheduleEpg = scheduleChannel?.let { epg[it.streamId] }
        val upcoming = remember(scheduleEpg, nowMs) {
            scheduleEpg?.programmes
                ?.filter { it.endMs > nowMs }
                ?.sortedBy { it.startMs }
                ?.take(7)
                .orEmpty()
        }
        if (scheduleChannel != null && upcoming.isNotEmpty()) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(TiviMateStyle.Divider))
            OverlayUpcomingSchedulePane(
                channelName = scheduleChannel.name,
                programmes = upcoming,
                nowMs = nowMs,
            )
        }
    }
}

@Composable
private fun OverlayGroupRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) { if (focused) latestFocused() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(TiviMateStyle.ShapeSm)
            .background(
                when {
                    focused -> TiviMateStyle.FocusSurface
                    selected -> TiviMateStyle.SelectedSurface
                    else -> Color.Transparent
                },
            )
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 13.sp,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                focused -> TiviMateStyle.FocusContent
                selected -> TiviMateStyle.TextPrimary
                else -> TiviMateStyle.TextSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChannelListRow(
    number: Int,
    channel: GuideChannel,
    nowProgramme: XtreamProgram?,
    nowMs: Long,
    playing: Boolean,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) { if (focused) latestFocused() }

    val logoContext = LocalContext.current
    val logoDensity = LocalDensity.current
    val logoPx = remember(logoDensity) { with(logoDensity) { 32.dp.roundToPx() }.coerceAtLeast(1) }
    val logoRequest = remember(channel.logo, logoPx) {
        channel.logo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(logoContext)
                .data(url)
                .size(logoPx, logoPx)
                .memoryCacheKey("${url}_${logoPx}x${logoPx}")
                .crossfade(false)
                .build()
        }
    }

    val titleColor = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.TextPrimary
    val subColor = if (focused) TiviMateStyle.FocusSubContent else TiviMateStyle.TextSecondary
    val progress = remember(nowProgramme, nowMs) {
        nowProgramme?.let { p ->
            val dur = (p.endMs - p.startMs).coerceAtLeast(1L)
            ((nowMs - p.startMs).toFloat() / dur).coerceIn(0f, 1f)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHANNEL_LIST_ROW_HEIGHT)
            .clip(TiviMateStyle.ShapeSm)
            .background(
                when {
                    focused -> TiviMateStyle.FocusSurface
                    playing -> TiviMateStyle.SelectedSurface
                    else -> Color.Transparent
                },
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) TiviMateStyle.FocusSubContent else if (playing) TiviMateStyle.PlayingAccent else TiviMateStyle.TextMuted,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(28.dp),
            maxLines = 1,
        )
        AsyncImage(
            model = logoRequest,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(TiviMateStyle.ShapeXs),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (playing) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.PlayingAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    color = titleColor,
                    fontWeight = if (focused || playing) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (favorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            val nowTitle = nowProgramme?.title?.takeIf { it.isNotBlank() }
            if (nowTitle != null) {
                Text(
                    text = nowTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress != null) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .clip(TiviMateStyle.ShapePill)
                            .background(if (focused) Color(0x33111518) else TiviMateStyle.Divider),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(if (focused) TiviMateStyle.FocusContent else TiviMateStyle.Accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayUpcomingSchedulePane(
    channelName: String,
    programmes: List<XtreamProgram>,
    nowMs: Long,
) {
    Column(
        modifier = Modifier
            .width(OVERLAY_EPG_WIDTH)
            .fillMaxHeight()
            .background(TiviMateStyle.OverlayEpgBackground),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OVERLAY_HEADER_HEIGHT)
                .background(TiviMateStyle.HeaderBackground)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = channelName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = TiviMateStyle.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(TiviMateStyle.Divider))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            programmes.forEach { prog ->
                val isNow = nowMs in prog.startMs until prog.endMs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = guideHhMm(prog.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                        color = if (isNow) TiviMateStyle.Accent else TiviMateStyle.TextMuted,
                        modifier = Modifier.width(38.dp),
                        maxLines = 1,
                    )
                    Text(
                        text = prog.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 12.sp,
                        fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isNow) TiviMateStyle.TextPrimary else TiviMateStyle.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val OVERLAY_GROUPS_WIDTH = 224.dp
private val CHANNEL_LIST_WIDTH = 356.dp
private val OVERLAY_EPG_WIDTH = 248.dp
private val OVERLAY_HEADER_HEIGHT = 40.dp
private val CHANNEL_LIST_ROW_HEIGHT = 56.dp
