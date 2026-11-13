package com.mine.myjizhang.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Finance
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class StatsFragment : Fragment() {

    private val db by lazy { AppDatabase.get(requireContext()) }
    private val adapter = StatAdapter()

    private var year: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var type: Int = 0 // 0=支出 1=收入
    private var collectJob: Job? = null

    private var categories: List<Category> = emptyList()

    private lateinit var textMonth: TextView
    private lateinit var pie: PieChartView
    private lateinit var lineChart: LineChartView
    private lateinit var barChart: BarChartView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textMonth = view.findViewById(R.id.text_month)
        pie = view.findViewById(R.id.pie)
        lineChart = view.findViewById(R.id.line_chart)
        barChart = view.findViewById(R.id.bar_chart)
        val rv = view.findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.isNestedScrollingEnabled = false

        view.findViewById<View>(R.id.btn_prev).setOnClickListener { shiftMonth(-1) }
        view.findViewById<View>(R.id.btn_next).setOnClickListener { shiftMonth(1) }
        view.findViewById<View>(R.id.btn_budget).setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), com.mine.myjizhang.ui.budget.BudgetActivity::class.java)
            )
        }
        view.findViewById<ChipGroup>(R.id.chip_group).setOnCheckedStateChangeListener { _, _ ->
            type = if (view.findViewById<ChipGroup>(R.id.chip_group).checkedChipId == R.id.chip_income) 1 else 0
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun shiftMonth(delta: Int) {
        var y = year
        var m = month + delta
        if (m < 1) { m = 12; y-- }
        if (m > 12) { m = 1; y++ }
        year = y; month = m
        refresh()
    }

    fun refresh() {
        textMonth.text = "${year}年${month}月"
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            val bookId = currentBookId()
            if (bookId.isBlank()) return@launch
            categories = db.categoryDao().getAll()
            val catById = categories.associateBy { it.id }

            val start = DateTimes.monthStart(year, month)
            val end = DateTimes.monthEnd(year, month)
            val monthKey = String.format("%04d-%02d", year, month)
            val budgets = db.budgetDao().getByBook(bookId)
            val totalBudget = budgets.firstOrNull {
                it.categoryId.isBlank() && (it.month.isBlank() || it.month == monthKey)
            }?.amount ?: 0.0
            db.billDao().observeByMonth(bookId, start, end).collectLatest { bills ->
                bindOverview(bills, catById)
                bindHealth(bills, catById, totalBudget)
                bindCharts(bills)
                bindPie(bills, catById)
            }
        }
    }

    /** 收支总览六宫格（支出按净额：扣退款与报销，并计入转账手续费） */
    private fun bindOverview(bills: List<Bill>, catById: Map<String, Category>) {
        val v = view ?: return
        val counted = bills.filter { Finance.countsInStats(it, catById[it.categoryId]) }
        val fee = bills.sumOf { Finance.fee(it) }
        val pay = counted.sumOf { Finance.netExpense(it) } + fee
        val income = counted.sumOf { Finance.income(it) }
        val transfer = bills.sumOf { Finance.transferAmount(it) }

        val today = Calendar.getInstance()
        val isCurrent = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) + 1 == month
        val dayCount = if (isCurrent) today.get(Calendar.DAY_OF_MONTH) else daysInMonth(year, month)
        val daily = if (dayCount > 0) pay / dayCount else 0.0
        val balance = income - pay

        v.findViewById<TextView>(R.id.ov_pay).text = Money.fmt(pay)
        v.findViewById<TextView>(R.id.ov_income).text = Money.fmt(income)
        v.findViewById<TextView>(R.id.ov_balance).text =
            (if (balance < 0) "-" else "") + Money.fmt(balance)
        v.findViewById<TextView>(R.id.ov_daily).text = Money.fmt(daily)
        v.findViewById<TextView>(R.id.ov_transfer).text = Money.fmt(transfer)
        v.findViewById<TextView>(R.id.ov_fee).text = Money.fmt(fee)
    }

    /** 财务健康：结余率、固定 vs 弹性支出、总预算执行度 */
    private fun bindHealth(bills: List<Bill>, catById: Map<String, Category>, totalBudget: Double) {
        val v = view ?: return
        val counted = bills.filter { Finance.countsInStats(it, catById[it.categoryId]) }
        val fee = bills.sumOf { Finance.fee(it) }
        val pay = counted.sumOf { Finance.netExpense(it) } + fee
        val income = counted.sumOf { Finance.income(it) }

        val rate = Finance.savingRate(income, pay)
        val rateView = v.findViewById<TextView>(R.id.hp_rate)
        rateView.text = if (income <= 0) "--" else String.format("%.0f%%", rate * 100)
        rateView.setTextColor(
            requireContext().getColor(
                when {
                    income <= 0 -> R.color.text_secondary
                    rate >= 0.3 -> R.color.income_green
                    rate >= 0 -> R.color.text_primary
                    else -> R.color.expense_red
                }
            )
        )

        val fixed = counted.filter { Finance.isFixed(it, catById[it.categoryId]) }
            .sumOf { Finance.netExpense(it) }
        val flex = counted.filter { Finance.isFlex(it, catById[it.categoryId]) }
            .sumOf { Finance.netExpense(it) }
        v.findViewById<TextView>(R.id.hp_fixed).text = Money.fmt(fixed)
        v.findViewById<TextView>(R.id.hp_flex).text = Money.fmt(flex)

        val bar = v.findViewById<android.widget.ProgressBar>(R.id.hp_budget_bar)
        val label = v.findViewById<TextView>(R.id.hp_budget_label)
        val value = v.findViewById<TextView>(R.id.hp_budget)
        if (totalBudget <= 0) {
            bar.progress = 0
            label.text = "本月预算"
            value.text = "未设置"
            value.setTextColor(requireContext().getColor(R.color.text_secondary))
        } else {
            val pct = (pay / totalBudget * 100).coerceIn(0.0, 999.0)
            bar.progress = pct.coerceAtMost(100.0).toInt()
            bar.progressTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(if (pct > 100) R.color.expense_red else R.color.primary)
            )
            label.text = "预算 ${Money.fmt(totalBudget)}"
            value.text = String.format("已用 %.0f%%", pct)
            value.setTextColor(
                requireContext().getColor(if (pct > 100) R.color.expense_red else R.color.text_secondary)
            )
        }
    }

    /** 资产走势图（当月累计结余） + 收支统计图（每日支出/收入） */
    private fun bindCharts(bills: List<Bill>) {
        val days = daysInMonth(year, month)
        val payByDay = FloatArray(days)
        val incomeByDay = FloatArray(days)
        val cal = Calendar.getInstance()
        for (b in bills) {
            cal.timeInMillis = b.time
            val day = cal.get(Calendar.DAY_OF_MONTH) - 1
            when (b.type) {
                0 -> payByDay[day] += Finance.netExpense(b).toFloat()
                1 -> incomeByDay[day] += Finance.income(b).toFloat()
            }
        }
        // 走势：逐日累计结余（只统计到有数据的最后一天，之后不画平线）
        val lastActive = (days - 1 downTo 0).firstOrNull {
            payByDay[it] > 0f || incomeByDay[it] > 0f
        } ?: (days - 1)
        val trend = FloatArray(lastActive + 1)
        var acc = 0f
        for (i in 0..lastActive) {
            acc += incomeByDay[i] - payByDay[i]
            trend[i] = acc
        }
        lineChart.setData(trend.toList())
        barChart.setData(payByDay.toList(), incomeByDay.toList())
    }

    /** 分类占比饼图 + 排行 */
    private fun bindPie(bills: List<Bill>, catById: Map<String, Category>) {
        val filtered = bills.filter {
            it.type == type && Finance.countsInStats(it, catById[it.categoryId])
        }
        val byCat = filtered.groupBy { it.categoryId }
            .map { (id, list) ->
                id to list.sumOf { if (type == 0) Finance.netExpense(it) else Finance.income(it) }
            }
            .sortedByDescending { it.second }
        val total = byCat.sumOf { it.second }
        val top = byCat.take(8)
        val slices = top.mapIndexed { i, (id, amount) ->
            PieChartView.Slice(
                catById[id]?.name ?: "未知",
                amount,
                PieChartView.PALETTE[i % PieChartView.PALETTE.size]
            )
        }
        val finalSlices = if (byCat.size > 8) {
            val rest = byCat.drop(8).sumOf { it.second }
            slices + PieChartView.Slice("其他", rest, PieChartView.PALETTE[7])
        } else slices
        pie.setData(finalSlices, "¥" + Money.fmt(total))
        adapter.submit(byCat.map { (id, amount) ->
            val cat = catById[id]
            StatAdapter.Item(
                icon = cat?.icon ?: "💡",
                name = cat?.name ?: "未知分类",
                amount = amount,
                percent = if (total > 0) (amount / total * 100).toFloat() else 0f,
                color = PieChartView.PALETTE[
                    top.indexOfFirst { it.first == id }.coerceAtLeast(0) % PieChartView.PALETTE.size
                ]
            )
        })
    }

    private fun daysInMonth(y: Int, m: Int): Int =
        Calendar.getInstance().apply { set(y, m - 1, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)

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
}
