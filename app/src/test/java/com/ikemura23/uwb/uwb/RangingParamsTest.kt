package com.ikemura23.uwb.uwb

import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class RangingParamsTest {
    private val ctrl = ControllerInfo(byteArrayOf(1, 2), 9, 11, 42, ByteArray(8) { 7 })
    private val ctle = ControleeInfo(byteArrayOf(3, 4))

    // UwbDevice / UwbComplexChannel は equals 未実装のためフィールドで比較する
    private fun assertPeers(p: RangingParameters, address: ByteArray) {
        assertEquals(1, p.peerDevices.size)
        assertArrayEquals(address, p.peerDevices[0].address.address)
    }

    @Test fun controllerParams() {
        val p = RangingParams.forController(ctrl, ctle)
        assertEquals(RangingParameters.CONFIG_UNICAST_DS_TWR, p.uwbConfigType)
        assertEquals(42, p.sessionId)
        assertArrayEquals(ByteArray(8) { 7 }, p.sessionKeyInfo)
        assertEquals(9, p.complexChannel?.channel)
        assertEquals(11, p.complexChannel?.preambleIndex)
        assertPeers(p, byteArrayOf(3, 4))
        assertEquals(RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC, p.updateRateType)
    }

    @Test fun controleeParamsPointAtController() {
        val p = RangingParams.forControlee(ctrl)
        assertPeers(p, byteArrayOf(1, 2))
        assertEquals(42, p.sessionId)
    }

    @Test fun newControllerInfoUsesSessionAndRandom() {
        val session = object : UwbSession {
            override val localAddress = UwbAddress(byteArrayOf(5, 6))
            override val complexChannel = UwbComplexChannel(9, 10)
            override fun prepare(params: RangingParameters) = emptyFlow<RangingResult>()
        }
        val info = RangingParams.newControllerInfo(session, Random(0))
        assertArrayEquals(byteArrayOf(5, 6), info.address)
        assertEquals(9, info.channel); assertEquals(10, info.preambleIndex)
        assertEquals(8, info.sessionKey.size)
    }
}
