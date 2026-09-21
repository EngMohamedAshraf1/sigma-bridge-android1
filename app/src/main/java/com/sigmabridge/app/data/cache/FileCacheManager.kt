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
 * Temporary Telegram voice/audio files live in app-private filesDir rather than cacheDir.
 * Android may evict cacheDir contents at any time; a translation job must not lose
 * its input file between download/copy completion and the Gemini read.
 *
 * Files remain explicitly temporary because callers delete them in their existing
 * finally blocks, while cleanup() removes leftovers.
 */
@Singleton
class FileCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) : CacheManager {

    private val voiceCacheDir: File by lazy {
        File(context.filesDir, VOICE_SUBDIR).apply { mkdirs() }
    }

    override fun createTempVoice(mimeType: String): TemporaryVoiceFile {
        val id = UUID.randomUUID().toString()
        val file = File(voiceCacheDir, "$id.${extensionForMimeType(mimeType)}")
        check(file.createNewFile()) {
            "Could not create temporary voice file: " + file.absolutePath
        }
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
