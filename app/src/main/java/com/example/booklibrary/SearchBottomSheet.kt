@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.example.booklibrary

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

class SearchBottomSheet : BottomSheetDialogFragment() {

    interface Host {
        fun getPublication(): Publication?
        fun getTableOfContents(): List<Link>
        fun onSearchResultSelected(locator: Locator)
    }

    companion object {
        fun newInstance(): SearchBottomSheet = SearchBottomSheet()
    }

    var onResultSelected: ((Locator) -> Unit)? = null

    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etSearch = view.findViewById<TextInputEditText>(R.id.et_search)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_search)
        val tvStatus = view.findViewById<TextView>(R.id.tv_search_status)
        val rvResults = view.findViewById<RecyclerView>(R.id.rv_search_results)

        rvResults.layoutManager = LinearLayoutManager(requireContext())

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearch.text?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    hideKeyboard(etSearch)
                    performSearch(query, progressBar, tvStatus, rvResults)
                }
                true
            } else false
        }

        etSearch.requestFocus()
        etSearch.postDelayed({
            val ctx = context ?: return@postDelayed
            val imm = ContextCompat.getSystemService(ctx, InputMethodManager::class.java)
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
    }

    private fun hideKeyboard(view: View) {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun performSearch(
        query: String,
        progressBar: ProgressBar,
        tvStatus: TextView,
        rvResults: RecyclerView
    ) {
        val pub = (activity as? Host)?.getPublication() ?: return
        val toc = (activity as? Host)?.getTableOfContents() ?: emptyList()

        searchJob?.cancel()
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.search_searching)
        rvResults.adapter = null

        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                searchPublication(pub, toc, query)
            }

            progressBar.visibility = View.GONE

            if (results.isEmpty()) {
                tvStatus.text = getString(R.string.search_no_results)
                return@launch
            }

            tvStatus.text = getString(R.string.search_results, results.size)

            val selectCb = onResultSelected ?: { loc ->
                (activity as? Host)?.onSearchResultSelected(loc)
            }

            rvResults.adapter = SearchResultAdapter(results, query) { result ->
                selectCb(result.locator)
                dismiss()
            }
        }
    }

    private suspend fun searchPublication(
        pub: Publication,
        toc: List<Link>,
        query: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val lowerQuery = query.lowercase()

        for (link in pub.readingOrder) {
            coroutineContext.ensureActive()

            val chapterTitle = findTocTitle(toc, link) ?: link.title ?: ""
            val locator = Locator(href = link.url(), mediaType = link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType("application/xhtml+xml")!!)
            val content = pub.content(locator) ?: continue

            val iterator = content.iterator()
            while (iterator.hasNext()) {
                coroutineContext.ensureActive()
                val element = iterator.next()
                if (element !is Content.TextElement) continue

                val fullText = element.text ?: continue
                val lowerText = fullText.lowercase()
                var searchFrom = 0

                while (true) {
                    val idx = lowerText.indexOf(lowerQuery, searchFrom)
                    if (idx < 0) break

                    val snippetStart = maxOf(0, idx - 40)
                    val snippetEnd = minOf(fullText.length, idx + query.length + 40)
                    val snippet = (if (snippetStart > 0) "\u2026" else "") +
                        fullText.substring(snippetStart, snippetEnd) +
                        (if (snippetEnd < fullText.length) "\u2026" else "")

                    val matchText = fullText.substring(idx, idx + query.length)
                    val before = fullText.substring(maxOf(0, idx - 30), idx)
                    val after = fullText.substring(
                        minOf(fullText.length, idx + query.length),
                        minOf(fullText.length, idx + query.length + 30)
                    )

                    val locator = element.locator.copy(
                        text = Locator.Text(
                            before = before.takeIf { it.isNotBlank() },
                            highlight = matchText,
                            after = after.takeIf { it.isNotBlank() }
                        )
                    )

                    results.add(SearchResult(
                        chapterTitle = chapterTitle,
                        snippet = snippet,
                        matchStartInSnippet = idx - snippetStart + (if (snippetStart > 0) 1 else 0),
                        matchLength = query.length,
                        locator = locator
                    ))

                    searchFrom = idx + query.length
                    if (results.size >= 200) break
                }
                if (results.size >= 200) break
            }
            if (results.size >= 200) break
        }
        return results
    }

    private fun findTocTitle(toc: List<Link>, link: Link): String? {
        for (entry in toc) {
            if (entry.url() == link.url()) return entry.title
            val child = findTocTitle(entry.children, link)
            if (child != null) return child
        }
        return null
    }

    data class SearchResult(
        val chapterTitle: String,
        val snippet: String,
        val matchStartInSnippet: Int,
        val matchLength: Int,
        val locator: Locator
    )

    private class SearchResultAdapter(
        private val results: List<SearchResult>,
        private val query: String,
        private val onClick: (SearchResult) -> Unit
    ) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val chapter: TextView = view.findViewById(R.id.tv_result_chapter)
            val snippet: TextView = view.findViewById(R.id.tv_result_snippet)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_result, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val result = results[position]
            holder.chapter.text = result.chapterTitle
            holder.chapter.visibility = if (result.chapterTitle.isBlank()) View.GONE else View.VISIBLE

            val spannable = SpannableString(result.snippet)
            val start = result.matchStartInSnippet.coerceIn(0, result.snippet.length)
            val end = (start + result.matchLength).coerceIn(start, result.snippet.length)
            if (start < end) {
                val color = ContextCompat.getColor(holder.itemView.context, R.color.primary)
                spannable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            holder.snippet.text = spannable

            holder.itemView.setOnClickListener { onClick(result) }
        }

        override fun getItemCount(): Int = results.size
    }
}
