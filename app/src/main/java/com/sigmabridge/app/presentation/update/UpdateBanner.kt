package com.sigmabridge.app.presentation.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sigmabridge.app.R
import com.sigmabridge.app.data.update.UpdateCheckResult

@Composable
fun UpdateBanner(
    update: UpdateCheckResult,
    downloading: Boolean,
    installing: Boolean,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.update_available_banner, update.latestVersion),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onUpdateClick,
                enabled = !downloading && !installing,
                content = {
                    if (downloading || installing) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.update_now))
                    }
                }
            )
        }
    }
}
