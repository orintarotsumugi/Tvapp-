package com.example.kurdishtv.data

import android.content.Context
import android.content.SharedPreferences
import com.example.kurdishtv.model.AccentColor
import com.example.kurdishtv.model.AppSettings
import com.example.kurdishtv.model.CategoryFilter
import com.example.kurdishtv.model.DeviceMode
import com.example.kurdishtv.model.ThemeMode
import com.example.kurdishtv.ui.player.ResizeMode
import com.example.kurdishtv.ui.player.VideoColorFilter

/** Persists [AppSettings] in SharedPreferences. */
class SettingsStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("kurdish_tv_settings_v1", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        return try {
            AppSettings(
                accent = enumOrDefault(prefs.getString(KEY_ACCENT, null), AccentColor.SUN_GOLD),
                themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.AMOLED),
                deviceMode = enumOrDefault(prefs.getString(KEY_DEVICE_MODE, null), DeviceMode.AUTO),
                startCategory = enumOrDefault(prefs.getString(KEY_START_CATEGORY, null), CategoryFilter.ALL),
                autoResume = prefs.getBoolean(KEY_AUTO_RESUME, true),
                autoplay = prefs.getBoolean(KEY_AUTOPLAY, true),
                showLogos = prefs.getBoolean(KEY_SHOW_LOGOS, true),
                autoHideControls = prefs.getBoolean(KEY_AUTO_HIDE, true),
                dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
                resizeMode = enumOrDefault(prefs.getString(KEY_RESIZE_MODE, null), ResizeMode.FILL),
                videoColorFilter = enumOrDefault(
                    prefs.getString(KEY_VIDEO_FILTER, null),
                    VideoColorFilter.None
                ),
                adsEnabled = prefs.getBoolean(KEY_ADS_ENABLED, false),
                adMenuUnlocked = prefs.getBoolean(KEY_AD_MENU_UNLOCKED, false)
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            prefs.edit()
                .putString(KEY_ACCENT, settings.accent.name)
                .putString(KEY_THEME, settings.themeMode.name)
                .putString(KEY_DEVICE_MODE, settings.deviceMode.name)
                .putString(KEY_START_CATEGORY, settings.startCategory.name)
                .putBoolean(KEY_AUTO_RESUME, settings.autoResume)
                .putBoolean(KEY_AUTOPLAY, settings.autoplay)
                .putBoolean(KEY_SHOW_LOGOS, settings.showLogos)
                .putBoolean(KEY_AUTO_HIDE, settings.autoHideControls)
                .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
                .putString(KEY_RESIZE_MODE, settings.resizeMode.name)
                .putString(KEY_VIDEO_FILTER, settings.videoColorFilter.name)
                .putBoolean(KEY_ADS_ENABLED, settings.adsEnabled)
                .putBoolean(KEY_AD_MENU_UNLOCKED, settings.adMenuUnlocked)
                .apply()
        } catch (_: Exception) {}
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() } ?: default

    companion object {
        private const val KEY_ACCENT = "accent"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DEVICE_MODE = "device_mode"
        private const val KEY_START_CATEGORY = "start_category"
        private const val KEY_AUTO_RESUME = "auto_resume"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_SHOW_LOGOS = "show_logos"
        private const val KEY_AUTO_HIDE = "auto_hide_controls"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_RESIZE_MODE = "player_resize_mode"
        private const val KEY_VIDEO_FILTER = "player_video_filter"
        private const val KEY_ADS_ENABLED = "ads_enabled"
        private const val KEY_AD_MENU_UNLOCKED = "ad_menu_unlocked"
    }
}
