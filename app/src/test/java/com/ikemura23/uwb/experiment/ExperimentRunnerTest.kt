package com.ikemura23.uwb.experiment

import androidx.core.uwb.RangingMeasurement
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingPosition
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import com.ikemura23.uwb.measure.TimingTracker
import com.ikemura23.uwb.model.Role
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import com.ikemura23.uwb.oob.OobEvent
import com.ikemura23.uwb.oob.OobTransport
import com.ikemura23.uwb.uwb.UwbSession
import com.ikemura23.uwb.uwb.UwbSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentRunnerTest {
    private val localAddr = byteArrayOf(0x0A, 0x0B)
    private val controleeAddr = byteArrayOf(0x01, 0x02)
    private val device = UwbDevice.createForAddress(controleeAddr)

    private fun position() = RangingResult.RangingResultPosition(
        device,
        RangingPosition(RangingMeasurement(1.5f), null, null, 0L),
    )

    private inner class FakeSession(private val results: List<RangingResult>) : UwbSession {
        var preparedParams: RangingParameters? = null
        override val localAddress = UwbAddress(localAddr)
        override val complexChannel: UwbComplexChannel? = UwbComplexChannel(9, 10)
        override fun prepare(params: RangingParameters): Flow<RangingResult> {
            preparedParams = params
            return flowOf(*results.toTypedArray())
        }
    }

    private class FakeSessions(private val session: UwbSession) : UwbSessions {
        override suspend fun openController() = session
        override suspend fun openControlee() = session
    }

    private class FakeTransport(
        private val onEvent: (OobEvent) -> Unit,
        private val behavior: suspend () -> Unit = {},
    ) : OobTransport {
        var closed = false
        var receivedControlee: ControleeInfo? = null
        val controllerInfo = ControllerInfo(byteArrayOf(5, 6), 9, 10, 1234, ByteArray(8))

        override suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo {
            onEvent(OobEvent.LinkEstablished)
            behavior()
            val peer = ControleeInfo(byteArrayOf(0x01, 0x02))
            build(peer)
            return peer
        }

        override suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo {
            onEvent(OobEvent.LinkEstablished)
            behavior()
            receivedControlee = mine
            return controllerInfo
        }

        override fun close() {
            closed = true
        }
    }

    private fun tracker(): TimingTracker {
        var now = 0L
        return TimingTracker { now += 1_000_000L; now }
    }

    private fun runner(
        role: Role,
        session: FakeSession,
        holder: MutableList<FakeTransport>,
        behavior: suspend () -> Unit = {},
    ) = ExperimentRunner(
        role = role,
        sessions = FakeSessions(session),
        transportFactory = { onEvent -> FakeTransport(onEvent, behavior).also { holder += it } },
        tracker = tracker(),
    )

    private fun List<ExperimentEvent>.statuses() = filterIsInstance<ExperimentEvent.Status>()

    @Test fun controllerHappyPath() = runTest {
        val session = FakeSession(
            listOf(RangingResult.RangingResultInitialized(device), position(), position()),
        )
        val transports = mutableListOf<FakeTransport>()
        val events = runner(Role.CONTROLLER, session, transports).run().toList()

        assertEquals(
            listOf(ExperimentStatus.OOB_CONNECTING, ExperimentStatus.EXCHANGED, ExperimentStatus.RANGING),
            events.statuses().map { it.status },
        )
        assertEquals(2, events.filterIsInstance<ExperimentEvent.Ranging>().size)
        val timings = events.filterIsInstance<ExperimentEvent.TimingsUpdated>().last().timings
        assertNotNull(timings.linkMs)
        assertNotNull(timings.exchangeMs)
        assertNotNull(timings.firstRangingMs)
        assertArrayEquals(controleeAddr, session.preparedParams!!.peerDevices[0].address.address)
        assertTrue(transports.single().closed)
    }

    @Test fun controleeSendsOwnAddress() = runTest {
        val session = FakeSession(listOf(RangingResult.RangingResultInitialized(device)))
        val transports = mutableListOf<FakeTransport>()
        runner(Role.CONTROLEE, session, transports).run().toList()

        assertArrayEquals(session.localAddress.address, transports.single().receivedControlee!!.address)
        assertArrayEquals(byteArrayOf(5, 6), session.preparedParams!!.peerDevices[0].address.address)
    }

    @Test fun exchangeTimesOut() = runTest {
        val session = FakeSession(emptyList())
        val transports = mutableListOf<FakeTransport>()
        val events = mutableListOf<ExperimentEvent>()
        val job = launch {
            runner(Role.CONTROLLER, session, transports) { awaitCancellation() }.run().toList(events)
        }
        advanceTimeBy(30_001)
        runCurrent()

        assertTrue(
            events.contains(
                ExperimentEvent.Status(ExperimentStatus.FAILED, "OOB 交換が 30 秒でタイムアウトしました"),
            ),
        )
        assertTrue(transports.single().closed)
        assertTrue(job.isCompleted)
    }

    @Test fun cancelClosesTransport() = runTest {
        val session = FakeSession(emptyList())
        val transports = mutableListOf<FakeTransport>()
        val job = launch {
            runner(Role.CONTROLLER, session, transports) { awaitCancellation() }.run().toList()
        }
        runCurrent()
        assertTrue(!transports.single().closed)
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertTrue(transports.single().closed)
    }

    @Test fun transportErrorBecomesFailure() = runTest {
        val session = FakeSession(emptyList())
        val transports = mutableListOf<FakeTransport>()
        val events = runner(Role.CONTROLLER, session, transports) {
            throw IllegalStateException("Bluetooth が OFF です")
        }.run().toList()

        assertEquals(
            ExperimentEvent.Status(ExperimentStatus.FAILED, "Bluetooth が OFF です"),
            events.statuses().last(),
        )
        assertTrue(transports.single().closed)
    }

    @Test fun peerDisconnectedStatus() = runTest {
        val session = FakeSession(
            listOf(
                RangingResult.RangingResultInitialized(device),
                RangingResult.RangingResultPeerDisconnected(device, 0),
            ),
        )
        val events = runner(Role.CONTROLLER, session, mutableListOf()).run().toList()

        assertEquals(ExperimentStatus.PEER_DISCONNECTED, events.statuses().last().status)
    }
}
