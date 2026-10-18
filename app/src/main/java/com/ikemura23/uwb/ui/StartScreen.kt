package com.ikemura23.uwb.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.model.Role
import com.ikemura23.uwb.permission.missingPermissions
import com.ikemura23.uwb.permission.requiredRuntimePermissions

@Composable
fun StartScreen(onStart: (Role, OobType) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uwbSupported = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
    }
    var role by rememberSaveable { mutableStateOf<Role?>(null) }
    var oob by rememberSaveable { mutableStateOf(OobType.DEFAULT) }
    var error by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val missing = missingPermissions(requiredRuntimePermissions(oob), granted)
        val selectedRole = role
        if (missing.isEmpty() && selectedRole != null) {
            error = null
            onStart(selectedRole, oob)
        } else {
            error = "権限が不足しています: ${missing.joinToString { it.substringAfterLast('.') }}"
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("UWB OOB 接続テスト", style = MaterialTheme.typography.headlineSmall)
        if (!uwbSupported) {
            Text("この端末は UWB 非対応です", color = MaterialTheme.colorScheme.error)
        }

        Text("役割", style = MaterialTheme.typography.titleMedium)
        Role.entries.forEach { r ->
            RadioRow(label = r.label, selected = role == r, enabled = true) { role = r }
        }

        Text("OOB 方式", style = MaterialTheme.typography.titleMedium)
        OobType.entries.forEach { t ->
            RadioRow(
                label = if (t.implemented) t.label else "${t.label}（未実装）",
                selected = oob == t,
                enabled = t.implemented,
            ) { oob = t }
        }

        Button(
            onClick = { launcher.launch(requiredRuntimePermissions(oob).toTypedArray()) },
            enabled = role != null && uwbSupported,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("開始") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            enabled = enabled,
            role = SemanticsRole.RadioButton,
            onClick = onSelect,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
