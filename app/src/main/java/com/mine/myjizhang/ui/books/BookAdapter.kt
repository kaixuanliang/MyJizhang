package com.mine.myjizhang.ui.books

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.entity.AccountBook

/** 账本列表条目：账本 + 账单数 + 账户数 + 是否当前 */
data class BookRow(
    val book: AccountBook,
    val billCount: Int,
    val assetCount: Int,
    val isCurrent: Boolean
)

class BookAdapter(
    private val onSwitch: (AccountBook) -> Unit,
    private val onMore: (AccountBook) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    private var items: List<BookRow> = emptyList()

    fun submit(list: List<BookRow>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
        holder.name.text = row.book.name
        holder.meta.text = "${row.billCount} 笔账单 · ${row.assetCount} 个账户"
        holder.check.visibility = if (row.isCurrent) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onSwitch(row.book) }
        holder.more.setOnClickListener { onMore(row.book) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val check: TextView = view.findViewById(R.id.text_check)
        val name: TextView = view.findViewById(R.id.text_name)
        val meta: TextView = view.findViewById(R.id.text_meta)
        val more: TextView = view.findViewById(R.id.btn_more)
    }
}
