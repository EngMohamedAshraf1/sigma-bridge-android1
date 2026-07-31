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
 * Filenames are always a freshly generated UUID, never Telegram's file_id —
 * so nothing sitting in local temp storage can be correlated back to a
 * specific Telegram file or message just by looking at the filename.
 */
@Singleton
class FileCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) : CacheManager {

    private val voiceCacheDir: File by lazy {
        File(context.cacheDir, VOICE_SUBDIR).apply { mkdirs() }
    }

    override fun createTempVoice(): TemporaryVoiceFile {
        val id = UUID.randomUUID().toString()
        val file = File(voiceCacheDir, "$id.ogg")
        return TemporaryVoiceFile(id = id, path = file.absolutePath)
    }

    override fun delete(file: TemporaryVoiceFile) {
        File(file.path).delete()
    }

    override fun cleanup() {
        voiceCacheDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val VOICE_SUBDIR = "voice_tmp"
    }
}
