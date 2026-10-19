package com.ikemura23.uwb.uwb

import androidx.core.uwb.RangingParameters
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import kotlin.random.Random

object RangingParams {
    private const val SESSION_KEY_SIZE = 8

    fun newControllerInfo(session: UwbSession, random: Random = Random.Default): ControllerInfo {
        val channel = checkNotNull(session.complexChannel) { "Controller セッションではありません" }
        return ControllerInfo(
            address = session.localAddress.address,
            channel = channel.channel,
            preambleIndex = channel.preambleIndex,
            sessionId = random.nextInt(),
            sessionKey = random.nextBytes(SESSION_KEY_SIZE),
        )
    }

    fun forController(mine: ControllerInfo, peer: ControleeInfo): RangingParameters =
        build(mine, peer.address)

    fun forControlee(peer: ControllerInfo): RangingParameters = build(peer, peer.address)

    private fun build(info: ControllerInfo, peerAddress: ByteArray) = RangingParameters(
        uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
        sessionId = info.sessionId,
        subSessionId = 0,
        sessionKeyInfo = info.sessionKey,
        subSessionKeyInfo = null,
        complexChannel = UwbComplexChannel(info.channel, info.preambleIndex),
        peerDevices = listOf(UwbDevice.createForAddress(peerAddress)),
        updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
    )
}
