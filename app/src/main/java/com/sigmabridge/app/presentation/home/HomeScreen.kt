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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sigmabridge.app.R
import com.sigmabridge.app.domain.model.HomeHealthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit = {},
    onGeminiTestClick: () -> Unit = {},
    onBridgeControlClick: () -> Unit = {},
    onChatClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val health by viewModel.health.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
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
                text = stringResource(R.string.home_features),
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

            TextButton(onClick = onChatClick) {
                Text(stringResource(R.string.home_private_chat))
            }

            TextButton(onClick = onGeminiTestClick) {
                Text(stringResource(R.string.home_gemini_test))
            }

            TextButton(onClick = onBridgeControlClick) {
                Text(stringResource(R.string.home_bridge_control))
            }
        }
    }
}

@Composable
private fun HealthSection(health: HomeHealthState) {
    Column {
        Text(text = stringResource(R.string.home_status), style = MaterialTheme.typography.titleMedium)
        HealthRow(stringResource(R.string.home_bridge), health.bridge.name)
        HealthRow(stringResource(R.string.home_telegram), health.telegram.name)
        HealthRow(stringResource(R.string.home_gemini), health.gemini.name)
        HealthRow(stringResource(R.string.home_internet), health.internet.name)
    }
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = label, modifier = Modifier.padding(end = 8.dp))
        Text(text = "— $value")
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
            Text(text = stringResource(tile.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(tile.subtitleRes), style = MaterialTheme.typography.bodySmall)
        }
    }
}
