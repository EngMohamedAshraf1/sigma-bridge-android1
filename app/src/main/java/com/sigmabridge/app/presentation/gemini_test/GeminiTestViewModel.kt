package com.sigmabridge.app.presentation.gemini_test

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.LanguagePair
import com.sigmabridge.app.domain.model.TranslationMode
import com.sigmabridge.app.domain.model.TranslationRequest
import com.sigmabridge.app.domain.repository.TranslationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class GeminiTestUiState(
    val isLoading: Boolean = false,
    val resultText: String? = null,
    val errorMessage: String? = null
)

/**
 * Internal-only screen: pick a local .ogg file -> call TranslationRepository
 * directly -> show the Arabic text. Deliberately bypasses TelegramRepository/
 * DownloadRepository entirely so Gemini's behavior can be validated on its
 * own, before Phase 6 wires the full Telegram -> Gemini -> Telegram pipeline.
 *
 * The SAF content:// Uri -> local file copy below is UI-layer plumbing
 * specific to this screen (turning a user-picked document into the
 * TemporaryVoiceFile every TranslationRequest expects) — it still asks
 * CacheManager for the destination, it just also has to write the picked
 * file's bytes into it via ContentResolver, which only a Context can do.
 */
@HiltViewModel
class GeminiTestViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheManager: CacheManager,
    private val translationRepository: TranslationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeminiTestUiState())
    val uiState: StateFlow<GeminiTestUiState> = _uiState.asStateFlow()

    fun onFileSelected(uri: Uri) {
        _uiState.value = GeminiTestUiState(isLoading = true)

        viewModelScope.launch {
            val destination = cacheManager.createTempVoice()
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected file")
                inputStream.use { input ->
                    File(destination.path).outputStream().use { output -> input.copyTo(output) }
                }

                val request = TranslationRequest(
                    mode = TranslationMode.VOICE,
                    languagePair = LanguagePair.DEFAULT_MVP_PAIR,
                    sourceFile = destination
                )

                translationRepository.translate(request)
                    .onSuccess { result ->
                        _uiState.value = GeminiTestUiState(resultText = result.translatedText)
                    }
                    .onFailure { error ->
                        _uiState.value = GeminiTestUiState(errorMessage = error.message ?: "Translation failed")
                    }
            } catch (error: Exception) {
                _uiState.value = GeminiTestUiState(errorMessage = error.message ?: "Unexpected error")
            } finally {
                cacheManager.delete(destination)
            }
        }
    }
}
