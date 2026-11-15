package com.mine.myjizhang.ui.stats

import android.graphics.drawable.GradientDrawable
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
        // 圆形淡色底：颜色取该分类在饼图/进度条上的同色，视觉上归到同一类
        holder.icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((it.color and 0x00FFFFFF) or (0x22 shl 24))
        }
        holder.name.text = it.name
        holder.amount.text = "¥" + Money.fmt(it.amount)
        holder.percent.text = if (it.percent < 0.1f) "<0.1%"
        else String.format("%.1f%%", it.percent)
        // 0.5% 的进度条实算就是 0，留 1% 的细条，至少能看出「有这个分类」
        holder.bar.progress = it.percent.toInt().coerceAtLeast(1).coerceAtMost(100)
        holder.bar.progressTintList =
            android.content.res.ColorStateList.valueOf(it.color)
    }
}
