package com.mine.myjizhang.ui.reconcile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.entity.Adjust
import com.mine.myjizhang.util.Money
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 对账列表的行：日期分组头 / 一条调整记录 */
sealed class AdjustRow {
    data class Day(val label: String, val total: String) : AdjustRow()
    data class Item(val adjust: Adjust) : AdjustRow()
}

/** 对账记录列表：按天分组，一条一条展示 */
class AdjustAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<AdjustRow> = emptyList()

    fun submit(list: List<Adjust>) {
        val sorted = list.sortedByDescending { it.time }
        val out = mutableListOf<AdjustRow>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        var currentDay = ""
        var dayTotal = 0.0
        val buffer = mutableListOf<Adjust>()

        fun flush() {
            if (buffer.isEmpty()) return
            out.add(AdjustRow.Day(dayLabel(currentDay), totalText(dayTotal)))
            buffer.forEach { out.add(AdjustRow.Item(it)) }
            buffer.clear()
        }

        for (a in sorted) {
            val day = dayFmt.format(Date(a.time))
            if (day != currentDay) {
                flush()
                currentDay = day
                dayTotal = 0.0
            }
            dayTotal += a.amount
            buffer.add(a)
        }
        flush()
        rows = out
        notifyDataSetChanged()
    }

    private fun dayLabel(day: String): String {
        val fmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val d = try { fmtIn.parse(day) } catch (e: Exception) { null } ?: return day
        val cal = Calendar.getInstance().apply { time = d }
        val week = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
        return SimpleDateFormat("M月d日", Locale.CHINA).format(d) + "  " + week
    }

    private fun totalText(v: Double): String = when {
        Math.abs(v) < 0.005 -> "无净调整"
        v > 0 -> "+" + Money.fmt(v)
        else -> "-" + Money.fmt(v)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is AdjustRow.Day) 0 else 1

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            DayHolder(inf.inflate(R.layout.item_bill_day, parent, false))
        } else {
            Holder(inf.inflate(R.layout.item_adjust, parent, false))
        }
    }

    class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        val day: TextView = view.findViewById(R.id.text_day)
        val total: TextView = view.findViewById(R.id.text_day_total)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val kind: TextView = view.findViewById(R.id.text_kind)
        val asset: TextView = view.findViewById(R.id.text_asset)
        val amount: TextView = view.findViewById(R.id.text_amount)
        val note: TextView = view.findViewById(R.id.text_note)
        val time: TextView = view.findViewById(R.id.text_time)
        val balanceAfter: TextView = view.findViewById(R.id.text_balance_after)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val ctx = holder.itemView.context
        when {
            holder is DayHolder -> {
                val d = row as AdjustRow.Day
                holder.day.text = d.label
                holder.total.text = d.total
            }
            holder is Holder -> {
                val a = (row as AdjustRow.Item).adjust
                val accent = when (a.kind) {
                    Adjust.KIND_ASSET_BALANCE -> R.color.transfer_blue
                    Adjust.KIND_BILL_DELETE, Adjust.KIND_BILL_EDIT -> R.color.expense_red
                    Adjust.KIND_LOAN_REPAY, Adjust.KIND_LOAN_DELETE -> R.color.income_green
                    Adjust.KIND_BILL_RESTORE -> R.color.income_green
                    else -> R.color.text_secondary
                }
                val color = ctx.getColor(accent)
                holder.kind.text = Adjust.kindName(a.kind)
                holder.kind.setTextColor(color)
                holder.kind.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 20f
                    setColor((color and 0x00FFFFFF) or (0x22 shl 24))
                }
                holder.asset.text = a.assetName.ifBlank { "已删除账户" }

                when {
                    Math.abs(a.amount) < 0.005 -> {
                        holder.amount.text = "备忘"
                        holder.amount.setTextColor(ctx.getColor(R.color.text_secondary))
                    }
                    a.amount > 0 -> {
                        holder.amount.text = "+" + Money.fmt(a.amount)
                        holder.amount.setTextColor(ctx.getColor(R.color.income_green))
                    }
                    else -> {
                        holder.amount.text = "-" + Money.fmt(a.amount)
                        holder.amount.setTextColor(ctx.getColor(R.color.expense_red))
                    }
                }

                holder.note.visibility = if (a.note.isBlank()) View.GONE else View.VISIBLE
                holder.note.text = a.note
                holder.time.text = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(a.time))
                holder.balanceAfter.text = "调整后余额 " + (if (a.balanceAfter < 0) "-¥" else "¥") +
                    Money.fmt(a.balanceAfter)
            }
        }
    }
}
