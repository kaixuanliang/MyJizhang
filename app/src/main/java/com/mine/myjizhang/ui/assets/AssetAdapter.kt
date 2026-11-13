package com.mine.myjizhang.ui.assets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.util.Money

/** 列表项：分组头 / 账户 / 组间隔 */
sealed class AssetRow {
    data class Header(val group: String, val total: String) : AssetRow()
    data class Item(val asset: Asset) : AssetRow()
    object Spacer : AssetRow()
}

class AssetAdapter(
    private val onClick: (Asset) -> Unit,
    private val onLongClick: (Asset) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<AssetRow> = emptyList()
        private set

    private fun amountText(v: Double): String {
        return (if (v < 0) "-¥" else "¥") + Money.fmt(Math.abs(v))
    }

    /** 按三大分组生成行：信贷账户 → 资金账户 → 投资理财账户 */
    fun submit(assets: List<Asset>) {
        val list = mutableListOf<AssetRow>()
        for (group in AssetTypes.groups) {
            val items = assets.filter {
                AssetTypes.groupOf(it.subType, it.type, it.name) == group
            }
            if (items.isEmpty()) continue
            val total = items.sumOf { it.amount }
            list.add(AssetRow.Header(group, amountText(total)))
            items.forEach { list.add(AssetRow.Item(it)) }
            list.add(AssetRow.Spacer)
        }
        rows = list
        notifyDataSetChanged()
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_section)
        val total: TextView = view.findViewById(R.id.text_section_total)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.text_icon)
        val name: TextView = view.findViewById(R.id.text_name)
        val amount: TextView = view.findViewById(R.id.text_amount)
        val progress: ProgressBar = view.findViewById(R.id.progress_quota)
        val creditInfo: View = view.findViewById(R.id.row_credit_info)
        val repayment: TextView = view.findViewById(R.id.text_repayment)
        val available: TextView = view.findViewById(R.id.text_available)
    }

    class SpacerHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is AssetRow.Header -> 0
        is AssetRow.Item -> 1
        else -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderHolder(inf.inflate(R.layout.item_asset_section, parent, false))
            1 -> Holder(inf.inflate(R.layout.item_asset, parent, false))
            else -> SpacerHolder(inf.inflate(R.layout.item_spacer, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val ctx = holder.itemView.context
        when {
            holder is HeaderHolder -> {
                val h = row as AssetRow.Header
                holder.title.text = h.group
                holder.total.text = h.total
                holder.total.setTextColor(
                    ctx.getColor(
                        if (h.group == AssetTypes.GROUP_CREDIT) R.color.expense_red else R.color.text_primary
                    )
                )
                holder.itemView.setBackgroundResource(R.drawable.bg_card_top)
            }
            holder is SpacerHolder -> {
                holder.itemView.background = null
            }
            holder is Holder -> {
                val a = (row as AssetRow.Item).asset
                holder.itemView.setBackgroundResource(R.drawable.bg_card_bottom)
                AssetTypes.bindIcon(holder.icon, a.name, a.subType, a.icon)
                holder.name.text = a.name
                holder.amount.text = amountText(a.amount)
                holder.amount.setTextColor(
                    ctx.getColor(if (a.amount < 0) R.color.expense_red else R.color.text_primary)
                )
                if (a.type == 1 && a.totalQuota > 0) {
                    val used = Math.abs(a.amount.coerceAtMost(0.0))
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.max = (a.totalQuota * 100).toInt().coerceAtLeast(1)
                    holder.progress.progress = (used * 100).toInt()
                    holder.creditInfo.visibility = View.VISIBLE
                    holder.repayment.text =
                        if (a.repaymentDate in 1..31) "每月 ${a.repaymentDate} 号还款" else "未设置还款日"
                    holder.available.text = "可用：" + amountText(a.totalQuota - used)
                } else {
                    holder.progress.visibility = View.GONE
                    holder.creditInfo.visibility = View.GONE
                }
                holder.itemView.setOnClickListener { onClick(a) }
                holder.itemView.setOnLongClickListener {
                    onLongClick(a)
                    true
                }
            }
        }
    }
}
