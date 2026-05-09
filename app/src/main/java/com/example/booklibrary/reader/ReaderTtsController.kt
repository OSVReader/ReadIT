@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary.reader

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.booklibrary.R
import com.example.booklibrary.tts.PiperTtsPlayer
import com.example.booklibrary.tts.SentenceSplitter
import com.example.booklibrary.tts.TtsForegroundService
import com.example.booklibrary.tts.TtsModelManager
import com.example.booklibrary.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.getOrElse
import java.util.concurrent.atomic.AtomicLong

data class SentenceLocator(val text: String, val locator: Locator)

data class SentenceExtractionCursor(
    val elementIndex: Int,
    val charOffset: Int,
    val href: String?
)

data class SentenceChunk(
    val sentences: List<SentenceLocator>,
    val nextCursor: SentenceExtractionCursor?,
    val isChapterComplete: Boolean
)

class ReaderTtsController(
    private val activity: androidx.appcompat.app.AppCompatActivity,
    private val prefs: ReaderPreferences,
    private val modelManager: TtsModelManager,
    private val notificationPermissionLauncher: ActivityResultLauncher<String>,
    private val getEpubFragment: () -> EpubNavigatorFragment?,
    private val getPublication: () -> Publication?,
    private val getEpubPath: () -> String = { "" },
    private val getBookId: () -> Int = { -1 },
    private val onPlaybackStateChanged: (playing: Boolean) -> Unit,
    private val onTtsControlsVisibility: (visible: Boolean) -> Unit,
    private val onStopped: () -> Unit,
    private val onGeneratingStateChanged: (generating: Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "ReaderTtsController"
        private const val TTS_INITIAL_CHUNK_SIZE = 40
        private const val TTS_KOKORO_INITIAL_CHUNK_SIZE = 12
        private const val TTS_REFILL_CHUNK_SIZE = 30
        private const val TTS_PREFETCH_REMAINING_THRESHOLD = 8
        private const val TTS_LOCATOR_RETAIN_BEFORE_CURRENT = 20
        private const val TTS_LOCATOR_MAX_WINDOW = 120
    }

    private class TtsStartupTrace(private val engine: String) {
        private val startedAt = SystemClock.elapsedRealtime()
        private var lastAt = startedAt
        private var finished = false

        init {
            mark("started")
        }

        @Synchronized
        fun mark(step: String) {
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            SafeLog.i(
                TAG,
                "TTS startup[$engine] step=$step totalMs=${now - startedAt} stepMs=${now - lastAt}"
            )
            lastAt = now
        }

        @Synchronized
        fun finish(step: String) {
            if (finished) return
            mark(step)
            finished = true
        }
    }

    var piperPlayer: PiperTtsPlayer? = null
        private set
    var isTtsPlaying = false
        private set
    var currentSentenceLocators: List<SentenceLocator> = emptyList()
        private set

    private var ttsNavigator: TtsNavigator<*, *, *, *>? = null
    private var ttsObserverJob: Job? = null
    private var ttsHighlightJob: Job? = null
    private var isAdvancingChapter = false
    private var piperSwipeRestartJob: Job? = null
    private var chapterAdvanceJob: Job? = null
    private var piperPrefetchJob: Job? = null
    private var ttsStartJob: Job? = null
    private var kokoroWarmPreloadJob: Job? = null
    private var kokoroWarmPreloadModelId: String? = null
    private val piperSessionId = AtomicLong(0L)
    private var piperChunkBaseLocator: Locator? = null
    private var piperNextCursor: SentenceExtractionCursor? = null
    private var piperChunkChapterComplete = false
    private var currentSentenceLocatorStartIndex = 0
    private var activeStartupTrace: TtsStartupTrace? = null
    private var loadedPlayerEngine: String? = null
    private var loadedKokoroModelId: String? = null
    private var destroyed = false
    @Volatile var piperNavigationSuppressed = false

    // Foreground service
    private var ttsService: TtsForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            ttsService = (binder as TtsForegroundService.LocalBinder).service
            ttsService?.callback = serviceCallback
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService?.callback = null
            ttsService = null
            serviceBound = false
        }
    }

    private val serviceCallback = object : TtsForegroundService.Callback {
        override fun onPlay() { activity.runOnUiThread { if (!destroyed && !isTtsPlaying) toggleTts(getPublication()) } }
        override fun onPause() { activity.runOnUiThread { if (!destroyed && isTtsPlaying) toggleTts(getPublication()) } }
        override fun onStop() { activity.runOnUiThread { stopAndReset() } }
        override fun onNext() { activity.runOnUiThread { if (!destroyed) skipToNext() } }
        override fun onPrevious() { activity.runOnUiThread { if (!destroyed) skipToPrevious() } }
    }

    // --- Public API ---

    fun preloadSelectedKokoroModelIfEnabled() {
        if (destroyed) return
        if (prefs.ttsEngine != "kokoro" || !prefs.kokoroPreloadEnabled) return
        if (isTtsPlaying || piperPlayer?.isActive == true) return

        val model = TtsModelManager.KOKORO_MODELS.find { it.id == prefs.selectedKokoroModelId }
            ?: TtsModelManager.KOKORO_MODELS.first()
        if (isReusableKokoroPlayer(piperPlayer, model.id)) return

        val paths = modelManager.getKokoroModelPaths(model) ?: return
        kokoroWarmPreloadJob?.cancel()
        kokoroWarmPreloadModelId = model.id
        kokoroWarmPreloadJob = activity.lifecycleScope.launch {
            val preloadPlayer = PiperTtsPlayer(activity)
            try {
                withContext(Dispatchers.IO) {
                    preloadPlayer.loadKokoroModel(
                        modelDir = paths.modelDir,
                        modelName = paths.modelFileName,
                        voicesName = paths.voicesFileName,
                        tokensFile = paths.tokensFile,
                        dataDir = paths.dataDir,
                        lexicon = paths.lexicon,
                        dictDir = paths.dictDir
                    )
                }

                if (
                    destroyed ||
                    prefs.ttsEngine != "kokoro" ||
                    !prefs.kokoroPreloadEnabled ||
                    model.id != prefs.selectedKokoroModelId ||
                    isTtsPlaying ||
                    piperPlayer?.isActive == true
                ) {
                    preloadPlayer.releaseAndWait()
                    return@launch
                }

                releaseIdleLocalPlayer()
                preloadPlayer.setSpeakerId(prefs.selectedKokoroSpeakerId)
                preloadPlayer.setSpeed(prefs.ttsSpeed.toFloat())
                piperPlayer = preloadPlayer
                loadedPlayerEngine = "kokoro"
                loadedKokoroModelId = model.id
                SafeLog.i(TAG, "Kokoro preload completed for selected model")
            } catch (e: CancellationException) {
                preloadPlayer.releaseAndWait()
                throw e
            } catch (e: Exception) {
                preloadPlayer.releaseAndWait()
                SafeLog.w(TAG, "Kokoro preload failed")
            } finally {
                if (kokoroWarmPreloadJob == this.coroutineContext[Job]) {
                    kokoroWarmPreloadJob = null
                    kokoroWarmPreloadModelId = null
                }
            }
        }
    }

    fun releaseWarmKokoroPreload() {
        kokoroWarmPreloadJob?.cancel()
        kokoroWarmPreloadJob = null
        kokoroWarmPreloadModelId = null
        if (!isTtsPlaying && loadedPlayerEngine == "kokoro") {
            releaseIdleLocalPlayer()
        }
    }

    fun toggleTts(pub: Publication?) {
        if (destroyed) return
        val publication = pub ?: return

        if (prefs.ttsEngine == "piper" || prefs.ttsEngine == "kokoro") {
            val player = piperPlayer
            if (player == null || (prefs.ttsEngine == "kokoro" && !player.isActive)) {
                if (prefs.ttsEngine == "kokoro") startKokoroTts(publication)
                else startPiperTts(publication)
                return
            }
            if (player.isPlaying) {
                player.pause()
                isTtsPlaying = false
                onPlaybackStateChanged(false)
                ttsService?.updateNotification(false)
            } else {
                player.resume()
                isTtsPlaying = true
                onPlaybackStateChanged(true)
                ttsService?.updateNotification(true)
            }
            return
        } else {
            // System TTS
            val nav = ttsNavigator
            if (nav == null) {
                startSystemTts(publication)
                return
            }
            if (isTtsPlaying) {
                nav.pause()
                isTtsPlaying = false
                onPlaybackStateChanged(false)
                ttsService?.updateNotification(false)
            } else {
                nav.play()
                isTtsPlaying = true
                onPlaybackStateChanged(true)
                ttsService?.updateNotification(true)
            }
        }
    }

    fun skipToNext() {
        if (destroyed) return
        if (prefs.ttsEngine == "piper" || prefs.ttsEngine == "kokoro") {
            if (piperPlayer?.skipToNext() == true) onGeneratingStateChanged(true)
        } else {
            (ttsNavigator as? TtsNavigator)?.skipToNextUtterance()
        }
    }

    fun skipToPrevious() {
        if (destroyed) return
        if (prefs.ttsEngine == "piper" || prefs.ttsEngine == "kokoro") {
            if (piperPlayer?.skipToPrevious() == true) onGeneratingStateChanged(true)
        } else {
            (ttsNavigator as? TtsNavigator)?.skipToPreviousUtterance()
        }
    }

    fun stopAndReset() {
        val allowUiCallbacks = !destroyed
        stopSystemTts()
        stopPiperTts(releasePlayer = !shouldKeepKokoroPlayerWarm())
        isTtsPlaying = false
        if (allowUiCallbacks) {
            onGeneratingStateChanged(false)
            onTtsControlsVisibility(false)
            activity.lifecycleScope.launch {
                getEpubFragment()?.applyDecorations(emptyList(), group = "tts")
            }
            onPlaybackStateChanged(false)
            onStopped()
        }
        stopTtsService()
    }

    fun restartPiperFromCurrentPage(pub: Publication) {
        val player = piperPlayer ?: return

        piperSwipeRestartJob?.cancel()
        piperSwipeRestartJob = activity.lifecycleScope.launch {
            player.stopAndWait()
            piperNavigationSuppressed = false
            if (!isTtsPlaying) return@launch

            try {
                val sentenceLocators = prepareInitialPiperChunk(pub)
                if (sentenceLocators.isEmpty()) {
                    stopAndReset()
                    return@launch
                }
                setCurrentSentenceLocatorWindow(sentenceLocators)
                player.listener = createPiperListener(pub)
                player.setSpeed(prefs.ttsSpeed.toFloat())
                player.speakSentences(sentenceLocators.map { it.text })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SafeLog.e(TAG, "Failed to restart TTS after page swipe", e)
                stopAndReset()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun applySystemTtsPreferences() {
        val nav = ttsNavigator ?: return
        try {
            val typedNav = nav as? TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, *, AndroidTtsEngine.Voice>
                ?: return
            typedNav.submitPreferences(prefs.buildAndroidTtsPreferences())
        } catch (_: ClassCastException) {}
    }

    @Suppress("UNCHECKED_CAST")
    fun getSystemTtsVoices(): Set<AndroidTtsEngine.Voice> {
        val nav = ttsNavigator ?: return emptySet()
        return try {
            (nav as? TtsNavigator<AndroidTtsSettings, AndroidTtsPreferences, *, AndroidTtsEngine.Voice>)
                ?.voices ?: emptySet()
        } catch (_: ClassCastException) { emptySet() }
    }

    @Suppress("UNCHECKED_CAST")
    fun getSystemTtsSettings(): AndroidTtsSettings? {
        val nav = ttsNavigator ?: return null
        return try {
            (nav as? TtsNavigator<AndroidTtsSettings, *, *, *>)?.settings?.value
        } catch (_: ClassCastException) { null }
    }

    private fun normalizeHref(href: Any?): String? {
        return href
            ?.toString()
            ?.substringBefore('#')
            ?.trimStart('/')
            ?.takeIf { it.isNotBlank() }
    }

    fun getCurrentChapterTitle(pub: Publication?): String? {
        val frag = getEpubFragment()
        val currentHref = normalizeHref(frag?.currentLocator?.value?.href) ?: return null
        val publication = pub ?: return null

        fun searchToc(links: List<Link>): String? {
            for (link in links) {
                if (normalizeHref(link.url()) == currentHref) return link.title
                val childResult = link.children.let { searchToc(it) }
                if (childResult != null) return childResult
            }
            return null
        }
        return searchToc(publication.tableOfContents)
    }

    fun destroy() {
        if (destroyed) {
            stopTtsService()
            return
        }
        destroyed = true
        cancelLocalTtsStart()
        kokoroWarmPreloadJob?.cancel()
        kokoroWarmPreloadJob = null
        kokoroWarmPreloadModelId = null
        piperSwipeRestartJob?.cancel()
        piperSwipeRestartJob = null
        chapterAdvanceJob?.cancel()
        chapterAdvanceJob = null
        piperPrefetchJob?.cancel()
        piperPrefetchJob = null
        ttsObserverJob?.cancel()
        ttsObserverJob = null
        ttsHighlightJob?.cancel()
        ttsHighlightJob = null
        ttsNavigator?.pause()
        ttsNavigator?.close()
        ttsNavigator = null
        isTtsPlaying = false
        onGeneratingStateChanged(false)
        piperPlayer?.release()
        piperPlayer = null
        clearLoadedPlayerState()
        isAdvancingChapter = false
        resetPiperChunkState()
        setCurrentSentenceLocatorWindow(emptyList())
        stopTtsService()
    }

    // --- Private: System TTS ---

    private fun startSystemTts(pub: Publication) {
        val frag = getEpubFragment() ?: return

        stopPiperTts()
        activity.lifecycleScope.launch {
            getEpubFragment()?.applyDecorations(emptyList(), group = "tts")
        }

        activity.lifecycleScope.launch {
            try {
                val factory = AndroidTtsNavigatorFactory(activity.application, pub)
                if (factory == null) {
                    SafeLog.e(TAG, "System TTS not available on this device")
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(activity, "System TTS not available", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val tts = factory.createNavigator(
                    listener = object : TtsNavigator.Listener {
                        override fun onStopRequested() {
                            activity.runOnUiThread { stopAndReset() }
                        }
                    },
                    initialLocator = (frag as? VisualNavigator)?.firstVisibleElementLocator(),
                    initialPreferences = prefs.buildAndroidTtsPreferences()
                ).getOrElse { err ->
                    SafeLog.e(TAG, "Failed to create TTS navigator")
                    return@launch
                }

                ttsNavigator?.close()
                ttsNavigator = tts

                applySystemTtsPreferences()

                ttsObserverJob?.cancel()
                ttsObserverJob = (tts as TtsNavigator).location
                    .map { it.utteranceLocator }
                    .distinctUntilChanged()
                    .onEach { locator -> frag.go(locator, animated = false) }
                    .launchIn(activity.lifecycleScope)

                ttsHighlightJob?.cancel()
                ttsHighlightJob = (tts as TtsNavigator).location
                    .map { it.utteranceLocator }
                    .distinctUntilChanged()
                    .onEach { locator ->
                        frag.applyDecorations(listOf(
                            Decoration(
                                id = "tts-utterance",
                                locator = locator,
                                style = Decoration.Style.Highlight(tint = prefs.resolveHighlightColor())
                            )
                        ), group = "tts")
                    }
                    .launchIn(activity.lifecycleScope)

                tts.play()
                isTtsPlaying = true
                onPlaybackStateChanged(true)
                onTtsControlsVisibility(true)
                startTtsService(pub.metadata.title ?: "")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                SafeLog.e(TAG, "TTS start failed", e)
                activity.runOnUiThread {
                    android.widget.Toast.makeText(activity, "TTS failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopSystemTts() {
        ttsObserverJob?.cancel()
        ttsObserverJob = null
        ttsHighlightJob?.cancel()
        ttsHighlightJob = null
        ttsNavigator?.pause()
        ttsNavigator?.close()
        ttsNavigator = null
    }

    private fun failTtsStart(message: String, error: Throwable? = null) {
        if (error != null) {
            SafeLog.w(TAG, "TTS start failed", error)
        } else {
            SafeLog.w(TAG, "TTS start failed")
        }
        isTtsPlaying = false
        stopTtsService()
        if (destroyed) return
        onGeneratingStateChanged(false)
        onPlaybackStateChanged(false)
        onTtsControlsVisibility(false)
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // --- Private: Piper TTS ---

    private fun startPiperTts(pub: Publication) {
        val model = TtsModelManager.PIPER_MODELS.find { it.id == prefs.selectedPiperModelId }
            ?: TtsModelManager.PIPER_MODELS.first()

        stopSystemTts()
        cancelLocalTtsStart()

        val startJob = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val myStartJob = this.coroutineContext[Job]
            var ownedPlayer: PiperTtsPlayer? = null
            try {
                onGeneratingStateChanged(true)
                // Wait for old player's native memory to be freed before loading new model.
                stopPiperTtsAndWait(cancelStartJob = false)
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch
                getEpubFragment()?.applyDecorations(emptyList(), group = "tts")

                // Auto-download default Piper model if not present.
                var paths = modelManager.getModelPaths(model)
                if (paths == null) {
                    activity.runOnUiThread {
                        onGeneratingStateChanged(true)
                        android.widget.Toast.makeText(activity, "Downloading voice model...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    val result = modelManager.downloadModel(model) { /* no progress UI for auto-download */ }
                    if (result.isFailure) {
                        SafeLog.e(TAG, "Failed to auto-download Piper model", result.exceptionOrNull())
                        failTtsStart("Failed to download voice. Check your connection.", result.exceptionOrNull())
                        return@launch
                    }
                    if (!isCurrentLocalTtsStart(myStartJob)) return@launch
                    paths = modelManager.getModelPaths(model)
                    if (paths == null) {
                        failTtsStart("Voice model files were not found after download.")
                        return@launch
                    }
                }

                val player = PiperTtsPlayer(activity)
                ownedPlayer = player
                withContext(Dispatchers.IO) {
                    player.loadModel(paths.modelDir, paths.modelFileName, paths.dataDir)
                }
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch

                player.setSpeed(prefs.ttsSpeed.toFloat())
                piperPlayer = player
                loadedPlayerEngine = "piper"
                loadedKokoroModelId = null

                val sentenceLocators = prepareInitialPiperChunk(pub)
                if (sentenceLocators.isEmpty()) {
                    player.releaseAndWait()
                    piperPlayer = null
                    clearLoadedPlayerState()
                    ownedPlayer = null
                    failTtsStart("This chapter cannot be read aloud.")
                    return@launch
                }
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch

                setCurrentSentenceLocatorWindow(sentenceLocators)
                player.listener = createPiperListener(pub)

                isTtsPlaying = true
                activity.runOnUiThread {
                    if (!isCurrentLocalTtsStart(myStartJob)) return@runOnUiThread
                    onGeneratingStateChanged(true)
                    onPlaybackStateChanged(true)
                    onTtsControlsVisibility(true)
                    startTtsService(pub.metadata.title ?: "")
                }

                player.speakSentences(sentenceLocators.map { it.text })
                ownedPlayer = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failTtsStart("Failed to load voice model.", e)
            } finally {
                val playerToRelease = ownedPlayer
                if (playerToRelease != null) {
                    if (piperPlayer === playerToRelease) {
                        piperPlayer = null
                        clearLoadedPlayerState()
                    }
                    withContext(NonCancellable) { playerToRelease.releaseAndWait() }
                }
                if (ttsStartJob == myStartJob) ttsStartJob = null
            }
        }
        ttsStartJob = startJob
        startJob.start()
    }

    private fun startKokoroTts(pub: Publication) {
        val startupTrace = TtsStartupTrace("kokoro")
        val model = TtsModelManager.KOKORO_MODELS.find { it.id == prefs.selectedKokoroModelId }
            ?: TtsModelManager.KOKORO_MODELS.first()
        val paths = modelManager.getKokoroModelPaths(model)
        startupTrace.mark("model_paths_checked")
        if (paths == null) {
            startupTrace.finish("model_missing")
            failTtsStart("Kokoro model is not downloaded. Open Voice settings to download it.")
            return
        }

        stopSystemTts()
        cancelLocalTtsStart()

        val startJob = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val myStartJob = this.coroutineContext[Job]
            var ownedPlayer: PiperTtsPlayer? = null
            try {
                activeStartupTrace = startupTrace
                onGeneratingStateChanged(true)
                val warmPreload = kokoroWarmPreloadJob
                if (warmPreload != null) {
                    if (kokoroWarmPreloadModelId == model.id && prefs.kokoroPreloadEnabled) {
                        try {
                            warmPreload.join()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    } else {
                        warmPreload.cancel()
                        try {
                            warmPreload.join()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    if (kokoroWarmPreloadJob == warmPreload) {
                        kokoroWarmPreloadJob = null
                        kokoroWarmPreloadModelId = null
                    }
                }
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch

                val cachedPlayer = piperPlayer.takeIf { isReusableKokoroPlayer(it, model.id) }
                val reusedPlayer = if (cachedPlayer != null) {
                    stopPiperTtsAndWait(releasePlayer = false, cancelStartJob = false)
                    startupTrace.mark("cached_model_reused")
                    cachedPlayer
                } else {
                    // Wait for old player's native memory to be freed before loading new model.
                    stopPiperTtsAndWait(cancelStartJob = false)
                    startupTrace.mark("old_player_released")
                    null
                }
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch
                getEpubFragment()?.applyDecorations(emptyList(), group = "tts")

                val player = reusedPlayer ?: PiperTtsPlayer(activity).also { newPlayer ->
                    ownedPlayer = newPlayer
                    withContext(Dispatchers.IO) {
                        newPlayer.loadKokoroModel(
                            modelDir = paths.modelDir,
                            modelName = paths.modelFileName,
                            voicesName = paths.voicesFileName,
                            tokensFile = paths.tokensFile,
                            dataDir = paths.dataDir,
                            lexicon = paths.lexicon,
                            dictDir = paths.dictDir
                        )
                    }
                    startupTrace.mark("native_model_loaded")
                }
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch

                player.setSpeakerId(prefs.selectedKokoroSpeakerId)
                player.setSpeed(prefs.ttsSpeed.toFloat())
                piperPlayer = player
                loadedPlayerEngine = "kokoro"
                loadedKokoroModelId = model.id

                val sentenceLocators = prepareInitialPiperChunk(pub)
                startupTrace.mark("initial_chunk_ready:${sentenceLocators.size}")
                if (sentenceLocators.isEmpty()) {
                    startupTrace.finish("empty_initial_chunk")
                    activeStartupTrace = null
                    player.releaseAndWait()
                    if (ownedPlayer === player) ownedPlayer = null
                    piperPlayer = null
                    clearLoadedPlayerState()
                    failTtsStart("This chapter cannot be read aloud.")
                    return@launch
                }
                if (!isCurrentLocalTtsStart(myStartJob)) return@launch

                setCurrentSentenceLocatorWindow(sentenceLocators)
                player.listener = createPiperListener(pub)

                isTtsPlaying = true
                activity.runOnUiThread {
                    if (!isCurrentLocalTtsStart(myStartJob)) return@runOnUiThread
                    onGeneratingStateChanged(true)
                    onPlaybackStateChanged(true)
                    onTtsControlsVisibility(true)
                    startTtsService(pub.metadata.title ?: "")
                }
                startupTrace.mark("playback_requested")

                player.speakSentences(sentenceLocators.map { it.text })
                ownedPlayer = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startupTrace.finish("model_load_failed")
                activeStartupTrace = null
                failTtsStart("Failed to load Kokoro voice.", e)
            } finally {
                val playerToRelease = ownedPlayer
                if (playerToRelease != null) {
                    if (piperPlayer === playerToRelease) {
                        piperPlayer = null
                        clearLoadedPlayerState()
                    }
                    withContext(NonCancellable) { playerToRelease.releaseAndWait() }
                }
                if (ttsStartJob == myStartJob) ttsStartJob = null
            }
        }
        ttsStartJob = startJob
        startJob.start()
    }

    private fun resetPiperChunkState() {
        piperChunkBaseLocator = null
        piperNextCursor = null
        piperChunkChapterComplete = false
    }

    private fun nextPiperSessionId(): Long = piperSessionId.incrementAndGet()

    private fun currentPiperSessionId(): Long = piperSessionId.get()

    private fun cancelLocalTtsStart() {
        ttsStartJob?.cancel()
        ttsStartJob = null
        activeStartupTrace = null
    }

    private fun isCurrentLocalTtsStart(job: Job?): Boolean {
        return !destroyed && job != null && ttsStartJob == job
    }

    private suspend fun cancelPrefetchAndWait() {
        val prefetch = piperPrefetchJob
        piperPrefetchJob = null
        prefetch?.cancel()
        try {
            prefetch?.join()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun isReusableKokoroPlayer(player: PiperTtsPlayer?, modelId: String): Boolean {
        return player != null &&
            prefs.kokoroPreloadEnabled &&
            loadedPlayerEngine == "kokoro" &&
            loadedKokoroModelId == modelId &&
            player.isModelLoaded
    }

    private fun shouldKeepKokoroPlayerWarm(): Boolean {
        return prefs.ttsEngine == "kokoro" &&
            prefs.kokoroPreloadEnabled &&
            loadedPlayerEngine == "kokoro" &&
            loadedKokoroModelId == prefs.selectedKokoroModelId &&
            piperPlayer?.isModelLoaded == true
    }

    private fun releaseIdleLocalPlayer() {
        val player = piperPlayer
        if (player == null || player.isActive) return
        piperPlayer = null
        clearLoadedPlayerState()
        activity.lifecycleScope.launch {
            player.releaseAndWait()
        }
    }

    private fun clearLoadedPlayerState() {
        loadedPlayerEngine = null
        loadedKokoroModelId = null
    }

    private fun stopPiperTts(releasePlayer: Boolean = true, cancelStartJob: Boolean = true) {
        if (cancelStartJob) cancelLocalTtsStart()
        nextPiperSessionId()
        piperSwipeRestartJob?.cancel()
        piperSwipeRestartJob = null
        chapterAdvanceJob?.cancel()
        chapterAdvanceJob = null
        piperPrefetchJob?.cancel()
        piperPrefetchJob = null
        resetPiperChunkState()
        val player = piperPlayer
        piperPlayer = null
        if (releasePlayer) {
            player?.release()
            clearLoadedPlayerState()
        } else {
            player?.stop()
            piperPlayer = player
        }
        isAdvancingChapter = false
        setCurrentSentenceLocatorWindow(emptyList())
    }

    private suspend fun stopPiperTtsAndWait(
        releasePlayer: Boolean = true,
        cancelStartJob: Boolean = true
    ) {
        if (cancelStartJob) cancelLocalTtsStart()
        nextPiperSessionId()
        piperSwipeRestartJob?.cancel()
        piperSwipeRestartJob = null
        chapterAdvanceJob?.cancel()
        chapterAdvanceJob = null
        cancelPrefetchAndWait()
        resetPiperChunkState()
        val player = piperPlayer
        piperPlayer = null
        if (releasePlayer) {
            player?.releaseAndWait()
            clearLoadedPlayerState()
        } else {
            player?.stopAndWait()
            piperPlayer = player
        }
        isAdvancingChapter = false
        setCurrentSentenceLocatorWindow(emptyList())
    }

    fun createPiperListener(pub: Publication): PiperTtsPlayer.Listener {
        var firstUtterance = true
        return object : PiperTtsPlayer.Listener {
            override fun onUtteranceStart(index: Int, text: String) {
                if (firstUtterance) {
                    firstUtterance = false
                    activeStartupTrace?.mark("first_utterance_start")
                }
                if (piperNavigationSuppressed) return
                val sl = currentSentenceLocatorForIndex(index) ?: return

                // Build decorations for current + next sentence block
                val decorations = mutableListOf(
                    Decoration(
                        id = "piper-sentence-0",
                        locator = sl.locator,
                        style = Decoration.Style.Highlight(tint = prefs.resolveHighlightColor())
                    )
                )
                val next = currentSentenceLocatorForIndex(index + 1)
                if (next != null) {
                    decorations.add(
                        Decoration(
                            id = "piper-sentence-1",
                            locator = next.locator,
                            style = Decoration.Style.Highlight(
                                tint = prefs.resolveHighlightColor() and 0x40FFFFFF
                            )
                        )
                    )
                }

                activity.lifecycleScope.launch {
                    if (piperNavigationSuppressed) return@launch
                    getEpubFragment()?.applyDecorations(decorations, group = "tts")
                    getEpubFragment()?.go(sl.locator, animated = true)
                }
            }

            override fun onUtteranceDone(index: Int) {
                maybePrefetchPiperSentences(pub, index)
                trimSentenceLocatorWindow(index)
            }

            override fun onBuffering(isBuffering: Boolean) {
                activity.runOnUiThread {
                    onGeneratingStateChanged(isBuffering)
                }
            }

            override fun onAudioStarted(index: Int) {
                activeStartupTrace?.finish("first_audio_started")
                activeStartupTrace = null
                activity.runOnUiThread { onGeneratingStateChanged(false) }
            }

            override fun onFinished() {
                activity.runOnUiThread { onGeneratingStateChanged(false) }
                activity.lifecycleScope.launch {
                    val sessionId = currentPiperSessionId()
                    val player = piperPlayer
                    if (!piperChunkChapterComplete && player != null) {
                        piperPrefetchJob?.join()
                        if (currentPiperSessionId() != sessionId || piperPlayer !== player || !isTtsPlaying) return@launch
                        val resumeIndex = player.currentIndex

                        if (windowIndexForSentence(resumeIndex) != null) {
                            player.listener = createPiperListener(pub)
                            player.setSpeed(prefs.ttsSpeed.toFloat())
                            restartPiperWithLocatorWindow(player, resumeIndex)
                            return@launch
                        }

                        val nextChunk = prepareContinuationPiperChunk(pub, expectedSessionId = sessionId)
                        if (currentPiperSessionId() != sessionId || piperPlayer !== player || !isTtsPlaying) return@launch
                        if (nextChunk.isNotEmpty()) {
                            appendSentenceLocators(nextChunk, currentIndex = resumeIndex)
                            player.listener = createPiperListener(pub)
                            player.setSpeed(prefs.ttsSpeed.toFloat())
                            restartPiperWithLocatorWindow(player, resumeIndex)
                            return@launch
                        }
                    }
                    if (currentPiperSessionId() == sessionId) advancePiperToNextChapter(pub)
                }
            }

            override fun onError(error: String) {
                activity.runOnUiThread {
                    onGeneratingStateChanged(false)
                    val crashedIndex = piperPlayer?.currentIndex ?: 0
                    val totalSentences = piperPlayer?.sentenceCount ?: 0
                    piperPlayer?.stop()
                    isTtsPlaying = false
                    onPlaybackStateChanged(false)

                    if (crashedIndex > 0 && crashedIndex < totalSentences && windowIndexForSentence(crashedIndex) != null) {
                        showPiperRecoveryDialog(pub, crashedIndex, error)
                    } else {
                        stopAndReset()
                    }
                }
            }
        }
    }

    private fun showPiperRecoveryDialog(pub: Publication, fromIndex: Int, error: String) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.tts_error_title)
            .setMessage(activity.getString(R.string.tts_error_recovery, error))
            .setPositiveButton(R.string.tts_resume) { _, _ -> resumePiperFromIndex(pub, fromIndex) }
            .setNegativeButton(R.string.tts_stop_reading) { _, _ -> stopAndReset() }
            .setCancelable(false)
            .show()
    }

    private fun resumePiperFromIndex(pub: Publication, fromIndex: Int) {
        val player = piperPlayer
        if (player == null || windowIndexForSentence(fromIndex) == null || currentSentenceLocators.isEmpty()) {
            stopAndReset()
            return
        }
        isTtsPlaying = true
        onPlaybackStateChanged(true)
        player.listener = createPiperListener(pub)
        player.setSpeed(prefs.ttsSpeed.toFloat())
        activity.lifecycleScope.launch {
            restartPiperWithLocatorWindow(player, fromIndex)
        }
    }

    private fun advancePiperToNextChapter(pub: Publication) {
        if (isAdvancingChapter) return
        isAdvancingChapter = true

        val frag = getEpubFragment()
        val currentHref = normalizeHref(frag?.currentLocator?.value?.href)
        val readingOrder = pub.readingOrder
        val currentIdx = readingOrder.indexOfFirst { normalizeHref(it.url()) == currentHref }
        if (currentIdx < 0 || currentIdx >= readingOrder.size - 1) {
            isAdvancingChapter = false
            stopAndReset()
            return
        }

        val nextLink = readingOrder[currentIdx + 1]
        frag?.go(nextLink)
        val advancingPlayer = piperPlayer

        chapterAdvanceJob?.cancel()
        chapterAdvanceJob = activity.lifecycleScope.launch {
            advancingPlayer?.stopAndWait()
            val targetHref = normalizeHref(nextLink.url())
            var waited = 0L
            while (waited < 5000) {
                val nowHref = normalizeHref(frag?.currentLocator?.value?.href)
                if (nowHref == targetHref) break
                delay(100)
                waited += 100
            }
            isAdvancingChapter = false

            // If navigation didn't reach the target chapter, stop instead of
            // re-reading the old chapter
            val finalHref = normalizeHref(frag?.currentLocator?.value?.href)
            if (finalHref != targetHref) {
                SafeLog.w(TAG, "Chapter advance timed out, stopping TTS")
                activity.runOnUiThread { stopAndReset() }
                return@launch
            }

            val player = piperPlayer
            if (player == null) {
                if (prefs.ttsEngine == "kokoro") startKokoroTts(pub)
                else startPiperTts(pub)
                return@launch
            }

            val sentenceLocators = prepareInitialPiperChunk(pub)
            if (sentenceLocators.isEmpty()) {
                stopAndReset()
                return@launch
            }

            setCurrentSentenceLocatorWindow(sentenceLocators)
            player.listener = createPiperListener(pub)
            player.setSpeed(prefs.ttsSpeed.toFloat())

            ttsService?.updateChapter(getCurrentChapterTitle(pub))
            player.speakSentences(sentenceLocators.map { it.text })
        }
    }

    private suspend fun prepareInitialPiperChunk(pub: Publication): List<SentenceLocator> {
        cancelPrefetchAndWait()
        val sessionId = nextPiperSessionId()
        val baseLocator = withContext(Dispatchers.Main) {
            (getEpubFragment() as? VisualNavigator)?.firstVisibleElementLocator()
        }
        piperChunkBaseLocator = baseLocator
        piperNextCursor = null
        piperChunkChapterComplete = false
        val cursorBefore = piperNextCursor

        val chunk = extractSentenceChunk(
            pub = pub,
            maxSentences = initialChunkSize(),
            cursor = null,
            baseLocator = baseLocator
        )
        if (currentPiperSessionId() != sessionId) return emptyList()
        piperNextCursor = chunk.nextCursor
        piperChunkChapterComplete = chunk.isChapterComplete
        logChunk("initial", chunk, sessionId, cursorBefore)
        return chunk.sentences
    }

    private fun initialChunkSize(): Int {
        return if (prefs.ttsEngine == "kokoro") {
            TTS_KOKORO_INITIAL_CHUNK_SIZE
        } else {
            TTS_INITIAL_CHUNK_SIZE
        }
    }

    private fun prefetchRemainingThreshold(): Int {
        return if (prefs.ttsEngine == "kokoro") 12 else TTS_PREFETCH_REMAINING_THRESHOLD
    }

    private fun logChunk(
        label: String,
        chunk: SentenceChunk,
        sessionId: Long,
        cursorBefore: SentenceExtractionCursor?
    ) {
        SafeLog.d(
            TAG,
            "TTS chunk[$label] session=$sessionId size=${chunk.sentences.size} " +
                "complete=${chunk.isChapterComplete} " +
                "hadCursorBefore=${cursorBefore != null} " +
                "hasCursorAfter=${chunk.nextCursor != null}"
        )
    }

    private fun setCurrentSentenceLocatorWindow(sentences: List<SentenceLocator>) {
        currentSentenceLocatorStartIndex = 0
        currentSentenceLocators = sentences
    }

    private fun currentSentenceLocatorEndIndex(): Int {
        return currentSentenceLocatorStartIndex + currentSentenceLocators.size
    }

    private fun windowIndexForSentence(sentenceIndex: Int): Int? {
        val index = sentenceIndex - currentSentenceLocatorStartIndex
        return if (index in currentSentenceLocators.indices) index else null
    }

    private fun currentSentenceLocatorForIndex(sentenceIndex: Int): SentenceLocator? {
        return windowIndexForSentence(sentenceIndex)?.let { currentSentenceLocators.getOrNull(it) }
    }

    private suspend fun restartPiperWithLocatorWindow(player: PiperTtsPlayer, sentenceIndex: Int) {
        val startInWindow = windowIndexForSentence(sentenceIndex) ?: return
        val windowSentences = currentSentenceLocators.map { it.text }
        currentSentenceLocatorStartIndex = 0
        player.speakSentences(windowSentences, startIndex = startInWindow)
    }

    private fun appendSentenceLocators(newLocators: List<SentenceLocator>, currentIndex: Int) {
        if (newLocators.isEmpty()) return
        currentSentenceLocators = currentSentenceLocators + newLocators
        trimSentenceLocatorWindow(currentIndex)
    }

    private fun trimSentenceLocatorWindow(currentIndex: Int) {
        if (currentSentenceLocators.size <= TTS_LOCATOR_MAX_WINDOW) return
        val currentEnd = currentSentenceLocatorEndIndex()
        val keepFromCurrent = (currentIndex - TTS_LOCATOR_RETAIN_BEFORE_CURRENT + 1)
            .coerceAtLeast(currentSentenceLocatorStartIndex)
        val keepFromMaxWindow = (currentEnd - TTS_LOCATOR_MAX_WINDOW)
            .coerceAtLeast(currentSentenceLocatorStartIndex)
        val newStart = maxOf(keepFromCurrent, keepFromMaxWindow)
        val dropCount = newStart - currentSentenceLocatorStartIndex
        if (dropCount <= 0) return
        currentSentenceLocators = currentSentenceLocators.drop(dropCount)
        currentSentenceLocatorStartIndex = newStart
    }
    private suspend fun prepareContinuationPiperChunk(
        pub: Publication,
        expectedSessionId: Long = currentPiperSessionId()
    ): List<SentenceLocator> {
        val cursorBefore = piperNextCursor
        val chunk = extractSentenceChunk(
            pub = pub,
            maxSentences = TTS_REFILL_CHUNK_SIZE,
            cursor = piperNextCursor,
            baseLocator = piperChunkBaseLocator
        )
        if (currentPiperSessionId() != expectedSessionId) {
            SafeLog.d(TAG, "Discarding stale continuation chunk")
            return emptyList()
        }
        piperNextCursor = chunk.nextCursor
        piperChunkChapterComplete = chunk.isChapterComplete
        logChunk("continuation", chunk, expectedSessionId, cursorBefore)
        return chunk.sentences
    }

    private fun maybePrefetchPiperSentences(pub: Publication, completedIndex: Int) {
        val remaining = currentSentenceLocatorEndIndex() - completedIndex - 1
        if (remaining > prefetchRemainingThreshold()) return
        if (piperChunkChapterComplete) return
        if (piperPrefetchJob?.isActive == true) return

        val sessionId = currentPiperSessionId()
        piperPrefetchJob = activity.lifecycleScope.launch {
            val nextChunk = prepareContinuationPiperChunk(pub, expectedSessionId = sessionId)
            if (currentPiperSessionId() != sessionId || !isTtsPlaying) return@launch
            val player = piperPlayer ?: return@launch
            if (nextChunk.isNotEmpty()) {
                player.appendSentences(nextChunk.map { it.text })
                appendSentenceLocators(nextChunk, currentIndex = completedIndex)
                SafeLog.d(TAG, "Prefetched TTS sentences count=${nextChunk.size}")
            }
        }
    }

    suspend fun extractSentencesWithLocators(pub: Publication): List<SentenceLocator> {
        val startLocator = withContext(Dispatchers.Main) {
            (getEpubFragment() as? VisualNavigator)?.firstVisibleElementLocator()
        }
        return withContext(Dispatchers.IO) {
            try {
                val content = pub.content(startLocator) ?: return@withContext emptyList()
                val startHref = normalizeHref(startLocator?.href)
                val result = mutableListOf<SentenceLocator>()
                val iterator = content.iterator()

                while (iterator.hasNext()) {
                    val element = iterator.next()
                    if (element is Content.TextElement) {
                        if (startHref != null && result.isNotEmpty()
                            && normalizeHref(element.locator.href) != startHref) break
                        val fullText = element.text
                        if (fullText.isBlank()) continue

                        val spans = SentenceSplitter.splitToSpans(fullText)
                        for (span in spans) {
                            val before = if (span.start > 0)
                                fullText.substring(maxOf(0, span.start - 50), span.start) else null
                            val after = if (span.end < fullText.length)
                                fullText.substring(
                                    span.end,
                                    minOf(fullText.length, span.end + 50)
                                ) else null
                            val subLocator = element.locator.copy(
                                text = Locator.Text(
                                    before = before?.takeIf { it.isNotBlank() },
                                    highlight = span.text,
                                    after = after?.takeIf { it.isNotBlank() }
                                )
                            )
                            result.add(SentenceLocator(span.text, subLocator))
                        }
                    }
                }
                SafeLog.d(TAG, "Extracted TTS sentences count=${result.size}")
                result
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SafeLog.e(TAG, "Failed to extract sentences", e)
                emptyList()
            }
        }
    }


    suspend fun extractSentenceChunk(
        pub: Publication,
        maxSentences: Int = TTS_INITIAL_CHUNK_SIZE,
        cursor: SentenceExtractionCursor? = null,
        baseLocator: Locator? = null
    ): SentenceChunk {
        val startLocator = withContext(Dispatchers.Main) {
            baseLocator ?: (getEpubFragment() as? VisualNavigator)?.firstVisibleElementLocator()
        }
        return withContext(Dispatchers.IO) {
            val startCursor = cursor ?: SentenceExtractionCursor(
                elementIndex = 0,
                charOffset = 0,
                href = normalizeHref(startLocator?.href)
            )

            try {
                val content = pub.content(startLocator)
                    ?: return@withContext SentenceChunk(emptyList(), startCursor, true)
                val startHref = startCursor.href ?: normalizeHref(startLocator?.href)
                val result = mutableListOf<SentenceLocator>()
                val iterator = content.iterator()
                var elementIndex = 0
                var nextCursor: SentenceExtractionCursor? = startCursor

                while (iterator.hasNext()) {
                    val element = iterator.next()
                    if (element !is Content.TextElement) continue

                    if (elementIndex < startCursor.elementIndex) {
                        elementIndex++
                        continue
                    }

                    if (startHref != null && normalizeHref(element.locator.href) != startHref) {
                        return@withContext SentenceChunk(result, nextCursor, true)
                    }

                    val fullText = element.text
                    if (fullText.isBlank()) {
                        elementIndex++
                        nextCursor = SentenceExtractionCursor(elementIndex, 0, normalizeHref(element.locator.href))
                        continue
                    }

                    val minOffset = if (elementIndex == startCursor.elementIndex) startCursor.charOffset else 0
                    for (span in SentenceSplitter.splitToSpans(fullText)) {
                        if (span.end <= minOffset) continue

                        val before = if (span.start > 0)
                            fullText.substring(maxOf(0, span.start - 50), span.start) else null
                        val after = if (span.end < fullText.length)
                            fullText.substring(
                                span.end,
                                minOf(fullText.length, span.end + 50)
                            ) else null
                        val subLocator = element.locator.copy(
                            text = Locator.Text(
                                before = before?.takeIf { it.isNotBlank() },
                                highlight = span.text,
                                after = after?.takeIf { it.isNotBlank() }
                            )
                        )
                        result.add(SentenceLocator(span.text, subLocator))
                        nextCursor = SentenceExtractionCursor(elementIndex, span.end, normalizeHref(element.locator.href))

                        if (result.size >= maxSentences) {
                            return@withContext SentenceChunk(result, nextCursor, false)
                        }
                    }

                    elementIndex++
                    nextCursor = SentenceExtractionCursor(elementIndex, 0, normalizeHref(element.locator.href))
                }

                SentenceChunk(result, nextCursor, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SafeLog.e(TAG, "Failed to extract sentence chunk", e)
                SentenceChunk(emptyList(), startCursor, true)
            }
        }
    }

    // --- Foreground service ---

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startTtsService(title: String) {
        ensureNotificationPermission()
        try {
            val chapter = getCurrentChapterTitle(getPublication())
            val intent = Intent(activity, TtsForegroundService::class.java).apply {
                action = TtsForegroundService.ACTION_START
                putExtra(TtsForegroundService.EXTRA_TITLE, title)
                putExtra(TtsForegroundService.EXTRA_CHAPTER, chapter)
                putExtra(TtsForegroundService.EXTRA_EPUB_PATH, getEpubPath())
                putExtra(TtsForegroundService.EXTRA_BOOK_ID, getBookId())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
            if (!serviceBound) {
                serviceBound = activity.bindService(
                    Intent(activity, TtsForegroundService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
                if (!serviceBound) {
                    SafeLog.w(TAG, "TTS service bind was not accepted")
                }
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to start TTS service", e)
        }
    }

    private fun stopTtsService() {
        ttsService?.callback = null
        ttsService?.stopService()
        if (ttsService == null) {
            try {
                activity.stopService(Intent(activity, TtsForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
        if (serviceBound) {
            try { activity.unbindService(serviceConnection) } catch (_: Exception) {}
            serviceBound = false
        }
        ttsService = null
    }
}
