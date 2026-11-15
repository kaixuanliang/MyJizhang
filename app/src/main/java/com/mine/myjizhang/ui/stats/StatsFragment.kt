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
import com.mine.myjizhang.ui.TabRefreshable
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

class StatsFragment : Fragment(), TabRefreshable {

    private val db by lazy { AppDatabase.get(requireContext()) }
    private val adapter = StatAdapter()

    private var year: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var type: Int = 0 // 0=支出 1=收入
    private var collectJob: Job? = null

    private var categories: List<Category> = emptyList()

    /** 固定 / 弹性支出的分类明细，点数字时弹出来解释「这个数是怎么算的」 */
    private data class NatureRow(val name: String, val amount: Double, val count: Int)
    private var fixedRows: List<NatureRow> = emptyList()
    private var flexRows: List<NatureRow> = emptyList()
    private var fixedCatCount: Int = 0

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
        // 财务健康里的「固定 / 弹性」来源提示，点一下也进预算与分类页
        view.findViewById<View>(R.id.hp_nature_hint).setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), com.mine.myjizhang.ui.budget.BudgetActivity::class.java)
            )
        }
        // 点数字看明细：很多情况下「固定支出 0.00」不是没生效，而是本月这部分分类没花钱
        view.findViewById<View>(R.id.hp_fixed).setOnClickListener { showNatureDetail(true) }
        view.findViewById<View>(R.id.hp_fixed_pct).setOnClickListener { showNatureDetail(true) }
        view.findViewById<View>(R.id.hp_flex).setOnClickListener { showNatureDetail(false) }
        view.findViewById<View>(R.id.hp_flex_pct).setOnClickListener { showNatureDetail(false) }
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

    override fun refreshTab() = refresh()

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

        // 固定 / 弹性 = 「分类性质」为固定（或其余）的分类，在本月实际花掉的钱
        val fixedBills = counted.filter { Finance.isFixed(it, catById[it.categoryId]) }
        val flexBills = counted.filter { Finance.isFlex(it, catById[it.categoryId]) }
        val fixed = fixedBills.sumOf { Finance.netExpense(it) }
        val flex = flexBills.sumOf { Finance.netExpense(it) }
        fixedRows = groupRows(fixedBills, catById)
        flexRows = groupRows(flexBills, catById)
        fixedCatCount = categories.count {
            it.type == 0 && !it.hide && it.nature == Category.NATURE_FIXED
        }

        v.findViewById<TextView>(R.id.hp_fixed).text = Money.fmt(fixed)
        v.findViewById<TextView>(R.id.hp_flex).text = Money.fmt(flex)

        // 固定 / 弹性各占支出的多少：只有金额看不出结构，加个占比一眼就知道刚性支出重不重
        val splitBase = fixed + flex
        fun pct(part: Double, count: Int): String =
            if (splitBase <= 0) "占 --"
            else String.format("占 %.0f%% · %d 笔", part / splitBase * 100, count)
        v.findViewById<TextView>(R.id.hp_fixed_pct).text = pct(fixed, fixedBills.size)
        v.findViewById<TextView>(R.id.hp_flex_pct).text = pct(flex, flexBills.size)
        // 0.00 的时候要说清「为什么是 0」，不然会以为标记没生效
        v.findViewById<TextView>(R.id.hp_nature_hint).text = when {
            fixedCatCount == 0 ->
                "还没有把分类标成「固定支出」，点这里去标记 ›"
            fixed <= 0.0 ->
                "已标记「固定」的分类本月还没支出（共 $fixedCatCount 个），点数字看明细 ›"
            else ->
                "固定 = 已标记分类的本月实际支出，与预算金额无关，点数字看明细 ›"
        }

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

    /** 按分类汇总（金额降序），用于「固定 / 弹性」的明细弹窗 */
    private fun groupRows(bills: List<Bill>, catById: Map<String, Category>): List<NatureRow> =
        bills.groupBy { it.categoryId }
            .map { (id, list) ->
                NatureRow(
                    catById[id]?.name ?: "已删除分类",
                    list.sumOf { Finance.netExpense(it) },
                    list.size
                )
            }
            .filter { it.amount > 0 }
            .sortedByDescending { it.amount }

    private fun showNatureDetail(fixed: Boolean) {
        val rows = if (fixed) fixedRows else flexRows
        val total = rows.sumOf { it.amount }
        val sb = StringBuilder()
        if (fixed) {
            sb.append("只统计「分类性质 = 固定支出」的账单在本月的实际金额。\n")
            sb.append("标记只是给分类打标签，不会产生金额；预算金额是上限，不计入这里。\n\n")
        } else {
            sb.append("本月除固定支出以外的支出都算弹性（含还没标记性质的分类）。\n\n")
        }
        if (rows.isEmpty()) {
            sb.append("本月这部分还没有支出。")
            if (fixed) {
                sb.append("\n\n已标记为固定的分类：")
                sb.append(
                    categories.filter { it.type == 0 && it.nature == Category.NATURE_FIXED }
                        .joinToString("、") { it.name }
                        .ifBlank { "（还没有）" }
                )
            }
        } else {
            rows.take(12).forEach {
                sb.append("${it.name}　¥${Money.fmt(it.amount)}（${it.count} 笔）\n")
            }
            if (rows.size > 12) sb.append("… 还有 ${rows.size - 12} 个分类\n")
            sb.append("\n合计 ¥${Money.fmt(total)}")
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (fixed) "固定支出明细" else "弹性支出明细")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .setNegativeButton("去标记分类") { _, _ ->
                startActivity(
                    android.content.Intent(
                        requireContext(),
                        com.mine.myjizhang.ui.budget.BudgetActivity::class.java
                    )
                )
            }
            .show()
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
        // 饼图只放前 6 名 + 其他：块太多引线标签会挤，完整排行在下方列表里
        val top = byCat.take(6)
        val slices = top.mapIndexed { i, (id, amount) ->
            PieChartView.Slice(
                catById[id]?.name ?: "未知",
                amount,
                PieChartView.PALETTE[i % PieChartView.PALETTE.size]
            )
        }
        val finalSlices = if (byCat.size > 6) {
            val rest = byCat.drop(6).sumOf { it.second }
            slices + PieChartView.Slice("其他 ${byCat.size - 6} 项", rest, PieChartView.PALETTE[7])
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
