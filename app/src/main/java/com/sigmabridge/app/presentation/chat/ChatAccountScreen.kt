package com.sigmabridge.app.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatAccountScreen(
    state: ChatAccountUiState,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
        ) {
            Text("Sigma Bridge", style = MaterialTheme.typography.headlineMedium)
            Text(
                "أدخل بريدك الإلكتروني للمتابعة. لا توجد كلمة مرور مطلوبة؛ نرسل لك رابط دخول آمن.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("البريد الإلكتروني") },
                placeholder = { Text("example@gmail.com") },
                singleLine = true
            )

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy
            ) {
                if (state.busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("متابعة")
                }
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
