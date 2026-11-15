package com.mine.myjizhang.ui.assets

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.ui.widget.SheetList
import com.mine.myjizhang.ui.TabRefreshable
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AssetsFragment : Fragment(), TabRefreshable {

    private val db by lazy { AppDatabase.get(requireContext()) }
    private val adapter = AssetAdapter(
        onClick = { asset ->
            startActivity(
                com.mine.myjizhang.ui.assets.AssetDetailActivity.startIntent(requireContext(), asset.id)
            )
        },
        onLongClick = { asset -> AssetEditActivity.start(requireContext(), asset.id) }
    )

    private lateinit var textNet: TextView
    private lateinit var textCash: TextView
    private lateinit var textInvest: TextView
    private lateinit var textReceivable: TextView
    private lateinit var textCredit: TextView
    private lateinit var textBorrowIn: TextView
    private lateinit var textBorrowOut: TextView
    private var collectJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_assets, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textNet = view.findViewById(R.id.text_net_asset)
        textCash = view.findViewById(R.id.text_cash_asset)
        textInvest = view.findViewById(R.id.text_invest_asset)
        textReceivable = view.findViewById(R.id.text_receivable_asset)
        textCredit = view.findViewById(R.id.text_total_credit)
        textBorrowIn = view.findViewById(R.id.text_borrow_in)
        textBorrowOut = view.findViewById(R.id.text_borrow_out)

        // 关键修复：绑定 RecyclerView + LayoutManager（此前 adapter 未挂载导致列表一直不显示）
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_assets)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<TextView>(R.id.btn_add_asset).setOnClickListener { showTypePicker() }

        // 总借入 / 总借出 → 借入/借出列表页
        view.findViewById<View>(R.id.card_borrow_in).setOnClickListener {
            com.mine.myjizhang.ui.loans.LoanListActivity.start(requireContext(), 0)
        }
        view.findViewById<View>(R.id.card_borrow_out).setOnClickListener {
            com.mine.myjizhang.ui.loans.LoanListActivity.start(requireContext(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refreshTab() = refresh()

    fun refresh() {
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            val bookId = currentBookId()
            if (bookId.isBlank()) return@launch
            combine(
                db.assetDao().observeByBook(bookId),
                db.loanDao().observeByBook(bookId)
            ) { assets, loans -> assets to loans }.collectLatest { (list, loans) ->
                adapter.submit(list)

                // 财务口径：
                //   可支配 / 投资 = 正余额账户按分组拆分
                //   应收借出 = 尚未收回的借出（钱借出去了，债权还在，不是损失）
                //   负债 = 信贷账户欠款（信用卡/花呗等，即使关掉「计入净资产」也必须扣） + 其他账户负余额 + 未还借入
                // 净资产 = 可支配 + 投资 + 应收借出 − 负债
                val groupOf = { a: Asset -> AssetTypes.groupOf(a.subType, a.type, a.name) }
                // 信贷账户单独拎出来：它代表欠款，不能因为「计入总资产」开关为 false 就从净资产里消失
                val creditAssets = list.filter { groupOf(it) == AssetTypes.GROUP_CREDIT }
                val inTotal = list.filter {
                    it.inTotalAsset && groupOf(it) != AssetTypes.GROUP_CREDIT
                }
                val invest = inTotal.filter {
                    groupOf(it) == AssetTypes.GROUP_INVEST && it.amount > 0
                }.sumOf { it.amount }
                // 信贷账户多还出来的正余额也是自己的钱，算进可支配
                val creditSurplus = creditAssets.sumOf { if (it.amount > 0) it.amount else 0.0 }
                val cash = inTotal.filter {
                    groupOf(it) != AssetTypes.GROUP_INVEST && it.amount > 0
                }.sumOf { it.amount } + creditSurplus
                // 信贷账户欠款（amount 为负）+ 其他账户的负余额
                val creditOwed = creditAssets.sumOf { if (it.amount < 0) -it.amount else 0.0 }
                val accountDebt = Math.abs(inTotal.filter { it.amount < 0 }.sumOf { it.amount }) + creditOwed
                val receivable = loans.filter { it.direction == 1 }.sumOf { it.outstanding }
                val payable = loans.filter { it.direction == 0 }.sumOf { it.outstanding }
                val debt = accountDebt + payable
                val net = cash + invest + receivable - debt

                textNet.text = (if (net < 0) "-" else "") + Money.fmt(Math.abs(net))
                textCash.text = Money.fmt(cash)
                textInvest.text = Money.fmt(invest)
                textReceivable.text = Money.fmt(receivable)
                textCredit.text = Money.fmt(debt)
                // 总借入 / 总借出：按未还净额，与净资产口径一致
                textBorrowIn.text = Money.fmt(payable)
                textBorrowOut.text = Money.fmt(receivable)
            }
        }
    }

    // ---------- 「请选择资产类型」底部弹窗（可滚动） ----------

    private fun showTypePicker() {
        val ctx = requireContext()
        // 标题悬浮 + 高度固定，内容在下面滚动
        val sheet = SheetList.create(ctx, "请选择资产类型", fillHeight = true)
        val content = sheet.list

        for (group in AssetTypes.groups) {
            val items = AssetTypes.all.filter { it.group == group }
            content.addView(TextView(ctx).apply {
                text = group
                textSize = 13f
                setTextColor(ctx.getColor(R.color.text_secondary))
                setPadding(0, dp(16), 0, dp(6))
            })
            val grid = GridLayout(ctx).apply { columnCount = 4 }
            items.forEach { info ->
                grid.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        sheet.dialog.dismiss()
                        onTypeSelected(info)
                    }
                    addView(TextView(ctx).apply {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                        // 类型图标：优先品牌 logo，无 logo 则用 emoji 淡色圆底
                        AssetTypes.bindIcon(this, info.name, info.name, "")
                    })
                    addView(TextView(ctx).apply {
                        text = info.name
                        textSize = 12f
                        setTextColor(ctx.getColor(R.color.text_primary))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    })
                })
            }
            content.addView(grid)
        }

        sheet.dialog.show()
    }

    private fun onTypeSelected(info: AssetTypeInfo) {
        if (info.name == "储蓄卡" || info.name == "信用卡") {
            showBankPicker(info)
        } else {
            AssetEditActivity.startNew(
                requireContext(),
                subType = info.name,
                prefillName = if (info.name in listOf("花呗", "京东白条")) info.name else "",
                credit = info.credit
            )
        }
    }

    // ---------- 「请选择银行」底部弹窗（可滚动 + 搜索） ----------

    private fun showBankPicker(info: AssetTypeInfo) {
        val ctx = requireContext()
        val rows: List<Pair<String, Bank?>> =
            Banks.all.map { it.name to it.bank } + listOf("其他银行（手动输入）" to null)

        // 标题悬浮，列表固定高度内部滚动（248 家银行，列表必然很长）
        val sheet = SheetList.create(ctx, "请选择银行（共 ${Banks.all.size} 家）", rows.size, 60)
        rows.forEach { (name, bank) ->
            val icon = SheetList.iconView(ctx, 40).also { v ->
                AssetTypes.bindIcon(v, name, info.name, bank?.short ?: "")
            }
            sheet.list.addView(SheetList.row(ctx, name, 60, icon) {
                sheet.dialog.dismiss()
                if (bank != null) {
                    AssetEditActivity.startNew(
                        requireContext(),
                        subType = info.name,
                        prefillName = name,
                        credit = info.credit,
                        icon = bank.short
                    )
                } else {
                    AssetEditActivity.startNew(requireContext(), subType = info.name, credit = info.credit)
                }
            })
        }
        sheet.dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
