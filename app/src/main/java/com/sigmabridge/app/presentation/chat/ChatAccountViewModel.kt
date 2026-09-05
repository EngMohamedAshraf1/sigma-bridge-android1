package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatAccountUiState(
    val loading: Boolean = true,
    val authenticated: Boolean = false,
    val email: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class ChatAccountViewModel @Inject constructor(
    private val accountRepository: ChatAccountRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ChatAccountUiState())
    val state: StateFlow<ChatAccountUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            accountRepository.awaitAuthInitialization()
            _state.value = _state.value.copy(
                loading = false,
                authenticated = accountRepository.isAuthenticated(),
                email = accountRepository.currentEmail().orEmpty(),
                error = null
            )
        }
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value, error = null, message = null)
    }

    fun sendEmailLogin() {
        val email = _state.value.email.trim()
        if (!email.contains("@") || email.length < 5) {
            _state.value = _state.value.copy(error = "أدخل بريدًا إلكترونيًا صحيحًا.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.requestEmailLogin(email)
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        email = email,
                        message = "أرسلنا رابط تسجيل الدخول إلى $email. افتح الرسالة واضغط الرابط للمتابعة.",
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
    }

    /**
     * Kept as a compatibility entry point for the previous screen wiring.
     * Authentication is now entirely passwordless, so this simply sends the
     * email login link.
     */
    fun createAccount() = sendEmailLogin()
    fun signIn(onAuthenticated: () -> Unit = {}) {
        sendEmailLoginWithCallback(onAuthenticated)
    }

    private fun sendEmailLoginWithCallback(onAuthenticated: () -> Unit) {
        val email = _state.value.email.trim()
        if (!email.contains("@") || email.length < 5) {
            _state.value = _state.value.copy(error = "أدخل بريدًا إلكترونيًا صحيحًا.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.requestEmailLogin(email)
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        email = email,
                        message = "أرسلنا رابط تسجيل الدخول إلى $email. افتح الرسالة واضغط الرابط للمتابعة.",
                        error = null
                    )
                    onAuthenticated()
                }
                .onFailure {
                    _state.value = _state.value.copy(busy = false, error = friendlyError(it))
                }
        }
    }

    fun onAuthReturned() {
        refresh()
    }

    private fun friendlyError(error: Throwable): String = when {
        error.message?.contains("EMAIL_REQUIRED", true) == true -> "أدخل البريد الإلكتروني."
        error.message?.contains("INVALID_EMAIL", true) == true -> "أدخل بريدًا إلكترونيًا صحيحًا."
        error.message?.contains("rate", true) == true -> "تم الوصول إلى حد إرسال الرسائل مؤقتًا. حاول لاحقًا."
        else -> error.message ?: "تعذر إرسال رابط تسجيل الدخول."
    }
}
