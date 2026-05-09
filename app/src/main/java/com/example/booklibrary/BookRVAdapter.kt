
package com.example.booklibrary

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.booklibrary.data.BookEntity

class BookRVAdapter(
    private val context: Context,
    private val onOpen: (BookEntity) -> Unit,
    private val onDelete: (BookEntity) -> Unit,
    private val onReorder: ((List<BookEntity>) -> Unit)? = null
) : RecyclerView.Adapter<BookRVAdapter.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    var viewType: Int = VIEW_TYPE_GRID

    private var books: MutableList<BookEntity> = mutableListOf()
    private var isDragging = false

    fun setBooks(newBooks: List<BookEntity>) {
        if (isDragging) return
        val diffCallback = BookDiffCallback(books, newBooks)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        books = newBooks.toMutableList()
        diffResult.dispatchUpdatesTo(this)
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= books.size || toPosition >= books.size) return
        val item = books.removeAt(fromPosition)
        books.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onDragStart() { isDragging = true }

    fun onDragEnd() {
        isDragging = false
        onReorder?.invoke(books.toList())
    }

    fun getBooks(): List<BookEntity> = books

    override fun getItemViewType(position: Int): Int = viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == VIEW_TYPE_LIST) R.layout.book_list_item else R.layout.book_rv_item
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val book = books[position]

        holder.title.text = book.title
        holder.author.text = book.author

        val placeholder = if (holder.itemViewType == VIEW_TYPE_LIST)
            R.drawable.ic_book_placeholder else R.drawable.ic_book_placeholder_large
        Glide.with(context)
            .load(book.coverPath)
            .placeholder(placeholder)
            .error(placeholder)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .centerCrop()
            .into(holder.cover)

        holder.itemView.setOnClickListener {
            onOpen(book)
        }

        holder.itemView.setOnLongClickListener {
            onDelete(book)
            true
        }

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag?.invoke(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = books.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.idIVBook)
        val title: TextView = view.findViewById(R.id.idTVBookTitle)
        val author: TextView = view.findViewById(R.id.idTVBookAuthor)
        val dragHandle: ImageView = view.findViewById(R.id.drag_handle)
    }

    private class BookDiffCallback(
        private val oldList: List<BookEntity>,
        private val newList: List<BookEntity>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldBook = oldList[oldItemPosition]
            val newBook = newList[newItemPosition]
            return oldBook.title == newBook.title &&
                    oldBook.author == newBook.author &&
                    oldBook.coverPath == newBook.coverPath
        }
    }
}
