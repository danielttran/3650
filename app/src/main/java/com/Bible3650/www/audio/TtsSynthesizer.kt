package com.Bible3650.www.audio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.net.toUri
import com.Bible3650.www.data.local.BibleTextDao
import com.Bible3650.www.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Renders a stored chapter's text to a cached WAV via Android TTS, so the existing ExoPlayer
 * pipeline (transport/notification/sleep/speed/scrub) plays text Bibles unchanged. Playback
 * rate is left to ExoPlayer's setPlaybackSpeed, so it is intentionally NOT part of the cache key.
 */
@Singleton
class TtsSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textDao: BibleTextDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var voiceTag: String = "default"
    private val initMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val utteranceCounter = AtomicInteger(0)

    private val cacheDir: File by lazy { File(context.cacheDir, "tts").apply { mkdirs() } }

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing

    private suspend fun ensureReady(): Boolean = initMutex.withLock {
        if (ready) return true
        val ok = suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                val success = status == TextToSpeech.SUCCESS
                if (success) {
                    val e = engine
                    val res = e?.setLanguage(Locale.getDefault())
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        e?.setLanguage(Locale.US)
                    }
                    voiceTag = (e?.voice?.name ?: e?.defaultEngine ?: "default")
                        .replace(Regex("[^A-Za-z0-9]"), "")
                        .ifBlank { "default" }
                }
                if (cont.isActive) cont.resume(success)
            }
            tts = engine
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.complete(true) }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) { pending.remove(utteranceId)?.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { pending.remove(utteranceId)?.complete(false) }
            })
        }
        ready = ok
        ok
    }

    /** Returns a cached audio Uri for the chapter, synthesizing it if necessary, or null. */
    suspend fun synthesizeChapter(translationId: Long?, book: String, chapter: Int): Uri? {
        if (translationId == null) return null
        return withContext(ioDispatcher) {
            val outFile = File(cacheDir, cacheFileName(translationId, book, chapter, voiceTag))
            if (outFile.isFile && outFile.length() > WAV_HEADER) {
                outFile.setLastModified(System.currentTimeMillis())
                return@withContext outFile.toUri()
            }
            if (!ensureReady()) return@withContext null
            // Re-evaluate the name after init (voiceTag may have changed) and re-check cache.
            val finalFile = File(cacheDir, cacheFileName(translationId, book, chapter, voiceTag))
            if (finalFile.isFile && finalFile.length() > WAV_HEADER) {
                finalFile.setLastModified(System.currentTimeMillis())
                return@withContext finalFile.toUri()
            }
            val text = textDao.getChapter(translationId, book, chapter)?.text?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            _isSynthesizing.value = true
            try {
                val chunks = chunkText(text, MAX_CHARS)
                val tmp = File(cacheDir, finalFile.name + ".tmp")
                val parts = ArrayList<File>(chunks.size)
                for ((i, c) in chunks.withIndex()) {
                    val part = File(cacheDir, finalFile.name + ".part$i")
                    if (!synthToFile(c, part)) {
                        parts.forEach { it.delete() }; part.delete(); return@withContext null
                    }
                    parts.add(part)
                }
                val combined = if (parts.size == 1) {
                    parts[0].copyTo(tmp, overwrite = true); true
                } else {
                    concatWav(parts, tmp)
                }
                parts.forEach { it.delete() }
                if (!combined || !tmp.isFile || tmp.length() <= WAV_HEADER) { tmp.delete(); return@withContext null }
                tmp.renameTo(finalFile)
                evictIfOverCap()
                if (finalFile.isFile) finalFile.toUri() else null
            } finally {
                _isSynthesizing.value = false
            }
        }
    }

    fun clearCacheForTranslation(translationId: Long) {
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("${translationId}_")) it.delete() }
    }

    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun synthToFile(text: String, file: File): Boolean {
        val engine = tts ?: return false
        val id = "u" + utteranceCounter.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        val params = Bundle()
        val result = engine.synthesizeToFile(text, params, file, id)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return deferred.await()
    }

    private fun evictIfOverCap() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        const val MAX_CHARS = 3500          // under TextToSpeech.getMaxSpeechInputLength() (~4000)
        private const val WAV_HEADER = 44L
        private const val MAX_CACHE_BYTES = 250L * 1024 * 1024

        private fun cacheFileName(translationId: Long, book: String, chapter: Int, voice: String): String {
            val safeBook = book.replace(Regex("[^A-Za-z0-9]"), "")
            return "${translationId}_${safeBook}_${chapter}_${voice}.wav"
        }

        /** Split text into <= [maxChars] pieces, preferring sentence then word boundaries. */
        fun chunkText(text: String, maxChars: Int): List<String> {
            val trimmed = text.trim()
            if (trimmed.length <= maxChars) return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
            val out = ArrayList<String>()
            var rest = trimmed
            while (rest.length > maxChars) {
                val window = rest.substring(0, maxChars)
                var cut = maxOf(
                    window.lastIndexOf('.'),
                    window.lastIndexOf('!'),
                    window.lastIndexOf('?')
                )
                if (cut <= 0) cut = window.lastIndexOf(' ')
                if (cut <= 0) cut = maxChars - 1
                out.add(rest.substring(0, cut + 1).trim())
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }

        // ---- minimal WAV (RIFF/PCM) concatenation ----

        private data class Wav(val sampleRate: Int, val channels: Int, val bits: Int, val pcm: ByteArray)

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun readWav(file: File): Wav? {
            val bytes = file.readBytes()
            if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            var pos = 12
            var sampleRate = 22050; var channels = 1; var bits = 16
            var pcm: ByteArray? = null
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4)
                val size = le32(bytes, pos + 4)
                val dataStart = pos + 8
                if (size < 0 || dataStart + size > bytes.size) break
                when (chunkId) {
                    "fmt " -> {
                        channels = le16(bytes, dataStart + 2)
                        sampleRate = le32(bytes, dataStart + 4)
                        bits = le16(bytes, dataStart + 14)
                    }
                    "data" -> pcm = bytes.copyOfRange(dataStart, dataStart + size)
                }
                pos = dataStart + size + (size and 1) // chunks are word-aligned
            }
            return pcm?.let { Wav(sampleRate, channels, bits, it) }
        }

        private fun concatWav(parts: List<File>, out: File): Boolean {
            val wavs = parts.mapNotNull { readWav(it) }
            if (wavs.isEmpty()) return false
            val first = wavs[0]
            val body = ByteArrayOutputStream()
            wavs.forEach { body.write(it.pcm) }
            writeWav(out, first.sampleRate, first.channels, first.bits, body.toByteArray())
            return true
        }

        private fun writeWav(out: File, sampleRate: Int, channels: Int, bits: Int, pcm: ByteArray) {
            val byteRate = sampleRate * channels * bits / 8
            val blockAlign = channels * bits / 8
            val dataLen = pcm.size
            val header = ByteArray(44)
            fun putStr(o: Int, s: String) { for (i in s.indices) header[o + i] = s[i].code.toByte() }
            fun put32(o: Int, v: Int) {
                header[o] = (v and 0xFF).toByte()
                header[o + 1] = ((v shr 8) and 0xFF).toByte()
                header[o + 2] = ((v shr 16) and 0xFF).toByte()
                header[o + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun put16(o: Int, v: Int) {
                header[o] = (v and 0xFF).toByte()
                header[o + 1] = ((v shr 8) and 0xFF).toByte()
            }
            putStr(0, "RIFF"); put32(4, 36 + dataLen); putStr(8, "WAVE")
            putStr(12, "fmt "); put32(16, 16); put16(20, 1); put16(22, channels)
            put32(24, sampleRate); put32(28, byteRate); put16(32, blockAlign); put16(34, bits)
            putStr(36, "data"); put32(40, dataLen)
            out.outputStream().use { it.write(header); it.write(pcm) }
        }
    }
}
