package com.sigmabridge.app.presentation.bridge_control

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
 * No notification, no Foreground Service — while this screen (or the
 * process) is alive, BridgeOrchestrator keeps polling; closing the app
 * stops it. That limitation is expected and goes away in Phase 7.
 */
@Composable
fun BridgeControlScreen(viewModel: BridgeControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bridge Control (internal)") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(text = "State: ${state.name}")

            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = viewModel::start) { Text("Start") }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = viewModel::stop) { Text("Stop") }
            }
        }
    }
}
