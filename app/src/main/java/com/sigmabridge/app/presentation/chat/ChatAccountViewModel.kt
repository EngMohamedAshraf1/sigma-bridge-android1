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

enum class ChatAccountMode { CREATE, SIGN_IN }
enum class ChatAccountStep { FORM, VERIFICATION, PASSWORD }

data class ChatAccountUiState(
    val loading: Boolean = true,
    val authenticated: Boolean = false,
    val mode: ChatAccountMode = ChatAccountMode.CREATE,
    val step: ChatAccountStep = ChatAccountStep.FORM,
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
            val authenticated = accountRepository.isAuthenticated()
            val anonymous = accountRepository.isAnonymousSession()
            _state.value = _state.value.copy(
                loading = false,
                authenticated = authenticated,
                mode = if (anonymous) ChatAccountMode.CREATE else _state.value.mode,
                error = null
            )
        }
    }

    fun chooseCreate() {
        _state.value = _state.value.copy(
            mode = ChatAccountMode.CREATE,
            step = ChatAccountStep.FORM,
            message = null,
            error = null
        )
    }

    fun chooseSignIn() {
        _state.value = _state.value.copy(
            mode = ChatAccountMode.SIGN_IN,
            step = ChatAccountStep.FORM,
            message = null,
            error = null
        )
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value, error = null)
    }

    fun createAccount() {
        val email = _state.value.email.trim()
        if (!email.contains("@") || email.length < 5) {
            _state.value = _state.value.copy(error = "أدخل بريدًا إلكترونيًا صحيحًا.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            val result = accountRepository.startAnonymousAccount()
                .flatMap { accountRepository.linkEmailToCurrentAnonymous(email) }

            result.onSuccess {
                _state.value = _state.value.copy(
                    busy = false,
                    email = email,
                    step = ChatAccountStep.VERIFICATION,
                    message = "أرسلنا رسالة تحقق إلى $email. افتحها ثم اضغط «تم التحقق» هنا.",
                    error = null
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    busy = false,
                    error = friendlyError(it)
                )
            }
        }
    }

    fun checkVerification() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.refreshAccountState()
                .onSuccess { verified ->
                    if (verified) {
                        _state.value = _state.value.copy(
                            loading = false,
                            busy = false,
                            authenticated = false,
                            step = ChatAccountStep.PASSWORD,
                            message = "تم توثيق الحساب. ضع كلمة مرور لاسترداد الحساب لاحقًا.",
                            error = null
                        )
                    } else {
                        _state.value = _state.value.copy(
                            busy = false,
                            message = "لم يتم توثيق البريد بعد. افتح رسالة التحقق ثم حاول مرة أخرى.",
                            error = null
                        )
                    }
                }.onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = friendlyError(it)
                    )
                }
        }
    }

    fun setPassword(password: String, confirmPassword: String, onAuthenticated: () -> Unit) {
        if (password.length < 8) {
            _state.value = _state.value.copy(error = "كلمة المرور يجب أن تكون 8 أحرف على الأقل.")
            return
        }
        if (password != confirmPassword) {
            _state.value = _state.value.copy(error = "كلمتا المرور غير متطابقتين.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.setPassword(password)
                .onSuccess {
                    _state.value = _state.value.copy(
                        loading = false,
                        busy = false,
                        authenticated = true,
                        message = null,
                        error = null
                    )
                    onAuthenticated()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = friendlyError(it)
                    )
                }
        }
    }

    fun signIn(password: String, onAuthenticated: () -> Unit) {
        val email = _state.value.email.trim()
        if (!email.contains("@") || email.length < 5) {
            _state.value = _state.value.copy(error = "أدخل بريدًا إلكترونيًا صحيحًا.")
            return
        }
        if (password.isBlank()) {
            _state.value = _state.value.copy(error = "أدخل كلمة المرور.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.signInWithEmail(email, password)
                .onSuccess {
                    _state.value = _state.value.copy(
                        loading = false,
                        busy = false,
                        authenticated = true,
                        step = ChatAccountStep.FORM,
                        error = null
                    )
                    onAuthenticated()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = friendlyError(it)
                    )
                }
        }
    }

    private fun friendlyError(error: Throwable): String = when {
        error.message?.contains("MANUAL_LINKING", true) == true ->
            "تفعيل ربط الحسابات مطلوب من إعدادات Supabase."
        error.message?.contains("EMAIL_REQUIRED", true) == true -> "أدخل البريد الإلكتروني."
        error.message?.contains("PASSWORD_REQUIRED", true) == true -> "أدخل كلمة المرور."
        error.message?.contains("PASSWORD_TOO_SHORT", true) == true -> "كلمة المرور يجب أن تكون 8 أحرف على الأقل."
        error.message?.contains("INVALID_LOGIN_CREDENTIALS", true) == true -> "البريد الإلكتروني أو كلمة المرور غير صحيحة."
        error.message?.contains("Email not confirmed", true) == true -> "البريد الإلكتروني لم يتم تأكيده بعد."
        else -> error.message ?: "حدث خطأ غير متوقع."
    }
}
