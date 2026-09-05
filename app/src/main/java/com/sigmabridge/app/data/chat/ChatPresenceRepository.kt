package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
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

        val channel = supabase.realtime.createChannel(topic) {
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
                supabase.realtime.connect()
                if (blockUntilJoined) {
                    channel.join(blockUntilJoined = true)
                } else {
                    channel.join()
                }
                channel.track(
                    ChatPresencePayload(
                        publicId = ownPublicId,
                        onlineAt = System.currentTimeMillis()
                    )
                )
            } finally {
                collector.cancel()
                runCatching { channel.untrack() }
                runCatching { channel.leave() }
            }
        }
    }
}
