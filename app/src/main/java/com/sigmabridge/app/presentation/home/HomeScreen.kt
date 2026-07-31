package com.sigmabridge.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sigmabridge.app.domain.model.HealthComponent
import com.sigmabridge.app.domain.model.HealthStatus
import com.sigmabridge.app.domain.model.ServiceHealth

/**
 * Hard-coded UNKNOWN placeholders — Phase 2 has no real Telegram/Gemini/
 * connectivity/service checks yet. The point of listing them now is that
 * HealthSection and this list shape (component + status + detail) are
 * exactly what Phase 3/5/7/8 will emit for real; only the source of the
 * values changes later, not the UI that renders them.
 */
private val PLACEHOLDER_HEALTH: List<ServiceHealth> = listOf(
    ServiceHealth(HealthComponent.TELEGRAM, HealthStatus.UNKNOWN, "Not connected yet"),
    ServiceHealth(HealthComponent.GEMINI, HealthStatus.UNKNOWN, "Not checked yet"),
    ServiceHealth(HealthComponent.INTERNET, HealthStatus.UNKNOWN, "Not checked yet"),
    ServiceHealth(HealthComponent.BRIDGE_SERVICE, HealthStatus.DISABLED, "Not implemented yet (Phase 7)")
)

@Composable
fun HomeScreen(onSettingsClick: () -> Unit = {}) {
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
            HealthSection(PLACEHOLDER_HEALTH)

            Text(
                text = "Features",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(FEATURE_TILES) { tile ->
                    FeatureTileCard(tile)
                }
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
