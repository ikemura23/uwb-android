package com.ikemura23.uwb.ui

import androidx.core.uwb.RangingMeasurement
import androidx.core.uwb.RangingPosition
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbDevice
import com.ikemura23.uwb.experiment.ExperimentEvent
import com.ikemura23.uwb.experiment.ExperimentStatus
import com.ikemura23.uwb.measure.Timings
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RangingUiStateTest {
    private val base = RangingUiState(Role.CONTROLLER, OobType.BLE)

    // RangingPosition has no equals(); the same instance is compared by identity.
    private val pos = RangingPosition(RangingMeasurement(1.234f), null, RangingMeasurement(-3f), 99L)
    private val dev = UwbDevice.createForAddress(byteArrayOf(1, 2))

    @Test fun positionUpdatesState() {
        val s = base.reduce(ExperimentEvent.Ranging(RangingResult.RangingResultPosition(dev, pos)), 10)
        assertSame(pos, s.position)
    }

    @Test fun peerDisconnectedKeepsLastPosition() {
        val s = base.reduce(ExperimentEvent.Ranging(RangingResult.RangingResultPosition(dev, pos)), 10)
            .reduce(ExperimentEvent.Status(ExperimentStatus.PEER_DISCONNECTED), 20)
        assertEquals(ExperimentStatus.PEER_DISCONNECTED, s.status)
        assertSame(pos, s.position)
    }

    @Test fun failureKeepsReasonAndLogs() {
        val s = base.reduce(ExperimentEvent.Status(ExperimentStatus.FAILED, "x"), 5)
        assertEquals("x", s.reason)
        assertEquals(5L, s.logs.last().timeMs)
    }

    @Test fun nonPositionRangingDoesNotTouchPosition() {
        val s = base.reduce(ExperimentEvent.Ranging(RangingResult.RangingResultPeerDisconnected(dev, 0)), 1)
        assertNull(s.position)
    }

    @Test fun timingsAndLogEventsAreApplied() {
        val t = Timings(linkMs = 1, exchangeMs = 2)
        val s = base.reduce(ExperimentEvent.TimingsUpdated(t), 3).reduce(ExperimentEvent.Log("hi"), 4)
        assertEquals(t, s.timings)
        assertEquals(listOf(3L, 4L), s.logs.map { it.timeMs })
        assertEquals("hi", s.logs.last().message)
    }

    @Test fun formatting() {
        assertEquals("1.23 m", formatMeters(RangingMeasurement(1.234f)))
        assertEquals("—", formatMeters(null))
        assertEquals("-12.5°", formatDegrees(RangingMeasurement(-12.5f)))
        assertEquals("—", formatMs(null))
        assertEquals("350 ms", formatMs(350))
    }
}
