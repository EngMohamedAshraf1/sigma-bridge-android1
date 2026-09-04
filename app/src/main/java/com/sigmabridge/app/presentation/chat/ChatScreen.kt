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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sigmabridge.app.data.chat.ChatForegroundState
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.Language

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val reactions by viewModel.reactions.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val conversationName by viewModel.conversationName.collectAsState()
    val partnerId by viewModel.partnerId.collectAsState()
    val error by viewModel.error.collectAsState()
    val translationTarget by viewModel.translationTargetLanguage.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }

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

    DisposableEffect(lifecycleOwner, partnerId, connected) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (partnerId.isNotBlank() && connected) {
                    ChatForegroundState.openPartnerId = partnerId
                    viewModel.markVisibleMessagesRead()
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                if (ChatForegroundState.openPartnerId == partnerId) {
                    ChatForegroundState.openPartnerId = null
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            partnerId.isNotBlank() && connected
        ) {
            ChatForegroundState.openPartnerId = partnerId
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ChatForegroundState.openPartnerId == partnerId) {
                ChatForegroundState.openPartnerId = null
            }
        }
    }

    LaunchedEffect(messages.size, connected) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        if (connected && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.markVisibleMessagesRead()
        }
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
                    IconButton(onClick = { viewModel.disconnect(); onBack() }) {
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
                    ChatMessageItem(
                        message = message,
                        reactions = reactions[message.id].orEmpty(),
                        isMine = message.senderId == viewModel.ownSenderId,
                        ownUserId = viewModel.ownSenderId,
                        reactionPickerVisible = selectedMessageId == message.id,
                        onLongPress = { selectedMessageId = message.id },
                        onReaction = { emoji ->
                            viewModel.setReaction(message.id, emoji)
                            selectedMessageId = null
                        }
                    )
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
