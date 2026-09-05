package com.sigmabridge.app.presentation.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.sigmabridge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

@Composable
fun ChatProfileAvatar(
    name: String,
    avatarPath: String?,
    modifier: Modifier
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, avatarPath) {
        value = if (avatarPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/chat_avatars/" + avatarPath
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = name,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.trim().firstOrNull()?.uppercase(Locale.getDefault()) ?: "S",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
