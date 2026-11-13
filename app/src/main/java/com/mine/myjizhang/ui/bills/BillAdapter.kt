package com.mine.myjizhang.ui.bills

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 列表项：日分组头 或 账单 */
sealed class Row {
    data class Day(val dayMillis: Long) : Row()
    data class BillRow(val bill: Bill) : Row()
}

class BillAdapter(
    private val onClick: (Bill) -> Unit,
    private val onLongClick: (Bill) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()

    fun submitRows(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }
    var categoryName: (String) -> String = { "" }
    var categoryIcon: (String) -> String = { "💡" }
    var assetName: (String) -> String = { "" }

    fun submit(bills: List<Bill>) {
        val list = mutableListOf<Row>()
        var lastDay = -1L
        for (b in bills) {
            val day = DateTimes.dayStart(b.time)
            if (day != lastDay) {
                list.add(Row.Day(day))
                lastDay = day
            }
            list.add(Row.BillRow(b))
        }
        rows = list
        notifyDataSetChanged()
    }

    class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dayText: TextView = view.findViewById(R.id.text_day)
        val dayTotal: TextView = view.findViewById(R.id.text_day_total)
    }

    class BillHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.text_icon)
        val title: TextView = view.findViewById(R.id.text_title)
        val sub: TextView = view.findViewById(R.id.text_sub)
        val amount: TextView = view.findViewById(R.id.text_amount)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Day) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            DayHolder(inf.inflate(R.layout.item_bill_day, parent, false))
        } else {
            BillHolder(inf.inflate(R.layout.item_bill, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is DayHolder) {
            val dayRow = row as Row.Day
            val todayStart = DateTimes.dayStart(System.currentTimeMillis())
            val label = when (dayRow.dayMillis) {
                todayStart -> "今天"
                todayStart - 24L * 3600 * 1000 -> "昨天"
                else -> SimpleDateFormat("EEE", Locale.CHINA)
                    .format(java.util.Date(dayRow.dayMillis))
            }
            holder.dayText.text =
                SimpleDateFormat("M月d日", Locale.CHINA).format(java.util.Date(dayRow.dayMillis)) +
                    "  " + label
            val dayBills = rows.filterIsInstance<Row.BillRow>()
                .filter { DateTimes.dayStart(it.bill.time) == dayRow.dayMillis }
                .map { it.bill }
            val pay = dayBills.filter { it.type == 0 }
                .sumOf { com.mine.myjizhang.data.Finance.netExpense(it) }
            val income = dayBills.filter { it.type == 1 }
                .sumOf { com.mine.myjizhang.data.Finance.income(it) }
            val sb = StringBuilder()
            if (pay > 0) sb.append("支：").append(Money.fmt(pay))
            if (income > 0) {
                if (sb.isNotEmpty()) sb.append("  ")
                sb.append("收：").append(Money.fmt(income))
            }
            holder.dayTotal.text = sb.toString()
        } else if (holder is BillHolder) {
            val b = (row as Row.BillRow).bill
            val context = holder.itemView.context
            when (b.type) {
                0 -> {
                    holder.icon.text = categoryIcon(b.categoryId)
                    holder.title.text = categoryName(b.categoryId).ifBlank { "支出" }
                    holder.amount.text = "-¥" + Money.fmt(b.amount)
                    holder.amount.setTextColor(context.getColor(R.color.expense_red))
                }
                1 -> {
                    holder.icon.text = categoryIcon(b.categoryId)
                    holder.title.text = categoryName(b.categoryId).ifBlank { "收入" }
                    holder.amount.text = "+¥" + Money.fmt(b.amount)
                    holder.amount.setTextColor(context.getColor(R.color.income_green))
                }
                else -> {
                    holder.icon.text = "🔁"
                    holder.title.text = "转账"
                    holder.amount.text = "¥" + Money.fmt(b.amount)
                    holder.amount.setTextColor(context.getColor(R.color.transfer_blue))
                }
            }
            val subParts = mutableListOf<String>()
            if (b.type == 2) {
                subParts.add(assetName(b.fromAssetId) + " → " + assetName(b.toAssetId))
            } else {
                val acc = assetName(b.assetId)
                if (acc.isNotBlank()) subParts.add(acc)
            }
            if (b.remark.isNotBlank()) subParts.add(b.remark)
            if (b.serviceCharge > 0) subParts.add("手续费 ¥" + Money.fmt(b.serviceCharge))
            if (b.refundAmount > 0) subParts.add("已退款 ¥" + Money.fmt(b.refundAmount))
            if (b.shouldReimburse) subParts.add("待报销")
            holder.sub.text = subParts.joinToString(" · ")
            holder.sub.visibility = if (subParts.isEmpty()) View.GONE else View.VISIBLE

            holder.itemView.setOnClickListener { onClick(b) }
            holder.itemView.setOnLongClickListener {
                onLongClick(b)
                true
            }
        }
    }
}
