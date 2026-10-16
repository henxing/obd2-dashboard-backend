package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Who drove a session (M14.2): set, cleared, and kept by the index's other changes. */
class SessionDriverTest {
    @Test
    fun `a session's driver is set, kept through other changes, and cleared`() = runTest {
        val index = InMemorySessionIndex()
        val archive = ArchiveService(index, InMemorySegmentStore())
        index.create(SessionRecord("s", "outback", null, null, 0, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
        archive.setDriver("s", "d1") shouldBe true
        index.get("s")!!.driver shouldBe "d1"
        index.append("s", 0, Segment(1, 1, "k", null, null), Instant.EPOCH) shouldBe true
        index.complete("s", 1, "sha", Instant.EPOCH) shouldBe true
        index.get("s")!!.driver shouldBe "d1"
        archive.setDriver("s", null) shouldBe true
        index.get("s")!!.driver shouldBe null
        archive.setDriver("nope", "d1") shouldBe false
    }

    @Test
    fun `driver changes (M27) - who drove a lap is the last change before it ended, else the session's driver`() = runTest {
        val record = SessionRecord("s", "outback", null, null, 0, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH, driver = "d-sam")
        record.driverAt(5_000) shouldBe "d-sam" // none: the session's driver throughout
        val changed = record.copy(driverChanges = listOf(DriverChange(10_000, "d-alex"), DriverChange(20_000, null)))
        changed.driverAt(10_000) shouldBe "d-sam" // a lap ending at the change is the driver's before
        changed.driverAt(10_001) shouldBe "d-alex"
        changed.driverAt(20_001) shouldBe null // a change to "not set" is not the session's driver
        // Stored, kept through the index's other changes, and replaced whole.
        val index = InMemorySessionIndex()
        val archive = ArchiveService(index, InMemorySegmentStore())
        index.create(record)
        archive.setDriverChanges("s", changed.driverChanges) shouldBe true
        archive.setDriver("s", "d-kim") shouldBe true
        index.get("s")!!.driverChanges shouldBe changed.driverChanges
        archive.setDriverChanges("s", emptyList()) shouldBe true
        index.get("s")!!.driverChanges shouldBe emptyList()
        archive.setDriverChanges("nope", emptyList()) shouldBe false
    }
}
