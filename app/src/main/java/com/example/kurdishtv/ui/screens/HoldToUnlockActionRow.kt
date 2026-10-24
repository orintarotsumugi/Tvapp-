package com.example.kurdishtv.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kurdishtv.ui.motion.tvClickable
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the viewer must hold the row for [onHoldCompleted] to fire.
 *
 * Ten seconds, and the whole point: this row is the only door to the ads menu and
 * it must not be opened by an elbow. The progress bar and the countdown are what
 * make ten seconds feel intentional rather than broken.
 */
private const val HOLD_DURATION_MS = 10_000L
private const val HOLD_SECONDS = 10

/**
 * The longest press that still counts as a tap.
 *
 * A press held for at least this long is a *hold attempt* — deliberate, and not a
 * request to clear the cache. Releasing one must do nothing at all: the previous
 * behaviour fired the destructive action on release regardless of duration, so a
 * viewer who started holding to unlock the ads menu and thought better of it at
 * four seconds cleared their whole channel cache on the way out.
 */
private const val TAP_MAX_HOLD_MS = 1_000L

/**
 * Whether releasing a press should count as a tap.
 *
 * Pure, and internal, because this is the entire safety property of a destructive
 * row: a tap clears the cache, a hold unlocks the ads menu, and a hold that was
 * *abandoned* does neither. It is a two-clause predicate, which is exactly the
 * shape that gets quietly "simplified" into something that fires a cache clear
 * from a long press.
 */
internal fun isTapRelease(heldMs: Long, holdCompleted: Boolean): Boolean =
    !holdCompleted && heldMs in 0L until TAP_MAX_HOLD_MS

/**
 * An action row that triggers a standard click on tap, but when held continuously
 * for [HOLD_DURATION_MS] (10 seconds), triggers [onHoldCompleted] with live visual
 * progress and countdown.
 *
 * ## Works by remote as well as by finger
 *
 * This row used to be driven by a raw `pointerInput` gesture and nothing else.
 * A remote's OK button is a *key*, not a pointer: Compose's gesture handlers never
 * see it, so on a television this row — and with it the entire ads-unlock flow and
 * the "Clear channel cache" action — could not be focused, could not be clicked and
 * could not be held. It was a settings row that looked live and did nothing.
 *
 * It is now driven by [tvClickable]'s interaction source, which both paths feed:
 *
 *  - **Touch** produces `Press`/`Release` interactions through `clickable`, which
 *    is what `collectIsPressedAsState` observes.
 *  - **D-pad** is handled by an explicit `onKeyEvent` below — key down starts the
 *    hold, key up ends it — rather than relying on how `clickable` translates key
 *    events into interactions, which is a framework detail. Consuming the key
 *    events also stops `clickable`'s own click from firing on key-up, so the tap
 *    decision stays in one place: [isTapRelease].
 *
 * Auto-repeat is ignored (`repeatCount > 0`), the same rule the channel grid's
 * number pad uses: a held key is one press, not a stream of them.
 */
@Composable
fun HoldToUnlockActionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onHoldCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    showDivider: Boolean = true
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    var isHolding by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableLongStateOf(HOLD_SECONDS.toLong()) }
    var holdCompleted by remember { mutableStateOf(false) }
    var pressStartedAt by remember { mutableLongStateOf(0L) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val progress = remember { Animatable(0f) }

    val currentOnHoldCompleted by rememberUpdatedState(onHoldCompleted)

    // The shared hold machinery, called from both input paths.
    val startHold: () -> Unit = {
        if (holdJob?.isActive != true) {
            pressStartedAt = SystemClock.uptimeMillis()
            holdCompleted = false
            isHolding = true
            remainingSeconds = HOLD_SECONDS.toLong()
            holdJob = scope.launch {
                progress.snapTo(0f)
                val animJob = launch {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = HOLD_DURATION_MS.toInt(),
                            easing = LinearEasing
                        )
                    )
                }
                for (sec in HOLD_SECONDS downTo 1) {
                    remainingSeconds = sec.toLong()
                    delay(1000L)
                }
                remainingSeconds = 0L
                isHolding = false
                animJob.cancel()
                holdCompleted = true
                currentOnHoldCompleted()
            }
        }
    }

    val endHold: () -> Unit = {
        holdJob?.cancel()
        holdJob = null
        isHolding = false
        scope.launch { progress.snapTo(0f) }
    }

    val rowClick: () -> Unit = {
        val heldFor =
            if (pressStartedAt == 0L) 0L else SystemClock.uptimeMillis() - pressStartedAt
        if (isTapRelease(heldFor, holdCompleted)) onClick()
    }

    // Touch: the press interaction that `clickable` emits is the signal. A press
    // starts the hold; a release cancels it. The click itself is handled by
    // `tvClickable`'s onClick above, which applies the tap-vs-hold rule.
    LaunchedEffect(isPressed) {
        if (isPressed) {
            if (pressStartedAt == 0L || !isHolding) startHold()
        } else if (pressStartedAt != 0L) {
            endHold()
        }
    }

    val accent = if (danger) colors.liveRed else colors.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) colors.primary else if (danger) colors.liveRed.copy(alpha = 0.35f) else colors.border,
                shape = RoundedCornerShape(8.dp)
            )
            // One focus target, from the click — the ring observes the same source.
            .tvClickable(
                interactionSource = interactionSource,
                onClick = rowClick
            )
            // D-pad, handled directly. Key down starts the hold, key up ends it,
            // and both are consumed so the clickable's own click never fires for
            // remote presses — the tap decision for keys lives here, beside the
            // hold it has to be able to overrule.
            .onKeyEvent { event ->
                val isCenterKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!isCenterKey) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        // Auto-repeat is the platform reporting the same physical
                        // press again; one press is one hold.
                        if (event.nativeKeyEvent.repeatCount == 0) startHold()
                        true
                    }
                    KeyEventType.KeyUp -> {
                        endHold()
                        val heldFor = if (pressStartedAt == 0L) {
                            0L
                        } else {
                            SystemClock.uptimeMillis() - pressStartedAt
                        }
                        if (isTapRelease(heldFor, holdCompleted)) onClick()
                        true
                    }
                    else -> false
                }
            },
        color = when {
            isFocused -> colors.surfaceHigh
            isHolding -> colors.surfaceVariant
            else -> colors.surfaceElevated
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (danger) colors.liveRed.copy(alpha = 0.15f) else colors.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = if (danger) colors.liveRed else colors.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = title,
                            color = if (danger) colors.liveRed else colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isHolding) {
                                "Hold for $remainingSeconds s to unlock Ads & Terms..."
                            } else {
                                subtitle
                            },
                            color = if (isHolding) accent else colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                if (isHolding) {
                    Text(
                        text = "${remainingSeconds}s",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isHolding,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = colors.border
                    )
                }
            }
        }
    }

    if (showDivider) {
        Spacer(modifier = Modifier.height(6.dp))
    }
}
