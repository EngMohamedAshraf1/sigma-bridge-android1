package com.sigmabridge.app.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val result by viewModel.searchResult.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val error by viewModel.error.collectAsState()

    var myUsername by remember(viewModel.myUsername) { mutableStateOf(viewModel.myUsername) }
    var search by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Private Chat") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Text("Your public username", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = myUsername,
                    onValueChange = { myUsername = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = { Text("@") }
                )
                Button(onClick = { viewModel.saveUsername(myUsername) }) { Text("Save") }
            }
            Text("Anyone can find you by this username.", style = MaterialTheme.typography.bodySmall)

            Text("Search users", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("@username") }
                )
                Button(onClick = { viewModel.search(search) }, enabled = search.isNotBlank() && !searching) {
                    Text(if (searching) "…" else "Search")
                }
            }

            result?.let { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("@${profile.username}", style = MaterialTheme.typography.titleMedium)
                            Text(profile.userId, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { viewModel.startChat(profile) }) { Text("Chat") }
                    }
                }
            }

            Text(
                text = when {
                    connected -> "@${viewModel.partner?.username ?: ""} • Connected • encrypted"
                    viewModel.partner != null -> "@${viewModel.partner?.username}"
                    else -> "No chat selected"
                },
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val mine = message.senderId == viewModel.ownSenderId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                        ) {
                            Text(message.text, modifier = Modifier.fillMaxWidth(), textAlign = if (mine) TextAlign.End else TextAlign.Start)
                            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)), style = MaterialTheme.typography.labelSmall)
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
