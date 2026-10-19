package com.ikemura23.uwb.ui

import androidx.core.uwb.RangingMeasurement
import androidx.core.uwb.RangingPosition
import androidx.core.uwb.RangingResult
import com.ikemura23.uwb.experiment.ExperimentEvent
import com.ikemura23.uwb.experiment.ExperimentStatus
import com.ikemura23.uwb.measure.Timings
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.model.Role
import java.util.Locale

/** [timeMs] is the elapsed time since the experiment started. */
data class LogLine(val timeMs: Long, val message: String)

data class RangingUiState(
    val role: Role,
    val oob: OobType,
    val status: ExperimentStatus = ExperimentStatus.OOB_CONNECTING,
    val reason: String? = null,
    val position: RangingPosition? = null,
    val timings: Timings = Timings(),
    val logs: List<LogLine> = emptyList(),
)

fun RangingUiState.reduce(event: ExperimentEvent, timeMs: Long): RangingUiState = when (event) {
    ExperimentEvent.Started -> this
    is ExperimentEvent.Log -> log(timeMs, event.message)
    is ExperimentEvent.Status -> copy(status = event.status, reason = event.reason)
        .log(timeMs, "状態: ${event.status.label}" + (event.reason?.let { " ($it)" } ?: ""))
    is ExperimentEvent.TimingsUpdated -> copy(timings = event.timings)
        .log(timeMs, "計測: ${event.timings.describe()}")
    is ExperimentEvent.Ranging -> when (val result = event.result) {
        is RangingResult.RangingResultPosition -> copy(position = result.position)
            .log(timeMs, "測距: 距離=${formatMeters(result.position.distance)}")
        else -> log(timeMs, "測距: ${result::class.simpleName}")
    }
}

private fun RangingUiState.log(timeMs: Long, message: String) = copy(logs = logs + LogLine(timeMs, message))

private fun Timings.describe() =
    "接続=${formatMs(linkMs)}, 交換=${formatMs(exchangeMs)}, 初回測距=${formatMs(firstRangingMs)}, " +
        "交換→測距=${formatMs(exchangeToRangingMs)}"

fun formatMeters(m: RangingMeasurement?): String = m?.let { "%.2f m".format(Locale.ROOT, it.value) } ?: "—"

fun formatDegrees(m: RangingMeasurement?): String = m?.let { "%.1f°".format(Locale.ROOT, it.value) } ?: "—"

fun formatMs(ms: Long?): String = ms?.let { "$it ms" } ?: "—"
