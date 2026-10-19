package com.ikemura23.uwb.oob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OobCodecTest {
    private val controller = ControllerInfo(
        byteArrayOf(0x12, 0x34),
        channel = 9,
        preambleIndex = 11,
        sessionId = 0x7fff_0001,
        sessionKey = ByteArray(8) { it.toByte() },
    )

    @Test
    fun controleeRoundTrip() {
        val info = ControleeInfo(byteArrayOf(0x0a, 0x0b))
        assertEquals(info, OobCodec.decodeControlee(OobCodec.encode(info)))
    }

    @Test
    fun controllerRoundTrip() {
        assertEquals(controller, OobCodec.decodeController(OobCodec.encode(controller)))
    }

    @Test
    fun controllerFitsWifiAwareLimit() {
        assertTrue(OobCodec.encode(controller).size <= 255)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongTypeRejected() {
        OobCodec.decodeController(OobCodec.encode(ControleeInfo(byteArrayOf(1, 2))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedRejected() {
        OobCodec.decodeController(OobCodec.encode(controller).copyOf(5))
    }
}
