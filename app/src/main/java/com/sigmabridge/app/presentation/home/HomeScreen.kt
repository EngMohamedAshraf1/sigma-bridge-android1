package com.sigmabridge.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The platform hub. Renders [FEATURE_TILES] as a grid — it has no idea how
 * many modes exist or which ones are enabled; that's all data, not layout.
 * Today: one enabled tile (Voice Bridge) + three "coming soon" tiles.
 * Later: OCR/Photos/PDF flip isEnabled = true and gain a real destination
 * in SigmaBridgeNavGraph. This screen does not change either time.
 */
@Composable
fun HomeScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sigma Bridge") })
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(FEATURE_TILES) { tile ->
                FeatureTileCard(tile)
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
