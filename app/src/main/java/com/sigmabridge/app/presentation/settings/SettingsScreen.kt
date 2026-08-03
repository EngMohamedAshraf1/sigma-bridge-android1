package com.sigmabridge.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.domain.model.GeminiKeyStatus
import com.sigmabridge.app.domain.usecase.SettingsValidationError

/**
 * Still deliberately plain (Phase 2 note applies): fields + buttons + error
 * text, no cards, no animation. The colored status indicators are emoji
 * glyphs rendered as plain Text - no icon library beyond what the project
 * already depends on (material-icons-core, used here only for the trash
 * icon, same as Icons.Filled.Settings already used on Home).
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
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

        Text(text = "Gemini API Keys", modifier = Modifier.padding(top = 16.dp))

        uiState.geminiKeySlots.forEachIndexed { index, slot ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(text = slot.status.toIndicator(), modifier = Modifier.padding(end = 8.dp))
                OutlinedTextField(
                    value = slot.value,
                    onValueChange = { viewModel.onKeySlotChanged(slot.id, it) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text("Key ${index + 1}") },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.onDeleteKeySlot(slot.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete Key ${index + 1}")
                }
            }
        }
        uiState.errors.filter { !it.isBotTokenError() }.forEach { error ->
            Text(text = error.toMessage(), color = MaterialTheme.colorScheme.error)
        }

        TextButton(onClick = viewModel::onAddKeySlot, modifier = Modifier.padding(top = 4.dp)) {
            Text("\u2795 Add Key")
        }

        val summary = uiState.geminiKeySummary
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(text = "Configured keys: ${summary.total}")
            Text("\uD83D\uDFE2 Active: ${summary.active}")
            Text("\uD83D\uDFE1 Ready: ${summary.ready}")
            Text("\uD83D\uDD34 Quota exceeded: ${summary.quotaExceeded}")
            Text("\u26AB Invalid: ${summary.invalid}")
        }

        Button(
            onClick = viewModel::save,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(if (uiState.isSaved) "Saved" else "Save")
        }
    }
}

/** Not yet saved / no live status observed for the current text -> a neutral indicator, never a fabricated real status. */
private fun GeminiKeyStatus?.toIndicator(): String = when (this) {
    GeminiKeyStatus.ACTIVE -> "\uD83D\uDFE2"
    GeminiKeyStatus.READY -> "\uD83D\uDFE1"
    GeminiKeyStatus.QUOTA_EXCEEDED -> "\uD83D\uDD34"
    GeminiKeyStatus.INVALID -> "\u26AB"
    null -> "\u26AA"
}

private fun SettingsValidationError.isBotTokenError() =
    this == SettingsValidationError.BOT_TOKEN_EMPTY || this == SettingsValidationError.BOT_TOKEN_INVALID_FORMAT

private fun SettingsValidationError.toMessage(): String = when (this) {
    SettingsValidationError.BOT_TOKEN_EMPTY -> "Bot token is required."
    SettingsValidationError.BOT_TOKEN_INVALID_FORMAT -> "That doesn't look like a valid bot token."
    SettingsValidationError.GEMINI_KEY_EMPTY -> "At least one Gemini API key is required."
    SettingsValidationError.GEMINI_KEY_INVALID_FORMAT -> "One of the Gemini API keys doesn't look valid."
}
