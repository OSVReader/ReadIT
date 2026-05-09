package com.example.booklibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.readium.r2.shared.publication.Link

class TocBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Host {
        fun getTocLinks(): List<Link>
        fun onTocLinkSelected(link: Link)
    }

    companion object {
        private const val ARG_TITLE = "arg_title"

        fun newInstance(title: String?, toc: List<Link>, onClick: (Link) -> Unit): TocBottomSheetDialogFragment {
            return TocBottomSheetDialogFragment().apply {
                this.pendingTocLinks = toc
                this.pendingOnLinkClick = onClick
                arguments = Bundle().apply { putString(ARG_TITLE, title) }
            }
        }
    }

    // Used only for initial creation; on recreation we fall back to Host
    private var pendingTocLinks: List<Link>? = null
    private var pendingOnLinkClick: ((Link) -> Unit)? = null

    private val tocLinks: List<Link>
        get() = pendingTocLinks ?: (activity as? Host)?.getTocLinks() ?: emptyList()

    private fun onLinkClicked(link: Link) {
        val pending = pendingOnLinkClick
        if (pending != null) {
            pending(link)
        } else {
            (activity as? Host)?.onTocLinkSelected(link)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_toc, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = arguments?.getString(ARG_TITLE) ?: getString(R.string.toc_title)
        view.findViewById<TextView>(R.id.toc_title).text = title

        val recycler = view.findViewById<RecyclerView>(R.id.toc_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val links = tocLinks
        if (links.isEmpty()) {
            dismiss()
            return
        }

        val flat = flattenToc(links)
        recycler.adapter = TocAdapter(flat) { link ->
            onLinkClicked(link)
            dismiss()
        }
    }

    private data class TocRow(val link: Link, val depth: Int)

    private fun flattenToc(links: List<Link>, depth: Int = 0, out: MutableList<TocRow> = mutableListOf()): List<TocRow> {
        for (l in links) {
            out.add(TocRow(l, depth))
            val children = l.children
            if (!children.isNullOrEmpty()) {
                flattenToc(children, depth + 1, out)
            }
        }
        return out
    }

    private class TocAdapter(
        private val items: List<TocRow>,
        private val onClick: (Link) -> Unit
    ) : RecyclerView.Adapter<TocAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val text: TextView = itemView.findViewById(R.id.toc_item_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_toc, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            val label = row.link.title?.takeIf { it.isNotBlank() } ?: row.link.href.toString()

            val basePadding = holder.itemView.resources.displayMetrics.density * 16
            val extra = holder.itemView.resources.displayMetrics.density * (24 * row.depth)
            holder.text.setPadding((basePadding + extra).toInt(), holder.text.paddingTop, holder.text.paddingRight, holder.text.paddingBottom)

            holder.text.text = label
            holder.itemView.setOnClickListener { onClick(row.link) }
        }

        override fun getItemCount(): Int = items.size
    }
}
