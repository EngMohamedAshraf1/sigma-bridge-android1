package com.sigmabridge.app.presentation.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.Language
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
    val conversationName by viewModel.conversationName.collectAsState()
    val partnerId by viewModel.partnerId.collectAsState()
    val error by viewModel.error.collectAsState()
    val translationTarget by viewModel.translationTargetLanguage.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(partnerId, connected) {
        if (partnerId.isNotBlank() && connected) {
            ContextCompat.startForegroundService(
                context,
                ChatNotificationService.startIntent(context)
            )
        }
    }

    LaunchedEffect(messages.size, connected) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        if (connected) viewModel.markVisibleMessagesRead()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conversationName, style = MaterialTheme.typography.titleMedium)
                        if (connected) {
                            Text(
                                "Connected • encrypted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Column(horizontalAlignment = Alignment.End) {
                        BoxWithLanguageMenu(
                            selected = translationTarget,
                            expanded = languageMenuExpanded,
                            onExpand = { languageMenuExpanded = true },
                            onDismiss = { languageMenuExpanded = false },
                            onSelect = { language ->
                                viewModel.setTranslationTargetLanguage(language)
                                languageMenuExpanded = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val mine = message.senderId == viewModel.ownSenderId
                    val background = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.86f),
                            colors = CardDefaults.cardColors(containerColor = background)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                            ) {
                                Text(
                                    text = message.text,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = if (mine) TextAlign.End else TextAlign.Start
                                )
                                Row(
                                    modifier = Modifier.padding(top = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                                        val receiptColor = if (message.deliveryStatus == MessageDeliveryStatus.READ) {
                                            Color(0xFF0084FF)
                                        } else MaterialTheme.colorScheme.onSurfaceVariant
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
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    singleLine = true,
                    enabled = connected
                )
                Button(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = connected && input.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun BoxWithLanguageMenu(
    selected: Language,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Language) -> Unit
) {
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = onExpand) {
            Text("🌐 ${selected.displayName}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss
        ) {
            listOf(
                LanguageCatalog.ARABIC,
                LanguageCatalog.RUSSIAN,
                LanguageCatalog.findByCode("en") ?: Language("en", "English")
            ).forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = { onSelect(language) }
                )
            }
        }
    }
}
