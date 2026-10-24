package com.example.kurdishtv.model

import com.example.kurdishtv.ui.player.ResizeMode
import com.example.kurdishtv.ui.player.VideoColorFilter

/** Accent palettes offered in Settings. Mapped to real colors in the UI theme layer. */
enum class AccentColor(val displayName: String) {
    SUN_GOLD("Sun Gold"),
    EMBER("Ember"),
    ROSE("Rose"),
    JADE("Jade"),
    AZURE("Azure")
}

enum class ThemeMode(val displayName: String) {
    DARK("Dim"),
    AMOLED("AMOLED Black")
}

/**
 * Which of the app's two layout families to present.
 *
 * The app draws a genuinely different interface for a television and for a handheld —
 * a ten-foot grid with large cards, a navigation rail, a channel sidebar and D-pad
 * focus rings, against a compact touch-first layout — and every layout decision reads
 * `LocalIsTv` rather than the window size, because a TV box and a tablet report
 * comparable sizes.
 *
 * [AUTO] keeps that single signal: the device's declared `uiMode`, which is right
 * almost always. The two overrides exist because it is not right always, and the two
 * cases that come up are not exotic:
 *
 *  - Plenty of Android TV boxes, projectors and HDMI sticks declare a phone or tablet
 *    `uiMode`. The app then draws the compact touch layout on the one device with the
 *    most pixels to spend, and every card is a thumbnail from a sofa.
 *  - Conversely, a tablet or a phone on a stand with a Bluetooth keyboard is being
 *    driven with a D-pad from across the room, and wants the room-sized targets.
 *
 * So this is a user-facing override of a heuristic, not a second code path: nothing
 * downstream branches on anything but `LocalIsTv`, which this setting resolves.
 */
enum class DeviceMode(val displayName: String) {
    /** Follow the device's declared `uiMode`. The default, and right almost always. */
    AUTO("Auto"),

    /** Force the ten-foot layout: large cells, D-pad focus, permanent sidebar. */
    TV("TV"),

    /** Force the handheld layout: dense grid, touch-first, no D-pad furniture. */
    MOBILE("Phone")
}

/**
 * User preferences. Immutable so the UI can safely diff it, and persisted by
 * [com.example.kurdishtv.data.SettingsStorage].
 */
data class AppSettings(
    val accent: AccentColor = AccentColor.SUN_GOLD,
    val themeMode: ThemeMode = ThemeMode.AMOLED,
    /**
     * Which layout family to present. [DeviceMode.AUTO] follows the device; the other
     * two override it. Resolved into `LocalIsTv` by the theme layer, which is the only
     * place the answer is turned into a boolean — see
     * [com.example.ui.theme.resolveIsTv].
     */
    val deviceMode: DeviceMode = DeviceMode.AUTO,
    val startCategory: CategoryFilter = CategoryFilter.ALL,
    /**
     * Whether the app starts itself when the device boots, and whether a cold
     * start opens the last watched channel instead of the browse screen.
     *
     * One switch for both, because they are one wish: a television that turns on
     * should be back on what was being watched, with no remote in the hand yet.
     * The boot half is [com.example.kurdishtv.boot.AutoStartReceiver]; the resume
     * half is the navigation layer, which also covers app launches that did not
     * come from a boot broadcast — a TV box kills its apps aggressively, so most
     * "turn on the TV" sessions are actually cold starts of a process that was
     * killed overnight.
     */
    val autoResume: Boolean = true,
    val autoplay: Boolean = true,
    val showLogos: Boolean = true,
    val autoHideControls: Boolean = true,
    /**
     * Material You: derive accent colors from the device wallpaper palette.
     * Requires Android 12+ (API 31); ignored on older releases.
     */
    val dynamicColor: Boolean = false,
    /**
     * How the video fills the screen: the FIT/FILL/ZOOM toggle in the player.
     *
     * Persisted because the choice is about the viewer's screen, not about a
     * stream: someone who wants FILL in portrait wants it tomorrow as well, and
     * an app that reset it on every cold start was asking them to set it again
     * each time. The side player pane draws the same picture, so it reads this
     * too rather than hard-coding its own idea of correctness.
     */
    val resizeMode: ResizeMode = ResizeMode.FILL,
    /** The video colour correction last chosen in the player, for the same reason. */
    val videoColorFilter: VideoColorFilter = VideoColorFilter.None,
    /**
     * Whether the viewer has explicitly agreed to and enabled ads.
     * Off by default.
     */
    val adsEnabled: Boolean = false,
    /**
     * Whether the Ads section in Settings has been unlocked by holding the
     * full cache reset button for 10 seconds.
     */
    val adMenuUnlocked: Boolean = false
) {
    val isAmoled: Boolean get() = themeMode == ThemeMode.AMOLED
}
