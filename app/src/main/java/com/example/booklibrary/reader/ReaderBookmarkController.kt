package com.example.booklibrary.reader

import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.booklibrary.R
import com.example.booklibrary.data.AppDatabase
import com.example.booklibrary.data.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class ReaderBookmarkController(
    private val activity: androidx.appcompat.app.AppCompatActivity,
    private val db: AppDatabase,
    private val bookId: Int,
    private val getEpubFragment: () -> EpubNavigatorFragment?,
    private val getCurrentChapterTitle: () -> String?
) {
    @Volatile
    var cachedBookmarks: List<BookmarkEntity> = emptyList()
        private set

    fun load() {
        if (bookId <= 0) return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            cachedBookmarks = db.bookmarkDao().getByBook(bookId)
        }
    }

    fun toggle(bookmarkBtn: ImageButton) {
        val frag = getEpubFragment() ?: return
        if (bookId <= 0) return

        val locator = frag.currentLocator.value
        val progression = locator.locations.totalProgression ?: 0.0
        val base64 = LocatorSerializer.toBase64(locator)
        val snippet = locator.text.highlight ?: locator.text.before?.takeLast(60)
        val chapterTitle = getCurrentChapterTitle()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val existing = cachedBookmarks.find {
                kotlin.math.abs(it.progression - progression) < 0.001
            }
            if (existing != null) {
                db.bookmarkDao().deleteById(existing.id)
                cachedBookmarks = db.bookmarkDao().getByBook(bookId)
                withContext(Dispatchers.Main) {
                    bookmarkBtn.setImageResource(R.drawable.ic_bookmark)
                    Toast.makeText(activity, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
                }
            } else {
                db.bookmarkDao().insert(
                    BookmarkEntity(
                        bookId = bookId,
                        locatorBase64 = base64,
                        title = chapterTitle,
                        textSnippet = snippet,
                        progression = progression
                    )
                )
                cachedBookmarks = db.bookmarkDao().getByBook(bookId)
                withContext(Dispatchers.Main) {
                    bookmarkBtn.setImageResource(R.drawable.ic_bookmark_filled)
                    Toast.makeText(activity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            db.bookmarkDao().deleteById(bookmark.id)
            cachedBookmarks = db.bookmarkDao().getByBook(bookId)
        }
    }
}
