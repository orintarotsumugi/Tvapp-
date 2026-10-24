package com.example.kurdishtv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainActivity
import com.example.kurdishtv.data.SettingsStorage

/**
 * Starts the app when the device boots, so a television that turns on goes
 * straight back to what was being watched.
 *
 * The viewer's "Auto-resume on start" switch gates everything here, and it is
 * read before the activity is launched rather than inside it — a broadcast
 * receiver that launched the app unconditionally would override the only control
 * the viewer has over this behaviour, and the switch would read as dead.
 *
 * The channel itself is not named here. The receiver starts [MainActivity]
 * normally and the navigation layer opens the last watched channel, so there is
 * exactly one place that decides *what* to resume and one place that decides
 * *whether* to start at all.
 *
 * The settings read is one small `SharedPreferences` file — a dozen keys — which
 * is well inside what a receiver may do on the main thread, and boot broadcasts
 * are not latency-critical. It is deliberately not wrapped in a coroutine: a
 * receiver's lifetime is not a coroutine scope, and a suspended read that outlives
 * [onReceive] is a crash waiting for the process to be killed.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isAutoStartAction(intent.action)) return
        if (!SettingsStorage(context).load().autoResume) return

        val launch = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_AUTO_RESUME
            // The receiver is not an activity context, so the framework requires
            // this flag; it also makes sure an already-running app is brought to
            // the foreground rather than a second instance being stacked.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }
}

/**
 * The broadcast actions that mean "the device just turned on".
 *
 * Pure, and internal, so the receiver's gate can be pinned by a test rather than
 * only by powering a television off and on: [AutoStartReceiver] must answer the
 * two power-on actions and *nothing* else — a receiver that reacted to any
 * broadcast it was handed would relaunch the app mid-session.
 */
internal fun isAutoStartAction(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == ACTION_QUICKBOOT_POWER_ON

/**
 * The same event under the name several TV boxes send instead of
 * [Intent.ACTION_BOOT_COMPLETED]. It is not in the SDK, hence the literal.
 */
internal const val ACTION_QUICKBOOT_POWER_ON = "android.intent.action.QUICKBOOT_POWER_ON"
