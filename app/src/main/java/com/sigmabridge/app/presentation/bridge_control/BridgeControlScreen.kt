package com.sigmabridge.app.presentation.bridge_control

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The real control screen for BridgeForegroundService as of Phase 7 — no
 * longer a placeholder standing in for a Service that didn't exist yet.
 * On API 33+, POST_NOTIFICATIONS is requested before starting; without it
 * the service still runs, but its mandatory notification stays invisible.
 * That's the only permission-related handling here — no battery-optimization
 * prompt, no boot-time anything (still out of scope for Phase 7).
 */
@Composable
fun BridgeControlScreen(viewModel: BridgeControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Proceed regardless of the result — the service works either way,
        // it just won't show a visible notification if denied.
        viewModel.start()
    }

    fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bridge Control") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(text = "State: ${state.name}")

            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = ::onStartClicked) { Text("Start") }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = viewModel::stop) { Text("Stop") }
            }
        }
    }
}
