package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.kurdishtv.ads.StartIoAdsHost
import com.example.kurdishtv.data.ChannelCacheStorage
import com.example.kurdishtv.data.CustomPlaylistStorage
import com.example.kurdishtv.data.FavoriteStorage
import com.example.kurdishtv.data.RecentStorage
import com.example.kurdishtv.data.SettingsStorage
import com.example.kurdishtv.network.NetworkClient
import com.example.kurdishtv.network.NetworkMonitor
import com.example.kurdishtv.repository.TvRepository
import com.example.kurdishtv.ui.navigation.KurdishTvNavGraph
import com.example.kurdishtv.update.UpdateChecker
import com.example.kurdishtv.viewmodel.SettingsViewModel
import com.example.kurdishtv.viewmodel.TvViewModel
import com.example.ui.theme.KurdishTvTheme

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * The action [com.example.kurdishtv.boot.AutoStartReceiver] launches the
         * activity with when the device boots. Informational by design: the resume
         * itself is driven by the viewer's "Auto-resume on start" setting rather
         * than by the shape of the intent, so every cold start — boot, launcher,
         * or a link — resumes the same way.
         */
        const val ACTION_AUTO_RESUME = "com.aistudio.kurdishtv.live.AUTO_RESUME"
    }

    private val favoriteStorage by lazy { FavoriteStorage(applicationContext) }
    private val recentStorage by lazy { RecentStorage(applicationContext) }
    private val customPlaylistStorage by lazy { CustomPlaylistStorage(applicationContext) }
    private val channelCacheStorage by lazy { ChannelCacheStorage(applicationContext) }
    private val settingsStorage by lazy { SettingsStorage(applicationContext) }
    private val okHttpClient by lazy { NetworkClient.getOkHttpClient(applicationContext) }
    private val networkMonitor by lazy { NetworkMonitor(applicationContext) }

    private val repository by lazy {
        TvRepository(
            favoriteStorage = favoriteStorage,
            recentStorage = recentStorage,
            customPlaylistStorage = customPlaylistStorage,
            channelCacheStorage = channelCacheStorage,
            okHttpClient = okHttpClient
        )
    }

    private val updateChecker by lazy {
        // The application context, so the check throttle survives the activity —
        // which is the point of it: throttling per-instance would mean every
        // launch is a first launch.
        UpdateChecker(okHttpClient, applicationContext)
    }

    private val viewModel: TvViewModel by viewModels {
        TvViewModel.Factory(
            repository = repository,
            networkMonitor = networkMonitor,
            updateChecker = updateChecker,
            appContext = applicationContext
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(settingsStorage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        enableEdgeToEdge()

        setContent {
            val settings by settingsViewModel.settings.collectAsState()
            val settingsLoaded by settingsViewModel.loaded.collectAsState()

            // Ads are driven from the viewer's own preference, and only once that
            // preference has actually been read. `settingsLoaded` is half the condition
            // because the default is "on" and the read is asynchronous: acting on the
            // default would start the ad SDK for a viewer who had switched ads off.
            // The pair is safe to read together, and that rests on the view model
            // assigning the stored preferences *before* it raises the flag — so a
            // composition can only see a raised flag beside stale settings if the flag
            // is still false, which reads as "no ads" either way.
            //
            // This used to initialise the SDK inline here and preload on the readiness
            // flag, which made the SDK reporting in read as part of this scope's
            // composition — and so recompose the whole app once on every launch.
            StartIoAdsHost(enabled = settingsLoaded && settings.adsEnabled)

            KurdishTvTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KurdishTvNavGraph(
                        viewModel = viewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }
    }
}
