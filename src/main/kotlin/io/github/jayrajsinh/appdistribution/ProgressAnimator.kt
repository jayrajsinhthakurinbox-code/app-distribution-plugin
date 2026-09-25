package io.github.jayrajsinh.appdistribution

import com.intellij.ui.components.JBLabel
import javax.swing.JProgressBar
import javax.swing.Timer
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Smoothly animates a progress bar and "message · N%" label toward the
 * latest reported percentage, counting up instead of jumping. The speed
 * scales with the remaining gap, so bursts of quick updates catch up fast
 * while small steps still visibly count.
 *
 * Between updates (e.g. a two-minute Gradle build) it keeps creeping slowly
 * toward a soft ceiling below 100%, so it never looks stuck. It never moves
 * backwards and only reaches 100% when told to.
 */
internal class ProgressAnimator(
    private val bar: JProgressBar,
    private val label: JBLabel
) {

    private var displayed = 0.0
    private var target = 0.0
    private var message = ""
    private var lastTickNanos = 0L

    private var onReached: (() -> Unit)? = null
    private var reachedTimer: Timer? = null

    private val timer = Timer(FRAME_MS) { tick() }

    fun start(initialMessage: String) {
        stop()

        displayed = 0.0
        target = 0.0
        message = initialMessage

        // Busy animation until the first real value arrives
        bar.isIndeterminate = true
        bar.value = 0
        label.text = initialMessage
    }

    fun update(
        percentage: Int,
        newMessage: String,
        onReached: (() -> Unit)? = null
    ) {
        bar.isIndeterminate = false

        // Never move backwards
        target = maxOf(
            target,
            percentage.coerceIn(0, 100).toDouble()
        )

        message = newMessage

        if (onReached != null) {
            this.onReached = onReached
        }

        if (!timer.isRunning) {
            lastTickNanos = System.nanoTime()
            timer.start()
        }

        render()
    }

    fun stop() {
        timer.stop()
        reachedTimer?.stop()
        reachedTimer = null
        onReached = null
        bar.isIndeterminate = false
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = (now - lastTickNanos) / 1_000_000_000.0
        lastTickNanos = now

        val gap = target - displayed

        if (gap <= EPSILON) {
            if (onReached != null) {
                displayed = maxOf(displayed, target)
                render()
                timer.stop()
                fireReached()
                return
            }

            creep(dt)
            return
        }

        // Ease out: fast when far behind, never slower than MIN_RATE
        val rate = maxOf(MIN_RATE, gap * CATCH_UP)

        displayed = minOf(target, displayed + rate * dt)

        render()
    }

    /** Slow, ever-decelerating drift toward a ceiling while waiting. */
    private fun creep(dt: Double) {
        if (target >= 100.0) {
            return
        }

        val ceiling = target + (CREEP_LIMIT - target) * CREEP_SHARE

        if (displayed < ceiling) {
            displayed += (ceiling - displayed) * CREEP_RATE * dt
            render()
        }
    }

    private fun fireReached() {
        val callback = onReached ?: return
        onReached = null

        // Brief hold so the final value is actually seen
        reachedTimer = Timer(HOLD_MS) {
            reachedTimer = null
            callback()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun render() {
        bar.value = (displayed / 100.0 * RESOLUTION).roundToInt()

        label.text =
            "$message  ·  ${floor(displayed).toInt()}%"
    }

    companion object {
        const val RESOLUTION = 1000

        private const val FRAME_MS = 16
        private const val HOLD_MS = 400

        /** Minimum count-up speed, in percent per second. */
        private const val MIN_RATE = 15.0

        /** Fraction of the remaining gap covered per second. */
        private const val CATCH_UP = 4.0

        private const val EPSILON = 0.05

        /** Creeping never goes past this, however long it waits. */
        private const val CREEP_LIMIT = 95.0

        /** Share of the remaining distance to CREEP_LIMIT creeping may cover. */
        private const val CREEP_SHARE = 0.6

        /** Fraction of the distance to the ceiling covered per second. */
        private const val CREEP_RATE = 0.025
    }
}
