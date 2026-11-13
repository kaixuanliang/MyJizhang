package com.mine.myjizhang.ui.edit

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.entity.Category

class CategoryGridAdapter(private val onClick: (Category) -> Unit) :
    RecyclerView.Adapter<CategoryGridAdapter.Holder>() {

    var categories: List<Category> = emptyList()
        private set
    var selectedId: String = ""

    fun submit(list: List<Category>, selectedId: String) {
        categories = list
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.text_icon)
        val name: TextView = view.findViewById(R.id.text_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun getItemCount(): Int = categories.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val c = categories[position]
        val ctx = holder.itemView.context
        val d = ctx.resources.displayMetrics.density
        val selected = c.id == selectedId

        holder.icon.text = c.icon.ifBlank { "🔖" }
        holder.name.text = c.name

        // 圆形底：选中淡蓝，未选中浅灰
        holder.icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (selected) "#D6E7FB" else "#F3F4F6"))
        }
        holder.name.setTextColor(
            Color.parseColor(if (selected) "#1E6FD9" else "#5A5A60")
        )
        holder.icon.scaleX = if (selected) 1.02f else 1f
        holder.icon.scaleY = if (selected) 1.02f else 1f
        holder.itemView.alpha = 1f
        holder.itemView.setOnClickListener { onClick(c) }
    }
}
