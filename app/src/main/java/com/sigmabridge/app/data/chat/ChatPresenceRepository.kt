package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ChatPresencePayload(
    val publicId: String,
    val onlineAt: Long
)

@Singleton
class ChatPresenceRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    fun observe(
        channelName: String,
        ownPublicId: String,
        blockUntilJoined: Boolean = true
    ): Flow<ChatPresencePayload> {
        val topic = channelName.trim()
        if (topic.isBlank() || ownPublicId.isBlank()) return emptyFlow()

        val channel = supabase.realtime.createChannel(topic) {
            presence {
                key = ownPublicId
            }
        }
        val changes = channel.presenceChangeFlow()

        return kotlinx.coroutines.flow.flow {
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

                changes.collect { action ->
                    action.decodeJoinsAs<ChatPresencePayload>()
                        .filter { it.publicId != ownPublicId }
                        .forEach { emit(it) }
                }
            } finally {
                runCatching { channel.untrack() }
                runCatching { channel.leave() }
            }
        }
    }
}
