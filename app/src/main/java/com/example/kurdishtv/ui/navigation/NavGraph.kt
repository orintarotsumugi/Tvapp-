package com.example.kurdishtv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.kurdishtv.ui.screens.MainTvScreen
import com.example.kurdishtv.ui.screens.PlayerScreen
import com.example.kurdishtv.ui.screens.SettingsScreen
import com.example.kurdishtv.viewmodel.SettingsViewModel
import com.example.kurdishtv.viewmodel.TvViewModel

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Player : Screen("player")
    object Settings : Screen("settings")
}

@Composable
fun KurdishTvNavGraph(
    viewModel: TvViewModel,
    settingsViewModel: SettingsViewModel,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    // The sleep-timer countdown is deliberately **not** part of [TvUiState]. It used
    // to be three fields on it, updated once a second, which meant a running timer
    // invalidated this whole composable — and therefore the entire `NavHost` and
    // every screen's worth of content — sixty times a minute, whether or not anyone
    // was looking at the timer. It is a readout for the player and nothing else, so
    // it gets its own flow that only the player collects.
    val sleepTimer by viewModel.sleepTimer.collectAsState()
    // The remote's number pad, likewise off the main state so that typing a digit
    // does not invalidate the grid of several hundred cards behind it.
    val channelJump by viewModel.channelJump.collectAsState()
    val needsInstallPermission by viewModel.needsInstallPermission.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val settingsLoaded by settingsViewModel.loaded.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    // The activity draws edge to edge, so screens must inset themselves out of the
    // status and navigation bars. The player is deliberately excluded: it hides the
    // system bars for fullscreen video and would be letterboxed by the padding.
    //
    // `safeDrawing` already includes the IME, which is what keeps the keyboard from
    // covering the results while a search is being typed. The manifest's
    // `windowSoftInputMode="adjustResize"` is what makes that inset *change* on older
    // releases and on TV boxes whose IMEs do not report a frame-accurate height; it
    // is belt to the insets' braces, not a substitute for them.
    val insetModifier = modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)

    // Identity of the destination currently on top of the back stack, handed to the
    // browse screen as the token it keys its focus placement on. `NavBackStackEntry.id`
    // is a UUID string — it was being fallback-initialised with `?: 0` and passed to an
    // `Int` parameter, which does not typecheck.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val backStackEntryId = backStackEntry?.id.orEmpty()

    // Auto-resume: a cold start opens the last watched channel instead of the
    // browse screen, so a television that turns on is back on what was being
    // watched before a remote is picked up.
    //
    // One shot per visit, and saved so a configuration change does not fire it
    // twice. It deliberately waits for `recentChannels` rather than for a loading
    // flag: the history is populated by the cold-start hydration, and a viewer who
    // has never watched anything — or who just cleared their history — gets the
    // normal browse screen, not an empty player.
    //
    // It runs on any cold start, not only on boot broadcasts. A television kills
    // its apps aggressively, so most "turn on the TV" sessions reach the app as a
    // process that was killed overnight, launched from the launcher rather than
    // from a boot intent — and gating on the intent would cover the rarer path
    // while missing the common one. The boot receiver's own job is only to open
    // the app in the first place. See `boot/AutoStartReceiver`.
    var autoResumeApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settingsLoaded, settings.autoResume, uiState.recentChannels) {
        if (autoResumeApplied) return@LaunchedEffect
        if (!settingsLoaded || !settings.autoResume) return@LaunchedEffect
        val lastWatched = uiState.recentChannels.firstOrNull() ?: return@LaunchedEffect
        autoResumeApplied = true
        viewModel.onChannelSelected(lastWatched)
        navController.navigate(Screen.Player.route)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Main.route,
        modifier = Modifier.fillMaxSize(),
        // No transition between destinations, explicitly.
        //
        // navigation-compose runs a NavHost through an AnimatedContent, and with no
        // transition declared the default is a fade. A fade is not a cosmetic
        // default here — it means **both screens stay composed for its whole
        // duration**, and this app has a decoder in each of two of them.
        //
        // `SidePlayerPane` holds its own ExoPlayer, and it is only released when the
        // browse screen leaves the composition. So opening a channel used to start the
        // fullscreen player while the preview pane's was still alive and still
        // attached to a surface, for the length of the fade. Two video decoders, two
        // surfaces, one of them feeding a pane that is already covered — which is a
        // hardware-decoder allocation contended for exactly when first frame is
        // wanted, on the low-end boxes this app is mostly used on.
        //
        // It also held the browse screen's D-pad focus machinery live underneath the
        // player, so focus requests fired against a screen the viewer had already
        // left.
        //
        // Cutting the transition removes the overlap rather than shortening it, and it
        // matches what the rest of the app has already decided: see
        // `ui/motion/ExpressiveMotion.kt`, where every animation was deleted on
        // purpose because "animates to the same value instantly" still costs a frame
        // callback and a recomposition. This was the one place that had been left
        // running by omission rather than by choice.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(Screen.Main.route) {
            MainTvScreen(
                uiState = uiState,
                settings = settings,
                settingsLoaded = settingsLoaded,
                onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
                onCategorySelected = { viewModel.onCategorySelected(it) },
                onChannelClick = { channel ->
                    viewModel.onChannelSelected(channel)
                    navController.navigate(Screen.Player.route)
                },
                onFavoriteToggle = { id -> viewModel.onFavoriteToggled(id) },
                onAddCustomPlaylist = { url -> viewModel.addCustomPlaylist(url) },
                onRemoveCustomPlaylist = { url -> viewModel.removeCustomPlaylist(url) },
                onRetryClick = { viewModel.loadChannels() },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                // What the grid needs to take D-pad focus back after the viewer comes
                // back from a video: a grid with nothing focused is a grid the remote
                // cannot drive.
                //
                // This is the id of whichever entry is on top, so it is the browse
                // screen's own id while the browse screen is the visible one — it does
                // not change when the viewer returns, because it is the same entry. It
                // does not have to: navigation-compose takes a destination out of the
                // composition as soon as it is covered, so returning re-runs the
                // grid's effects against the focus it saved on the way out.
                focusToken = backStackEntryId,
                // Moving the highlight in the sidebar selects the channel without
                // opening it — the preview pane follows the D-pad, and OK is what
                // actually starts something.
                onChannelFocused = { channel -> viewModel.onChannelSelected(channel) },
                channelJump = channelJump,
                onNumericKey = { digit -> viewModel.onNumericKey(digit) },
                onStepChannel = { delta ->
                    if (delta > 0) {
                        viewModel.selectNextChannel()
                    } else {
                        viewModel.selectPreviousChannel()
                    }
                },
                onNumericBackspace = { viewModel.onNumericBackspace() },
                onNumericCommit = { viewModel.commitChannelJump() },
                onNumericCancel = { viewModel.cancelChannelJump() },
                // The import snackbar clears its own message once shown, so the same
                // text can be shown again. See `onImportMessageShown`.
                onImportMessageShown = { viewModel.clearImportMessage() },
                modifier = insetModifier
            )
        }

        composable(Screen.Player.route) {
            val selectedChannel = uiState.selectedChannel
            // The player has nothing to render without a channel (the source was
            // removed or a refresh dropped it). Popping here would be a side
            // effect inside composition, which Compose can and does re-run, so it
            // is deferred to a coroutine keyed on the null-ness of the channel.
            LaunchedEffect(selectedChannel) {
                if (selectedChannel == null) navController.popBackStack()
            }
            if (selectedChannel != null) {
                PlayerScreen(
                    channel = selectedChannel,
                    settings = settings,
                    autoplay = settings.autoplay,
                    autoHideControls = settings.autoHideControls,
                    sleepTimerMinutes = sleepTimer.minutes,
                    sleepTimerFormattedText = sleepTimer.formattedText,
                    isPlaybackPaused = uiState.isPlaybackPaused,
                    isMuted = uiState.isMuted,
                    onToggleMute = { viewModel.toggleMute() },
                    onSetSleepTimer = { minutes -> viewModel.setSleepTimer(minutes) },
                    onNextChannel = { viewModel.selectNextChannel() },
                    onPreviousChannel = { viewModel.selectPreviousChannel() },
                    onFavoriteToggle = { id -> viewModel.onFavoriteToggled(id) },
                    onResizeModeChange = { mode ->
                        settingsViewModel.updatePlayerPreferences(resizeMode = mode)
                    },
                    onColorFilterChange = { filter ->
                        settingsViewModel.updatePlayerPreferences(videoColorFilter = filter)
                    },
                    onBackClick = { navController.popBackStack() },
                    // The number pad on the player.
                    //
                    // A viewer watching live television changes channel by typing its
                    // number, and until now the only way to do that was Back, then hunt
                    // through the grid. It is the same jump the browse screen already
                    // runs — same number, same list, same overlay — so it is wired to
                    // the same view model calls rather than reimplemented.
                    channelJump = channelJump,
                    onNumericKey = { digit -> viewModel.onNumericKey(digit) },
                    onNumericCommit = { viewModel.commitChannelJump() },
                    onNumericBackspace = { viewModel.onNumericBackspace() },
                    onNumericCancel = { viewModel.cancelChannelJump() }
                )
            }
        }

        composable(Screen.Settings.route) {
            // Re-read when the screen is opened: this is one of only two moments the
            // answer can have changed, the other being an install attempt. Reading it
            // on every recomposition instead meant a PackageManager binder call per
            // frame of the settings list's own animations.
            LaunchedEffect(Unit) { viewModel.refreshInstallPermission() }

            SettingsScreen(
                settings = settings,
                onUpdate = { transform -> settingsViewModel.update(transform) },
                onResetSettings = { settingsViewModel.resetToDefaults() },
                onClearFavorites = { viewModel.clearFavorites() },
                onClearRecents = { viewModel.clearRecents() },
                onClearCustomPlaylists = { viewModel.clearCustomPlaylists() },
                onClearCache = { viewModel.clearChannelCache() },
                message = uiState.actionMessage,
                onMessageShown = { viewModel.clearActionMessage() },
                onBack = { navController.popBackStack() },
                updateState = updateState,
                onCheckForUpdate = { viewModel.checkForUpdate() },
                onDownloadUpdate = { update -> viewModel.downloadUpdate(update) },
                onInstallUpdate = { update, path ->
                    viewModel.requestInstallUpdate(update, path)
                },
                onDismissUpdate = { viewModel.clearUpdateMessage() },
                // Read here rather than fetched inside the card, so the update UI can
                // warn about the permission *before* the viewer taps Install.
                needsInstallPermission = needsInstallPermission,
                modifier = insetModifier
            )
        }
    }
}
