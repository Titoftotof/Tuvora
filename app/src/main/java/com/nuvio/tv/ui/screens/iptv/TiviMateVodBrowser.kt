package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * TiviMate-inspired Movies & Series browser:
 *  - Left category column (collapses instantaneously when moving RIGHT into the poster grid,
 *    re-expands on LEFT from column 0 or BACK, and LEFT from categories opens the main sidebar).
 *  - Compact top metadata header (focused title, rating badge, Playlist • Category breadcrumb,
 *    exact "index / total" counter).
 *  - High-speed 5-column (categories open) / 7-column (full-width) poster grid using lean,
 *    size-bounded Coil cards for 60fps browsing on constrained Android TV / Fire TV hardware.
 */
@Composable
internal fun TiviMateVodBrowser(
    uiState: XtreamHubUiState,
    accountName: String,
    showAccountHeader: Boolean,
    onOpenAccountPicker: () -> Unit,
    onActivate: (XtreamHubItem) -> Unit,
    onRetry: () -> Unit,
    onLoadCategory: (String) -> Unit,
    onRetryCategory: (String) -> Unit,
    onPrefetchCategory: (String) -> Unit,
    onLoadMoreCategory: (String) -> Unit,
    topUpFocusRequester: FocusRequester? = null,
    autoFocusCategories: Boolean = true,
    categoryListFocusRequester: FocusRequester? = null,
) {
    val pageStatus = rememberEffectiveLoadStatus(uiState.categoriesLoad)
    when {
        uiState.error != null -> ErrorState(message = uiState.error, onRetry = onRetry)
        pageStatus is LoadStatus.Failed && uiState.categories.isEmpty() ->
            ErrorState(message = stringResource(R.string.iptv_hub_error_timeout), onRetry = onRetry)
        pageStatus is LoadStatus.Loading && uiState.categories.isEmpty() -> VodBrowserSkeleton()
        uiState.categories.isEmpty() -> EmptyScreenState(
            title = stringResource(R.string.iptv_hub_empty_title),
            subtitle = stringResource(R.string.iptv_hub_empty_subtitle),
        )
        else -> {
            TiviMateVodBrowserContent(
                uiState = uiState,
                accountName = accountName,
                showAccountHeader = showAccountHeader,
                onOpenAccountPicker = onOpenAccountPicker,
                onActivate = onActivate,
                onLoadCategory = onLoadCategory,
                onRetryCategory = onRetryCategory,
                onPrefetchCategory = onPrefetchCategory,
                onLoadMoreCategory = onLoadMoreCategory,
                topUpFocusRequester = topUpFocusRequester,
                autoFocusCategories = autoFocusCategories,
                categoryListFocusRequester = categoryListFocusRequester,
            )
        }
    }
}

