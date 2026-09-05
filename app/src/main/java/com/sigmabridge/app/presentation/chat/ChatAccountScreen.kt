package com.sigmabridge.app.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ChatAccountScreen(
    state: ChatAccountUiState,
    onChooseCreate: () -> Unit,
    onChooseSignIn: () -> Unit,
    onEmailChange: (String) -> Unit,
    onCreateAccount: () -> Unit,
    onVerificationCodeChange: (String) -> Unit,
    onVerifyEmailOtp: () -> Unit,
    onResendVerification: () -> Unit,
    onSetPassword: (String, String) -> Unit,
    onSignIn: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text("Sigma Bridge", style = MaterialTheme.typography.headlineMedium)
            Text(
                "أنشئ حسابًا مرة واحدة، ثم يبقى ملفك ومحادثاتك مرتبطين بالحساب حتى بعد التحديث أو إعادة التثبيت.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onChooseCreate,
                    enabled = state.mode != ChatAccountMode.CREATE
                ) { Text("إنشاء حساب") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onChooseSignIn,
                    enabled = state.mode != ChatAccountMode.SIGN_IN
                ) { Text("تسجيل الدخول") }
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("البريد الإلكتروني") },
                singleLine = true
            )

            when (state.mode) {
                ChatAccountMode.CREATE -> when (state.step) {
                    ChatAccountStep.FORM -> {
                        Button(
                            onClick = onCreateAccount,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy
                        ) {
                            if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp)
                            else Text("إنشاء حساب")
                        }
                    }
                    ChatAccountStep.VERIFICATION -> {
                        Text(
                            state.message ?: "أدخل رمز التحقق الذي وصلك إلى بريدك الإلكتروني.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.verificationCode,
                            onValueChange = onVerificationCodeChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("رمز التحقق") },
                            placeholder = { Text("123456") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        Button(
                            onClick = onVerifyEmailOtp,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && state.verificationCode.length in 5..6
                        ) {
                            if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp)
                            else Text("تحقق من الرمز")
                        }
                        OutlinedButton(
                            onClick = onResendVerification,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy && state.resendCooldownSeconds == 0
                        ) {
                            if (state.resendCooldownSeconds > 0) {
                                Text("إعادة إرسال الرمز بعد ${state.resendCooldownSeconds} ثانية")
                            } else {
                                Text("إعادة إرسال الرمز")
                            }
                        }
                    }
                    ChatAccountStep.PASSWORD -> {
                        var password by remember { mutableStateOf("") }
                        var confirmPassword by remember { mutableStateOf("") }
                        Text(state.message ?: "ضع كلمة مرور للحساب.")
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("كلمة المرور") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("تأكيد كلمة المرور") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Button(
                            onClick = { onSetPassword(password, confirmPassword) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy
                        ) {
                            if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp)
                            else Text("حفظ الحساب")
                        }
                    }
                }

                ChatAccountMode.SIGN_IN -> {
                    var password by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("كلمة المرور") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Button(
                        onClick = { onSignIn(password) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy
                    ) {
                        if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp)
                        else Text("تسجيل الدخول")
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
