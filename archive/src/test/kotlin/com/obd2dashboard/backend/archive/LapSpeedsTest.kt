package com.obd2dashboard.backend.archive

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Each lap's top, lowest and average speed from the raw lines (M29), the session page's rule (lapSpeeds.ts). */
class LapSpeedsTest {
    private fun speed(signal: String, wall: Long, value: Double?) =
        """{"type":"sample","signal":"$signal","value":${value ?: "null"},"seq":0,"at":$wall,"wall":$wall}"""

    /** A fix at [wall], [north] metres north of a point. */
    private fun fix(wall: Long, north: Double, at: Long = wall, fixAt: Long? = null) =
        """{"type":"sample","signal":"gps.position","lat":${43 + north / 111_195.0},"lon":-71.5,"seq":0,"at":$at""" +
            (fixAt?.let { ",\"fixAt\":$it" } ?: "") + ""","wall":$wall}"""

    private fun LapSpeeds.feed(vararg lines: String) = lines.forEach { line(it.toByteArray()) }

    /** A fix a second due north at [mps] metres a second, from [t0] for [n] seconds. */
    private fun LapSpeeds.north(t0: Long, n: Int, mps: Double) = (0..n).forEach { i -> feed(fix(t0 + i * 1000L, i * mps)) }

    @Test
    fun `top and lowest are the samples within the lap, a null passed over`() {
        val s = LapSpeeds()
        s.session()
        listOf(1_000L to 50.0, 3_000L to 120.0, 5_000L to 80.0, 7_000L to null, 9_000L to 30.0, 11_000L to 200.0)
            .forEach { (t, v) -> s.feed(speed("gps.speed", t, v)) }
        s.north(0, 10, 25.0)
        val lap = s.of(0, 10_000).shouldNotBeNull()
        lap.max shouldBe 120.0
        lap.min shouldBe 30.0
        lap.avg.shouldNotBeNull() shouldBe (90.0 plusOrMinus 0.1)
        s.of(0, 30_000).shouldBeNull() // the last sample 19 s before the end
        s.of(-10_000, 10_000).shouldBeNull() // the first 11 s after the start
    }

    @Test
    fun `no average where GPS was lost mid-lap, or with no fixes`() {
        val s = LapSpeeds()
        s.session()
        (0..60).forEach { s.feed(speed("gps.speed", it * 1000L, 90.0)) }
        (0..60).filter { it < 20 || it > 40 }.forEach { s.feed(fix(it * 1000L, it * 25.0)) }
        s.of(0, 60_000).shouldNotBeNull().avg.shouldBeNull()
        val none = LapSpeeds().apply { session(); (0..60).forEach { feed(speed("gps.speed", it * 1000L, 90.0)) } }
        none.of(0, 60_000).shouldNotBeNull().avg.shouldBeNull()
    }

    @Test
    fun `each session's GPS speed, else its car's`() {
        val s = LapSpeeds()
        s.session()
        (0..10).forEach { s.feed(speed("vehicle.speed", it * 1000L, 10.0), speed("gps.speed", it * 1000L, 12.0)) }
        s.session()
        (20..30).forEach { s.feed(speed("vehicle.speed", it * 1000L, 40.0)) }
        s.of(0, 10_000)!!.max shouldBe 12.0
        s.of(20_000, 30_000)!!.max shouldBe 40.0
    }

    @Test
    fun `positions at the receiver's time, and one no car could reach left out (M28)`() {
        val s = LapSpeeds()
        s.session()
        (0..60).forEach { s.feed(speed("gps.speed", it * 1000L, 90.0)) }
        // Each fix heard 400 ms late: placed when it was taken, so the lap is covered from its start.
        (0..60).forEach { i ->
            s.feed(fix(i * 1000L + 400, i * 25.0, at = i * 1000L + 400, fixAt = i * 1000L))
            if (i == 30) s.feed(fix(30_500, 5_000.0, at = 30_500)) // 5 km off in half a second
        }
        s.of(0, 60_000)!!.avg.shouldNotBeNull() shouldBe (90.0 plusOrMinus 0.1)
    }

    @Test
    fun `lines that aren't speed or position are passed over, and a bad one too`() {
        val s = LapSpeeds()
        s.session()
        s.feed("""{"type":"session","id":"a","vin":"V"}""", """{"type":"sample","signal":"engine.rpm","value":3000,"wall":1}""", "{not json gps.speed")
        s.of(0, 1_000).shouldBeNull()
    }
}
