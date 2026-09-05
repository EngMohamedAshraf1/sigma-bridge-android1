package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val verificationCode: String = "",
    val resendCooldownSeconds: Int = 0,
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

    private var resendCountdownJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            accountRepository.awaitAuthInitialization()
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
            verificationCode = "",
            resendCooldownSeconds = 0,
            message = null,
            error = null
        )
        resendCountdownJob?.cancel()
    }

    fun chooseSignIn() {
        _state.value = _state.value.copy(
            mode = ChatAccountMode.SIGN_IN,
            step = ChatAccountStep.FORM,
            verificationCode = "",
            resendCooldownSeconds = 0,
            message = null,
            error = null
        )
        resendCountdownJob?.cancel()
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value, error = null)
    }

    fun updateVerificationCode(value: String) {
        _state.value = _state.value.copy(
            verificationCode = value.filter(Char::isDigit).take(6),
            error = null
        )
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
                .fold(
                    onSuccess = {
                        accountRepository.linkEmailToCurrentAnonymous(email)
                    },
                    onFailure = { Result.failure(it) }
                )

            result.onSuccess {
                _state.value = _state.value.copy(
                    busy = false,
                    email = email,
                    verificationCode = "",
                    resendCooldownSeconds = 60,
                    step = ChatAccountStep.VERIFICATION,
                    message = "أرسلنا رمز تحقق إلى $email. أدخل الرمز الموجود في رسالة Sigma Bridge.",
                    error = null
                )
                startResendCountdown()
            }.onFailure {
                _state.value = _state.value.copy(
                    busy = false,
                    error = friendlyError(it)
                )
            }
        }
    }

    fun verifyEmailOtp() {
        val email = _state.value.email.trim()
        val code = _state.value.verificationCode.trim()

        if (!email.contains("@") || email.length < 5) {
            _state.value = _state.value.copy(error = "أدخل بريدًا إلكترونيًا صحيحًا.")
            return
        }
        if (code.length !in 5..6 || !code.all(Char::isDigit)) {
            _state.value = _state.value.copy(error = "أدخل رمز التحقق المكون من 5 أو 6 أرقام.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.verifyEmailChangeOtp(email, code)
                .onSuccess {
                    resendCountdownJob?.cancel()
                    _state.value = _state.value.copy(
                        loading = false,
                        busy = false,
                        authenticated = true,
                        resendCooldownSeconds = 0,
                        step = ChatAccountStep.PASSWORD,
                        message = "تم التحقق من البريد. ضع كلمة مرور للحساب.",
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

    fun resendVerification() {
        val email = _state.value.email.trim()
        if (_state.value.step != ChatAccountStep.VERIFICATION || _state.value.busy) return
        if (_state.value.resendCooldownSeconds > 0) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            accountRepository.resendEmailVerification(email)
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        verificationCode = "",
                        resendCooldownSeconds = 60,
                        message = "تم إرسال رمز تحقق جديد إلى $email.",
                        error = null
                    )
                    startResendCountdown()
                }
                .onFailure {
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

    private fun startResendCountdown() {
        resendCountdownJob?.cancel()
        resendCountdownJob = viewModelScope.launch {
            for (remaining in 60 downTo 1) {
                _state.value = _state.value.copy(resendCooldownSeconds = remaining)
                delay(1_000)
            }
            _state.value = _state.value.copy(resendCooldownSeconds = 0)
        }
    }

    override fun onCleared() {
        resendCountdownJob?.cancel()
        super.onCleared()
    }

    private fun friendlyError(error: Throwable): String = when {
        error.message?.contains("MANUAL_LINKING", true) == true ->
            "تفعيل ربط الحسابات مطلوب من إعدادات Supabase."
        error.message?.contains("EMAIL_REQUIRED", true) == true -> "أدخل البريد الإلكتروني."
        error.message?.contains("OTP_REQUIRED", true) == true -> "أدخل رمز التحقق."
        error.message?.contains("PASSWORD_REQUIRED", true) == true -> "أدخل كلمة المرور."
        error.message?.contains("PASSWORD_TOO_SHORT", true) == true -> "كلمة المرور يجب أن تكون 8 أحرف على الأقل."
        error.message?.contains("INVALID_LOGIN_CREDENTIALS", true) == true -> "البريد الإلكتروني أو كلمة المرور غير صحيحة."
        error.message?.contains("Email not confirmed", true) == true -> "البريد الإلكتروني لم يتم تأكيده بعد."
        error.message?.contains("expired", true) == true -> "انتهت صلاحية رمز التحقق. اطلب رمزًا جديدًا."
        error.message?.contains("invalid", true) == true && error.message?.contains("token", true) == true ->
            "رمز التحقق غير صحيح."
        else -> error.message ?: "حدث خطأ غير متوقع."
    }
}
