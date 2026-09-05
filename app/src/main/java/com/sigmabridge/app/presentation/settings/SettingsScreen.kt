package com.sigmabridge.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sigmabridge.app.BuildConfig
import com.sigmabridge.app.data.update.GitHubUpdateChecker
import com.sigmabridge.app.domain.model.GeminiKeyStatus
import com.sigmabridge.app.domain.usecase.SettingsValidationError
import kotlinx.coroutines.launch

/**
 * Still deliberately plain (Phase 2 note applies): fields + buttons + error
 * text, no cards, no animation. The colored status indicators are emoji
 * glyphs rendered as plain Text - no icon library beyond what the project
 * already depends on (material-icons-core, used here only for the trash
 * icon, same as Icons.Filled.Settings already used on Home).
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val updateChecker = remember { GitHubUpdateChecker() }
    var checkingForUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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

        Button(
            onClick = {
                if (checkingForUpdate) return@Button
                checkingForUpdate = true
                updateMessage = null
                scope.launch {
                    try {
                        val result = updateChecker.check(BuildConfig.VERSION_NAME)
                        updateMessage = if (result.updateAvailable) {
                            "Update available: v${result.latestVersion}"
                        } else {
                            "You are using the latest version (v${result.currentVersion})."
                        }
                    } catch (error: Exception) {
                        updateMessage = "Could not check for updates. Please try again."
                    } finally {
                        checkingForUpdate = false
                    }
                }
            },
            enabled = !checkingForUpdate,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            if (checkingForUpdate) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .align(Alignment.CenterVertically),
                    strokeWidth = 2.dp
                )
            }
            Text(if (checkingForUpdate) "Checking..." else "Check for updates")
        }

        updateMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
