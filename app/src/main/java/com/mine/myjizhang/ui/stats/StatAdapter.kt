package com.mine.myjizhang.ui.stats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.util.Money

class StatAdapter : RecyclerView.Adapter<StatAdapter.Holder>() {

    data class Item(val icon: String, val name: String, val amount: Double, val percent: Float, val color: Int)

    var items: List<Item> = emptyList()
        private set

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.text_icon)
        val name: TextView = view.findViewById(R.id.text_name)
        val amount: TextView = view.findViewById(R.id.text_amount)
        val percent: TextView = view.findViewById(R.id.text_percent)
        val bar: ProgressBar = view.findViewById(R.id.progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_stat, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val it = items[position]
        holder.icon.text = it.icon
        holder.name.text = it.name
        holder.amount.text = "¥" + Money.fmt(it.amount)
        holder.percent.text = String.format("%.1f%%", it.percent)
        holder.bar.progress = it.percent.toInt().coerceIn(0, 100)
        holder.bar.progressTintList =
            android.content.res.ColorStateList.valueOf(it.color)
    }
}
