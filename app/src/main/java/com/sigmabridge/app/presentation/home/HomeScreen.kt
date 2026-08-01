package com.sigmabridge.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.domain.model.ServiceHealth

@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit = {},
    onGeminiTestClick: () -> Unit = {},
    onBridgeControlClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val health by viewModel.health.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sigma Bridge") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            HealthSection(health)

            Text(
                text = "Features",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(FEATURE_TILES) { tile ->
                    FeatureTileCard(tile)
                }
            }

            // Internal-only, not a feature tile: validates Gemini in isolation (Phase 5),
            // ahead of the full Telegram pipeline (Phase 6).
            TextButton(onClick = onGeminiTestClick) {
                Text("Gemini Test (internal)")
            }

            // No longer a placeholder: this is the real control surface for
            // BridgeForegroundService as of Phase 7. Still not a feature tile —
            // that's Phase 9 UI polish territory.
            TextButton(onClick = onBridgeControlClick) {
                Text("Bridge Control")
            }
        }
    }
}

@Composable
private fun HealthSection(items: List<ServiceHealth>) {
    Column {
        Text(text = "Status", style = MaterialTheme.typography.titleMedium)
        items.forEach { health ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(text = health.component.name, modifier = Modifier.padding(end = 8.dp))
                Text(text = "\u2014 ${health.status.name}")
            }
        }
    }
}

@Composable
private fun FeatureTileCard(tile: FeatureTile) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (tile.isEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = tile.title, style = MaterialTheme.typography.titleMedium)
            Text(text = tile.subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
