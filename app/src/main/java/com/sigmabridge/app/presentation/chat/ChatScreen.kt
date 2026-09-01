package com.sigmabridge.app.presentation.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import com.sigmabridge.app.service.ChatNotificationService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val partnerId by viewModel.partnerId.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var partnerInput by remember { mutableStateOf(partnerId) }
    var input by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(partnerId) {
        partnerInput = partnerId
        if (partnerId.isNotBlank()) {
            ContextCompat.startForegroundService(context, ChatNotificationService.startIntent(context))
        } else {
            context.startService(ChatNotificationService.stopIntent(context))
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Private Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Your ID", style = MaterialTheme.typography.labelMedium)
            Text(
                text = viewModel.myId,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp).clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sigma Bridge ID", viewModel.myId))
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = partnerInput,
                    onValueChange = { partnerInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Partner ID") },
                    singleLine = true,
                    enabled = !connected
                )
                if (connected) {
                    OutlinedButton(onClick = { input = ""; viewModel.startNewChat() }) { Text("New Chat") }
                } else {
                    Button(onClick = { viewModel.setPartnerId(partnerInput) }, enabled = partnerInput.isNotBlank()) { Text("Connect") }
                }
            }

            Text(
                text = if (connected) "Connected • encrypted" else "Not connected",
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val mine = message.senderId == viewModel.ownSenderId
                    val background = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = background)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                        ) {
                            Text(
                                text = message.text,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = if (mine) TextAlign.End else TextAlign.Start
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (mine) {
                                    val receiptText = when (message.deliveryStatus) {
                                        MessageDeliveryStatus.PENDING -> "• Sending…"
                                        MessageDeliveryStatus.SENT -> "✓"
                                        MessageDeliveryStatus.DELIVERED -> "✓✓"
                                        MessageDeliveryStatus.READ -> "✓✓"
                                    }
                                    val receiptColor = when (message.deliveryStatus) {
                                        MessageDeliveryStatus.READ -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = receiptText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = receiptColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") },
                    singleLine = true,
                    enabled = connected
                )
                Button(onClick = { viewModel.send(input); input = "" }, enabled = connected && input.isNotBlank()) {
                    Text("Send")
                }
            }
        }
    }
}
