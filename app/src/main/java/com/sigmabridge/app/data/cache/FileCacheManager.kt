package com.sigmabridge.app.data.cache

import android.content.Context
import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.model.TemporaryVoiceFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filenames are always freshly generated UUIDs, never Telegram file_ids.
 * The extension mirrors the MIME type so local temp files remain internally
 * consistent with the media bytes and Gemini upload metadata.
 */
@Singleton
class FileCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) : CacheManager {

    private val voiceCacheDir: File by lazy {
        File(context.cacheDir, VOICE_SUBDIR).apply { mkdirs() }
    }

    override fun createTempVoice(mimeType: String): TemporaryVoiceFile {
        val id = UUID.randomUUID().toString()
        val file = File(voiceCacheDir, "$id.${extensionForMimeType(mimeType)}")
        return TemporaryVoiceFile(id = id, path = file.absolutePath, mimeType = mimeType)
    }

    override fun delete(file: TemporaryVoiceFile) {
        File(file.path).delete()
    }

    override fun cleanup() {
        voiceCacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun extensionForMimeType(mimeType: String): String = when (mimeType) {
        "audio/mp3" -> "mp3"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/flac" -> "flac"
        "audio/wav" -> "wav"
        "audio/aiff" -> "aiff"
        else -> "bin"
    }

    private companion object {
        const val VOICE_SUBDIR = "voice_tmp"
    }
}
