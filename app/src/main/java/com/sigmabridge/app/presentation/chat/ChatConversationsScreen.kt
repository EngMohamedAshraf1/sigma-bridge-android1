package com.sigmabridge.app.presentation.chat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.BuildConfig
import com.sigmabridge.app.data.chat.ChatProfile
import com.sigmabridge.app.service.ChatNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun ChatConversationsScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit
) {
    val accountViewModel: ChatAccountViewModel = hiltViewModel()
    val accountState by accountViewModel.state.collectAsState()
    val context = LocalContext.current

    if (!accountState.authenticated) {
        ChatAccountScreen(
            state = accountState,
            onContinueWithGoogle = {
                context.findActivity()?.let(accountViewModel::signInWithGoogle)
                    ?: accountViewModel.reportError("تعذر فتح شاشة تسجيل الدخول. حاول مرة أخرى.")
            }
        )
        return
    }

    LaunchedEffect(accountState.authenticated) {
        ContextCompat.startForegroundService(context, ChatNotificationService.startIntent(context))
    }

    ChatConversationsContent(onBack = onBack, onOpenChat = onOpenChat)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatConversationsContent(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: ChatConversationsViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val profileBusy by viewModel.profileBusy.collectAsState()
    val profileError by viewModel.profileError.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchBusy by viewModel.searchBusy.collectAsState()
    val searchError by viewModel.searchError.collectAsState()

    var showProfile by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val filtered = remember(conversations, search) {
        val query = search.trim()
        if (query.isBlank()) conversations
        else conversations.filter {
            it.conversation.displayName.contains(query, ignoreCase = true) ||
                it.conversation.partnerId.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Private Chat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showProfile = true }) {
                        RemoteChatAvatar(
                            name = profile?.displayName?.ifBlank { profile?.username.orEmpty() }.orEmpty(),
                            avatarPath = profile?.avatarPath,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = {
                        viewModel.clearSearch()
                        showNewChat = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .height(52.dp),
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                )
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (conversations.isEmpty()) "No conversations yet" else "No results",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (conversations.isEmpty()) {
                            Text(
                                text = "Start a private chat with a username.",
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showNewChat = true },
                                modifier = Modifier.padding(top = 16.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text("New chat")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(filtered, key = { it.conversation.partnerId }) { row ->
                        ConversationRow(
                            row = row,
                            onClick = {
                                viewModel.openConversation(row.conversation)
                                onOpenChat()
                            },
                            onRename = { name -> viewModel.rename(row.conversation, name) },
                            onDelete = { viewModel.delete(row.conversation) }
                        )
                    }
                }
            }
        }
    }

    if (showProfile) {
        ProfileDialog(
            profile = profile,
            busy = profileBusy,
            error = profileError,
            onDismiss = { showProfile = false },
            onSave = { first, last, username ->
                viewModel.saveProfile(first, last, username) { showProfile = false }
            },
            onUploadAvatar = { bytes, extension -> viewModel.uploadAvatar(bytes, extension) }
        )
    }

    if (showNewChat) {
        NewChatDialog(
            results = searchResults,
            busy = searchBusy,
            error = searchError,
            onDismiss = { showNewChat = false },
            onSearch = viewModel::searchUsers,
            onSelect = { person ->
                if (viewModel.addProfileToConversation(person)) {
                    showNewChat = false
                    onOpenChat()
                }
            }
        )
    }
}

@Composable
private fun ProfileDialog(
    profile: ChatProfile?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onUploadAvatar: (ByteArray, String) -> Unit
) {
    var firstName by remember(profile) { mutableStateOf(profile?.firstName.orEmpty()) }
    var lastName by remember(profile) { mutableStateOf(profile?.lastName.orEmpty()) }
    var username by remember(profile) { mutableStateOf(profile?.username.orEmpty()) }
    var editingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        if (bytes.isEmpty()) return@rememberLauncherForActivityResult
        val bitmap = decodeAvatarBitmap(bytes)
        if (bitmap != null) editingBitmap = bitmap
    }

    AlertDialog(
        onDismissRequest = { if (!busy && editingBitmap == null) onDismiss() },
        title = { Text("My profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteChatAvatar(
                    name = profile?.displayName?.ifBlank { profile?.username.orEmpty() }.orEmpty(),
                    avatarPath = profile?.avatarPath,
                    modifier = Modifier.size(96.dp)
                )
                OutlinedButton(onClick = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }, enabled = !busy) {
                    Text("Choose photo")
                }
                OutlinedTextField(value = firstName, onValueChange = { firstName = it }, label = { Text("First name") }, singleLine = true)
                OutlinedTextField(value = lastName, onValueChange = { lastName = it }, label = { Text("Last name") }, singleLine = true)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.lowercase(Locale.ROOT).replace(" ", "_") },
                    label = { Text("Username") },
                    placeholder = { Text("example_name") },
                    prefix = { Text("@") },
                    singleLine = true,
                    supportingText = { Text("3–24: lowercase English letters, numbers and _") }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(firstName, lastName, username) }, enabled = !busy && username.length >= 3) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Close") } }
    )

    editingBitmap?.let { bitmap ->
        AvatarEditorDialog(
            source = bitmap,
            onDismiss = { editingBitmap = null },
            onApply = { editedBitmap ->
                val output = ByteArrayOutputStream()
                val compressed = editedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                if (compressed) {
                    onUploadAvatar(output.toByteArray(), "jpg")
                    editingBitmap = null
                }
                editedBitmap.recycle()
            }
        )
    }
}

