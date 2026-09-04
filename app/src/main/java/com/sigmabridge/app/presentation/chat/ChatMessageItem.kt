package com.sigmabridge.app.presentation.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.ChatReaction
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val REACTION_OPTIONS = listOf("❤️", "😂", "👍", "😢", "😡", "😍", "🔥")

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    reactions: List<ChatReaction>,
    isMine: Boolean,
    ownUserId: String,
    reactionPickerVisible: Boolean,
    onLongPress: () -> Unit,
    onReaction: (String) -> Unit
) {
    val background = if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            if (reactionPickerVisible) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(REACTION_OPTIONS) { emoji ->
                        FilterChip(
                            selected = reactions.any { it.userId == ownUserId && it.emoji == emoji },
                            onClick = { onReaction(emoji) },
                            label = { Text(emoji) }
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .pointerInput(message.id) {
                        detectTapGestures(onLongPress = { onLongPress() })
                    },
                colors = CardDefaults.cardColors(containerColor = background)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = if (isMine) TextAlign.End else TextAlign.Start
                    )
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isMine) {
                            val receiptText = when (message.deliveryStatus) {
                                MessageDeliveryStatus.PENDING -> "• Sending…"
                                MessageDeliveryStatus.SENT -> "✓"
                                MessageDeliveryStatus.DELIVERED -> "✓✓"
                                MessageDeliveryStatus.READ -> "✓✓"
                            }
                            val receiptColor = if (message.deliveryStatus == MessageDeliveryStatus.READ) {
                                Color(0xFF0084FF)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                receiptText,
                                style = MaterialTheme.typography.labelSmall,
                                color = receiptColor
                            )
                        }
                    }
                }
            }

            val grouped = reactions.groupingBy { it.emoji }.eachCount()
            if (grouped.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(grouped.entries.toList()) { (emoji, count) ->
                        FilterChip(
                            selected = reactions.any { it.userId == ownUserId && it.emoji == emoji },
                            onClick = { onReaction(emoji) },
                            label = { Text("$emoji $count") }
                        )
                    }
                }
            }
        }
    }
}