@Composable
private fun TiviMateVodBrowserContent(
    uiState: XtreamHubUiState,
    accountName: String,
    showAccountHeader: Boolean,
    onOpenAccountPicker: () -> Unit,
    onActivate: (XtreamHubItem) -> Unit,
    onLoadCategory: (String) -> Unit,
    onRetryCategory: (String) -> Unit,
    onPrefetchCategory: (String) -> Unit,
    onLoadMoreCategory: (String) -> Unit,
    topUpFocusRequester: FocusRequester?,
    autoFocusCategories: Boolean,
    categoryListFocusRequester: FocusRequester?,
) {
    val categories = uiState.categories
    var selectedCategoryId by remember(uiState.selectedAccountId, uiState.section) {
        mutableStateOf(categories.firstOrNull()?.id)
    }
    LaunchedEffect(categories) {
        if (selectedCategoryId == null || categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = categories.firstOrNull()?.id
        }
    }

    val activeCategoryId = selectedCategoryId ?: categories.firstOrNull()?.id
    val activeCategory = remember(categories, activeCategoryId) {
        categories.firstOrNull { it.id == activeCategoryId }
    }
    val activeCategoryIndex = remember(categories, activeCategoryId) {
        categories.indexOfFirst { it.id == activeCategoryId }.coerceAtLeast(0)
    }

    // Load active category immediately on cache hit, or with tiny 110ms debounce while scrolling categories.
    LaunchedEffect(uiState.selectedAccountId, uiState.section, activeCategoryId, uiState.catalogueGeneration) {
        val id = activeCategoryId ?: return@LaunchedEffect
        val alreadyLoaded = uiState.itemsByCategory.containsKey(id)
        if (!alreadyLoaded) delay(110L)
        onLoadCategory(id)
        for (offset in 1..VOD_PREFETCH_LOOKAHEAD) {
            val next = categories.getOrNull(activeCategoryIndex + offset) ?: break
            onPrefetchCategory(next.id)
        }
    }

    val rawItems = activeCategoryId?.let { uiState.itemsByCategory[it] }.orEmpty()
    val items = remember(rawItems) { rawItems.distinctBy { it.cardId } }
    val hasMore = activeCategoryId?.let { uiState.hasMoreByCategory[it] == true } == true
    val loaded = activeCategoryId != null && uiState.itemsByCategory.containsKey(activeCategoryId)
    val rowStatus = rememberEffectiveLoadStatus(
        activeCategoryId?.let { uiState.rowLoads[it] } ?: LoadStatus.Idle,
    )

    var inGrid by remember(uiState.selectedAccountId, uiState.section) { mutableStateOf(false) }
    var pendingCategoryFocus by remember(uiState.selectedAccountId, uiState.section, autoFocusCategories) {
        mutableStateOf(autoFocusCategories)
    }
    var pendingGridFocus by remember { mutableStateOf(false) }
    var focusedItemIndex by remember(uiState.selectedAccountId, uiState.section, activeCategoryId) {
        mutableIntStateOf(0)
    }

    val categoryListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val categoryFocusRequesters = remember(uiState.selectedAccountId, uiState.section) {
        mutableMapOf<String, FocusRequester>()
    }
    val gridContainerFocus = remember { FocusRequester() }
    val firstGridItemFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(activeCategoryId) {
        gridState.scrollToItem(0)
        focusedItemIndex = 0
    }

    LaunchedEffect(pendingCategoryFocus, inGrid, activeCategoryId, categories.size) {
        if (!pendingCategoryFocus || inGrid || categories.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val target = activeCategoryId?.let { categoryFocusRequesters[it] }
        val landed = target?.requestFocusOrFalse() == true ||
            (categories.firstOrNull()?.id?.let { categoryFocusRequesters[it]?.requestFocusOrFalse() } == true)
        if (landed) {
            pendingCategoryFocus = false
        }
    }

    LaunchedEffect(pendingGridFocus, inGrid, items.size) {
        if (!pendingGridFocus || !inGrid) return@LaunchedEffect
        withFrameNanos { }
        val landed = when {
            items.isNotEmpty() -> firstGridItemFocus.requestFocusOrFalse() || gridContainerFocus.requestFocusOrFalse()
            rowStatus is LoadStatus.Failed -> retryFocus.requestFocusOrFalse()
            else -> false
        }
        if (landed || (!loaded && rowStatus is LoadStatus.Failed)) {
            pendingGridFocus = false
        }
    }

    fun exitGridToCategories() {
        inGrid = false
        pendingCategoryFocus = true
    }

    fun enterGrid() {
        if (!inGrid) {
            inGrid = true
            pendingGridFocus = true
        } else if (items.isNotEmpty()) {
            firstGridItemFocus.requestFocusOrFalse()
        }
    }

    BackHandler(enabled = inGrid) { exitGridToCategories() }

    val focusedItem = items.getOrNull(focusedItemIndex) ?: items.firstOrNull()
    val columns = if (inGrid) VOD_GRID_COLS_FULL else VOD_GRID_COLS_SPLIT

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TiviMateStyle.Background),
    ) {
        // Left Category Column — TiviMate-style, instant collapse to 0dp when browsing the grid.
        if (!inGrid) {
            Column(
                modifier = Modifier
                    .width(VOD_CATEGORY_COL_WIDTH)
                    .fillMaxHeight()
                    .background(TiviMateStyle.PanelBackground),
            ) {
                if (showAccountHeader) {
                    VodPlaylistHeader(
                        accountName = accountName,
                        section = uiState.section,
                        onClick = onOpenAccountPicker,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(TiviMateStyle.Divider),
                    )
                }
                LazyColumn(
                    state = categoryListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(categoryListFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(categories, key = { _, cat -> cat.id }) { index, category ->
                        val requester = remember(category.id) {
                            categoryFocusRequesters.getOrPut(category.id) { FocusRequester() }
                        }
                        VodCategoryRow(
                            label = category.name,
                            selected = category.id == activeCategoryId,
                            focusRequester = requester,
                            upFocusRequester = if (index == 0 && !showAccountHeader) topUpFocusRequester else null,
                            onFocused = { selectedCategoryId = category.id },
                            onEnterGrid = {
                                selectedCategoryId = category.id
                                enterGrid()
                            },
                        )
                    }
                }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(TiviMateStyle.Divider),
            )
        }

        // Right Content Area: Compact TiviMate Hero/Metadata Strip + Dense Fast Poster Grid.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            VodFocusedHeader(
                item = focusedItem,
                accountName = accountName,
                categoryName = activeCategory?.name.orEmpty(),
                focusedIndex = if (items.isEmpty()) 0 else focusedItemIndex + 1,
                totalCount = items.size,
                hasMore = hasMore,
                showImdbRatings = uiState.showImdbRatings,
                inGrid = inGrid,
            )

            Spacer(Modifier.height(10.dp))

            when {
                !loaded && rowStatus is LoadStatus.Failed -> {
                    VodFailedCategoryBox(
                        focusRequester = retryFocus,
                        onRetry = { activeCategoryId?.let(onRetryCategory) },
                        onLeft = { exitGridToCategories() },
                    )
                }
                !loaded -> {
                    VodGridSkeleton(columns = columns)
                }
                items.isEmpty() -> {
                    EmptyScreenState(
                        title = stringResource(R.string.iptv_hub_empty_title),
                        subtitle = stringResource(R.string.iptv_hub_empty_subtitle),
                        height = 240.dp,
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(gridContainerFocus)
                            .onFocusChanged { if (it.hasFocus && !inGrid) inGrid = true }
                            .focusRestorer(firstGridItemFocus),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItemsIndexed(items, key = { _, item -> item.cardId }) { index, item ->
                            if (hasMore && index >= items.lastIndex - VOD_LOAD_MORE_THRESHOLD) {
                                LaunchedEffect(activeCategoryId, items.size) {
                                    onLoadMoreCategory(activeCategoryId)
                                }
                            }
                            TiviMateVodPosterCard(
                                item = item,
                                showLabel = uiState.posterLabelsEnabled,
                                showRating = uiState.showImdbRatings,
                                focusRequester = if (index == 0) firstGridItemFocus else null,
                                isFirstColumn = index % columns == 0,
                                onFocused = { focusedItemIndex = index },
                                onExitLeft = { exitGridToCategories() },
                                onClick = { onActivate(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VodPlaylistHeader(
    accountName: String,
    section: XtreamSection,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val sectionIcon = if (section == XtreamSection.SERIES) Icons.Default.Tv else Icons.Default.Movie
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(TiviMateStyle.ShapeSm)
            .background(if (focused) TiviMateStyle.FocusSurface else TiviMateStyle.HeaderBackground)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = sectionIcon,
            contentDescription = null,
            tint = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.Accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = accountName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun VodCategoryRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onEnterGrid: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnEnterGrid by rememberUpdatedState(onEnterGrid)
    LaunchedEffect(focused) { if (focused) latestOnFocused() }
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
            .then(
                upFocusRequester?.let { req ->
                    Modifier.focusProperties { up = req }
                } ?: Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionRight) {
                    if (event.type == KeyEventType.KeyDown) latestOnEnterGrid()
                    true
                } else {
                    false
                }
            }
            .clickable { latestOnEnterGrid() }
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
private fun VodFocusedHeader(
    item: XtreamHubItem?,
    accountName: String,
    categoryName: String,
    focusedIndex: Int,
    totalCount: Int,
    hasMore: Boolean,
    showImdbRatings: Boolean,
    inGrid: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = item?.name ?: categoryName,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = TiviMateStyle.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val rating = item?.imdbRating?.takeIf { showImdbRatings && it > 0f }
            if (rating != null) {
                Row(
                    modifier = Modifier
                        .clip(TiviMateStyle.ShapeXs)
                        .background(TiviMateStyle.SelectedSurface)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = TiviMateStyle.RatingText,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f", rating),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TiviMateStyle.RatingText,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (categoryName.isNotBlank()) "$accountName  •  $categoryName" else accountName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = TiviMateStyle.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(
                    if (inGrid) R.string.iptv_vod_hint_grid else R.string.iptv_vod_hint_categories,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = TiviMateStyle.TextMuted,
                maxLines = 1,
            )
            if (totalCount > 0) {
                Spacer(Modifier.width(14.dp))
                val totalLabel = if (hasMore) "$totalCount+" else "$totalCount"
                Text(
                    text = "$focusedIndex / $totalLabel",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TiviMateStyle.TextPrimary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TiviMateStyle.Divider),
        )
    }
}

@Composable
private fun TiviMateVodPosterCard(
    item: XtreamHubItem,
    showLabel: Boolean,
    showRating: Boolean,
    focusRequester: FocusRequester?,
    isFirstColumn: Boolean,
    onFocused: () -> Unit,
    onExitLeft: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnExitLeft by rememberUpdatedState(onExitLeft)
    LaunchedEffect(focused) { if (focused) latestOnFocused() }

    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = remember(density) { with(density) { 126.dp.roundToPx() }.coerceAtLeast(1) }
    val heightPx = remember(density) { with(density) { 189.dp.roundToPx() }.coerceAtLeast(1) }
    val posterRequest = remember(item.poster, widthPx, heightPx) {
        item.poster?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(widthPx, heightPx)
                .memoryCacheKey("${url}_${widthPx}x${heightPx}")
                .crossfade(false)
                .build()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (isFirstColumn && event.key == Key.DirectionLeft) {
                    if (event.type == KeyEventType.KeyDown) latestOnExitLeft()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(TiviMateStyle.ShapeSm)
                .background(TiviMateStyle.CardPlaceholder)
                .border(
                    width = if (focused) 2.5.dp else 1.dp,
                    color = if (focused) TiviMateStyle.FocusSurface else TiviMateStyle.SubtleDivider,
                    shape = TiviMateStyle.ShapeSm,
                ),
        ) {
            if (posterRequest != null) {
                AsyncImage(
                    model = posterRequest,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TiviMateStyle.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val rating = item.imdbRating?.takeIf { showRating && it > 0f }
            if (rating != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(TiviMateStyle.ShapeXs)
                        .background(TiviMateStyle.RatingBg)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f", rating),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TiviMateStyle.RatingText,
                    )
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                color = if (focused) TiviMateStyle.TextPrimary else TiviMateStyle.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VodFailedCategoryBox(
    focusRequester: FocusRequester,
    onRetry: () -> Unit,
    onLeft: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(TiviMateStyle.ShapeMd)
                .background(if (focused) TiviMateStyle.FocusSurface else TiviMateStyle.PanelBackground)
                .border(1.dp, TiviMateStyle.Divider, TiviMateStyle.ShapeMd)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyDown) onLeft()
                        true
                    } else false
                }
                .clickable(onClick = onRetry)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.iptv_hub_row_failed),
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) TiviMateStyle.FocusContent else TiviMateStyle.TextPrimary,
            )
        }
    }
}

@Composable
private fun VodBrowserSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TiviMateStyle.Background),
    ) {
        Column(
            modifier = Modifier
                .width(VOD_CATEGORY_COL_WIDTH)
                .fillMaxHeight()
                .background(TiviMateStyle.PanelBackground)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(12) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(TiviMateStyle.ShapeSm)
                        .background(TiviMateStyle.CardPlaceholder),
                )
            }
        }
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(TiviMateStyle.Divider),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Box(
                Modifier
                    .width(280.dp)
                    .height(22.dp)
                    .clip(TiviMateStyle.ShapeXs)
                    .background(TiviMateStyle.CardPlaceholder),
            )
            Spacer(Modifier.height(18.dp))
            VodGridSkeleton(columns = VOD_GRID_COLS_SPLIT)
        }
    }
}

@Composable
private fun VodGridSkeleton(columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(2f / 3f)
                            .clip(TiviMateStyle.ShapeSm)
                            .background(TiviMateStyle.CardPlaceholder),
                    )
                }
            }
        }
    }
}

private val VOD_CATEGORY_COL_WIDTH = 228.dp
private const val VOD_GRID_COLS_SPLIT = 5
private const val VOD_GRID_COLS_FULL = 7
private const val VOD_PREFETCH_LOOKAHEAD = 2
private const val VOD_LOAD_MORE_THRESHOLD = 14