@Composable
private fun AvatarEditorDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onApply: (Bitmap) -> Unit
) {
    var zoom by remember(source) { mutableStateOf(1f) }
    var rotation by remember(source) { mutableStateOf(0f) }
    var panX by remember(source) { mutableStateOf(0f) }
    var panY by remember(source) { mutableStateOf(0f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Adjust photo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Move, zoom and rotate before setting your profile photo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Box(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .size(290.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                                panX += pan.x
                                panY += pan.y
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = source.asImageBitmap(),
                        contentDescription = "Avatar preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panX
                                translationY = panY
                                rotationZ = rotation
                            },
                        contentScale = ContentScale.Crop
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Zoom", modifier = Modifier.padding(end = 8.dp), fontWeight = FontWeight.Medium)
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 1f..4f,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { rotation = (rotation + 270f) % 360f }) {
                        Text("↶ 90°")
                    }
                    Spacer(modifier = Modifier.size(10.dp))
                    OutlinedButton(onClick = { rotation = (rotation + 90f) % 360f }) {
                        Text("90° ↷")
                    }
                    Spacer(modifier = Modifier.size(10.dp))
                    TextButton(onClick = {
                        zoom = 1f
                        rotation = 0f
                        panX = 0f
                        panY = 0f
                    }) {
                        Text("Reset")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(onClick = {
                        val edited = renderAvatar(source, zoom, rotation, panX, panY, 290f)
                        onApply(edited)
                    }) {
                        Text("Set photo")
                    }
                }
            }
        }
    }
}

private fun decodeAvatarBitmap(bytes: ByteArray): Bitmap? {
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
}

private fun renderAvatar(
    source: Bitmap,
    zoom: Float,
    rotation: Float,
    panX: Float,
    panY: Float,
    previewSizePx: Float
): Bitmap {
    val outputSize = 512
    val result = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawColor(android.graphics.Color.TRANSPARENT)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val baseScale = max(previewSizePx / source.width.toFloat(), previewSizePx / source.height.toFloat())
    val scale = baseScale * zoom * (outputSize / previewSizePx)
    val tx = outputSize / 2f + panX * (outputSize / previewSizePx)
    val ty = outputSize / 2f + panY * (outputSize / previewSizePx)

    canvas.save()
    canvas.translate(tx, ty)
    canvas.rotate(rotation)
    canvas.scale(scale, scale)
    canvas.drawBitmap(source, -source.width / 2f, -source.height / 2f, paint)
    canvas.restore()

    return result
}

@Composable
private fun RemoteChatAvatar(
    name: String,
    avatarPath: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val url by produceState<String?>(initialValue = null, avatarPath) {
        value = withContext(Dispatchers.IO) {
            avatarPath?.let { path ->
                runCatching {
                    "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/chat_avatars/$path"
                }.getOrNull()
            }
        }
    }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            url?.let {
                runCatching { URL(it).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
            }
        }
    }

    Box(
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ConversationRow(
    row: ChatConversationRow,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var rename by remember(row.conversation.partnerId) { mutableStateOf(row.conversation.displayName) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteChatAvatar(
            name = row.conversation.displayName,
            avatarPath = row.conversation.avatarPath,
            modifier = Modifier.size(52.dp)
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = row.conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.lastMessage.ifBlank { "No messages yet" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatConversationTime(row.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.unreadCount > 0) {
                Text(
                    text = row.unreadCount.toString(),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        renameOpen = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = rename,
                    onValueChange = { rename = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRename(rename.trim().ifBlank { row.conversation.displayName })
                    renameOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } }
        )
    }
}

private fun formatConversationTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun NewChatDialog(
    results: List<ChatProfile>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (ChatProfile) -> Unit
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New private chat") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    singleLine = true,
                    label = { Text("Search username") },
                    prefix = { Text("@") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(18.dp))
                }
                error?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    results.take(8).forEach { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(person) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RemoteChatAvatar(
                                name = person.displayName.ifBlank { person.username },
                                avatarPath = person.avatarPath,
                                modifier = Modifier.size(42.dp)
                            )
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(person.displayName.ifBlank { person.username }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "@${person.username}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
