package com.obd2dashboard.backend.archive

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * **Each lap's top, lowest and average speed** (M29), the session page's rule
 * (`web/src/lib/lapSpeeds.ts`) on a car's raw lines, for the event download.
 * Speeds are km/h, as the tablet sends them (§4.1).
 *
 * Fed each session's lines in turn ([session], then [line]), it keeps only
 * speeds and positions, as the session's series does: each session's GPS
 * speed, else its car's speed; positions placed when the receiver took them,
 * those no car could reach left out (M28). Then [of] answers per lap:
 * - **top and lowest:** the speed samples within the lap;
 * - **average:** the distance between the lap's positions over the time they
 *   span.
 *
 * A lap the samples don't cover end to end (within [COVER_MS]) has none of
 * the three.
 */
public class LapSpeeds {
    public data class Speed(val max: Double, val min: Double, val avg: Double?)

    private val gps = Samples()
    private val car = Samples()
    private var fixes = FixFilter()
    private val speedT = LongList()
    private val speedV = DoubleList()
    private val fixT = LongList()
    private val fixLat = DoubleList()
    private val fixLon = DoubleList()
    private var sorted: Sorted? = null

    private class Samples {
        val t = LongList()
        val v = DoubleList()
        fun clear() { t.clear(); v.clear() }
    }

    /** A new session begins: its own choice of speed signal, its own positions' filter. */
    public fun session() {
        finishSession()
        fixes = FixFilter()
    }

    /** One raw line of the current session. Only samples of speed and position are read. */
    public fun line(bytes: ByteArray) {
        // Most lines are other signals: parsed only when one of these might be in it.
        val text = String(bytes, Charsets.UTF_8)
        if (SIGNALS.none { text.contains(it) }) return
        val record = Records.parseObject(bytes) ?: return
        if (record.string("type") != "sample") return
        val wall = record.long("wall") ?: return
        when (record.string("signal")) {
            "gps.position" -> {
                val lat = (record["lat"] as? JsonPrimitive)?.doubleOrNull ?: return
                val lon = (record["lon"] as? JsonPrimitive)?.doubleOrNull ?: return
                for (f in fixes.offer(FixFilter.placed(record, wall, lat, lon))) {
                    fixT.add(f.t); fixLat.add(f.lat); fixLon.add(f.lon)
                }
                sorted = null
            }
            "gps.speed", "vehicle.speed" -> {
                val value = (record["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return
                if (!value.isFinite()) return
                val into = if (record.string("signal") == "gps.speed") gps else car
                into.t.add(wall)
                into.v.add(value)
            }
        }
    }

    /** The speeds of a lap from [start] to [end] (the tablet's `wall`), or null where the speed samples don't cover it. */
    public fun of(start: Long, end: Long): Speed? {
        finishSession()
        val s = sorted ?: sort().also { sorted = it }
        val span = covering(s.speedT, start, end) ?: return null
        var top = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        for (i in span.first..span.last) {
            top = max(top, s.speedV[i])
            low = min(low, s.speedV[i])
        }
        return Speed(top, low, average(s, start, end))
    }

    /** km/h: the distance between the lap's positions over the time they span; null unless they cover it. */
    private fun average(s: Sorted, start: Long, end: Long): Double? {
        val span = covering(s.fixT, start, end) ?: return null
        val seconds = (s.fixT[span.last] - s.fixT[span.first]) / 1000.0
        if (seconds <= 0) return null
        var m = 0.0
        for (i in span.first until span.last) {
            // A gap longer than the cover allows is GPS lost mid-lap: no average, not a straight line across it.
            if (s.fixT[i + 1] - s.fixT[i] > COVER_MS) return null
            m += metres(s.fixLat[i], s.fixLon[i], s.fixLat[i + 1], s.fixLon[i + 1])
        }
        return m / seconds * 3.6
    }

    /** The session's samples join the rest: its GPS speed if it has any, else its car's, as its series' map and laps show. */
    private fun finishSession() {
        val chosen = if (gps.t.size > 0) gps else car
        for (i in 0 until chosen.t.size) { speedT.add(chosen.t[i]); speedV.add(chosen.v[i]) }
        if (chosen.t.size > 0) sorted = null
        gps.clear()
        car.clear()
    }

    private class Sorted(val speedT: LongArray, val speedV: DoubleArray, val fixT: LongArray, val fixLat: DoubleArray, val fixLon: DoubleArray)

    /** Everything in time order: sessions back to back nearly always are, but a corrected clock can step back. */
    private fun sort(): Sorted {
        val so = (0 until speedT.size).sortedBy { speedT[it] }
        val fo = (0 until fixT.size).sortedBy { fixT[it] }
        return Sorted(
            LongArray(so.size) { speedT[so[it]] }, DoubleArray(so.size) { speedV[so[it]] },
            LongArray(fo.size) { fixT[fo[it]] }, DoubleArray(fo.size) { fixLat[fo[it]] }, DoubleArray(fo.size) { fixLon[fo[it]] },
        )
    }

    public companion object {
        /** How near a lap's start and end its first and last sample must be: a thinned series' slice, and a little. */
        public const val COVER_MS: Long = 6_000

        private val SIGNALS = listOf("\"gps.position\"", "\"gps.speed\"", "\"vehicle.speed\"")

        /** The indexes of [t] (ascending) within `[start, end]`, or null unless they reach within [COVER_MS] of both. */
        internal fun covering(t: LongArray, start: Long, end: Long): IntRange? {
            val a = firstAtOrAfter(t, start)
            val b = firstAtOrAfter(t, end + 1) - 1
            if (a > b || t[a] - start > COVER_MS || end - t[b] > COVER_MS) return null
            return a..b
        }

        private fun firstAtOrAfter(t: LongArray, x: Long): Int {
            var lo = 0
            var hi = t.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (t[mid] < x) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Metres between two fixes, in a flat frame (a lap is small enough). */
        private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * FixFilter.METRES_PER_DEGREE
            val dx = (lon2 - lon1) * FixFilter.METRES_PER_DEGREE * cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(dx, dy)
        }
    }
}
