package com.mine.myjizhang.ui.bills

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.ui.edit.BillEditActivity
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class BillsFragment : Fragment() {

    private val db by lazy { AppDatabase.get(requireContext()) }
    private val adapter = BillAdapter(
        onClick = { bill -> BillEditActivity.start(requireContext(), bill.id) },
        onLongClick = { bill -> confirmDelete(bill) }
    )

    private var year: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var collectJob: Job? = null

    private var categories: List<Category> = emptyList()
    private var assets: List<Asset> = emptyList()
    private var latestBills: List<Bill> = emptyList()
    private var bookName: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bills, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.recycler_bills)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<View>(R.id.btn_prev).setOnClickListener { shiftMonth(-1) }
        view.findViewById<View>(R.id.btn_next).setOnClickListener { shiftMonth(1) }
        view.findViewById<View>(R.id.text_month_title).setOnClickListener { showMonthPicker() }

        view.findViewById<View>(R.id.btn_quick_pay).setOnClickListener {
            BillEditActivity.startNew(requireContext(), 0)
        }
        view.findViewById<View>(R.id.btn_quick_income).setOnClickListener {
            BillEditActivity.startNew(requireContext(), 1)
        }
        view.findViewById<View>(R.id.btn_quick_transfer).setOnClickListener {
            BillEditActivity.startNew(requireContext(), 2)
        }

        view.findViewById<View>(R.id.btn_search).setOnClickListener { showSearch() }

        // 左滑删除账单（红色底 + 「删除」）
        attachSwipeDelete(rv)
    }

    private fun attachSwipeDelete(rv: RecyclerView) {
        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#E24B4A")
            isAntiAlias = true
        }
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = dp(14).toFloat()
            isAntiAlias = true
        }
        val helper = androidx.recyclerview.widget.ItemTouchHelper(object :
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0, androidx.recyclerview.widget.ItemTouchHelper.LEFT
            ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (viewHolder.itemViewType == 0) 0
            else makeMovementFlags(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val row = adapter.rows.getOrNull(pos)
                if (row is Row.BillRow) swipeDelete(row.bill) else adapter.notifyDataSetChanged()
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    c.drawRect(
                        item.right + dX, item.top.toFloat(),
                        item.right.toFloat(), item.bottom.toFloat(), bgPaint
                    )
                    val label = "删除"
                    val tw = textPaint.measureText(label)
                    c.drawText(
                        label,
                        item.right - tw - dp(18).toFloat(),
                        item.top + item.height / 2f + dp(5).toFloat(),
                        textPaint
                    )
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })
        helper.attachToRecyclerView(rv)
    }

    /** 左滑删除：冲正余额 + 写对账备忘 + 可撤销 */
    private fun swipeDelete(bill: Bill) {
        val ctx = requireContext()
        lifecycleScope.launch {
            com.mine.myjizhang.data.Reconcile.reverseBill(
                db, bill, com.mine.myjizhang.data.entity.Adjust.KIND_BILL_DELETE,
                "左滑删除账单：冲正 ¥${Money.fmt(bill.amount)}"
            )
            db.billDao().delete(bill)
            refresh()
            val root = view ?: return@launch
            com.google.android.material.snackbar.Snackbar
                .make(root, "已删除 1 笔账单", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("撤销") {
                    lifecycleScope.launch {
                        db.billDao().insert(bill)
                        com.mine.myjizhang.data.Reconcile.applyBill(
                            db, bill, com.mine.myjizhang.data.entity.Adjust.KIND_BILL_RESTORE,
                            "撤销删除：恢复 ¥${Money.fmt(bill.amount)}"
                        )
                        refresh()
                    }
                }
                .show()
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

    private fun showMonthPicker() {
        val ctx = requireContext()
        val content = LayoutInflater.from(ctx).inflate(R.layout.dialog_month_picker, null)
        val yearPicker = content.findViewById<NumberPicker>(R.id.picker_year)
        val monthPicker = content.findViewById<NumberPicker>(R.id.picker_month)
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        yearPicker.minValue = thisYear - 20
        yearPicker.maxValue = thisYear + 5
        yearPicker.value = year
        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.value = month
        MaterialAlertDialogBuilder(ctx)
            .setTitle("选择月份")
            .setView(content)
            .setPositiveButton("确定") { _, _ ->
                year = yearPicker.value
                month = monthPicker.value
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSearch() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "搜索备注 / 分类 / 账户"
            setSingleLine()
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("搜索账单")
            .setView(box)
            .setPositiveButton("搜索") { _, _ ->
                val kw = input.text.toString().trim()
                if (kw.isNotEmpty()) doSearch(kw)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSearch(keyword: String) {
        lifecycleScope.launch {
            val all = db.billDao().getAll()
            val hits = all.filter { b ->
                val cat = categories.firstOrNull { it.id == b.categoryId }?.name ?: ""
                val acc = assets.firstOrNull { it.id == b.assetId }?.name
                    ?: assets.firstOrNull { it.id == b.fromAssetId }?.name ?: ""
                cat.contains(keyword) || acc.contains(keyword) || b.remark.contains(keyword)
            }.sortedByDescending { it.time }.take(50)
            if (hits.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("搜索结果")
                    .setMessage("没有找到包含「$keyword」的账单")
                    .setPositiveButton("好的", null)
                    .show()
                return@launch
            }
            val lines = hits.map { b ->
                val cat = categories.firstOrNull { it.id == b.categoryId }?.name ?: "转账"
                val acc = assets.firstOrNull { it.id == b.assetId }?.name
                    ?: assets.firstOrNull { it.id == b.fromAssetId }?.name ?: ""
                "${DateTimes.format(b.time)}  $cat  ${if (b.type == 1) "+" else "-"}¥${Money.fmt(b.amount)}  $acc"
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("搜索结果（${hits.size} 条）")
                .setItems(lines.toTypedArray()) { _, i ->
                    BillEditActivity.start(requireContext(), hits[i].id)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    fun refresh() {
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            val bookId = currentBookId()
            if (bookId.isBlank()) return@launch
            categories = db.categoryDao().getAll()
            assets = db.assetDao().getByBook(bookId)
            bookName = db.bookDao().getAll().firstOrNull { it.id == bookId }?.name ?: ""

            adapter.categoryName = { id -> categories.firstOrNull { it.id == id }?.name ?: "" }
            adapter.categoryIcon = { id -> categories.firstOrNull { it.id == id }?.icon ?: "💡" }
            val nameMap = assets.associate { it.id to it.name }
            adapter.assetName = { id -> nameMap[id] ?: "已删除账户" }

            val start = DateTimes.monthStart(year, month)
            val end = DateTimes.monthEnd(year, month)
            db.billDao().observeByMonth(bookId, start, end).collectLatest { bills ->
                latestBills = bills
                bindOverview(bills)
                adapter.submit(bills)
            }
        }
    }

    /** 顶部蓝色月度总览卡（对照一飞记账）：月支出大字 + 月收入/月结余 */
    private fun bindOverview(bills: List<Bill>) {
        val v = view ?: return
        val pay = bills.filter { it.type == 0 }.sumOf { com.mine.myjizhang.data.Finance.netExpense(it) } +
            bills.sumOf { com.mine.myjizhang.data.Finance.fee(it) }
        val income = bills.filter { it.type == 1 }.sumOf { com.mine.myjizhang.data.Finance.income(it) }
        val balance = income - pay

        v.findViewById<TextView>(R.id.text_month_title).text =
            "${year % 100}年${month}月账单 ▼"
        v.findViewById<TextView>(R.id.text_book_name).text = bookName

        v.findViewById<TextView>(R.id.text_overview_label).text = "${month}月支出"
        v.findViewById<TextView>(R.id.text_overview_amount).text = Money.fmt(pay)
        v.findViewById<TextView>(R.id.text_income_label).text = "${month}月收入"
        v.findViewById<TextView>(R.id.text_income_amount).text = Money.fmt(income)
        v.findViewById<TextView>(R.id.text_balance_label).text = "${month}月结余"
        v.findViewById<TextView>(R.id.text_balance_amount).text = Money.fmt(balance)
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

    private fun confirmDelete(bill: Bill) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除账单")
            .setMessage("确定删除这笔账单吗？金额 ¥${Money.fmt(bill.amount)}")
            .setPositiveButton("删除") { _, _ -> swipeDelete(bill) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
