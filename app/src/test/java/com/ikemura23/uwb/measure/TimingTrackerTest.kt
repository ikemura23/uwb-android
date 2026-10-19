package com.ikemura23.uwb.measure

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingTrackerTest {
    private var now = 0L
    private val tracker = TimingTracker { now }
    private fun at(ms: Long) { now = ms * 1_000_000 }

    @Test fun measuresFromStart() {
        at(1000); tracker.start()
        at(1200); tracker.markLinkEstablished()
        at(1500); tracker.markExchangeCompleted()
        at(1800); tracker.markFirstRanging()
        assertEquals(Timings(200, 500, 800), tracker.timings)
        assertEquals(300L, tracker.timings.exchangeToRangingMs)
    }
    @Test fun onlyFirstRangingCounts() {
        at(0); tracker.start(); at(100); tracker.markFirstRanging(); at(900); tracker.markFirstRanging()
        assertEquals(100L, tracker.timings.firstRangingMs)
    }
    @Test fun marksBeforeStartIgnored() {
        at(50); tracker.markLinkEstablished()
        assertEquals(Timings(), tracker.timings)
    }
}
