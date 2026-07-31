package com.sigmabridge.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.domain.usecase.SettingsValidationError

/**
 * Still deliberately plain (Phase 2 note applies): fields + button + error
 * text, no cards, no icons, no animation.
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
        uiState.errors.filter { it.isBotTokenError() }.forEach { error ->
            Text(text = error.toMessage(), color = MaterialTheme.colorScheme.error)
        }

        Text(text = "Gemini API Key", modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = uiState.geminiApiKey,
            onValueChange = viewModel::onGeminiApiKeyChanged,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        uiState.errors.filter { !it.isBotTokenError() }.forEach { error ->
            Text(text = error.toMessage(), color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = viewModel::save,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(if (uiState.isSaved) "Saved" else "Save")
        }
    }
}

private fun SettingsValidationError.isBotTokenError() =
    this == SettingsValidationError.BOT_TOKEN_EMPTY || this == SettingsValidationError.BOT_TOKEN_INVALID_FORMAT

private fun SettingsValidationError.toMessage(): String = when (this) {
    SettingsValidationError.BOT_TOKEN_EMPTY -> "Bot token is required."
    SettingsValidationError.BOT_TOKEN_INVALID_FORMAT -> "That doesn't look like a valid bot token."
    SettingsValidationError.GEMINI_KEY_EMPTY -> "Gemini API key is required."
    SettingsValidationError.GEMINI_KEY_INVALID_FORMAT -> "That doesn't look like a valid Gemini API key."
}
