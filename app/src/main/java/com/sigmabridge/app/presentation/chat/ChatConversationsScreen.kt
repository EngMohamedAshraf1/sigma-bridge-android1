package com.sigmabridge.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.data.chat.ChatProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatConversationsScreen(
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
                title = { Text("Private Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Home")
                    }
                },
                actions = {
                    IconButton(onClick = { showProfile = true }) {
                        Icon(Icons.Filled.Person, contentDescription = "My profile")
                    }
                    IconButton(onClick = {
                        viewModel.clearSearch()
                        showNewChat = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (conversations.isEmpty()) "No private conversations yet" else "No matching conversations",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (conversations.isEmpty()) {
                            Text(
                                text = "Search for someone by username to start a chat.",
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { showNewChat = true }, modifier = Modifier.padding(top = 16.dp)) {
                                Text("Find a person")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
            }
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
    onSave: (String, String, String) -> Unit
) {
    var firstName by remember(profile) { mutableStateOf(profile?.firstName.orEmpty()) }
    var lastName by remember(profile) { mutableStateOf(profile?.lastName.orEmpty()) }
    var username by remember(profile) { mutableStateOf(profile?.username.orEmpty()) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("My profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("First name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last name") },
                    singleLine = true
                )
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
            Button(
                onClick = { onSave(firstName, lastName, username) },
                enabled = !busy && username.length >= 3
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }
    )
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
        title = { Text("Find someone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it.removePrefix("@").lowercase(Locale.ROOT)
                        onSearch(query)
                    },
                    label = { Text("Username") },
                    placeholder = { Text("@username") },
                    prefix = { Text("@") },
                    singleLine = true
                )

                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                results.forEach { person ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(person) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(person.displayName, style = MaterialTheme.typography.titleMedium)
                            person.username?.let { Text("@$it", color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }

                if (!busy && query.length >= 2 && results.isEmpty() && error == null) {
                    Text("No users found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ConversationRow(
    row: ChatConversationRow,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(row.conversation.displayName) }
    val conversation = row.conversation

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Card(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = conversation.displayName.take(1).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (conversation.lastMessageAt > 0L) {
                        Text(
                            text = formatConversationTime(conversation.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(modifier = Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.lastMessage.ifBlank { "Private chat" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.unreadCount > 0) {
                        Card(
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(
                                text = row.unreadCount.coerceAtMost(99).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Conversation menu")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            renameText = conversation.displayName
                            showRename = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRename(renameText)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRename = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatConversationTime(timeMillis: Long): String {
    val date = Date(timeMillis)
    val now = Date()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date) ==
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
    return if (sameDay) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("dd/MM", Locale.getDefault()).format(date)
    }
}
