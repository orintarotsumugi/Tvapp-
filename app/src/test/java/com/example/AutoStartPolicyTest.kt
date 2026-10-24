package com.example

import android.content.Intent
import com.example.kurdishtv.boot.isAutoStartAction
import com.example.kurdishtv.ui.screens.isTapRelease
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two gates that decide, respectively, whether the app starts itself when a
 * television boots and whether a press on the cache-reset row counts as a tap.
 *
 * Both are small predicates with outsized, invisible consequences, which is the
 * profile every test in this repository exists for:
 *
 *  - A receiver that answered *any* broadcast would relaunch the app mid-session,
 *    on a schedule no one asked for, with no way for the viewer to connect the
 *    two events.
 *  - A tap predicate that treated a hold as a tap — or vice versa — would clear
 *    the channel cache from an abandoned hold, which is a destructive action
 *    firing on the *release* of a button the viewer was trying not to press.
 */
class AutoStartPolicyTest {

    @Test
    fun `the two power-on actions start the app`() {
        assertTrue(isAutoStartAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(isAutoStartAction("android.intent.action.QUICKBOOT_POWER_ON"))
    }

    @Test
    fun `nothing else starts the app`() {
        // The realistic near-misses first: locked boot happens before user
        // unlock (the settings file is not readable there yet), and
        // MY_PACKAGE_REPLACED fires after every app update — honouring it would
        // yank the viewer into the app every time an update lands.
        assertFalse(isAutoStartAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(isAutoStartAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isAutoStartAction(Intent.ACTION_SHUTDOWN))
        assertFalse(isAutoStartAction(null))
        assertFalse(isAutoStartAction(""))
    }

    // ── Tap vs hold on the cache-reset row ───────────────────────────────

    @Test
    fun `a quick release is a tap`() {
        assertTrue(isTapRelease(heldMs = 0, holdCompleted = false))
        assertTrue(isTapRelease(heldMs = 120, holdCompleted = false))
        assertTrue(isTapRelease(heldMs = 999, holdCompleted = false))
    }

    @Test
    fun `a hold that ran to completion is not a tap`() {
        // The 10-second unlock fired on this very release; letting the release
        // also count as a tap would clear the cache a moment after unlocking.
        assertFalse(isTapRelease(heldMs = 10_000, holdCompleted = true))
        assertFalse(isTapRelease(heldMs = 250, holdCompleted = true))
    }

    @Test
    fun `an abandoned hold is not a tap`() {
        // The viewer held, thought better of it, and let go. A cache reset on
        // that release is the worst possible answer — the action they were
        // explicitly *not* confident enough to complete.
        assertFalse(isTapRelease(heldMs = 1_000, holdCompleted = false))
        assertFalse(isTapRelease(heldMs = 4_500, holdCompleted = false))
    }
}
