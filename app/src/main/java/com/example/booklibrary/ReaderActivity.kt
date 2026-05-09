@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.booklibrary.data.AppDatabase
import com.example.booklibrary.data.BookmarkEntity
import com.example.booklibrary.reader.LocatorSerializer
import com.example.booklibrary.reader.ReaderBookmarkController
import com.example.booklibrary.reader.ReaderPreferences
import com.example.booklibrary.reader.ReaderTtsController
import com.example.booklibrary.tts.TtsModelManager
import com.example.booklibrary.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class ReaderActivity : AppCompatActivity(),
    TocBottomSheetDialogFragment.Host,
    TtsSettingsSheet.Host,
    DisplaySettingsSheet.Host,
    BookmarksBottomSheet.Host,
    SearchBottomSheet.Host {

    companion object {
        const val EXTRA_EPUB_PATH = "extra_epub_path"
        const val EXTRA_BOOK_ID = "extra_book_id"
        private const val FRAG_TAG = "EPUB_NAV"
        private const val TAG = "ReaderActivity"
    }

    private val db by lazy { AppDatabase.getInstance(this) }
    private lateinit var prefs: ReaderPreferences
    private lateinit var ttsController: ReaderTtsController
    private var bookmarkController: ReaderBookmarkController? = null
    private val modelManager by lazy { TtsModelManager(this) }

    private var bookId: Int = -1
    private lateinit var epubPath: String

    private var saveJob: Job? = null
    private var progressJob: Job? = null
    private var lastSavedBase64: String? = null

    private lateinit var gestureDetector: GestureDetector
    private var controlsVisible = false
    private var currentPublication: Publication? = null

    private val epubFragment: EpubNavigatorFragment?
        get() = supportFragmentManager.findFragmentByTag(FRAG_TAG) as? EpubNavigatorFragment

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, we still start the service */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // On process death, Android tries to restore EpubNavigatorFragment during
        // super.onCreate() using the default FragmentFactory. The Readium factory
        // requires a Publication that we haven't opened yet (async), so the restored
        // fragment crashes. Clearing the saved state prevents auto-restoration;
        // we re-open the publication and re-add the fragment in the coroutine below.
        if (savedInstanceState != null) {
            savedInstanceState.remove("android:support:fragments")
            savedInstanceState.remove("android:fragments")
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        prefs = ReaderPreferences(this)

        ttsController = ReaderTtsController(
            activity = this,
            prefs = prefs,
            modelManager = modelManager,
            notificationPermissionLauncher = notificationPermissionLauncher,
            getEpubFragment = { epubFragment },
            getPublication = { currentPublication },
            getEpubPath = { epubPath },
            getBookId = { bookId },
            onPlaybackStateChanged = { playing ->
                val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_headphones
                findViewById<ImageButton>(R.id.btn_tts).setImageResource(icon)
            },
            onTtsControlsVisibility = { visible ->
                setTtsControlsVisible(visible)
            },
            onStopped = {
                findViewById<ImageButton>(R.id.btn_tts).setImageResource(R.drawable.ic_headphones)
            },
            onGeneratingStateChanged = { generating ->
                val btn = findViewById<ImageButton>(R.id.btn_tts)
                val indicator = findViewById<ProgressBar>(R.id.tts_preparing_indicator)
                val overlay = findViewById<View>(R.id.tts_preparing_overlay)
                indicator.visibility = if (generating) View.VISIBLE else View.GONE
                overlay.visibility = if (generating) View.VISIBLE else View.GONE
                btn.alpha = if (generating) 0.35f else 1.0f
                btn.isEnabled = !generating
            }
        )

        // --- Button wiring ---

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_toc).setOnClickListener { showToc() }

        val ttsButton = findViewById<ImageButton>(R.id.btn_tts)
        ttsButton.isEnabled = false
        ttsButton.setOnClickListener { ttsController.toggleTts(currentPublication) }

        findViewById<ImageButton>(R.id.btn_tts_previous).setOnClickListener { ttsController.skipToPrevious() }
        findViewById<ImageButton>(R.id.btn_tts_next).setOnClickListener { ttsController.skipToNext() }
        findViewById<ImageButton>(R.id.btn_tts_stop).setOnClickListener { ttsController.stopAndReset() }
        findViewById<ImageButton>(R.id.btn_tts_settings).setOnClickListener { showTtsSettings() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { showDisplaySettings() }
        findViewById<ImageButton>(R.id.btn_search).setOnClickListener { showSearch() }
        findViewById<ImageButton>(R.id.btn_bookmark).setOnClickListener {
            bookmarkController?.toggle(findViewById(R.id.btn_bookmark))
        }
        findViewById<ImageButton>(R.id.btn_bookmarks).setOnClickListener { showBookmarks() }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return false
            }
        })

        epubPath = intent.getStringExtra(EXTRA_EPUB_PATH).orEmpty()
        if (epubPath.isBlank()) {
            Toast.makeText(this, getString(R.string.no_epub_path), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bookId = intent.getIntExtra(EXTRA_BOOK_ID, -1)

        lifecycleScope.launch {
            if (bookId <= 0) {
                val entity = withContext(Dispatchers.IO) { db.bookDao().getByPath(epubPath) }
                bookId = entity?.id ?: -1
            }

            SafeLog.d(TAG, "Opening reader")

            bookmarkController = ReaderBookmarkController(
                activity = this@ReaderActivity,
                db = db,
                bookId = bookId,
                getEpubFragment = { epubFragment },
                getCurrentChapterTitle = { ttsController.getCurrentChapterTitle(currentPublication) }
            )

            val pubResult = openPublication(epubPath)
            pubResult.fold(
                onSuccess = { pub ->
                    val initial = loadSavedLocator()
                    SafeLog.d(TAG, "Initial reader locator present=${initial != null}")

                    findViewById<TextView?>(R.id.reader_title)?.text = pub.metadata.title ?: ""
                    currentPublication = pub

                    showEpub(pub, initial)
                    startSavingProgress()
                    startProgressTracking()
                    bookmarkController?.load()

                    findViewById<ImageButton>(R.id.btn_tts).isEnabled = true
                    ttsController.preloadSelectedKokoroModelIfEnabled()
                },
                onFailure = { e ->
                    SafeLog.e(TAG, "Open failed", e)
                    Toast.makeText(this@ReaderActivity, getString(R.string.failed_to_open_epub, e.message), Toast.LENGTH_LONG).show()
                    finish()
                }
            )
        }
    }

    // --- Touch routing: tap handling + swipe detection during Piper TTS ---
    private var swipeTouchStartX = 0f
    private var piperSwipeDetected = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ttsController.isTtsPlaying && (prefs.ttsEngine == "piper" || prefs.ttsEngine == "kokoro")) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeTouchStartX = ev.x
                    piperSwipeDetected = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!piperSwipeDetected && kotlin.math.abs(ev.x - swipeTouchStartX) > 80) {
                        piperSwipeDetected = true
                        ttsController.piperNavigationSuppressed = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (piperSwipeDetected) {
                        piperSwipeDetected = false
                        currentPublication?.let { ttsController.restartPiperFromCurrentPage(it) }
                    }
                }
            }
        }
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // --- TOC ---

    private fun showToc() {
        val pub = currentPublication ?: run {
            Toast.makeText(this, getString(R.string.publication_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val toc = pub.tableOfContents
        if (toc.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_toc_found), Toast.LENGTH_SHORT).show()
            return
        }
        val frag = epubFragment ?: run {
            Toast.makeText(this, getString(R.string.reader_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        TocBottomSheetDialogFragment
            .newInstance(pub.metadata.title, toc) { link -> frag.go(link) }
            .show(supportFragmentManager, "TOC_SHEET")
    }

    // --- TocBottomSheetDialogFragment.Host ---

    override fun getTocLinks(): List<Link> = currentPublication?.tableOfContents ?: emptyList()

    override fun onTocLinkSelected(link: Link) {
        epubFragment?.go(link)
    }

    // --- TtsSettingsSheet.Host ---

    @Suppress("UNCHECKED_CAST")
    override fun getTtsVoices(): Set<AndroidTtsEngine.Voice> = ttsController.getSystemTtsVoices()

    override fun getTtsLanguage(): Language? {
        val settings = ttsController.getSystemTtsSettings()
        return settings?.language
    }

    override fun getTtsSpeed(): Double = prefs.ttsSpeed
    override fun getTtsPitch(): Double = prefs.ttsPitch
    override fun getTtsEngine(): String = prefs.ttsEngine
    override fun getTtsSelectedVoiceId(): AndroidTtsEngine.Voice.Id? =
        prefs.ttsVoiceId?.let { AndroidTtsEngine.Voice.Id(it) }

    override fun isPiperModelReady(): Boolean {
        val model = TtsModelManager.PIPER_MODELS.find { it.id == prefs.selectedPiperModelId }
            ?: TtsModelManager.PIPER_MODELS.first()
        return modelManager.isModelDownloaded(model)
    }

    override fun getSelectedPiperModelId(): String = prefs.selectedPiperModelId
    override fun getSelectedKokoroSpeakerId(): Int = prefs.selectedKokoroSpeakerId
    override fun getSelectedKokoroModelId(): String = prefs.selectedKokoroModelId
    override fun isKokoroPreloadEnabled(): Boolean = prefs.kokoroPreloadEnabled

    override fun onKokoroModelSelected(modelId: String) {
        if (modelId != prefs.selectedKokoroModelId) {
            val wasPlaying = ttsController.isTtsPlaying
            prefs.updateKokoroModel(modelId)
            if (wasPlaying && prefs.ttsEngine == "kokoro") {
                ttsController.stopAndReset()
            } else {
                ttsController.releaseWarmKokoroPreload()
            }
        }
    }

    override fun onKokoroSpeakerSelected(speakerId: Int) {
        prefs.updateKokoroSpeaker(speakerId)
        ttsController.piperPlayer?.setSpeakerId(speakerId)
    }

    override fun onPiperModelSelected(modelId: String) {
        if (modelId != prefs.selectedPiperModelId) {
            prefs.updatePiperModel(modelId)
            if (ttsController.isTtsPlaying && prefs.ttsEngine == "piper") {
                ttsController.stopAndReset()
            }
        }
    }

    override fun onTtsSpeedChanged(speed: Double) {
        prefs.updateTtsSpeed(speed)
        ttsController.piperPlayer?.setSpeed(speed.toFloat())
        ttsController.applySystemTtsPreferences()
    }

    override fun onTtsPitchChanged(pitch: Double) {
        prefs.updateTtsPitch(pitch)
        ttsController.applySystemTtsPreferences()
    }

    override fun onTtsVoiceSelected(voice: AndroidTtsEngine.Voice) {
        prefs.updateTtsVoice(voice.id.value, voice.language.locale.toLanguageTag())
        ttsController.applySystemTtsPreferences()
    }

    override fun onTtsEngineSelected(engine: String) {
        if (engine != prefs.ttsEngine) {
            val wasPlaying = ttsController.isTtsPlaying
            prefs.updateTtsEngine(engine)
            if (wasPlaying) {
                ttsController.stopAndReset()
            } else {
                ttsController.releaseWarmKokoroPreload()
            }
        }
    }

    override fun onKokoroPreloadEnabledChanged(enabled: Boolean) {
        prefs.updateKokoroPreloadEnabled(enabled)
        if (enabled) {
            ttsController.preloadSelectedKokoroModelIfEnabled()
        } else {
            ttsController.releaseWarmKokoroPreload()
        }
    }

    // --- DisplaySettingsSheet.Host ---

    override fun getDisplayFontFamily(): FontFamily? = prefs.fontFamily
    override fun getDisplayFontSize(): Double = prefs.fontSize
    override fun getDisplayTheme(): Theme? = prefs.theme
    override fun getHighlightColor(): String = prefs.highlightColor

    override fun onDisplayPreferencesChanged(fontFamily: FontFamily?, fontSize: Double, theme: Theme?) {
        prefs.updateDisplay(fontFamily, fontSize, theme)
        epubFragment?.submitPreferences(prefs.buildEpubPreferences())
    }

    override fun onHighlightColorChanged(color: String) {
        prefs.updateHighlightColor(color)
    }

    // --- TTS controls visibility ---

    private fun setTtsControlsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btn_tts_previous).visibility = visibility
        findViewById<ImageButton>(R.id.btn_tts_next).visibility = visibility
        findViewById<ImageButton>(R.id.btn_tts_stop).visibility = visibility
    }

    // --- Show/hide overlay controls ---

    private fun toggleControls() {
        val top = findViewById<View>(R.id.top_controls)
        val bottom = findViewById<View>(R.id.bottom_controls)
        controlsVisible = !controlsVisible

        if (controlsVisible) {
            top.visibility = View.VISIBLE
            bottom.visibility = View.VISIBLE
            top.alpha = 0f
            bottom.alpha = 0f
            top.animate().alpha(1f).setDuration(150).start()
            bottom.animate().alpha(1f).setDuration(150).start()
        } else {
            top.animate().alpha(0f).setDuration(150).withEndAction { top.visibility = View.GONE }.start()
            bottom.animate().alpha(0f).setDuration(150).withEndAction { bottom.visibility = View.GONE }.start()
        }
    }

    // --- Publication opening ---

    private suspend fun openPublication(epubPath: String): Result<Publication> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(epubPath)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("File not found: $epubPath"))
                }
                val httpClient = DefaultHttpClient()
                val assetRetriever = AssetRetriever(contentResolver, httpClient)
                val publicationParser = DefaultPublicationParser(
                    context = this@ReaderActivity,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null
                )
                val publicationOpener = PublicationOpener(
                    publicationParser = publicationParser,
                    contentProtections = emptyList()
                )
                val asset = assetRetriever.retrieve(file.toUrl())
                    .getOrElse { err ->
                        val message = when {
                            err.toString().contains("FileNotFoundException") -> "File not found or inaccessible"
                            err.toString().contains("SecurityException") -> "Permission denied to read this file"
                            else -> "Failed to access file: ${err.message}"
                        }
                        return@withContext Result.failure(Exception(message))
                    }
                val publication = publicationOpener.open(asset, allowUserInteraction = false)
                    .getOrElse { err ->
                        val message = when {
                            err.toString().contains("ZipException") || err.toString().contains("zip") -> "This EPUB file is corrupted or invalid"
                            err.toString().contains("ParserException") -> "Unable to parse this EPUB format"
                            err.toString().contains("OutOfMemory") -> "This EPUB file is too large to open"
                            else -> "Failed to open EPUB: ${err.message}"
                        }
                        return@withContext Result.failure(Exception(message))
                    }
                Result.success(publication)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                SafeLog.e(TAG, "Unexpected error opening publication", t)
                val message = when (t) {
                    is OutOfMemoryError -> "Not enough memory to open this book"
                    is SecurityException -> "Permission denied"
                    else -> "Unexpected error: ${t.message}"
                }
                Result.failure(Exception(message))
            }
        }

    private suspend fun loadSavedLocator(): Locator? {
        if (bookId <= 0) return null
        return withContext(Dispatchers.IO) {
            val base64 = db.bookDao().getLastLocator(bookId) ?: return@withContext null
            LocatorSerializer.fromBase64(base64)
        }
    }

    private fun showEpub(publication: Publication, initialLocator: Locator?) {
        val factory = EpubNavigatorFactory(publication)
        supportFragmentManager.fragmentFactory =
            factory.createFragmentFactory(
                initialLocator = initialLocator,
                initialPreferences = prefs.buildEpubPreferences()
            )
        supportFragmentManager.beginTransaction()
            .replace(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle(), FRAG_TAG)
            .commitNow()
    }

    // --- Progress saving ---

    @OptIn(FlowPreview::class)
    private fun startSavingProgress() {
        if (bookId <= 0) return
        val fragment = epubFragment ?: return

        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            fragment.currentLocator
                .debounce(800)
                .distinctUntilChanged()
                .collect { locator -> saveLocator(locator) }
        }
    }

    private suspend fun saveLocator(locator: Locator) {
        if (bookId <= 0) return
        val base64 = LocatorSerializer.toBase64(locator)
        if (base64 == lastSavedBase64) return
        lastSavedBase64 = base64
        withContext(Dispatchers.IO) {
            db.bookDao().updateLastLocator(bookId, base64, System.currentTimeMillis())
        }
    }

    // --- Progress tracking UI ---

    @OptIn(FlowPreview::class)
    private fun startProgressTracking() {
        val fragment = epubFragment ?: return
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)
        val tvProgress = findViewById<TextView>(R.id.tv_progress)

        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            fragment.currentLocator
                .debounce(300)
                .collect { locator ->
                    val totalProg = locator.locations.totalProgression
                    if (totalProg != null) {
                        val pct = (totalProg * 100).toInt().coerceIn(0, 100)
                        progressBar.progress = (totalProg * 1000).toInt().coerceIn(0, 1000)
                        tvProgress.text = getString(R.string.progress_format, pct)
                    }
                }
        }
    }

    // --- Storage & model import ---

    // --- Settings sheets ---

    @Suppress("UNCHECKED_CAST")
    private fun showTtsSettings() {
        val sheet = TtsSettingsSheet.newInstance()

        val voices = ttsController.getSystemTtsVoices()
        val settings = ttsController.getSystemTtsSettings()
        val selectedVoiceId = prefs.ttsVoiceId?.let { AndroidTtsEngine.Voice.Id(it) }
        val currentLang = settings?.language
        val selModel = TtsModelManager.PIPER_MODELS.find { it.id == prefs.selectedPiperModelId }
            ?: TtsModelManager.PIPER_MODELS.first()
        val piperReady = modelManager.isModelDownloaded(selModel)

        sheet.setCurrentValues(
            speed = prefs.ttsSpeed,
            pitch = prefs.ttsPitch,
            voices = voices,
            selectedVoiceId = selectedVoiceId,
            language = currentLang,
            engine = prefs.ttsEngine,
            piperReady = piperReady,
            piperModelId = prefs.selectedPiperModelId,
            kokoroSpeakerId = prefs.selectedKokoroSpeakerId,
            kokoroModelId = prefs.selectedKokoroModelId,
            kokoroPreloadEnabled = prefs.kokoroPreloadEnabled
        )

        sheet.onSpeedChanged = { speed ->
            prefs.updateTtsSpeed(speed)
            ttsController.piperPlayer?.setSpeed(speed.toFloat())
            ttsController.applySystemTtsPreferences()
        }
        sheet.onPitchChanged = { pitch ->
            prefs.updateTtsPitch(pitch)
            ttsController.applySystemTtsPreferences()
        }
        sheet.onVoiceSelected = { voice ->
            prefs.updateTtsVoice(voice.id.value, voice.language.locale.toLanguageTag())
            ttsController.applySystemTtsPreferences()
        }
        sheet.onEngineSelected = { engine ->
            if (engine != prefs.ttsEngine) {
                val wasPlaying = ttsController.isTtsPlaying
                prefs.updateTtsEngine(engine)
                if (wasPlaying) {
                    ttsController.stopAndReset()
                } else {
                    ttsController.releaseWarmKokoroPreload()
                }
            }
        }
        sheet.onPiperModelSelected = { modelId ->
            prefs.updatePiperModel(modelId)
            if (ttsController.isTtsPlaying && prefs.ttsEngine == "piper") {
                ttsController.stopAndReset()
            }
        }
        sheet.onKokoroSpeakerSelected = { speakerId ->
            prefs.updateKokoroSpeaker(speakerId)
            ttsController.piperPlayer?.setSpeakerId(speakerId)
        }
        sheet.onKokoroModelSelected = { modelId ->
            if (modelId != prefs.selectedKokoroModelId) {
                val wasPlaying = ttsController.isTtsPlaying
                prefs.updateKokoroModel(modelId)
                if (wasPlaying && prefs.ttsEngine == "kokoro") {
                    ttsController.stopAndReset()
                } else {
                    ttsController.releaseWarmKokoroPreload()
                }
            }
        }
        sheet.onKokoroPreloadEnabledChanged = { enabled ->
            prefs.updateKokoroPreloadEnabled(enabled)
            if (enabled) {
                ttsController.preloadSelectedKokoroModelIfEnabled()
            } else {
                ttsController.releaseWarmKokoroPreload()
            }
        }
        sheet.show(supportFragmentManager, "TTS_SETTINGS")
    }

    private fun showDisplaySettings() {
        val sheet = DisplaySettingsSheet.newInstance()
        sheet.setCurrentPreferences(prefs.fontFamily, prefs.fontSize, prefs.theme, prefs.highlightColor)
        sheet.onPreferencesChanged = { fontFamily, fontSize, theme ->
            prefs.updateDisplay(fontFamily, fontSize, theme)
            epubFragment?.submitPreferences(prefs.buildEpubPreferences())
        }
        sheet.onHighlightColorChanged = { color ->
            prefs.updateHighlightColor(color)
        }
        sheet.show(supportFragmentManager, "DISPLAY_SETTINGS")
    }

    // --- Bookmarks ---

    private fun showBookmarks() {
        val sheet = BookmarksBottomSheet.newInstance()
        val controller = bookmarkController ?: return
        sheet.setBookmarks(controller.cachedBookmarks)
        sheet.onSelect = { bm ->
            val locator = LocatorSerializer.fromBase64(bm.locatorBase64)
            if (locator != null) epubFragment?.go(locator)
        }
        sheet.onDelete = { bm -> controller.deleteBookmark(bm) }
        sheet.show(supportFragmentManager, "BOOKMARKS_SHEET")
    }

    // --- BookmarksBottomSheet.Host ---

    override fun getBookmarks(): List<BookmarkEntity> = bookmarkController?.cachedBookmarks ?: emptyList()

    override fun onBookmarkSelected(bookmark: BookmarkEntity) {
        val locator = LocatorSerializer.fromBase64(bookmark.locatorBase64)
        if (locator != null) epubFragment?.go(locator)
    }

    override fun onBookmarkDeleted(bookmark: BookmarkEntity) {
        bookmarkController?.deleteBookmark(bookmark)
    }

    // --- Search ---

    private fun showSearch() {
        val sheet = SearchBottomSheet.newInstance()
        sheet.onResultSelected = { locator -> epubFragment?.go(locator) }
        sheet.show(supportFragmentManager, "SEARCH_SHEET")
    }

    // --- SearchBottomSheet.Host ---

    override fun getPublication(): Publication? = currentPublication

    override fun getTableOfContents(): List<Link> = currentPublication?.tableOfContents ?: emptyList()

    override fun onSearchResultSelected(locator: Locator) {
        epubFragment?.go(locator)
    }

    // --- Lifecycle ---

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // WebView-based EpubNavigatorFragment handles its own re-layout.
        // Ensure overlay controls remain in a consistent visible/hidden state.
        val top = findViewById<View>(R.id.top_controls)
        val bottom = findViewById<View>(R.id.bottom_controls)
        if (controlsVisible) {
            top.visibility = View.VISIBLE
            top.alpha = 1f
            bottom.visibility = View.VISIBLE
            bottom.alpha = 1f
        }
        // Restore TTS control button visibility if TTS is active
        if (ttsController.isTtsPlaying) {
            setTtsControlsVisible(true)
        }
    }

    override fun onPause() {
        super.onPause()
        val fragment = epubFragment ?: return
        if (bookId > 0) {
            val locator = fragment.currentLocator.value
            val base64 = LocatorSerializer.toBase64(locator)
            if (base64 != lastSavedBase64) {
                lastSavedBase64 = base64
                lifecycleScope.launch(Dispatchers.IO) {
                    db.bookDao().updateLastLocator(bookId, base64, System.currentTimeMillis())
                }
            }
        }
    }

    override fun onDestroy() {
        saveJob?.cancel()
        progressJob?.cancel()
        ttsController.destroy()
        currentPublication?.close()
        currentPublication = null
        super.onDestroy()
    }
}
