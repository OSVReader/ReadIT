@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.booklibrary.data.AppDatabase
import com.example.booklibrary.data.BookEntity
import com.example.booklibrary.reader.ReaderPreferences
import com.example.booklibrary.tts.TtsModelManager
import com.example.booklibrary.work.BookMetadataWorker
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.shared.util.Language
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity(), TtsSettingsSheet.Host {

    private lateinit var adapter: BookRVAdapter
    private lateinit var db: AppDatabase
    private lateinit var recyclerView: RecyclerView
    private var isListView = false
    private val booksDir by lazy { File(filesDir, "books").apply { mkdirs() } }
    private val viewModePrefs by lazy { getSharedPreferences("library_prefs", MODE_PRIVATE) }
    private val readerPrefs by lazy { ReaderPreferences(this) }
    private val modelManager by lazy { TtsModelManager(this) }

    private val pickEpub =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { importEpub(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        isListView = viewModePrefs.getBoolean("is_list_view", false)

        recyclerView = findViewById(R.id.idRVBooks)

        // Adapter with callbacks (open + delete + reorder)
        adapter = BookRVAdapter(
            context = this,
            onOpen = { book ->
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                    putExtra(ReaderActivity.EXTRA_EPUB_PATH, book.epubPath)
                }
                startActivity(intent)
            },
            onDelete = { book ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_book_title))
                    .setMessage(getString(R.string.delete_book_message))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        deleteBook(book.id)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            },
            onReorder = { reorderedBooks ->
                persistBookOrder(reorderedBooks)
            }
        )

        adapter.viewType = if (isListView) BookRVAdapter.VIEW_TYPE_LIST else BookRVAdapter.VIEW_TYPE_GRID
        recyclerView.adapter = adapter
        recyclerView.layoutManager = if (isListView) LinearLayoutManager(this) else GridLayoutManager(this, 3)

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    adapter.onDragStart()
                    viewHolder?.itemView?.alpha = 0.7f
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                adapter.onDragEnd()
            }

            override fun isLongPressDragEnabled(): Boolean = false
        })
        touchHelper.attachToRecyclerView(recyclerView)

        adapter.onStartDrag = { viewHolder ->
            touchHelper.startDrag(viewHolder)
        }

        val emptyState = findViewById<View>(R.id.empty_state)

        db.bookDao().getAll().observe(this) { list ->
            adapter.setBooks(list)
            emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }

        val fab = findViewById<ExtendedFloatingActionButton>(R.id.idFABAdd)
        fab.setOnClickListener {
            pickEpub.launch("application/epub+zip")
        }

        // Shrink FAB on scroll for cleaner look
        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) fab.shrink() else if (dy < 0) fab.extend()
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_library, menu)
        menu.findItem(R.id.action_toggle_view)?.icon =
            getDrawable(if (isListView) R.drawable.ic_view_grid else R.drawable.ic_view_list)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_view -> {
                isListView = !isListView
                viewModePrefs.edit().putBoolean("is_list_view", isListView).apply()

                item.icon = getDrawable(if (isListView) R.drawable.ic_view_grid else R.drawable.ic_view_list)
                adapter.viewType = if (isListView) BookRVAdapter.VIEW_TYPE_LIST else BookRVAdapter.VIEW_TYPE_GRID
                recyclerView.layoutManager = if (isListView) LinearLayoutManager(this) else GridLayoutManager(this, 3)
                adapter.notifyDataSetChanged()
                true
            }
            R.id.action_voice_settings -> {
                showVoiceSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showVoiceSettings() {
        val sheet = TtsSettingsSheet.newInstance()
        val selectedPiper = TtsModelManager.PIPER_MODELS.find { it.id == readerPrefs.selectedPiperModelId }
            ?: TtsModelManager.PIPER_MODELS.first()

        sheet.setCurrentValues(
            speed = readerPrefs.ttsSpeed,
            pitch = readerPrefs.ttsPitch,
            voices = emptySet(),
            selectedVoiceId = null,
            language = null,
            engine = readerPrefs.ttsEngine,
            piperReady = modelManager.isModelDownloaded(selectedPiper),
            piperModelId = readerPrefs.selectedPiperModelId,
            kokoroSpeakerId = readerPrefs.selectedKokoroSpeakerId,
            kokoroModelId = readerPrefs.selectedKokoroModelId,
            kokoroPreloadEnabled = readerPrefs.kokoroPreloadEnabled
        )
        sheet.onSpeedChanged = { readerPrefs.updateTtsSpeed(it) }
        sheet.onPitchChanged = { readerPrefs.updateTtsPitch(it) }
        sheet.onVoiceSelected = { voice ->
            readerPrefs.updateTtsVoice(voice.id.value, voice.language.locale.toLanguageTag())
        }
        sheet.onEngineSelected = { readerPrefs.updateTtsEngine(it) }
        sheet.onPiperModelSelected = { readerPrefs.updatePiperModel(it) }
        sheet.onKokoroSpeakerSelected = { readerPrefs.updateKokoroSpeaker(it) }
        sheet.onKokoroModelSelected = { readerPrefs.updateKokoroModel(it) }
        sheet.onKokoroPreloadEnabledChanged = { readerPrefs.updateKokoroPreloadEnabled(it) }
        sheet.show(supportFragmentManager, "TTS_SETTINGS")
    }

    override fun getTtsSpeed(): Double = readerPrefs.ttsSpeed
    override fun getTtsPitch(): Double = readerPrefs.ttsPitch
    override fun getTtsEngine(): String = readerPrefs.ttsEngine
    override fun getTtsVoices(): Set<AndroidTtsEngine.Voice> = emptySet()
    override fun getTtsSelectedVoiceId(): AndroidTtsEngine.Voice.Id? =
        readerPrefs.ttsVoiceId?.let { AndroidTtsEngine.Voice.Id(it) }

    override fun getTtsLanguage(): Language? = null
    override fun isPiperModelReady(): Boolean {
        val model = TtsModelManager.PIPER_MODELS.find { it.id == readerPrefs.selectedPiperModelId }
            ?: TtsModelManager.PIPER_MODELS.first()
        return modelManager.isModelDownloaded(model)
    }
    override fun getSelectedPiperModelId(): String = readerPrefs.selectedPiperModelId
    override fun getSelectedKokoroSpeakerId(): Int = readerPrefs.selectedKokoroSpeakerId
    override fun getSelectedKokoroModelId(): String = readerPrefs.selectedKokoroModelId
    override fun isKokoroPreloadEnabled(): Boolean = readerPrefs.kokoroPreloadEnabled
    override fun onTtsSpeedChanged(speed: Double) = readerPrefs.updateTtsSpeed(speed)
    override fun onTtsPitchChanged(pitch: Double) = readerPrefs.updateTtsPitch(pitch)
    override fun onTtsVoiceSelected(voice: AndroidTtsEngine.Voice) {
        readerPrefs.updateTtsVoice(voice.id.value, voice.language.locale.toLanguageTag())
    }
    override fun onTtsEngineSelected(engine: String) = readerPrefs.updateTtsEngine(engine)
    override fun onPiperModelSelected(modelId: String) = readerPrefs.updatePiperModel(modelId)
    override fun onKokoroSpeakerSelected(speakerId: Int) = readerPrefs.updateKokoroSpeaker(speakerId)
    override fun onKokoroModelSelected(modelId: String) = readerPrefs.updateKokoroModel(modelId)
    override fun onKokoroPreloadEnabledChanged(enabled: Boolean) {
        readerPrefs.updateKokoroPreloadEnabled(enabled)
    }

    private fun importEpub(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        } else null
                    } ?: "book_${UUID.randomUUID()}.epub"

                    val safeDisplayName = displayName.substringAfterLast('/').substringAfterLast('\\')
                    val destFile = uniqueFile(booksDir, safeDisplayName)
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open selected EPUB")
                    inputStream.use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                    if (!destFile.exists() || destFile.length() == 0L) {
                        destFile.delete()
                        throw IllegalStateException("Selected EPUB was empty or could not be copied")
                    }

                    // Fast insert with placeholder metadata, at the front of the list
                    val provisionalTitle = destFile.nameWithoutExtension
                    val provisionalAuthor = "Loading…"
                    val coverPath: String? = null
                    val nextOrder = db.bookDao().getMaxSortOrder() + 1

                    val rowId = db.bookDao().insert(
                        BookEntity(
                            title = provisionalTitle,
                            author = provisionalAuthor,
                            epubPath = destFile.absolutePath,
                            coverPath = coverPath,
                            sortOrder = nextOrder
                        )
                    )

                    // Background metadata extraction
                    enqueueMetadataWork(bookId = rowId.toInt(), epubPath = destFile.absolutePath)

                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            result.fold(
                onSuccess = {
                    Toast.makeText(this@MainActivity, getString(R.string.book_added), Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(this@MainActivity, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun enqueueMetadataWork(bookId: Int, epubPath: String) {
        val input = Data.Builder()
            .putInt(BookMetadataWorker.KEY_BOOK_ID, bookId)
            .putString(BookMetadataWorker.KEY_EPUB_PATH, epubPath)
            .build()

        val request = OneTimeWorkRequestBuilder<BookMetadataWorker>()
            .setInputData(input)
            .addTag("meta_$bookId") // so we can cancel it on delete
            .build()

        WorkManager.getInstance(this).enqueue(request)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val baseName = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists()) {
            val suffix = if (ext.isNotEmpty()) ".$ext" else ""
            candidate = File(dir, "${baseName}_$counter$suffix")
            counter++
        }
        return candidate
    }

    private fun persistBookOrder(books: List<BookEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            // First item in the list (position 0) gets the highest sortOrder
            val count = books.size
            for ((index, book) in books.withIndex()) {
                db.bookDao().updateSortOrder(book.id, count - index)
            }
        }
    }

    private fun deleteBook(bookId: Int) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Cancel any pending metadata work
                WorkManager.getInstance(this@MainActivity).cancelAllWorkByTag("meta_$bookId")

                // Fetch book for file paths
                val book = db.bookDao().getById(bookId) ?: return@withContext

                // Delete epub file
                runCatching { File(book.epubPath).takeIf { it.exists() }?.delete() }

                // Delete cover file if exists
                runCatching {
                    book.coverPath?.let { path ->
                        File(path).takeIf { it.exists() }?.delete()
                    }
                }

                // Delete bookmarks and DB row
                db.bookmarkDao().deleteByBook(bookId)
                db.bookDao().deleteById(bookId)
            }

            Toast.makeText(this@MainActivity, getString(R.string.book_deleted), Toast.LENGTH_SHORT).show()
        }
    }
}
