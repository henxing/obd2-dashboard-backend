package com.obd2dashboard.backend.archive

import kotlin.math.cos
import kotlin.math.hypot

/**
 * **Positions no car could have reached** (M28), dropped from a session's
 * series: further than [minJumpM] from the last kept fix *and* faster than
 * [maxSpeedMps] to get there. Both, so a few metres of noise between fixes a
 * tenth of a second apart never counts, and a dropout (seconds without a fix,
 * the car far on) is kept.
 *
 * Fed in order, each fix with its time on the tablet's `at` clock (`fixAt`,
 * else `at`); [offer] returns the fixes to keep, in order. **Never stuck on a
 * wrong one:** [adoptAfter] fixes in a row that are each wrong against the
 * last kept but fine against each other say the last kept was the wrong one,
 * and are kept. A fix no newer than the last (§22.3 says never) is dropped.
 * Lap timing never reads this: re-timing is the tablet's rule on the tablet's
 * own fixes (decision 33).
 */
public class FixFilter(
    private val minJumpM: Double = 30.0,
    private val maxSpeedMps: Double = 90.0,
    private val adoptAfter: Int = 3,
) {
    /** A fix at [at] on `at`'s clock; [t] is where the series places it, carried through. */
    public data class Fix(val at: Long, val t: Long, val lat: Double, val lon: Double)

    private var last: Fix? = null
    private val pending = mutableListOf<Fix>()

    /** How many offered fixes weren't kept. */
    public var dropped: Long = 0
        private set

    /** Fixes held back for now: wrong against the last kept, waiting to see if more agree with them. */
    public val held: Int get() = pending.size

    public fun offer(fix: Fix): List<Fix> {
        val kept = last
        if (kept == null) {
            last = fix
            return listOf(fix)
        }
        if (fix.at <= (pending.lastOrNull() ?: kept).at) {
            dropped++
            return emptyList()
        }
        if (plausible(kept, fix)) {
            dropped += pending.size
            pending.clear()
            last = fix
            return listOf(fix)
        }
        if (pending.isNotEmpty() && !plausible(pending.last(), fix)) {
            dropped += pending.size
            pending.clear()
        }
        pending += fix
        if (pending.size < adoptAfter) return emptyList()
        val adopted = pending.toList()
        pending.clear()
        last = adopted.last()
        return adopted
    }

    /** Whether [b] could follow [a]. */
    private fun plausible(a: Fix, b: Fix): Boolean {
        val m = metres(a, b)
        return m <= minJumpM || m / ((b.at - a.at) / 1000.0) <= maxSpeedMps
    }

    private fun metres(a: Fix, b: Fix): Double {
        val dy = (b.lat - a.lat) * METRES_PER_DEGREE
        val dx = (b.lon - a.lon) * METRES_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(dx, dy)
    }

    private companion object {
        /** Metres a degree of latitude, as the lap rule's frame has it. */
        const val METRES_PER_DEGREE = 111_195.0
    }
}
