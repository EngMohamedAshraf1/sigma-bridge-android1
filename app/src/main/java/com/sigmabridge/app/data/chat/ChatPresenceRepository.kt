package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeJoinsAs
import io.github.jan.supabase.realtime.decodeLeavesAs
import io.github.jan.supabase.realtime.presenceChangeFlow
import io.github.jan.supabase.realtime.track
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ChatPresencePayload(
    val publicId: String,
    val onlineAt: Long
)

data class ChatPresenceUpdate(
    val publicId: String,
    val online: Boolean,
    val timestamp: Long
)

@Singleton
class ChatPresenceRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    fun observe(
        channelName: String,
        ownPublicId: String,
        blockUntilJoined: Boolean = true
    ): Flow<ChatPresenceUpdate> {
        val topic = channelName.trim()
        if (topic.isBlank() || ownPublicId.isBlank()) return emptyFlow()

        val channel = supabase.channel(topic) {
            presence {
                key = ownPublicId
            }
        }
        val changes = channel.presenceChangeFlow()

        return channelFlow {
            val collector = launch {
                changes.collect { action ->
                    action.decodeJoinsAs<ChatPresencePayload>()
                        .filter { it.publicId != ownPublicId }
                        .forEach { payload ->
                            send(
                                ChatPresenceUpdate(
                                    publicId = payload.publicId,
                                    online = true,
                                    timestamp = payload.onlineAt
                                )
                            )
                        }

                    action.decodeLeavesAs<ChatPresencePayload>()
                        .filter { it.publicId != ownPublicId }
                        .forEach { payload ->
                            send(
                                ChatPresenceUpdate(
                                    publicId = payload.publicId,
                                    online = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                }
            }

            try {
                channel.subscribe(blockUntilSubscribed = blockUntilJoined)
                channel.track(
                    ChatPresencePayload(
                        publicId = ownPublicId,
                        onlineAt = System.currentTimeMillis()
                    )
                )
                awaitCancellation()
            } finally {
                collector.cancel()
                runCatching { channel.untrack() }
                runCatching { channel.unsubscribe() }
            }
        }
    }
}
