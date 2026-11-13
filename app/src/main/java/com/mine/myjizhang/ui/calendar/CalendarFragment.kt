package com.mine.myjizhang.ui.calendar

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.ui.bills.BillAdapter
import com.mine.myjizhang.ui.bills.Row
import com.mine.myjizhang.ui.edit.BillEditActivity
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class CalendarFragment : Fragment() {

    private val db by lazy { AppDatabase.get(requireContext()) }
    private val adapter = BillAdapter(
        onClick = { bill -> BillEditActivity.start(requireContext(), bill.id) },
        onLongClick = { }
    )

    private var year: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var selectedDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    private var collectJob: Job? = null

    private var categories: List<Category> = emptyList()
    private var assets: List<Asset> = emptyList()
    private var monthBills: List<Bill> = emptyList()

    /** 每个日期格子的可复用视图，便于单独刷新选中态 */
    private class DayCell(
        val root: LinearLayout,
        val num: TextView,
        val pay: TextView?,
        val income: TextView?
    )

    private val cells = HashMap<Int, DayCell>()

    private lateinit var textMonth: TextView
    private lateinit var grid: GridLayout
    private lateinit var textDayTitle: TextView
    private lateinit var textDaySum: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_calendar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textMonth = view.findViewById(R.id.text_month)
        grid = view.findViewById(R.id.calendar_grid)
        textDayTitle = view.findViewById(R.id.text_day_title)
        textDaySum = view.findViewById(R.id.text_day_sum)

        val rv = view.findViewById<RecyclerView>(R.id.recycler_day_bills)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        textMonth.setOnClickListener { showMonthPicker() }
        view.findViewById<View>(R.id.btn_add_bill).setOnClickListener {
            BillEditActivity.startNew(requireContext(), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 月份选择（年 / 月） */
    private fun showMonthPicker() {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(4))
        }
        val yearPicker = NumberPicker(ctx).apply {
            minValue = 2000
            maxValue = 2100
            value = year
            wrapSelectorWheel = false
        }
        val monthPicker = NumberPicker(ctx).apply {
            minValue = 1
            maxValue = 12
            value = month
            wrapSelectorWheel = true
        }
        val yearLabel = TextView(ctx).apply {
            text = " 年 "
            setTextColor(ctx.getColor(R.color.text_primary))
        }
        val monthLabel = TextView(ctx).apply {
            text = " 月"
            setTextColor(ctx.getColor(R.color.text_primary))
        }
        row.addView(yearPicker)
        row.addView(yearLabel)
        row.addView(monthPicker)
        row.addView(monthLabel)

        AlertDialog.Builder(ctx)
            .setTitle("选择月份")
            .setView(row)
            .setPositiveButton("确定") { _, _ ->
                year = yearPicker.value
                month = monthPicker.value
                // 切月后选中 1 号（当月则保留今天）
                val now = Calendar.getInstance()
                selectedDay = if (now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) + 1 == month)
                    now.get(Calendar.DAY_OF_MONTH) else 1
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun refresh() {
        textMonth.text = "${year}年${month}月 ▼"
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            val bookId = currentBookId()
            if (bookId.isBlank()) return@launch
            categories = db.categoryDao().getAll()
            assets = db.assetDao().getByBook(bookId)

            adapter.categoryName = { id -> categories.firstOrNull { it.id == id }?.name ?: "" }
            adapter.categoryIcon = { id -> categories.firstOrNull { it.id == id }?.icon ?: "💡" }
            val nameMap = assets.associate { it.id to it.name }
            adapter.assetName = { id -> nameMap[id] ?: "已删除账户" }

            val start = DateTimes.monthStart(year, month)
            val end = DateTimes.monthEnd(year, month)
            db.billDao().observeByMonth(bookId, start, end).collectLatest { bills ->
                monthBills = bills
                rebuildGrid()
                updateMonthSummary()
                showDay(selectedDay)
            }
        }
    }

    // ---------------- 日历网格 ----------------

    private fun rebuildGrid() {
        grid.removeAllViews()
        cells.clear()

        val ctx = requireContext()
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // 周一为一周起点：Calendar 里周日=1，故 (dow + 5) % 7 → 周一=0
        val leading = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

        if (selectedDay > daysInMonth) selectedDay = daysInMonth

        grid.columnCount = 7

        // 前导空位（保持与日期格等高，避免首行错位）
        for (i in 0 until leading) {
            grid.addView(View(ctx).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(56)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            })
        }

        val byDay = HashMap<Int, MutableList<Bill>>()
        for (b in monthBills) {
            val d = Calendar.getInstance().apply { timeInMillis = b.time }.get(Calendar.DAY_OF_MONTH)
            byDay.getOrPut(d) { mutableListOf() }.add(b)
        }

        for (d in 1..daysInMonth) {
            val dayBills = byDay[d] ?: emptyList()
            val pay = dayBills.filter { it.type == 0 }.sumOf { it.amount }
            val income = dayBills.filter { it.type == 1 }.sumOf { it.amount }

            val num = TextView(ctx).apply {
                text = d.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Color.parseColor("#26262B"))
            }
            var payView: TextView? = null
            var incView: TextView? = null
            if (pay > 0) {
                payView = TextView(ctx).apply {
                    text = "-" + Money.fmt(pay)
                    textSize = 8f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(ctx.getColor(R.color.expense_red))
                }
            }
            if (income > 0) {
                incView = TextView(ctx).apply {
                    text = "+" + Money.fmt(income)
                    textSize = 8f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(ctx.getColor(R.color.income_green))
                }
            }

            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(56)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
                addView(num, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                payView?.let {
                    addView(it, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
                incView?.let {
                    addView(it, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
                setOnClickListener {
                    val old = selectedDay
                    if (old == d) {
                        showDay(d)
                        return@setOnClickListener
                    }
                    selectedDay = d
                    paintDay(old)   // 之前的格子恢复常态
                    paintDay(d)     // 新的格子高亮
                    showDay(d)
                }
            }
            cells[d] = DayCell(cell, num, payView, incView)
            grid.addView(cell)
        }

        // 初始着色
        for (d in 1..daysInMonth) paintDay(d)
    }

    /** 重绘单个日期格：选中 = 蓝底白字；今天 = 浅蓝底 */
    private fun paintDay(day: Int) {
        val cell = cells[day] ?: return
        val ctx = requireContext()
        val selected = day == selectedDay
        val today = Calendar.getInstance().let {
            it.get(Calendar.YEAR) == year && it.get(Calendar.MONTH) + 1 == month &&
                it.get(Calendar.DAY_OF_MONTH) == day
        }

        cell.root.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(
                when {
                    selected -> Color.parseColor("#4C8FE0")
                    today -> Color.parseColor("#E8F1FD")
                    else -> Color.TRANSPARENT
                }
            )
        }

        val normal = Color.parseColor("#26262B")
        cell.num.setTextColor(if (selected) Color.WHITE else normal)
        cell.num.typeface = if (selected || today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        cell.pay?.setTextColor(
            if (selected) Color.parseColor("#E8F1FF") else ctx.getColor(R.color.expense_red)
        )
        cell.income?.setTextColor(
            if (selected) Color.parseColor("#DCF3E7") else ctx.getColor(R.color.income_green)
        )
    }

    private fun updateMonthSummary() {
        val pay = monthBills.filter { it.type == 0 }.sumOf { it.amount }
        val income = monthBills.filter { it.type == 1 }.sumOf { it.amount }
        view?.findViewById<TextView>(R.id.text_month_pay)?.text = Money.fmt(pay)
        view?.findViewById<TextView>(R.id.text_month_income)?.text = Money.fmt(income)
        view?.findViewById<TextView>(R.id.text_month_balance)?.text = Money.fmt(income - pay)
    }

    private fun showDay(day: Int) {
        val bills = monthBills.filter {
            Calendar.getInstance().apply { timeInMillis = it.time }.get(Calendar.DAY_OF_MONTH) == day
        }
        adapter.submitRows(bills.map { Row.BillRow(it) })

        val pay = bills.filter { it.type == 0 }.sumOf { it.amount }
        val income = bills.filter { it.type == 1 }.sumOf { it.amount }

        val today = Calendar.getInstance()
        val isToday = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) + 1 == month &&
            today.get(Calendar.DAY_OF_MONTH) == day
        textDayTitle.text = "${month}月${day}日" + if (isToday) "  今天" else ""
        textDaySum.text = "支：${Money.fmt(pay)}   收：${Money.fmt(income)}"
    }

    private suspend fun currentBookId(): String {
        var id = Pref.currentBookId(requireContext())
        if (id.isBlank()) {
            val book = db.bookDao().getAll().firstOrNull()
            book?.let {
                id = it.id
                Pref.setCurrentBookId(requireContext(), it.id)
            }
        }
        return id
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
