package com.example.kurdishtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import com.example.BuildConfig
import com.example.kurdishtv.ads.FullPageAdState
import com.example.kurdishtv.ads.StartIoAds
import com.example.kurdishtv.ads.StartIoBanner
import com.example.kurdishtv.ads.StartIoFullPage
import com.example.kurdishtv.ads.findActivity
import com.example.kurdishtv.model.AppSettings
import com.example.kurdishtv.model.CategoryFilter
import com.example.kurdishtv.model.DeviceMode
import com.example.kurdishtv.model.ThemeMode
import com.example.kurdishtv.network.NetworkClient
import com.example.kurdishtv.ui.components.KurdishTvIcons
import com.example.kurdishtv.ui.components.UpdateCard
import com.example.kurdishtv.update.AppUpdate
import com.example.kurdishtv.update.UpdateState
import com.example.kurdishtv.ui.player.ResizeMode
import com.example.kurdishtv.ui.player.VideoColorFilter
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LocalIsTv
import com.example.ui.theme.isTvMode

/**
 * One line explaining what the device-mode control is currently doing.
 *
 * Pure so it can be unit-tested without a `Configuration`, and kept next to the
 * control it describes rather than inline in the composable.
 */
internal fun deviceModeHint(mode: DeviceMode, detectedIsTv: Boolean): String = when (mode) {
    DeviceMode.AUTO ->
        if (detectedIsTv) {
            "Auto — this device reports itself as a TV, so the ten-foot layout is in use."
        } else {
            "Auto — this device reports itself as a phone or tablet, so the handheld layout is in use."
        }
    DeviceMode.TV ->
        "TV — the room-sized layout is forced, whatever the device reports."
    DeviceMode.MOBILE ->
        "Phone — the compact touch layout is forced, whatever the device reports."
}

/**
 * The line under the "Watch an ad" button.
 *
 * Pure so it can be unit-tested, and kept next to the button it describes rather than
 * inline in the composable.
 *
 * The two ways of not being able to show an ad read differently on purpose. A request
 * that is still out resolves itself in a second; one that came back empty does not
 * until the viewer asks again. One "no ad" line for both would make the button look
 * broken for the whole of the second or two it is merely working.
 */
internal fun adActionSubtitle(sdkReady: Boolean, adState: FullPageAdState): String = when {
    !sdkReady -> "Getting the ad service ready…"
    adState == FullPageAdState.Ready -> "Plays a short video ad, and keeps the app free"
    adState == FullPageAdState.Loading -> "Preparing an ad…"
    else -> "No ad right now — tap to try again"
}

/**
 * The line under the ads switch.
 *
 * Pure so it can be unit-tested, and kept next to the switch it describes.
 *
 * It says what the switch actually does rather than repeating its label. "Show ads" off
 * is not a preference about which ads — it stops the app requesting any, and stops the
 * ad service being started at all, which is more than a viewer would guess from a
 * toggle going grey.
 */
