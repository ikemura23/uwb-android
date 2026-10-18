package com.ikemura23.uwb.experiment

import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import com.ikemura23.uwb.measure.TimingTracker
import com.ikemura23.uwb.measure.Timings
import com.ikemura23.uwb.model.Role
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import com.ikemura23.uwb.oob.OobEvent
import com.ikemura23.uwb.oob.OobTransport
import com.ikemura23.uwb.uwb.RangingParams
import com.ikemura23.uwb.uwb.UwbSession
import com.ikemura23.uwb.uwb.UwbSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ExperimentStatus(val label: String) {
    OOB_CONNECTING("OOB 接続中"),
    EXCHANGED("パラメータ交換済み"),
    RANGING("UWB 測距中"),
    PEER_DISCONNECTED("相手切断"),
    FAILED("失敗"),
}

sealed interface ExperimentEvent {
    data class Log(val message: String) : ExperimentEvent
    data class Status(val status: ExperimentStatus, val reason: String? = null) : ExperimentEvent
    data class TimingsUpdated(val timings: Timings) : ExperimentEvent
    data class Ranging(val result: RangingResult) : ExperimentEvent

    /** Emitted right after the timing tracker's t0 (OOB 処理開始); the UI uses it as the log time origin. */
    data object Started : ExperimentEvent
}

/**
 * Drives one experiment run independent of the OOB mechanism:
 * open UWB session -> exchange params over the transport -> range.
 */
class ExperimentRunner(
    private val role: Role,
    private val sessions: UwbSessions,
    private val transportFactory: ((OobEvent) -> Unit) -> OobTransport,
    private val tracker: TimingTracker,
    private val exchangeTimeout: Duration = 30.seconds,
) {
    fun run(): Flow<ExperimentEvent> = channelFlow {
        var transport: OobTransport? = null
        try {
            send(ExperimentEvent.Status(ExperimentStatus.OOB_CONNECTING))

            val session = when (role) {
                Role.CONTROLLER -> sessions.openController()
                Role.CONTROLEE -> sessions.openControlee()
            }

            // Transports call back from arbitrary threads; funnel events into this coroutine
            // so that the (non-thread-safe) tracker is only touched here.
            val oobEvents = Channel<OobEvent>(Channel.UNLIMITED)
            // t0 = OOB 処理開始 (spec): taken once the UWB session is open, right before the transport starts.
            tracker.start()
            send(ExperimentEvent.Started)
            send(ExperimentEvent.Log("自アドレス: ${session.localAddress.address.toHex()}"))
            val t = transportFactory { oobEvents.trySend(it) }
            transport = t

            val params = try {
                withTimeout(exchangeTimeout) { exchange(session, t, oobEvents) }
            } catch (e: TimeoutCancellationException) {
                closeOob(t, oobEvents)
                send(
                    ExperimentEvent.Status(
                        ExperimentStatus.FAILED,
                        "OOB 交換が ${exchangeTimeout.inWholeSeconds} 秒でタイムアウトしました",
                    ),
                )
                return@channelFlow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeOob(t, oobEvents)
                throw e
            }
            closeOob(t, oobEvents)
            startRanging(session, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ExperimentEvent.Status(ExperimentStatus.FAILED, e.message ?: e.toString()))
        } finally {
            runCatching { transport?.close() }
        }
    }

    /** Closes the transport without letting a close failure mask the real outcome, then flushes its close logs. */
    private suspend fun ProducerScope<ExperimentEvent>.closeOob(t: OobTransport, oobEvents: Channel<OobEvent>) {
        runCatching { t.close() }.onFailure { send(ExperimentEvent.Log("OOB close 失敗: ${it.message}")) }
        while (true) handleOob(oobEvents.tryReceive().getOrNull() ?: break)
    }

    private suspend fun ProducerScope<ExperimentEvent>.exchange(
        session: UwbSession,
        transport: OobTransport,
        oobEvents: Channel<OobEvent>,
    ): RangingParameters = coroutineScope {
        val exchange = async {
            when (role) {
                Role.CONTROLLER -> {
                    var mine: ControllerInfo? = null
                    val controlee = transport.exchangeAsController { _ ->
                        RangingParams.newControllerInfo(session).also { mine = it }
                    }
                    ExchangeResult(
                        params = RangingParams.forController(checkNotNull(mine), controlee),
                        sent = "親機情報を送信: ${checkNotNull(mine).describe()}",
                        received = "子機情報を受信: ${controlee.describe()}",
                    )
                }
                Role.CONTROLEE -> {
                    val mine = ControleeInfo(session.localAddress.address)
                    val controller = transport.exchangeAsControlee(mine)
                    ExchangeResult(
                        params = RangingParams.forControlee(controller),
                        sent = "子機情報を送信: ${mine.describe()}",
                        received = "親機情報を受信: ${controller.describe()}",
                    )
                }
            }
        }

        var result: ExchangeResult? = null
        while (result == null) {
            result = select {
                oobEvents.onReceive { handleOob(it); null }
                exchange.onAwait { it }
            }
        }
        while (true) handleOob(oobEvents.tryReceive().getOrNull() ?: break)

        tracker.markExchangeCompleted()
        send(ExperimentEvent.Log(result.sent))
        send(ExperimentEvent.Log(result.received))
        send(ExperimentEvent.Status(ExperimentStatus.EXCHANGED))
        send(ExperimentEvent.TimingsUpdated(tracker.timings))
        result.params
    }

    private suspend fun ProducerScope<ExperimentEvent>.handleOob(event: OobEvent) {
        when (event) {
            OobEvent.LinkEstablished -> {
                tracker.markLinkEstablished()
                send(ExperimentEvent.Log("OOB リンク確立"))
                send(ExperimentEvent.TimingsUpdated(tracker.timings))
            }
            is OobEvent.Log -> send(ExperimentEvent.Log(event.message))
        }
    }

    private suspend fun ProducerScope<ExperimentEvent>.startRanging(
        session: UwbSession,
        params: RangingParameters,
    ) {
        var firstRangingSeen = false
        var lastStatus: ExperimentStatus? = null
        suspend fun status(s: ExperimentStatus, reason: String? = null) {
            lastStatus = s
            send(ExperimentEvent.Status(s, reason))
        }
        session.prepare(params).collect { result ->
            // Position results are logged (with values) by the UI reducer; avoid a duplicate line here.
            if (result !is RangingResult.RangingResultPosition) {
                send(ExperimentEvent.Log("測距結果: ${result::class.simpleName}"))
            }
            when (result) {
                is RangingResult.RangingResultInitialized -> status(ExperimentStatus.RANGING)
                is RangingResult.RangingResultPosition -> {
                    if (lastStatus != ExperimentStatus.RANGING) status(ExperimentStatus.RANGING)
                    if (!firstRangingSeen) {
                        firstRangingSeen = true
                        tracker.markFirstRanging()
                        send(ExperimentEvent.TimingsUpdated(tracker.timings))
                    }
                    send(ExperimentEvent.Ranging(result))
                }
                is RangingResult.RangingResultPeerDisconnected -> status(ExperimentStatus.PEER_DISCONNECTED)
                is RangingResult.RangingResultFailure ->
                    status(ExperimentStatus.FAILED, "測距失敗 (reason=${result.reason})")
            }
        }
    }

    private class ExchangeResult(val params: RangingParameters, val sent: String, val received: String)

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    private fun ControleeInfo.describe() = "address=${address.toHex()}"
    private fun ControllerInfo.describe() =
        "address=${address.toHex()}, channel=$channel, preamble=$preambleIndex, sessionId=$sessionId"
}
