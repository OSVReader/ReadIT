package com.example.booklibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.booklibrary.data.BookmarkEntity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BookmarksBottomSheet : BottomSheetDialogFragment() {

    interface Host {
        fun getBookmarks(): List<BookmarkEntity>
        fun onBookmarkSelected(bookmark: BookmarkEntity)
        fun onBookmarkDeleted(bookmark: BookmarkEntity)
    }

    companion object {
        fun newInstance(): BookmarksBottomSheet = BookmarksBottomSheet()
    }

    private var pendingBookmarks: List<BookmarkEntity>? = null
    var onSelect: ((BookmarkEntity) -> Unit)? = null
    var onDelete: ((BookmarkEntity) -> Unit)? = null

    fun setBookmarks(bookmarks: List<BookmarkEntity>) {
        pendingBookmarks = bookmarks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_bookmarks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val bookmarks = pendingBookmarks
            ?: (activity as? Host)?.getBookmarks()
            ?: emptyList()

        val selectCb = onSelect ?: { bm -> (activity as? Host)?.onBookmarkSelected(bm) }
        val deleteCb = onDelete ?: { bm -> (activity as? Host)?.onBookmarkDeleted(bm) }

        val recycler = view.findViewById<RecyclerView>(R.id.rv_bookmarks)
        val emptyView = view.findViewById<TextView>(R.id.tv_no_bookmarks)

        if (bookmarks.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = BookmarkAdapter(bookmarks, { bm ->
            selectCb(bm)
            dismiss()
        }, { bm ->
            deleteCb(bm)
            dismiss()
        })
    }

    private class BookmarkAdapter(
        private val items: List<BookmarkEntity>,
        private val onSelect: (BookmarkEntity) -> Unit,
        private val onDelete: (BookmarkEntity) -> Unit
    ) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tv_bookmark_title)
            val snippet: TextView = view.findViewById(R.id.tv_bookmark_snippet)
            val progress: TextView = view.findViewById(R.id.tv_bookmark_progress)
            val deleteBtn: ImageButton = view.findViewById(R.id.btn_delete_bookmark)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bookmark, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val bm = items[position]
            holder.title.text = bm.title ?: "Bookmark"
            holder.snippet.text = bm.textSnippet ?: ""
            holder.snippet.visibility = if (bm.textSnippet.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.progress.text = "${(bm.progression * 100).toInt()}%"
            holder.itemView.setOnClickListener { onSelect(bm) }
            holder.deleteBtn.setOnClickListener { onDelete(bm) }
        }

        override fun getItemCount(): Int = items.size
    }
}
