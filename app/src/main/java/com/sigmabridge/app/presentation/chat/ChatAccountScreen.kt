package com.sigmabridge.app.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ChatAccountScreen(
    state: ChatAccountUiState,
    onContinueWithGoogle: () -> Unit
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
                "سجّل الدخول مرة واحدة بحساب Google. لا تحتاج إلى كلمة مرور.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onContinueWithGoogle,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy
            ) {
                if (state.busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "G",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("المتابعة باستخدام Google")
                    }
                }
            }

            OutlinedButton(
                onClick = onContinueWithGoogle,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy
            ) {
                Text("اختيار حساب Google آخر")
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
