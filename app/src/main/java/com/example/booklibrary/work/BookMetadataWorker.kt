
package com.example.booklibrary.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.booklibrary.data.AppDatabase
import com.example.booklibrary.util.SafeLog
import kotlinx.coroutines.CancellationException
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class BookMetadataWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getInt(KEY_BOOK_ID, -1)
        val epubPath = inputData.getString(KEY_EPUB_PATH)

        if (bookId <= 0 || epubPath.isNullOrBlank()) {
            SafeLog.e(TAG, "Invalid metadata input")
            return Result.failure()
        }

        // Limit retry attempts to prevent infinite loops
        if (runAttemptCount >= 3) {
            SafeLog.e(TAG, "Metadata extraction retry limit reached")
            return Result.failure()
        }

        return try {
            val epubFile = File(epubPath)
            if (!epubFile.exists()) {
                SafeLog.e(TAG, "EPUB file not found for metadata extraction")
                return Result.failure()
            }

            // Check file is readable
            if (!epubFile.canRead()) {
                SafeLog.e(TAG, "EPUB file not readable for metadata extraction")
                return Result.failure()
            }

            // Readium open pipeline
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(applicationContext.contentResolver, httpClient)

            val publicationParser = DefaultPublicationParser(
                context = applicationContext,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null
            )

            val publicationOpener = PublicationOpener(
                publicationParser = publicationParser,
                contentProtections = emptyList()
            )

            val asset = assetRetriever.retrieve(epubFile.toUrl())
                .getOrElse { err -> 
                    SafeLog.e(TAG, "Failed to retrieve asset for metadata extraction")
                    // Don't retry if file is fundamentally broken
                    return if (err.toString().contains("ZipException") || 
                               err.toString().contains("FileNotFoundException")) {
                        Result.failure()
                    } else {
                        Result.retry()
                    }
                }

            val publication = publicationOpener.open(asset, allowUserInteraction = false)
                .getOrElse { err ->
                    SafeLog.e(TAG, "Failed to open publication for metadata extraction")
                    return if (err.toString().contains("ZipException") ||
                               err.toString().contains("ParserException") ||
                               err.toString().contains("corrupted")) {
                        Result.failure()
                    } else {
                        Result.retry()
                    }
                }

            try {
                val title = publication.metadata.title
                    ?.takeIf { it.isNotBlank() }
                    ?: epubFile.nameWithoutExtension

                val author = publication.metadata.authors
                    .joinToString(", ") { it.name }
                    .takeIf { it.isNotBlank() }
                    ?: "Unknown"

                val coverPath: String? = runCatching {
                    val coverLink =
                        (publication.links + publication.resources + publication.readingOrder)
                            .firstOrNull { it.rels.contains("cover") }
                            ?: return@runCatching null

                    val resource = publication.get(coverLink) ?: return@runCatching null

                    val bytes = resource.read()
                        .getOrElse { err -> throw IllegalStateException(err.toString()) }

                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching null

                    val coversDir = File(applicationContext.filesDir, "covers").apply { mkdirs() }
                    val coverFile = File(coversDir, "cover_$bookId.jpg")

                    coverFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }

                    coverFile.absolutePath
                }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    null
                }

                val db = AppDatabase.getInstance(applicationContext)
                db.bookDao().updateMetadata(bookId, title, author, coverPath)

                Result.success()
            } finally {
                publication.close()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            SafeLog.e(TAG, "Metadata extraction failed", t)
            
            // Determine if we should retry or fail permanently
            val shouldRetry = when (t) {
                is java.util.zip.ZipException -> false  // Corrupted file, don't retry
                is OutOfMemoryError -> false  // File too large, don't retry
                is SecurityException -> false  // Permission issue, don't retry
                else -> runAttemptCount < 2  // Other errors: retry up to 2 times
            }
            
            if (shouldRetry) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "BookMetadataWorker"
        const val KEY_BOOK_ID = "KEY_BOOK_ID"
        const val KEY_EPUB_PATH = "KEY_EPUB_PATH"
    }
}
