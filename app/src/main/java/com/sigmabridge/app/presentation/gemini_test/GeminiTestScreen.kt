package com.sigmabridge.app.presentation.gemini_test

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
 * Deliberately bare-bones — this exists to validate Gemini in isolation,
 * not to be a polished feature. No file-name display, no history, no
 * styling beyond what's needed to read the result.
 */
@Composable
fun GeminiTestScreen(viewModel: GeminiTestViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::onFileSelected)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Gemini Test (internal)") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Button(onClick = { filePicker.launch("audio/*") }) {
                Text("Select local .ogg file")
            }

            when {
                uiState.isLoading -> Text(
                    text = "Translating\u2026",
                    modifier = Modifier.padding(top = 16.dp)
                )
                uiState.errorMessage != null -> Text(
                    text = "Error: ${uiState.errorMessage}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
                uiState.resultText != null -> Text(
                    text = uiState.resultText.orEmpty(),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
