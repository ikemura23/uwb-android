package com.ikemura23.uwb

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.model.Role
import com.ikemura23.uwb.ui.RangingScreen
import com.ikemura23.uwb.ui.RangingViewModel
import com.ikemura23.uwb.ui.StartScreen
import com.ikemura23.uwb.ui.theme.UwbandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UwbandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    App(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun App(modifier: Modifier = Modifier) {
    var role by rememberSaveable { mutableStateOf<Role?>(null) }
    var oob by rememberSaveable { mutableStateOf(OobType.DEFAULT) }
    // Last-used role survives 停止 so StartScreen can restore the selection.
    var lastRole by rememberSaveable { mutableStateOf<Role?>(null) }
    // Bumped on every start so each run gets a fresh ViewModel.
    var runId by rememberSaveable { mutableIntStateOf(0) }

    val activeRole = role
    if (activeRole == null) {
        StartScreen(
            onStart = { r, o ->
                role = r
                lastRole = r
                oob = o
                runId++
            },
            modifier = modifier,
            initialRole = lastRole,
            initialOob = oob,
        )
    } else {
        val app = LocalContext.current.applicationContext as Application
        val vm: RangingViewModel = viewModel(
            key = "ranging-$runId",
            factory = viewModelFactory { initializer { RangingViewModel(app, activeRole, oob) } },
        )
        val state by vm.state.collectAsState()
        RangingScreen(
            state = state,
            onStop = {
                vm.stop()
                role = null
            },
            modifier = modifier,
        )
    }
}
