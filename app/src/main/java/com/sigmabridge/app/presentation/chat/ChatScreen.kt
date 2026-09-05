package com.sigmabridge.app.presentation.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sigmabridge.app.R
import com.sigmabridge.app.data.chat.ChatForegroundState
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import com.sigmabridge.app.domain.language.LanguageCatalog
import com.sigmabridge.app.domain.model.Language
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val partnerOnline by viewModel.partnerOnline.collectAsState()
    val partnerLastSeen by viewModel.partnerLastSeen.collectAsState()
    val conversationName by viewModel.conversationName.collectAsState()
    val partnerId by viewModel.partnerId.collectAsState()
    val partnerAvatarPath by viewModel.partnerAvatarPath.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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
                if (ChatForegroundState.openPartnerId == partnerId) ChatForegroundState.openPartnerId = null
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && partnerId.isNotBlank() && connected) {
            ChatForegroundState.openPartnerId = partnerId
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ChatForegroundState.openPartnerId == partnerId) ChatForegroundState.openPartnerId = null
        }
    }

    LaunchedEffect(messages.size, connected) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        if (connected && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.markVisibleMessagesRead()
        }
    }

    val imageAlpha = if (darkTheme) 0.42f else 0.78f
    val imageOverlay = if (darkTheme) Color.Black.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatProfileAvatar(
                            name = conversationName,
                            avatarPath = partnerAvatarPath,
                            modifier = Modifier.size(42.dp)
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = conversationName.ifBlank { stringResource(R.string.chat_private_chat) },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val statusText = when {
                                !connected -> stringResource(R.string.chat_connecting)
                                partnerOnline -> stringResource(R.string.chat_online)
                                partnerLastSeen > 0L -> stringResource(
                                    R.string.chat_last_seen,
                                    formatLastSeen(partnerLastSeen)
                                )
                                else -> stringResource(R.string.chat_offline)
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connected && partnerOnline) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.disconnect(); onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_back))
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Text(
                            text = if (darkTheme) "☀" else "☾",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Box {
                        IconButton(onClick = { languageMenuExpanded = true }) {
                            Text("A/文", color = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false }
                        ) {
                            listOf(
                                LanguageCatalog.ARABIC,
                                LanguageCatalog.RUSSIAN,
                                LanguageCatalog.findByCode("en") ?: Language("en", "English")
                            ).forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(language.displayName) },
                                    onClick = {
                                        viewModel.setTranslationTargetLanguage(language)
                                        languageMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (darkTheme) 0.72f else 0.42f)
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.sigma_chat_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = imageAlpha
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(imageOverlay)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                error?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val mine = message.senderId == viewModel.ownSenderId
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                modifier = Modifier.widthIn(max = 300.dp),
                                shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp),
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                shadowElevation = 1.dp
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (mine) {
                                            Text(
                                                text = when (message.deliveryStatus) {
                                                    MessageDeliveryStatus.PENDING -> " · …"
                                                    MessageDeliveryStatus.SENT -> " · ✓"
                                                    MessageDeliveryStatus.DELIVERED -> " · ✓✓"
                                                    MessageDeliveryStatus.READ -> " · ✓✓"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (message.deliveryStatus == MessageDeliveryStatus.READ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.chat_message)) },
                            singleLine = true,
                            enabled = connected,
                            shape = RoundedCornerShape(22.dp)
                        )
                        IconButton(
                            onClick = {
                                val text = input.trim()
                                if (text.isNotEmpty()) {
                                    viewModel.send(text)
                                    input = ""
                                }
                            },
                            enabled = connected && input.isNotBlank(),
                            modifier = Modifier.padding(start = 2.dp).size(48.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = CircleShape,
                                color = if (connected && input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Send,
                                        contentDescription = stringResource(R.string.chat_send_message),
                                        tint = if (connected && input.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLastSeen(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
