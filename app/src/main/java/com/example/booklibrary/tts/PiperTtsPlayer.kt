package com.example.booklibrary.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.booklibrary.util.SafeLog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PiperTtsPlayer(private val context: Context) {

    companion object {
        private const val TAG = "PiperTtsPlayer"
    }

    data class WordTiming(val charStart: Int, val charEnd: Int, val sampleStart: Int)

    interface Listener {
        fun onUtteranceStart(index: Int, text: String)
        fun onUtteranceDone(index: Int)
        fun onFinished()
        fun onError(error: String)
        fun onAudioStarted(index: Int) {}
        fun onWordHighlight(sentenceIndex: Int, charStart: Int, charEnd: Int) {}
        fun onBuffering(isBuffering: Boolean) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generateMutex = Mutex()
    @Volatile private var offlineTts: OfflineTts? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    @Volatile
    private var isPaused = false
    @Volatile
    private var isStopping = false
    private var speed: Float = 1.0f
    private var speakerId: Int = 0
    var listener: Listener? = null

    @Volatile
    private var currentSentenceIndex = 0
    @Volatile
    private var skipToIndex = -1
    private val sentenceLock = Any()
    private var sentences: MutableList<String> = mutableListOf()

    val isPlaying: Boolean get() = playbackJob?.isActive == true && !isPaused
    val isActive: Boolean get() = playbackJob?.isActive == true
    val isModelLoaded: Boolean get() = offlineTts != null
    val currentIndex: Int get() = currentSentenceIndex
    val sentenceCount: Int get() = synchronized(sentenceLock) { sentences.size }

    fun loadModel(modelDir: String, modelName: String, dataDir: String) {
        clearIdleResourcesBeforeLoad()

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = "$modelDir/$modelName",
            lexicon = "",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir
        )

        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        )

        speakerId = 0
        offlineTts = OfflineTts(config = config)
        initAudioTrack()
        SafeLog.i(TAG, "Piper model loaded")
    }

    fun loadKokoroModel(modelDir: String, modelName: String, voicesName: String, tokensFile: String, dataDir: String, lexicon: String = "", dictDir: String = "") {
        clearIdleResourcesBeforeLoad()

        val kokoroConfig = OfflineTtsKokoroModelConfig(
            model = "$modelDir/$modelName",
            voices = "$modelDir/$voicesName",
            tokens = tokensFile,
            dataDir = dataDir,
            lexicon = lexicon,
            dictDir = dictDir
        )

        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = kokoroConfig,
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        )

        offlineTts = OfflineTts(config = config)
        initAudioTrack()
        SafeLog.i(TAG, "Kokoro model loaded")
    }

    private fun clearIdleResourcesBeforeLoad() {
        if (playbackJob?.isActive == true || stopJob?.isActive == true) {
            throw IllegalStateException("Release player before loading a new model")
        }

        isStopping = false
        isPaused = false
        skipToIndex = -1
        synchronized(sentenceLock) { sentences.clear() }
        listener = null

        val tts = offlineTts
        val track = audioTrack
        offlineTts = null
        audioTrack = null

        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        try { tts?.free() } catch (_: Exception) {}
    }

    fun setSpeakerId(sid: Int) {
        speakerId = sid
    }

    private fun initAudioTrack() {
        val sampleRate = offlineTts?.sampleRate() ?: 22050
        var bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (bufLength <= 0) bufLength = sampleRate * 4 // fallback: 1 second buffer

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        audioTrack = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 3.0f)
    }

    fun skipToNext(): Boolean {
        val next = currentSentenceIndex + 1
        if (next >= sentenceCount) return false
        skipToIndex = next
        mainHandler.post { listener?.onBuffering(true) }
        flushAudio()
        return true
    }

    fun skipToPrevious(): Boolean {
        val prev = currentSentenceIndex - 1
        if (prev < 0) return false
        skipToIndex = prev
        mainHandler.post { listener?.onBuffering(true) }
        flushAudio()
        return true
    }

    private fun flushAudio() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            if (!isPaused) audioTrack?.play()
        } catch (_: IllegalStateException) { }
    }

    private suspend fun generateSentence(tts: OfflineTts, text: String): FloatArray {
        return generateMutex.withLock {
            if (isStopping) return@withLock FloatArray(0)
            val callback = object : Function1<FloatArray, Int> {
                override fun invoke(chunk: FloatArray): Int {
                    return if (isStopping || skipToIndex >= 0) 0 else 1
                }
            }
            val audio = tts.generateWithCallback(text, speakerId, speed, callback)
            val samples = audio.samples
            if (samples.isEmpty()) return@withLock samples
            postProcessSamples(samples)
        }
    }

    private fun postProcessSamples(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (s in samples) sum += s
        val dcOffset = (sum / samples.size).toFloat()
        if (kotlin.math.abs(dcOffset) > 0.001f) {
            for (i in samples.indices) samples[i] -= dcOffset
        }
        val sr = offlineTts?.sampleRate() ?: 22050
        val fadeSamples = minOf((sr * 0.008f).toInt(), samples.size / 2)
        for (i in 0 until fadeSamples) {
            val gain = i.toFloat() / fadeSamples
            samples[i] *= gain
            samples[samples.size - 1 - i] *= gain
        }
        return samples
    }

    private suspend fun generateSentenceStreaming(
        tts: OfflineTts,
        text: String,
        onChunk: (FloatArray) -> Unit
    ): FloatArray {
        return generateMutex.withLock {
            val callback = object : Function1<FloatArray, Int> {
                override fun invoke(chunk: FloatArray): Int {
                    if (isStopping) return 0
                    if (chunk.isNotEmpty()) {
                        onChunk(chunk)
                    }
                    return if (skipToIndex >= 0) 0 else 1
                }
            }
            val audio = tts.generateWithCallback(text, speakerId, speed, callback)
            val samples = audio.samples
            if (samples.isEmpty()) return@withLock samples
            postProcessSamples(samples)
        }
    }

    private fun computeWordTimings(text: String, totalSamples: Int): List<WordTiming> {
        if (totalSamples == 0) return emptyList()
        val wordPattern = Regex("\\S+")
        val matches = wordPattern.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        val totalChars = text.length.toFloat()
        return matches.map { match ->
            WordTiming(
                charStart = match.range.first,
                charEnd = match.range.last + 1,
                sampleStart = ((match.range.first / totalChars) * totalSamples).toInt()
            )
        }
    }

    private fun writeSilence(sentence: String) {
        val silenceMs = when {
            sentence.endsWith('.') || sentence.endsWith('!') || sentence.endsWith('?') -> 100
            sentence.endsWith(';') || sentence.endsWith(':') -> 70
            else -> 50
        }
        val sr = offlineTts?.sampleRate() ?: 22050
        val silence = FloatArray((sr * silenceMs / 1000f).toInt())
        try {
            audioTrack?.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) { }
    }

    fun appendSentences(newSentences: List<String>): Int {
        if (newSentences.isEmpty()) return sentenceCount
        return synchronized(sentenceLock) {
            sentences.addAll(newSentences)
            sentences.size
        }
    }

    private fun getSentence(index: Int): String? = synchronized(sentenceLock) {
        sentences.getOrNull(index)
    }

    suspend fun speakSentences(initialSentences: List<String>, startIndex: Int = 0) = coroutineScope {
        synchronized(sentenceLock) {
            sentences = initialSentences.toMutableList()
        }
        val tts = offlineTts ?: run {
            listener?.onError("Model not loaded")
            return@coroutineScope
        }

        playbackJob = launch(Dispatchers.IO) {
            try {
                val initialCount = sentenceCount
                if (initialCount == 0) {
                    withContext(Dispatchers.Main) { listener?.onFinished() }
                    return@launch
                }
                currentSentenceIndex = startIndex.coerceIn(0, initialCount - 1)
                skipToIndex = -1
                isStopping = false
                var audioStarted = false
                var audioPreparing = false
                var pregenSamples: FloatArray? = null
                var pregenIndex = -1

                fun notifyAudioReady(index: Int) {
                    val shouldClearBuffering = audioPreparing
                    val shouldReportAudioStarted = !audioStarted
                    if (!shouldClearBuffering && !shouldReportAudioStarted) return
                    audioPreparing = false
                    if (shouldReportAudioStarted) audioStarted = true
                    mainHandler.post {
                        if (shouldClearBuffering) listener?.onBuffering(false)
                        if (shouldReportAudioStarted || shouldClearBuffering) listener?.onAudioStarted(index)
                    }
                }

                while (currentSentenceIndex < sentenceCount) {
                    if (isStopping) break
                    if (offlineTts == null) break

                    val skip = skipToIndex
                    if (skip >= 0) {
                        skipToIndex = -1
                        currentSentenceIndex = skip.coerceIn(0, sentenceCount - 1)
                        audioPreparing = true
                        pregenSamples = null
                        pregenIndex = -1
                        flushAudio()
                        continue
                    }

                    val sentence = getSentence(currentSentenceIndex) ?: break
                    if (sentence.isBlank()) {
                        currentSentenceIndex++
                        continue
                    }

                    val sentIdx = currentSentenceIndex
                    withContext(Dispatchers.Main) {
                        listener?.onUtteranceStart(sentIdx, sentence)
                    }

                    val currentTts = offlineTts ?: break

                    if (pregenSamples != null && pregenIndex == sentIdx) {
                        // We have pre-generated audio -- play it while pre-generating next
                        val samples = pregenSamples!!
                        pregenSamples = null
                        pregenIndex = -1

                        if (!audioStarted) {
                            audioTrack?.play()
                        }
                        notifyAudioReady(sentIdx)

                        // Find next non-blank sentence to pre-generate
                        var nextIdx = sentIdx + 1
                        while (nextIdx < sentenceCount && getSentence(nextIdx).isNullOrBlank()) nextIdx++

                        // Launch pre-generation in parallel with playback
                        val pregenJob = if (nextIdx < sentenceCount && skipToIndex < 0 && !isStopping) {
                            val nextText = getSentence(nextIdx)
                            val nextI = nextIdx
                            if (nextText != null) {
                                async(Dispatchers.IO) {
                                    try {
                                        val nextTts = offlineTts ?: return@async
                                        val nextSamples = generateSentence(nextTts, nextText)
                                        if (skipToIndex < 0) {
                                            pregenSamples = nextSamples
                                            pregenIndex = nextI
                                        }
                                    } catch (_: Exception) { }
                                }
                            } else null
                        } else null

                        // Play current samples (blocking writes overlap with pre-gen)
                        val chunkSize = 4096
                        var offset = 0
                        while (offset < samples.size) {
                            if (isStopping || skipToIndex >= 0) break
                            while (isPaused && !isStopping) {
                                kotlinx.coroutines.delay(100)
                            }
                            if (isStopping) break
                            val len = minOf(chunkSize, samples.size - offset)
                            try {
                                audioTrack?.write(samples, offset, len, AudioTrack.WRITE_BLOCKING) ?: break
                            } catch (_: Exception) { break }
                            offset += len
                        }

                        // Wait for pre-gen to finish if playback was short
                        pregenJob?.await()

                    } else {
                        // No pre-generated audio (first sentence or after skip): stream it
                        pregenSamples = null
                        pregenIndex = -1
                        val generateStart = SystemClock.elapsedRealtime()
                        SafeLog.d(TAG, "TTS sentence generation started")

                        generateSentenceStreaming(currentTts, sentence) { chunk ->
                            if (!audioStarted) {
                                val now = SystemClock.elapsedRealtime()
                                SafeLog.d(TAG, "TTS sentence first chunk delayMs=${now - generateStart}")
                            }
                            if (!audioStarted) {
                                audioTrack?.play()
                            }
                            notifyAudioReady(sentIdx)
                            try {
                                audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                            } catch (_: Exception) { }
                        }

                        // After streaming finishes, pre-generate next sentence
                        if (!isStopping) {
                            var nextIdx = sentIdx + 1
                            while (nextIdx < sentenceCount && getSentence(nextIdx).isNullOrBlank()) nextIdx++
                            if (nextIdx < sentenceCount && skipToIndex < 0) {
                                val nextTts = offlineTts
                                val nextSentence = getSentence(nextIdx)
                                if (nextTts != null && nextSentence != null) {
                                    try {
                                        pregenSamples = generateSentence(nextTts, nextSentence)
                                        pregenIndex = nextIdx
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                    }

                    if (isStopping) break
                    if (skipToIndex >= 0) continue

                    writeSilence(sentence)

                    withContext(Dispatchers.Main) {
                        listener?.onUtteranceDone(sentIdx)
                    }
                    currentSentenceIndex++
                }

                try { audioTrack?.stop() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    listener?.onFinished()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SafeLog.e(TAG, "Playback error", e)
                withContext(Dispatchers.Main) {
                    listener?.onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun pause() {
        isPaused = true
        audioTrack?.pause()
    }

    fun resume() {
        isPaused = false
        try { audioTrack?.play() } catch (_: IllegalStateException) {}
    }

    private var stopJob: Job? = null

    private suspend fun joinStopWork(job: Job?) {
        try {
            withTimeoutOrNull(10_000) { job?.join() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun stop() {
        isStopping = true
        isPaused = false
        skipToIndex = -1
        val job = playbackJob
        playbackJob = null
        synchronized(sentenceLock) { sentences.clear() }
        listener = null
        stopJob = CoroutineScope(Dispatchers.IO).launch {
            joinStopWork(job)
            isStopping = false
            try { audioTrack?.pause() } catch (_: Exception) { }
            try { audioTrack?.flush() } catch (_: Exception) { }
        }
    }

    suspend fun stopAndWait() {
        isStopping = true
        isPaused = false
        skipToIndex = -1
        val job = playbackJob
        val pendingStop = stopJob
        playbackJob = null
        stopJob = null
        synchronized(sentenceLock) { sentences.clear() }
        listener = null

        withContext(Dispatchers.IO) {
            joinStopWork(pendingStop)
            joinStopWork(job)
            isStopping = false
            try { audioTrack?.pause() } catch (_: Exception) { }
            try { audioTrack?.flush() } catch (_: Exception) { }
        }
    }

    fun release() {
        isStopping = true
        isPaused = false
        skipToIndex = -1
        val job = playbackJob
        val pendingStop = stopJob
        playbackJob = null
        stopJob = null
        synchronized(sentenceLock) { sentences.clear() }
        listener = null

        CoroutineScope(Dispatchers.IO).launch {
            // Wait for any pending stop() to finish first
            joinStopWork(pendingStop)
            joinStopWork(job)
            // NOW safe to free native resources
            val tts = offlineTts
            val track = audioTrack
            offlineTts = null
            audioTrack = null
            isStopping = false
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { tts?.free() } catch (_: Exception) {}
            SafeLog.d(TAG, "Native resources released")
        }
    }

    suspend fun releaseAndWait() {
        isStopping = true
        isPaused = false
        skipToIndex = -1
        val job = playbackJob
        val pendingStop = stopJob
        playbackJob = null
        stopJob = null
        synchronized(sentenceLock) { sentences.clear() }
        listener = null

        withContext(Dispatchers.IO) {
            joinStopWork(pendingStop)
            joinStopWork(job)
            val tts = offlineTts
            val track = audioTrack
            offlineTts = null
            audioTrack = null
            isStopping = false
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { tts?.free() } catch (_: Exception) {}
            SafeLog.d(TAG, "Native resources released")
        }
    }
}
