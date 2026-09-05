package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.auth.GoogleSignInManager
import com.sigmabridge.app.data.chat.ChatAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

 data class ChatAccountUiState(
    val loading: Boolean = true,
    val authenticated: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class ChatAccountViewModel @Inject constructor(
    private val accountRepository: ChatAccountRepository,
    private val googleSignInManager: GoogleSignInManager
) : ViewModel() {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ChatAccountUiState())
    val state: kotlinx.coroutines.flow.StateFlow<ChatAccountUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            accountRepository.awaitAuthInitialization()
            _state.value = _state.value.copy(
                loading = false,
                authenticated = accountRepository.isAuthenticated(),
                error = null
            )
        }
    }

    fun signInWithGoogle(activity: android.app.Activity) {
        if (_state.value.busy) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)

            googleSignInManager.signIn(activity)
                .onSuccess { idToken ->
                    accountRepository.signInWithGoogleIdToken(idToken)
                        .onSuccess {
                            _state.value = _state.value.copy(
                                loading = false,
                                authenticated = true,
                                busy = false,
                                message = null,
                                error = null
                            )
                        }
                        .onFailure {
                            _state.value = _state.value.copy(
                                busy = false,
                                error = friendlyError(it)
                            )
                        }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = friendlyGoogleError(it)
                    )
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            accountRepository.signOut()
            _state.value = _state.value.copy(authenticated = false, message = null, error = null)
        }
    }

    /** Compatibility hooks for the current parent screen while it migrates to Google-only auth. */
    fun updateEmail(@Suppress("UNUSED_PARAMETER") value: String) = Unit
    fun sendEmailLogin() {
        _state.value = _state.value.copy(error = "استخدم زر تسجيل الدخول عبر Google.")
    }

    private fun friendlyGoogleError(error: Throwable): String = when {
        error.message?.contains("GOOGLE_WEB_CLIENT_ID_MISSING", true) == true ->
            "Google Sign-In غير مُعد بعد في التطبيق."
        error.message?.contains("cancel", true) == true ->
            "تم إلغاء تسجيل الدخول."
        error.message?.contains("GOOGLE_CREDENTIAL", true) == true ->
            "تعذر الحصول على حساب Google."
        else -> error.message ?: "تعذر تسجيل الدخول باستخدام Google."
    }

    private fun friendlyError(error: Throwable): String = when {
        error.message?.contains("GOOGLE_ID_TOKEN_REQUIRED", true) == true ->
            "لم يصل رمز Google. حاول مرة أخرى."
        else -> error.message ?: "تعذر تسجيل الدخول باستخدام Google."
    }
}
