package com.example.kurdishtv.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.example.kurdishtv.model.AppSettings
import com.example.kurdishtv.model.CategoryFilter
import com.example.kurdishtv.model.Channel
import com.example.kurdishtv.ui.components.CategoryBar
import com.example.kurdishtv.ui.components.EmptyChannelState
import com.example.kurdishtv.ui.components.OfflineBanner
import com.example.kurdishtv.ui.components.SearchBarM3
import com.example.kurdishtv.ui.components.TopHeaderBar
import com.example.kurdishtv.viewmodel.TvUiState
import com.example.ui.theme.LocalAppColors

/**
 * The pinned chrome above the channel grid: offline state, brand row, search, and
 * the category chips.
 *
 * This is the app's only piece of permanent top-level navigation, shared consistently
 * across all layout configurations (phones, tablets, and TVs).
 */
@Composable
internal fun TvTopChrome(
    uiState: TvUiState,
    filteredCount: Int,
    onSearchQueryChanged: (String) -> Unit,
    onCategorySelected: (CategoryFilter) -> Unit,
    onRetryClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onHomeClick: () -> Unit = onRetryClick,
    onVisibleCategories: (List<CategoryFilter>) -> Unit = {},
    onSearchFocusChanged: (Boolean) -> Unit = {},
    /** See [SearchBarM3.focusEnabled] — held false for the first frame of a visit. */
    searchFocusable: Boolean = true,
    compact: Boolean = false
) {
    Column {
        OfflineBanner(isOffline = uiState.isOffline, onRetry = onRetryClick)
        TopHeaderBar(
            channelCount = filteredCount,
            isLoading = uiState.isLoading,
            onOpenImport = onOpenImport,
            onRefresh = onRetryClick,
            onOpenSettings = onOpenSettings,
            onHomeClick = onHomeClick,
            compact = compact
        )
        SearchBarM3(
            query = uiState.searchQuery,
            onQueryChange = onSearchQueryChanged,
            onFocusChanged = onSearchFocusChanged,
            focusEnabled = searchFocusable
        )
        CategoryBar(
            selectedCategory = uiState.selectedCategory,
            onCategorySelected = onCategorySelected,
            channels = uiState.channels,
            onVisibleCategories = onVisibleCategories,
            compact = compact
        )
    }
}

/**
 * Single-pane layout for short landscape windows (a phone held sideways).
 *
 * The header, search and category chips share one row so the grid gets the full
 * height of the screen. Losing a whole row to chrome matters here because vertical
 * space is exactly what is scarce in landscape.
 */
@Composable
internal fun LandscapeCompactLayout(
    uiState: TvUiState,
    settings: AppSettings,
    filtered: List<Channel>,
    isBrowsingHome: Boolean,
    gridMinCellSize: Dp,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChanged: (String) -> Unit,
    onCategorySelected: (CategoryFilter) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onRetryClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    searchFocusable: Boolean,
    focusChannelId: String?,
    focusToken: String
) {
    val colors = LocalAppColors.current

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TvTopChrome(
                uiState = uiState,
                filteredCount = filtered.size,
                onSearchQueryChanged = onSearchQueryChanged,
                onCategorySelected = onCategorySelected,
                onRetryClick = onRetryClick,
                onOpenSettings = onOpenSettings,
                onOpenImport = onOpenImport,
                onHomeClick = {
                    onSearchQueryChanged("")
                    onCategorySelected(CategoryFilter.ALL)
                },
                onSearchFocusChanged = onSearchFocusChanged,
                searchFocusable = searchFocusable,
                compact = true
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (filtered.isEmpty()) {
                EmptyChannelState(
                    searchQuery = uiState.searchQuery,
                    onReset = {
                        onSearchQueryChanged("")
                        onCategorySelected(CategoryFilter.ALL)
                    }
                )
            } else {
                ChannelGrid(
                    filtered = filtered,
                    uiState = uiState,
                    showLogos = settings.showLogos,
                    isHome = isBrowsingHome,
                    minCellSize = gridMinCellSize,
                    onChannelClick = onChannelClick,
                    onFavoriteToggle = onFavoriteToggle,
                    focusChannelId = focusChannelId,
                    focusToken = focusToken,
                    compact = true
                )
            }
        }
    }
}
