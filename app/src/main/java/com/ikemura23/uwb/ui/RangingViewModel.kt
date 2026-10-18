package com.ikemura23.uwb.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ikemura23.uwb.experiment.ExperimentEvent
import com.ikemura23.uwb.experiment.ExperimentRunner
import com.ikemura23.uwb.experiment.ExperimentStatus
import com.ikemura23.uwb.measure.TimingTracker
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.model.Role
import com.ikemura23.uwb.oob.OobTransportFactory
import com.ikemura23.uwb.uwb.AndroidUwbSessions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RangingViewModel(app: Application, role: Role, oob: OobType) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(RangingUiState(role, oob))
    val state: StateFlow<RangingUiState> = _state.asStateFlow()

    private val runner = ExperimentRunner(
        role = role,
        sessions = AndroidUwbSessions(app),
        transportFactory = { onEvent -> OobTransportFactory.create(app, oob, onEvent) },
        tracker = TimingTracker(),
    )

    private val job: Job = viewModelScope.launch {
        // Log times share the tracker's t0: it is reset when the runner reports Started (OOB 処理開始).
        // Events before that (e.g. OOB_CONNECTING) are stamped 0.
        var t0: Long? = null
        // Safety net: ExperimentRunner converts most errors to FAILED itself; catch() skips cancellation.
        runner.run()
            .catch { e -> emit(ExperimentEvent.Status(ExperimentStatus.FAILED, e.message ?: e.toString())) }
            .collect { event ->
                if (event == ExperimentEvent.Started) t0 = SystemClock.elapsedRealtime()
                val timeMs = t0?.let { SystemClock.elapsedRealtime() - it } ?: 0L
                _state.update { it.reduce(event, timeMs) }
            }
    }

    fun stop() {
        job.cancel()
    }
}
