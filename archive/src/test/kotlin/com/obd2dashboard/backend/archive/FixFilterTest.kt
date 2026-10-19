package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Positions no car could have reached, left out of the series (M28). */
class FixFilterTest {
    /** A fix at [at] ms, [north] and [east] metres from a point at NHMS. */
    private fun fix(at: Long, north: Double, east: Double = 0.0) =
        FixFilter.Fix(at, at, 43.36 + north / 111_195.0, -71.46 + east / (111_195.0 * Math.cos(Math.toRadians(43.36))))

    private fun FixFilter.kept(vararg fixes: FixFilter.Fix): List<Long> = fixes.flatMap { offer(it) }.map { it.at }

    @Test
    fun `noise between fixes a tenth of a second apart is kept`() {
        // 4 m sideways in 0.1 s is 40 m/s on paper, but within 30 m: never a jump.
        val f = FixFilter()
        f.kept(fix(0, 0.0), fix(100, 3.0, 4.0), fix(200, 6.0, -4.0), fix(300, 9.0)) shouldBe listOf(0L, 100L, 200L, 300L)
        f.dropped shouldBe 0
    }

    @Test
    fun `a single fix off where the car could be is dropped`() {
        val f = FixFilter()
        f.kept(fix(0, 0.0), fix(100, 3.0), fix(200, 150.0), fix(300, 9.0), fix(400, 12.0)) shouldBe listOf(0L, 100L, 300L, 400L)
        f.dropped shouldBe 1
    }

    @Test
    fun `a dropout is kept, seconds without a fix and the car far on`() {
        // 110 m in 3.6 s is 31 m/s.
        FixFilter().kept(fix(0, 0.0), fix(3600, 110.0), fix(3700, 113.0)) shouldBe listOf(0L, 3600L, 3700L)
    }

    @Test
    fun `three that agree with each other say the last kept was the wrong one`() {
        // The first fix is 400 m off; the next three agree, so the filter goes on from them.
        val f = FixFilter()
        f.kept(fix(0, 400.0), fix(100, 0.0), fix(200, 3.0), fix(300, 6.0), fix(400, 9.0)) shouldBe listOf(0L, 100L, 200L, 300L, 400L)
        f.dropped shouldBe 0
    }

    @Test
    fun `two wrong ones in a row that disagree are both dropped`() {
        val f = FixFilter()
        f.kept(fix(0, 0.0), fix(100, 200.0), fix(200, -300.0), fix(300, 9.0)) shouldBe listOf(0L, 300L)
        f.dropped shouldBe 2
        f.held shouldBe 0
    }

    @Test
    fun `wrong ones still waiting are held, not yet dropped`() {
        val f = FixFilter()
        f.kept(fix(0, 0.0), fix(100, 200.0)) shouldBe listOf(0L)
        f.held shouldBe 1
        f.dropped shouldBe 0
    }

    @Test
    fun `a fix no newer than the last is dropped`() {
        val f = FixFilter()
        f.kept(fix(100, 0.0), fix(100, 1.0), fix(50, 2.0), fix(200, 3.0)) shouldBe listOf(100L, 200L)
        f.dropped shouldBe 2
    }

    @Test
    fun `a fast car is a fast car`() {
        // 80 m/s (288 km/h), a fix a second: 80 m each.
        FixFilter().kept(fix(0, 0.0), fix(1000, 80.0), fix(2000, 160.0)) shouldBe listOf(0L, 1000L, 2000L)
    }
}
