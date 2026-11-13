package com.mine.myjizhang.ui.assets

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AssetsFragment : Fragment() {

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
                //   负债 = 账户负余额（信用卡等） + 尚未归还的借入
                // 净资产 = 可支配 + 投资 + 应收借出 − 负债
                val inTotal = list.filter { it.inTotalAsset }
                val invest = inTotal.filter {
                    AssetTypes.groupOf(it.subType, it.type, it.name) == AssetTypes.GROUP_INVEST &&
                        it.amount > 0
                }.sumOf { it.amount }
                val cash = inTotal.filter {
                    AssetTypes.groupOf(it.subType, it.type, it.name) != AssetTypes.GROUP_INVEST &&
                        it.amount > 0
                }.sumOf { it.amount }
                val accountDebt = Math.abs(inTotal.filter { it.amount < 0 }.sumOf { it.amount })
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
        val dialog = BottomSheetDialog(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        content.addView(TextView(ctx).apply {
            text = "请选择资产类型"
            textSize = 17f
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        for (group in AssetTypes.groups) {
            val items = AssetTypes.all.filter { it.group == group }
            content.addView(TextView(ctx).apply {
                text = group
                textSize = 13f
                setTextColor(ctx.getColor(R.color.text_secondary))
                setPadding(0, dp(20), 0, dp(8))
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
                        dialog.dismiss()
                        onTypeSelected(info)
                    }
                    addView(TextView(ctx).apply {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                        // 类型图标：优先官方风格 logo，无 logo 则用 emoji 淡色圆底
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

        // 包进 ScrollView：类型多时（如滑到投资理财账户）可以继续向上滑动
        val scroll = ScrollView(ctx).apply { addView(content) }
        dialog.setContentView(scroll)
        dialog.show()
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
        val dialog = BottomSheetDialog(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        content.addView(TextView(ctx).apply {
            text = "请选择银行（共 ${com.mine.myjizhang.ui.assets.Banks.all.size} 家）"
            textSize = 17f
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        val rows: List<Pair<String, com.mine.myjizhang.ui.assets.Bank?>> =
            Banks.all.map { it.name to it.bank } + listOf("其他银行（手动输入）" to null)
        rows.forEach { (name, bank) ->
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener {
                    dialog.dismiss()
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
                }
                addView(TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    // 银行图标：优先官方风格 logo，无 logo 用缩写字圆底
                    val logoRes = com.mine.myjizhang.ui.assets.BankLogos.res(name)
                    if (logoRes != 0) {
                        text = ""
                        background = androidx.core.content.ContextCompat.getDrawable(ctx, logoRes)
                    } else {
                        textSize = if ((bank?.short?.length ?: 0) >= 2) 11f else 16f
                        text = bank?.short ?: "？"
                        setTextColor(android.graphics.Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(android.graphics.Color.parseColor(bank?.color ?: "#3D9BE9"))
                        }
                    }
                })
                addView(TextView(ctx).apply {
                    text = name
                    textSize = 15f
                    setTextColor(ctx.getColor(R.color.text_primary))
                    setPadding(dp(16), 0, 0, 0)
                })
            })
        }

        val scroll = ScrollView(ctx).apply { addView(content) }
        dialog.setContentView(scroll)
        dialog.show()
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
