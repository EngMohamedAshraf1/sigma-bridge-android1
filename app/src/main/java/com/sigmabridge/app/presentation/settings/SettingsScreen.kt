package com.sigmabridge.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.text.input.KeyboardType

/**
 * Deliberately plain: two labeled fields + a save button, no cards, no
 * icons, no animation. Phase 2 is about the secrets being stored correctly
 * (Keystore-backed), not about how this form looks — visual pass is later.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
        Text(text = "Telegram Bot Token")
        OutlinedTextField(
            value = uiState.botToken,
            onValueChange = viewModel::onBotTokenChanged,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Text(text = "Gemini API Key", modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = uiState.geminiApiKey,
            onValueChange = viewModel::onGeminiApiKeyChanged,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = viewModel::save,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(if (uiState.isSaved) "Saved" else "Save")
        }
    }
}