internal fun adsConsentHint(adsEnabled: Boolean): String = if (adsEnabled) {
    "Ads are what keep the app free. Watching one is always your choice."
} else {
    "Off — no ads are requested, and the ad service is not started."
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onResetSettings: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearRecents: () -> Unit,
    onClearCustomPlaylists: () -> Unit,
    onClearCache: () -> Unit,
    message: String?,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
    updateState: UpdateState = UpdateState.Idle,
    onCheckForUpdate: () -> Unit = {},
    onDownloadUpdate: (AppUpdate) -> Unit = {},
    onInstallUpdate: (AppUpdate, String) -> Unit = { _, _ -> },
    onDismissUpdate: () -> Unit = {},
    needsInstallPermission: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val isTv = LocalIsTv.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val supportsDynamicColor = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    // What the *device* says, as opposed to what the preference resolved to. Shown
    // beside the control so "Auto" is not a mystery, and read from the configuration
    // here rather than passed down: it is the one place the raw answer is wanted.
    val detectedIsTv = LocalConfiguration.current.isTvMode()
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var showTermsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Ten-foot treatment.
            //
            // On a television the window is roughly 1920dp wide, and a `fillMaxWidth`
            // card behind a 20dp phone gutter stretched a settings row across the
            // whole screen: label hard against the left edge, switch hard against
            // the right, a metre of empty card between them. Each card is capped to
            // a column that is comfortable to read from a sofa and
            // `horizontalAlignment` centres it. Phones and tablets are untouched.
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = if (isTv) 40.dp else 20.dp,
                end = if (isTv) 40.dp else 20.dp,
                top = 12.dp,
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") {
                SettingsHeader(onBack = onBack)
            }

            // ── Playback ──────────────────────────────────────────────────────
            //
            // First, not third. Of everything on this screen, playback is the only
            // thing someone opens Settings to change *because it annoyed them while
            // watching* — autoplay fighting them, controls vanishing mid-tap. It is
            // the section most likely to be needed and the least likely to be found
            // if it is buried under colour pickers.
            item(key = "playback") {
                SettingsSection(
                    title = "Playback",
                    subtitle = "How streams and controls behave",
                    iconRes = KurdishTvIcons.PlayRes
                ) {
                    SettingsSwitchRow(
                        iconRes = KurdishTvIcons.PlayRes,
                        title = "Autoplay",
                        subtitle = "Start a channel as soon as it opens",
                        checked = settings.autoplay,
                        onCheckedChange = { value -> onUpdate { it.copy(autoplay = value) } }
                    )
                    SettingsSwitchRow(
                        iconRes = KurdishTvIcons.Bedtime,
                        title = "Auto-hide controls",
                        subtitle = "Hide player controls while watching",
                        checked = settings.autoHideControls,
                        onCheckedChange = { value -> onUpdate { it.copy(autoHideControls = value) } }
                    )
                    // Resize mode and picture correction were already persisted and
                    // already applied — the player overlay writes them — but neither
                    // appeared anywhere on this screen, so a viewer who had not found
                    // the player's controls had no idea the choice existed, let alone
                    // how to undo it.
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionLabel("Video fit")
                    SegmentedOptions(
                        options = ResizeMode.entries,
                        selected = settings.resizeMode,
                        labelOf = { it.label },
                        onSelect = { mode -> onUpdate { it.copy(resizeMode = mode) } }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    SectionLabel("Picture correction")
                    ChipRow(
                        options = VideoColorFilter.entries,
                        selected = settings.videoColorFilter,
                        labelOf = { it.label },
                        onSelect = { filter -> onUpdate { it.copy(videoColorFilter = filter) } }
                    )

                }
            }

            // ── Browsing ──────────────────────────────────────────────────────
            item(key = "browsing") {
                SettingsSection(
                    title = "Browsing",
                    subtitle = "What you see first",
                    iconRes = KurdishTvIcons.LiveTv
                ) {
                    SectionLabel("Default category")
                    ChipRow(
                        options = CategoryFilter.entries,
                        selected = settings.startCategory,
                        labelOf = { it.displayName },
                        onSelect = { category -> onUpdate { it.copy(startCategory = category) } }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSwitchRow(
                        iconRes = KurdishTvIcons.Tv,
                        title = "Channel logos",
                        subtitle = "Load logos over the network",
                        checked = settings.showLogos,
                        onCheckedChange = { value -> onUpdate { it.copy(showLogos = value) } }
                    )

                    SettingsSwitchRow(
                        iconRes = KurdishTvIcons.PlayRes,
                        title = "Auto-resume on start",
                        subtitle = "Turn on the TV → the app opens by itself " +
                            "and plays your last watched channel",
                        checked = settings.autoResume,
                        onCheckedChange = { value -> onUpdate { it.copy(autoResume = value) } },
                        showDivider = false
                    )
                }
            }

            // ── Interface ──────────────────────────────────────────────────────
            //
            // Its own section rather than a row at the bottom of Appearance, because
            // it is the one setting on this screen that changes the *shape* of the
            // rest of the app rather than its colours. It also has to sit above
            // Motion, because a viewer who arrives here because the cards are
            // unreadable on their box should meet that reason before they are asked
            // to decide whether the animation is worth the frame rate.
            item(key = "interface") {
                SettingsSection(
                    title = "Interface",
                    subtitle = "Which layout to use",
                    iconRes = KurdishTvIcons.Tv
                ) {
                    SectionLabel("Device mode")
                    SegmentedOptions(
                        options = DeviceMode.entries,
                        selected = settings.deviceMode,
                        labelOf = { it.displayName },
                        onSelect = { mode -> onUpdate { it.copy(deviceMode = mode) } }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    // Says what "Auto" actually resolved to. Without this the row is
                    // a three-way switch with no way to find out which one the
                    // device is currently on — and on a box that misreports its
                    // `uiMode`, "Auto" producing the wrong layout is exactly the
                    // thing the viewer needs to see explained before they can tell
                    // whether the override has fixed it.
                    Text(
                        text = deviceModeHint(settings.deviceMode, detectedIsTv),
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            // ── Appearance ────────────────────────────────────────────────────
            item(key = "appearance") {
                SettingsSection(
                    title = "Appearance",
                    subtitle = "Colors and theme",
                    iconRes = KurdishTvIcons.Tune
                ) {
                    SectionLabel("Accent color")
                    AccentSwatchRow(
                        selected = settings.accent,
                        // With Material You on, the swatch that is visibly in use is
                        // the wallpaper's, not the stored one. Marking the stored
                        // accent as selected made the screen claim a colour the app
                        // was not drawing.
                        overridden = settings.dynamicColor && supportsDynamicColor,
                        // Tapping a colour is a request for *that* colour, so it also
                        // switches Material You off. Leaving it on meant the wallpaper
                        // kept winning and the tap changed nothing on screen, which
                        // reads as a dead control rather than as a conflict.
                        onSelect = { accent ->
                            onUpdate { it.copy(accent = accent, dynamicColor = false) }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSwitchRow(
                        iconRes = KurdishTvIcons.Palette,
                        title = "Material You colors",
                        subtitle = if (supportsDynamicColor) {
                            "Match your wallpaper (Android 12+)"
                        } else {
                            "Requires Android 12 or newer"
                        },
                        checked = settings.dynamicColor && supportsDynamicColor,
                        enabled = supportsDynamicColor,
                        onCheckedChange = { value -> onUpdate { it.copy(dynamicColor = value) } }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    SectionLabel("Theme")
                    SegmentedOptions(
                        options = ThemeMode.entries,
                        selected = settings.themeMode,
                        labelOf = { it.displayName },
                        onSelect = { mode -> onUpdate { it.copy(themeMode = mode) } }
                    )
                }
            }

            // ── Update ────────────────────────────────────────────────────────
            item(key = "update") {
                UpdateCard(
                    modifier = Modifier.readableColumn(isTv),
                    state = updateState,
                    onCheck = onCheckForUpdate,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                    onDismiss = onDismissUpdate,
                    needsInstallPermission = needsInstallPermission
                )
            }

            // ── Data ──────────────────────────────────────────────────────────
            //
            // Below Update, above About. Every row in this section destroys
            // something, and one of them resets every other preference on the screen.
            // Sat in the middle of the list it read as just another group of
            // switches, with "Reset all settings" two rows above the app version.
            // Buried at the end, it is where destructive actions belong.
            item(key = "data") {
                SettingsSection(
                    title = "Data & storage",
                    subtitle = "Remove saved information",
                    iconRes = KurdishTvIcons.Delete
                ) {
                    SettingsActionRow(
                        iconRes = KurdishTvIcons.FavoriteFilledRes,
                        title = "Clear favorites",
                        subtitle = "Unmark every saved channel",
                        onClick = { pendingAction = PendingAction.CLEAR_FAVORITES }
                    )
                    SettingsActionRow(
                        iconRes = KurdishTvIcons.History,
                        title = "Clear watch history",
                        subtitle = "Reset the recently watched row",
                        onClick = { pendingAction = PendingAction.CLEAR_RECENTS }
                    )
                    SettingsActionRow(
                        iconRes = KurdishTvIcons.AddLink,
                        title = "Remove custom playlists",
                        subtitle = "Delete every imported IPTV link",
                        onClick = { pendingAction = PendingAction.CLEAR_PLAYLISTS }
                    )
                    HoldToUnlockActionRow(
                        iconRes = KurdishTvIcons.Delete,
                        title = "Clear channel cache",
                        subtitle = if (settings.adMenuUnlocked) {
                            "Re-download the channel list next launch"
                        } else {
                            "Re-download channel list • Hold 10s to unlock Ads menu"
                        },
                        onClick = { pendingAction = PendingAction.CLEAR_CACHE },
                        onHoldCompleted = {
                            onUpdate { it.copy(adMenuUnlocked = true) }
                            showTermsDialog = true
                        }
                    )
                    SettingsActionRow(
                        iconRes = KurdishTvIcons.Refresh,
                        title = "Reset all settings",
                        subtitle = "Restore every preference to default",
                        danger = true,
                        showDivider = false,
                        onClick = { pendingAction = PendingAction.RESET_SETTINGS }
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────────────
            item(key = "about") {
                SettingsSection(
                    title = "About",
                    subtitle = "Kurdish TV Live",
                    iconRes = KurdishTvIcons.Globe
                ) {
                    AboutRow("Version", BuildConfig.VERSION_NAME)
                    AboutRow("Build", BuildConfig.VERSION_CODE.toString())
                    AboutRow("Start category", settings.startCategory.displayName)
                    AboutRow("Sources", "Curated list, IPTV-org, community")

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Play Protect may warn that this app is from an unknown " +
                            "developer. That is expected for a build that is not on the " +
                            "Play Store: choose “More details” → “Install anyway”, or turn " +
                            "off Play Protect in Play Store → Settings.",
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If an update fails with “package conflicts with an " +
                            "existing package”, an older copy signed with a different " +
                            "key is still installed. Uninstall this app once, then install " +
                            "the new version.",
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // ── Ads ───────────────────────────────────────────────────────────
            //
            // Only displayed once unlocked by holding the cache reset row for 10s.
            if (settings.adMenuUnlocked) {
                item(key = "ads") {
                    // `LocalContext` is the activity here, but only an unwrap can prove it.
                    val activity = remember(context) { context.findActivity() }
                    SettingsSection(
                        title = "Ads",
                        subtitle = if (settings.adsEnabled) {
                            "How the app pays for itself"
                        } else {
                            "Off"
                        },
                        iconRes = KurdishTvIcons.FavoriteOutline
                    ) {
                        SettingsSwitchRow(
                            iconRes = KurdishTvIcons.Globe,
                            title = "Show ads",
                            subtitle = adsConsentHint(settings.adsEnabled),
                            checked = settings.adsEnabled,
                            showDivider = settings.adsEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !settings.adsEnabled) {
                                    showTermsDialog = true
                                } else {
                                    onUpdate { it.copy(adsEnabled = enabled) }
                                }
                            }
                        )

                        // Composed only while ads are on, so a viewer who has switched them
                        // off pays nothing for them — no composable, no request, no timer.
                        if (settings.adsEnabled) {
                            SettingsActionRow(
                                iconRes = KurdishTvIcons.PlayRes,
                                title = "Watch an ad",
                                subtitle = adActionSubtitle(
                                    StartIoAds.isReady,
                                    StartIoFullPage.state
                                ),
                                onClick = {
                                    val host = activity
                                    if (host == null) {
                                        NetworkClient.logDebug(
                                            "No activity to show a full-page ad in"
                                        )
                                    } else {
                                        StartIoFullPage.show(host)
                                    }
                                }
                            )
                            StartIoBanner()
                        }
                    }
                }
            }
        }
    }

    if (showTermsDialog) {
        AdConsentTermsDialog(
            onAcceptAndEnable = {
                onUpdate { it.copy(adsEnabled = true, adMenuUnlocked = true) }
                showTermsDialog = false
            },
            onDecline = {
                onUpdate { it.copy(adsEnabled = false, adMenuUnlocked = true) }
                showTermsDialog = false
            },
            onDismiss = { showTermsDialog = false }
        )
    }

    pendingAction?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = {
                when (action) {
                    PendingAction.CLEAR_FAVORITES -> onClearFavorites()
                    PendingAction.CLEAR_RECENTS -> onClearRecents()
                    PendingAction.CLEAR_PLAYLISTS -> onClearCustomPlaylists()
                    PendingAction.CLEAR_CACHE -> onClearCache()
                    PendingAction.RESET_SETTINGS -> onResetSettings()
                }
                pendingAction = null
            },
            onDismiss = { pendingAction = null }
        )
    }
}
