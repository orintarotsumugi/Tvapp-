package com.example.kurdishtv.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.example.kurdishtv.model.AppSettings
import com.example.kurdishtv.model.CategoryFilter
import com.example.kurdishtv.model.Channel
import com.example.kurdishtv.ui.components.AdaptiveNavigationRail
import com.example.kurdishtv.ui.components.CategoryBar
import com.example.kurdishtv.ui.components.ChannelNumberOverlay
import com.example.kurdishtv.ui.components.ChannelSidebar
import com.example.kurdishtv.ui.components.EmptyChannelState
import com.example.kurdishtv.ui.components.ImportPlaylistDialog
import com.example.kurdishtv.ui.components.SidePlayerPane
import com.example.kurdishtv.ui.keys.RemoteKeyPolicy.acceptsChannelStep
import com.example.kurdishtv.ui.keys.RemoteKeyPolicy.isAutoRepeat
import com.example.kurdishtv.viewmodel.ChannelJump
import com.example.kurdishtv.viewmodel.TvUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LocalIsTv

@Composable
fun MainTvScreen(
    uiState: TvUiState,
    settings: AppSettings,
    settingsLoaded: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onCategorySelected: (CategoryFilter) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onAddCustomPlaylist: (String) -> Unit,
    onRemoveCustomPlaylist: (String) -> Unit,
    onRetryClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Identity of the navigation entry this screen is showing.
     *
     * The grid keys its focus placement on this, so a viewer who watched a channel and
     * came back lands on the card they left rather than on a grid with nothing focused.
     * See [ChannelGrid].
     */
    focusToken: String = "",
    /**
     * The channel number being typed on the remote, or null when nothing is.
     *
     * Off [uiState] on purpose — it changes on every digit, and a field on the main
     * state would invalidate the whole navigation graph and a grid of several
     * hundred cards to redraw a two-digit readout. See [ChannelJump].
     */
    channelJump: ChannelJump? = null,
    /**
     * A channel the viewer *moved onto*, as opposed to opened.
     *
     * Arrowing down the sidebar changes what is selected — the preview pane follows
     * along — without starting anything. Deliberately separate from [onChannelClick],
     * which opens the player: browsing by highlight and watching a channel are two
     * different intentions and conflating them would start a stream on every arrow
     * press.
     */
    onChannelFocused: (Channel) -> Unit = {},
    onNumericKey: (Int) -> Unit = {},
    /**
     * Steps the selection by [delta] channels — `+1` for the remote's CHANNEL UP.
     *
     * Selects, rather than opens, for the same reason a typed number selects: this
     * is how a viewer browses, and starting a stream on every press of a key that a
     * thumb can hold would be worse than doing nothing.
     */
    onStepChannel: (Int) -> Unit = {},
    onNumericBackspace: () -> Unit = {},
    onNumericCommit: () -> Unit = {},
    onNumericCancel: () -> Unit = {},
    /**
     * Called once an [TvUiState.importMessage] has been shown.
     *
     * The message has to be cleared after it is displayed, and it was never
     * cleared: `LaunchedEffect(uiState.importMessage)` is keyed on the *text*, so
     * a second identical message — importing a second bad link, or a second good
     * one — produced exactly the same string, the key did not change, the effect
     * did not re-run, and the viewer got no confirmation at all for an action
     * they had just taken. The state also stayed set forever, so nothing could
     * tell "shown" from "not yet shown".
     */
    onImportMessageShown: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    val isTv = LocalIsTv.current
    var showImportDialog by remember { mutableStateOf(false) }

    // Whether the search field holds D-pad focus right now.
    //
    // The number pad must not steal digits from a viewer who is typing a search:
    // pressing `4` into the search box has to put a 4 in the search box. Key
    // events reach this screen from the focused node *after* the text field has had
    // its chance at them, which handles most of it, but a field that has not yet
    // taken its first character would still be overwritten — so the state is tracked
    // explicitly rather than relied upon.
    var searchFieldFocused by remember { mutableStateOf(false) }
    var appliedStartCategory by rememberSaveable { mutableStateOf(false) }

    // Whether the search field may take focus on this visit yet.
    //
    // It may not for the first frame. A focused text field on Android TV opens the
    // on-screen keyboard, and the focus system's very first placement happens
    // before the grid has had a frame to claim focus for the first card — so on
    // some devices the keyboard rose over the channel grid at launch, over and
    // above whatever the system's own soft-input state did (the manifest's
    // `stateHidden` handles that half; this handles the focus half). It also ate
    // the remote's digits: the number pad is disabled while the search field is
    // focused, so a launch that landed focus here made `47` type into a search
    // box instead of jumping to channel 47.
    //
    // One frame is far below human reaction time, so nobody can lose the field:
    // from the second frame on it is a normal, reachable search box.
    var searchFocusable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        searchFocusable = true
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Which categories the chips decided to show, handed to the navigation rail so
    // both rows of navigation describe the same set. Seeded with every category so a
    // rail that composes before the first `CategoryBar` pass — which is what happens
    // on a wide screen, where the rail precedes the chrome in the tree — is never
    // briefly empty.
    //
    // Typed as `List` rather than left to inference: `CategoryFilter.entries` is an
    // `EnumEntries`, and a `List` of a different length coming back from `CategoryBar`
    // cannot be assigned to it.
    var visibleCategories by remember { mutableStateOf<List<CategoryFilter>>(CategoryFilter.entries) }

    // Apply the preferred landing category once, after stored settings have loaded.
    LaunchedEffect(settingsLoaded, settings.startCategory) {
        if (settingsLoaded && !appliedStartCategory) {
            appliedStartCategory = true
            onCategorySelected(settings.startCategory)
        }
    }

    LaunchedEffect(uiState.importMessage) {
        val msg = uiState.importMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            // Cleared *after* the snackbar has been shown rather than before, so
            // that returning to null cannot race the display. The state now means
            // "there is something new to say" again, which is what makes a repeat
            // of the same message show a second time.
            onImportMessageShown()
        }
    }

    if (showImportDialog) {
        ImportPlaylistDialog(
            customUrls = uiState.customPlaylistUrls,
            onAddUrl = { url ->
                onAddCustomPlaylist(url)
                showImportDialog = false
            },
            onRemoveUrl = onRemoveCustomPlaylist,
            onDismiss = { showImportDialog = false }
        )
    }

    // When CH+ or CH- last stepped the selection, for the held-key rate limit; null
    // until it has. Plain remembered state rather than saved: it describes *this*
    // visit, and restoring a timestamp across a trip to the player would suppress the
    // first press on the way back.
    var lastChannelStepAt by remember { mutableStateOf<Long?>(null) }

    // CH+/CH- with the two things a held key gets wrong handled here rather than in
    // the event handler: the rate limit, and throwing away a half-typed number before
    // stepping away from it.
    //
    // Returns whether the step was taken, so a dropped repeat is still consumed by
    // the caller rather than falling through to whatever was focused.
    val stepChannelFromRemote: (Int, Boolean) -> Boolean = { delta, isRepeat ->
        val now = SystemClock.uptimeMillis()
        if (isRepeat && !acceptsChannelStep(now, lastChannelStepAt)) {
            false
        } else {
            lastChannelStepAt = now
            // The number on screen names a position in the list. Stepping away from
            // it and leaving it up means the idle timer commits a channel the viewer
            // deliberately navigated past, a second and a half after they stopped.
            if (channelJump != null) onNumericCancel()
            onStepChannel(delta)
            true
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            // The remote's number pad.
            //
            // Every key on a television remote was ignored here before, which is why
            // reaching a channel on a screen of six hundred meant arcing across the
            // grid and counting. Digits build a channel number, and the number is a
            // position in the list on screen — the same list the sidebar numbers.
            //
            // Key Up is ignored for the obvious reason: it is the same physical press
            // reported again, and acting on both would enter every digit twice.
            //
            // Key Up is *not* what stops a held key, though, and treating it as if it
            // were is the bug this used to have. Auto-repeat is delivered as a stream
            // of **extra Key Down events** with a growing `repeatCount`, so holding
            // `4` typed `4444` and landed the viewer on a channel they had never
            // asked for — the four-digit cap in the view model quietly truncated the
            // damage instead of preventing it. The two kinds of key want opposite
            // treatment, and `RemoteKeyPolicy` is where that is written down: a key
            // that builds a value ignores repeats, a key that moves a selection
            // accepts them at a rate a person can follow.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // A viewer typing a search gets their digits, and a viewer typing a
                // playlist URL gets them too. The dialog is its own window on Android
                // and takes its own keys, but the number pad is answered here and
                // "the dialog is open" is a far more honest guard than relying on
                // window boundaries that differ by platform version.
                if (searchFieldFocused || showImportDialog) return@onKeyEvent false
                // `repeatCount` is the platform's own auto-repeat counter: 0 on the
                // initial press, growing for as long as the key is held.
                val repeat = event.nativeKeyEvent.repeatCount
                when (event.key) {
                    // `NumPad*` is a separate set of key codes on Android, and
                    // several remotes send the keypad codes rather than the main
                    // ones, so both have to be accepted.
                    //
                    // A digit builds a number, so a repeat would build a number
                    // nobody asked for. The check is on the event rather than on the
                    // key, so the keypad codes are covered by the same one.
                    Key.Zero, Key.NumPad0 -> { if (!isAutoRepeat(repeat)) onNumericKey(0); true }
                    Key.One, Key.NumPad1 -> { if (!isAutoRepeat(repeat)) onNumericKey(1); true }
                    Key.Two, Key.NumPad2 -> { if (!isAutoRepeat(repeat)) onNumericKey(2); true }
                    Key.Three, Key.NumPad3 -> { if (!isAutoRepeat(repeat)) onNumericKey(3); true }
                    Key.Four, Key.NumPad4 -> { if (!isAutoRepeat(repeat)) onNumericKey(4); true }
                    Key.Five, Key.NumPad5 -> { if (!isAutoRepeat(repeat)) onNumericKey(5); true }
                    Key.Six, Key.NumPad6 -> { if (!isAutoRepeat(repeat)) onNumericKey(6); true }
                    Key.Seven, Key.NumPad7 -> { if (!isAutoRepeat(repeat)) onNumericKey(7); true }
                    Key.Eight, Key.NumPad8 -> { if (!isAutoRepeat(repeat)) onNumericKey(8); true }
                    Key.Nine, Key.NumPad9 -> { if (!isAutoRepeat(repeat)) onNumericKey(9); true }
                    // The dedicated channel keys, which most television remotes
                    // carry and every one of which was ignored. They step the
                    // selection through the list on screen.
                    //
                    // This is the one key here where holding it down is the point: a
                    // viewer with six hundred channels and no pointer scrolls by
                    // holding CH+. So repeats are allowed — at a rate a person can
                    // follow, and the first press is always taken.
                    Key.ChannelUp -> stepChannelFromRemote(1, isAutoRepeat(repeat))
                    Key.ChannelDown -> stepChannelFromRemote(-1, isAutoRepeat(repeat))
                    // Commit early rather than waiting out the idle timer. Only while
                    // a number is up, so OK still reaches the card it is on — and only
                    // on the first press, so leaning on OK does not commit a number
                    // that is still being typed.
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                        if (channelJump != null) {
                            if (!isAutoRepeat(repeat)) onNumericCommit()
                            true
                        } else false
                    Key.Backspace ->
                        if (channelJump != null) {
                            // One press, one digit: a held backspace used to clear
                            // the whole number at once.
                            if (!isAutoRepeat(repeat)) onNumericBackspace()
                            true
                        } else false
                    // Back cancels the number, and only the number. With nothing
                    // being typed it is not consumed at all, so the system back
                    // gesture still leaves the app as it always did.
                    Key.Back, Key.Escape ->
                        if (channelJump != null) {
                            if (!isAutoRepeat(repeat)) onNumericCancel()
                            true
                        } else false
                    else -> false
                }
            }
    ) {
        val isShortLandscape = maxHeight < 480.dp && !isTv
        val isExpandedTvOrLandscape = isTv || (maxWidth >= 900.dp && !isShortLandscape)
        val isMediumScreen = maxWidth >= 600.dp
        // Phones in portrait are the tightest case. Shrinking the grid's minimum cell
        // there keeps two comfortable columns instead of squeezing one very wide one,
        // and keeps the header actions from colliding with the title on small screens.
        val isCompactWidth = maxWidth < 600.dp
        // The channel sidebar costs 260dp of fixed width that the grid does not get
        // back. On a television there is always room; on a tablet the grid can
        // afford it only once the window is genuinely wide, or the channel names
        // start truncating — which defeats the point of a list.
        val showChannelSidebar = isTv || maxWidth >= 1100.dp
        val gridMinCellSize = when {
            // Ten-foot UI. Every other branch here is sized for a screen held at
            // arm's length: a 132-172dp card is a comfortable cell on a tablet,
            // but across a television it is a thumbnail, and the channel name
            // inside it cannot be read from a sofa.
            //
            // This has to be tested *before* the width rules, because a TV box
            // reports a compact width by phone standards and was being handed
            // the two-column phone grid — the most cramped layout in the app, on
            // the one device with the most pixels to spend.
            isTv -> 220.dp
            isShortLandscape -> 168.dp
            isCompactWidth -> 132.dp
            maxWidth >= 900.dp -> 172.dp
            else -> 150.dp
        }
        val filtered = uiState.filteredChannels
        val isBrowsingHome =
            uiState.searchQuery.isBlank() && uiState.selectedCategory == CategoryFilter.ALL

        when {
            // Short landscape (phone on its side) gets the compact single-pane
            // layout even though it is wide, because vertical space is the scarce
            // resource there.
            isShortLandscape -> {
                LandscapeCompactLayout(
                    uiState = uiState,
                    settings = settings,
                    filtered = filtered,
                    isBrowsingHome = isBrowsingHome,
                    gridMinCellSize = gridMinCellSize,
                    // Shared with the caller: the LaunchedEffect that surfaces
                    // importMessage targets *this* host state, so a locally created
                    // one here would mean nothing is ever shown.
                    snackbarHostState = snackbarHostState,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onCategorySelected = onCategorySelected,
                    onChannelClick = onChannelClick,
                    onFavoriteToggle = onFavoriteToggle,
                    onRetryClick = onRetryClick,
                    onOpenSettings = onOpenSettings,
                    onOpenImport = { showImportDialog = true },
                    onSearchFocusChanged = { searchFieldFocused = it },
                    searchFocusable = searchFocusable,
                    focusChannelId = channelJump?.target?.id,
                    focusToken = focusToken
                )
            }

            isExpandedTvOrLandscape -> {
                // This branch has no Scaffold, so the snackbar host is overlaid
                // directly. Without it the import/sync messages were silently
                // dropped on exactly the layout with the most room to show them.
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        AdaptiveNavigationRail(
                            selectedCategory = uiState.selectedCategory,
                            onCategorySelected = onCategorySelected,
                            visibleCategories = visibleCategories,
                            onOpenImport = { showImportDialog = true },
                            onRefresh = onRetryClick,
                            onOpenSettings = onOpenSettings
                        )

                        // The channel list, beside the grid rather than instead of it.
                        //
                        // Wide enough to be worth the horizontal space, and no wider:
                        // the rail and the sidebar are both fixed, so on a 1000dp
                        // tablet a third column would leave the grid too narrow to
                        // read a channel name in. A television always has the room.
                        if (showChannelSidebar) {
                            ChannelSidebar(
                                channels = filtered,
                                selectedChannelId = uiState.selectedChannel?.id,
                                showLogos = settings.showLogos,
                                onChannelClick = onChannelClick,
                                onChannelFocused = onChannelFocused,
                                onFavoriteToggle = onFavoriteToggle
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1.15f)
                                .fillMaxHeight()
                        ) {
                            TvTopChrome(
                                uiState = uiState,
                                filteredCount = filtered.size,
                                onSearchQueryChanged = onSearchQueryChanged,
                                onCategorySelected = onCategorySelected,
                                onRetryClick = onRetryClick,
                                onOpenSettings = onOpenSettings,
                                onOpenImport = { showImportDialog = true },
                                onHomeClick = {
                                    onSearchQueryChanged("")
                                    onCategorySelected(CategoryFilter.ALL)
                                },
                                onVisibleCategories = { visibleCategories = it },
                                onSearchFocusChanged = { searchFieldFocused = it },
                                searchFocusable = searchFocusable
                            )

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
                                    focusToken = focusToken,
                                    focusChannelId = channelJump?.target?.id
                                )
                            }
                        }

                        SidePlayerPane(
                            channel = uiState.selectedChannel ?: filtered.firstOrNull(),
                            onFullscreenClick = onChannelClick,
                            onFavoriteToggle = onFavoriteToggle,
                            // Same picture shape the fullscreen player was set to.
                            resizeMode = settings.resizeMode,
                            modifier = Modifier
                                .weight(0.85f)
                                .fillMaxHeight()
                        )
                    }
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }
            }

            isMediumScreen -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    AdaptiveNavigationRail(
                        selectedCategory = uiState.selectedCategory,
                        onCategorySelected = onCategorySelected,
                        visibleCategories = visibleCategories,
                        onOpenImport = { showImportDialog = true },
                        onRefresh = onRetryClick,
                        onOpenSettings = onOpenSettings
                    )

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
                                onOpenImport = { showImportDialog = true },
                                onHomeClick = {
                                    onSearchQueryChanged("")
                                    onCategorySelected(CategoryFilter.ALL)
                                },
                                onVisibleCategories = { visibleCategories = it },
                                onSearchFocusChanged = { searchFieldFocused = it },
                                searchFocusable = searchFocusable
                            )
                        },
                        modifier = Modifier.weight(1f)
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
                                    minCellSize = 160.dp,
                                    onChannelClick = onChannelClick,
                                    onFavoriteToggle = onFavoriteToggle,
                                    focusToken = focusToken,
                                    focusChannelId = channelJump?.target?.id
                                )
                            }
                        }
                    }
                }
            }

            else -> {
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
                            onOpenImport = { showImportDialog = true },
                            onHomeClick = {
                                onSearchQueryChanged("")
                                onCategorySelected(CategoryFilter.ALL)
                            },
                            onVisibleCategories = { visibleCategories = it },
                            onSearchFocusChanged = { searchFieldFocused = it },
                            searchFocusable = searchFocusable
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
                                focusToken = focusToken,
                                focusChannelId = channelJump?.target?.id
                            )
                        }
                    }
                }
            }
        }

        // The number pad's readout, above whichever layout is showing.
        //
        // A sibling of the `when` rather than part of any branch, because the digits
        // have to appear the same way in all four layouts — a viewer who has learned
        // where it is on a television must not have to hunt for it on a tablet.
        channelJump?.let { jump ->
            ChannelNumberOverlay(
                jump = jump,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
