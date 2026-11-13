package com.mine.myjizhang.ui.importwizard

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.BalanceEngine
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.excel.BillImporter
import com.mine.myjizhang.excel.ImportPreview
import com.mine.myjizhang.excel.ImportScanner
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导入迁移向导：把「一飞记账的旧账」一次性迁移成自己的账。
 *
 *  1 体检     —— 先看清数据长什么样（账单数、时间跨度、账户、分类、异常行）
 *  2 账户认领 —— 每个账户填今天的真实余额，反推期初余额，把账一次性对平
 *  3 分类重构 —— 给分类标固定 / 弹性 / 不计收支，并归入大类
 *  4 执行稽核 —— 写入后逐账户校验「期初 + 流水 = 期末」，差异列明细
 */
class ImportWizardActivity : AppCompatActivity() {

    companion object {
        fun start(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(ctx, ImportWizardActivity::class.java)
                    .putExtra("uri", uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }

        /** 支出大类（只用于报表归类，不会出现在记账页的分类网格里） */
        private val EXPENSE_PARENTS = listOf("食", "住", "行", "购物", "娱乐", "医疗教育", "人情", "金融", "其他")
    }

    private val db by lazy { AppDatabase.get(this) }

    private lateinit var container: LinearLayout
    private lateinit var btnBack: TextView
    private lateinit var textStep: TextView
    private lateinit var btnPrev: com.google.android.material.button.MaterialButton
    private lateinit var btnNext: com.google.android.material.button.MaterialButton

    private var preview: ImportPreview? = null
    private var step = 0
    private var running = false
    private var finished = false

    /** 账户行：用于把输入框的值读回来 */
    private val balanceInputs = mutableListOf<Pair<Int, EditText>>()
    private val natureSpinners = mutableListOf<Pair<Int, Spinner>>()
    private val parentSpinners = mutableListOf<Pair<Int, Spinner>>()

    private val df = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_wizard)
        container = findViewById(R.id.container)
        btnBack = findViewById(R.id.btn_back)
        textStep = findViewById(R.id.text_step)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)

        btnBack.setOnClickListener { finish() }
        btnPrev.setOnClickListener {
            if (step > 0) {
                step--
                render()
            }
        }
        btnNext.setOnClickListener { onNext() }

        textStep.text = "解析中…"
        btnNext.isEnabled = false
        lifecycleScope.launch {
            val uriStr = intent.getStringExtra("uri")
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(Uri.parse(uriStr!!))!!.readBytes()
                    ImportScanner.scan(bytes)
                }.getOrNull()
            }
            btnNext.isEnabled = true
            if (parsed == null) {
                Toast.makeText(this@ImportWizardActivity, "文件解析失败，请确认是一飞记账导出的 xlsx", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            preview = parsed
            if (parsed.billCount == 0) {
                Toast.makeText(this@ImportWizardActivity, "文件里没有可识别的账单", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            render()
        }
    }

    // ---------------- 步骤调度 ----------------

    private fun onNext() {
        if (running) return
        when (step) {
            0 -> { step = 1; render() }
            1 -> { collectBalances(); step = 2; render() }
            2 -> { collectCategories(); step = 3; render() }
            3 -> {
                if (finished) { finish() } else { runMigration() }
            }
        }
    }

    private fun render() {
        container.removeAllViews()
        balanceInputs.clear()
        natureSpinners.clear()
        parentSpinners.clear()
        textStep.text = "${step + 1} / 4"
        btnPrev.visibility = if (step == 0 || finished) View.GONE else View.VISIBLE
        btnNext.text = when (step) {
            0 -> "下一步：认领账户"
            1 -> "下一步：整理分类"
            2 -> "下一步：确认迁移"
            else -> if (finished) "完成" else "开始迁移"
        }
        btnNext.isEnabled = true
        when (step) {
            0 -> renderCheckup()
            1 -> renderAccounts()
            2 -> renderCategories()
            else -> if (finished) renderReport() else renderConfirm()
        }
    }

    // ---------------- 第 1 步：体检 ----------------

    private fun renderCheckup() {
        val p = preview ?: return
        container.addView(title("体检报告", "先确认数据范围对不对，再决定怎么迁移"))
        val card = card()

        card.addView(kv("账单笔数", "${p.billCount} 笔"))
        card.addView(kv("时间跨度", "${df.format(Date(p.minTime))} — ${df.format(Date(p.maxTime))}"))
        card.addView(kv("覆盖天数", "${p.days} 天"))
        card.addView(kv("账本", if (p.books.isEmpty()) "默认账本" else p.books.joinToString("、")))
        card.addView(kv("识别到的账户", "${p.accounts.size} 个"))
        card.addView(kv("识别到的分类", "${p.categories.size} 个"))
        card.addView(kv("支出合计", "¥" + Money.fmt(p.expenseTotal)))
        card.addView(kv("收入合计", "¥" + Money.fmt(p.incomeTotal)))
        if (p.skipped > 0) {
            card.addView(kv("跳过的异常行", "${p.skipped} 行（金额或时间无法识别）"))
        }
        container.addView(card)

        container.addView(
            note(
                "接下来会给每个账户填一次「今天的真实余额」。系统用「真实余额 − 流水累计」" +
                    "算出期初余额，从此账永远是平的——这是导入里最关键的一步。"
            )
        )
    }

    // ---------------- 第 2 步：账户认领 ----------------

    private fun renderAccounts() {
        val p = preview ?: return
        container.addView(
            title("账户认领", "每个账户填一次当前真实余额（对着银行 App 看一眼即可）")
        )
        if (p.accounts.isEmpty()) {
            container.addView(note("这笔账单里没有记录账户信息，可直接跳到下一步。"))
            return
        }
        val card = card()
        p.accounts.forEachIndexed { index, acc ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = acc.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text_primary))
            })
            row.addView(TextView(this).apply {
                text = "流水累计 ¥${Money.fmt(acc.flow)} · ${acc.count} 笔"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, dp(4))
            })
            val input = EditText(this).apply {
                hint = "填今天的真实余额"
                textSize = 15f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(String.format(Locale.CHINA, "%.2f", acc.realBalance))
                setSelection(text.length)
            }
            row.addView(input, LinearLayout.LayoutParams(-1, -2))
            balanceInputs.add(index to input)
            if (index < p.accounts.size - 1) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(1))
                    setBackgroundColor(getColor(R.color.divider))
                })
            }
            card.addView(row)
        }
        container.addView(card)
        container.addView(
            note("填的和流水累计一样，说明期初为 0（记录很完整）；差得多，说明有没记的账或有初始余额，差额会进期初。")
        )
    }

    private fun collectBalances() {
        val p = preview ?: return
        for ((index, input) in balanceInputs) {
            val v = input.text.toString().trim().toDoubleOrNull()
            if (v != null && index in p.accounts.indices) p.accounts[index].realBalance = v
        }
    }

    // ---------------- 第 3 步：分类重构 ----------------

    private fun renderCategories() {
        val p = preview ?: return
        container.addView(title("分类重构", "标出固定 / 弹性支出，报表才能算出真实可压缩空间"))
        if (p.categories.isEmpty()) {
            container.addView(note("没有可整理的分类。"))
            return
        }

        val expense = p.categories.filter { it.type == 0 }
        val income = p.categories.filter { it.type == 1 }

        if (expense.isNotEmpty()) {
            container.addView(sectionLabel("支出分类（${expense.size} 个）"))
            val card = card()
            val natureOptions = listOf("弹性支出", "固定支出", "不计收支")
            val parentOptions = listOf("不归类") + EXPENSE_PARENTS
            expense.forEach { cat ->
                val idx = p.categories.indexOf(cat)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                row.addView(TextView(this).apply {
                    text = "${cat.name}　¥${Money.fmt(cat.amount)} · ${cat.count} 笔"
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                })
                val spinners = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, 0)
                }
                val nature = Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@ImportWizardActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        natureOptions
                    )
                    setSelection(
                        when (cat.nature) {
                            Category.NATURE_FIXED -> 1
                            Category.NATURE_EXCLUDED -> 2
                            else -> 0
                        }
                    )
                }
                val parent = Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@ImportWizardActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        parentOptions
                    )
                    setSelection(if (cat.parent.isBlank()) 0 else parentOptions.indexOf(cat.parent).coerceAtLeast(0))
                }
                spinners.addView(nature, LinearLayout.LayoutParams(0, -2, 1f))
                spinners.addView(parent, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(spinners)
                natureSpinners.add(idx to nature)
                parentSpinners.add(idx to parent)
                card.addView(row)
            }
            container.addView(card)
        }

        if (income.isNotEmpty()) {
            container.addView(sectionLabel("收入分类（${income.size} 个，仅作展示）"))
            val card = card()
            income.forEach { cat ->
                card.addView(kv(cat.name, "¥${Money.fmt(cat.amount)} · ${cat.count} 笔"))
            }
            container.addView(card)
        }
    }

    private fun collectCategories() {
        val p = preview ?: return
        for ((idx, spinner) in natureSpinners) {
            if (idx !in p.categories.indices) continue
            p.categories[idx].nature = when (spinner.selectedItemPosition) {
                1 -> Category.NATURE_FIXED
                2 -> Category.NATURE_EXCLUDED
                else -> Category.NATURE_FLEX
            }
        }
        for ((idx, spinner) in parentSpinners) {
            if (idx !in p.categories.indices) continue
            val pos = spinner.selectedItemPosition
            p.categories[idx].parent = if (pos <= 0) "" else EXPENSE_PARENTS[pos - 1]
        }
    }

    // ---------------- 第 4 步：确认 / 稽核 ----------------

    private fun renderConfirm() {
        val p = preview ?: return
        container.addView(title("确认迁移", "确认后开始写入，全程本地完成"))
        val card = card()
        card.addView(kv("将导入账单", "${p.billCount} 笔"))
        card.addView(kv("将建立/更新账户", "${p.accounts.size} 个"))
        card.addView(kv("将整理分类", "${p.categories.size} 个"))
        val calibrated = p.accounts.count { Math.abs(it.opening) > 0.005 }
        card.addView(kv("需要建立期初余额", "$calibrated 个账户"))
        container.addView(card)
        container.addView(
            note("迁移完成后会逐个账户核对「期初 + 流水 = 期末」，差异会直接列出，不会悄悄改数。")
        )
    }

    private fun runMigration() {
        val p = preview ?: return
        running = true
        btnNext.isEnabled = false
        btnPrev.visibility = View.GONE
        container.removeAllViews()
        container.addView(title("正在迁移…", "请勿退出"))
        val progressText = TextView(this).apply {
            text = "准备中…"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(progressText)

        lifecycleScope.launch {
            val uriStr = intent.getStringExtra("uri")
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(Uri.parse(uriStr!!))!!.readBytes()
            }
            val result = withContext(Dispatchers.IO) {
                BillImporter.import(this@ImportWizardActivity, bytes) { done, total, stage ->
                    runOnUiThread {
                        progressText.text = if (total > 0) "$stage（$done/$total）" else stage
                    }
                }
            }
            withContext(Dispatchers.IO) {
                applyCategories(p)
                for (acc in p.accounts) {
                    val asset = db.assetDao().findByName(acc.name) ?: continue
                    BalanceEngine.calibrate(db, asset.id, acc.realBalance)
                }
            }
            running = false
            finished = true
            reportResult = result
            render()
        }
    }

    private var reportResult: com.mine.myjizhang.excel.ImportResult? = null

    private fun renderReport() {
        val p = preview ?: return
        val r = reportResult
        container.removeAllViews()
        container.addView(title("迁移完成", "逐账户核对，差异应全部为 0"))
        val card = card()
        card.addView(kv("导入账单", "${r?.bills ?: 0} 笔"))
        if ((r?.duplicates ?: 0) > 0) {
            card.addView(kv("跳过重复账单", "${r!!.duplicates} 笔（之前已导入过）"))
        }
        if ((r?.skipped ?: 0) > 0) {
            card.addView(kv("跳过异常行", "${r!!.skipped} 行"))
        }
        card.addView(kv("新建分类", "${r?.newCategories?.size ?: 0} 个"))
        container.addView(card)

        if (p.accounts.isNotEmpty()) {
            container.addView(sectionLabel("账户稽核"))
            val ac = card()
            for (acc in p.accounts) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(TextView(this).apply {
                    text = acc.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.text_primary))
                })
                row.addView(TextView(this).apply {
                    text = "期初 ¥${Money.fmt(acc.opening)} ＋ 流水 ¥${Money.fmt(acc.flow)}" +
                        " ＝ 期末 ¥${Money.fmt(acc.realBalance)}"
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                })
                ac.addView(row)
            }
            container.addView(ac)
        }

        container.addView(
            note("以后新增、修改、删除账单都会自动按「期初 + 流水 + 调整」重算余额，" +
                "不会再出现余额和流水对不上的情况。可在「设置 → 对账记录」查看每一笔调整。")
        )
    }

    // ---------------- 分类落库 ----------------

    private suspend fun applyCategories(p: ImportPreview) {
        for (c in p.categories) {
            val cat = db.categoryDao().findByName(c.name, c.type) ?: continue
            var changed = false
            if (c.type == 0 && cat.nature != c.nature) {
                cat.nature = c.nature
                changed = true
            }
            if (c.parent.isNotBlank()) {
                val rootId = ensureParent(c.parent, c.type)
                if (cat.parentId != rootId) {
                    cat.parentId = rootId
                    changed = true
                }
            }
            if (changed) db.categoryDao().update(cat)
        }
    }

    /** 大类节点：hide = true，不会出现在记账页的分类选择里，只服务于报表归集 */
    private suspend fun ensureParent(name: String, type: Int): String {
        db.categoryDao().getAll()
            .firstOrNull { it.type == type && it.name == name && it.parentId.isBlank() && it.hide }
            ?.let { return it.id }
        val id = AppDatabase.newId()
        db.categoryDao().insert(
            Category(
                id = id,
                name = name,
                type = type,
                icon = "📁",
                positionWeight = 999,
                hide = true
            )
        )
        return id
    }

    // ---------------- 小工具 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_card)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun title(main: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@ImportWizardActivity).apply {
            text = main
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_primary))
        })
        addView(TextView(this@ImportWizardActivity).apply {
            text = sub
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(4), dp(14), 0, 0)
    }

    private fun kv(k: String, v: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@ImportWizardActivity).apply {
            text = k
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@ImportWizardActivity).apply {
            text = v
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            gravity = Gravity.END
            maxLines = 2
        }, LinearLayout.LayoutParams(-2, -2))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_secondary))
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(6), dp(11), dp(6), 0)
    }
}
