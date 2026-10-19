package com.ikemura23.uwb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ikemura23.uwb.experiment.ExperimentStatus
import kotlinx.coroutines.flow.filter

@Composable
fun RangingScreen(state: RangingUiState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Follow the tail only while the user is at the bottom; scrolling up pauses auto-scroll.
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { followTail = !listState.canScrollForward }
    }
    LaunchedEffect(state.logs.size) {
        if (followTail && state.logs.isNotEmpty()) listState.scrollToItem(state.logs.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("役割: ${state.role.label}")
        Text("OOB: ${state.oob.label}")
        Text(
            "状態: ${state.status.label}" + (state.reason?.let { " ($it)" } ?: ""),
            color = if (state.status == ExperimentStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val p = state.position
        Value("距離", formatMeters(p?.distance))
        Value("方位角", formatDegrees(p?.azimuth))
        Value("仰角", formatDegrees(p?.elevation))
        Value("elapsedRealtimeNanos", p?.elapsedRealtimeNanos?.toString() ?: "—")

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Value("OOB 接続確立まで", formatMs(state.timings.linkMs))
        Value("交換完了まで", formatMs(state.timings.exchangeMs))
        Value("初回測距まで", formatMs(state.timings.firstRangingMs))
        Value("交換完了→初回測距", formatMs(state.timings.exchangeToRangingMs))

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.logs) { line ->
                Text(
                    "[${formatMs(line.timeMs)}] ${line.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止") }
    }
}

@Composable
private fun Value(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
