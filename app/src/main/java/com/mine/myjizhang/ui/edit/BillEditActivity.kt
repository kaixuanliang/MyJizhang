package com.mine.myjizhang.ui.edit

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.CategoryIcons
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.ui.assets.AssetTypes
import com.mine.myjizhang.ui.widget.SheetList
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.launch
import java.util.Calendar

class BillEditActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var billType = 0 // 0支出 1收入 2转账
    private var editingId: String? = null
    private var time: Long = System.currentTimeMillis()
    private var selectedCategoryId: String = ""
    private var selectedAssetId: String = ""
    private var fromAssetId: String = ""
    private var toAssetId: String = ""
    private var bookId: String = ""
    private var bookName: String = "个人账本"

    private var assets: List<Asset> = emptyList()
    private var categories: List<Category> = emptyList()

    private lateinit var categoryGrid: RecyclerView
    private lateinit var categoryAdapter: CategoryGridAdapter
    private lateinit var amountText: TextView
    private lateinit var remarkInput: EditText
    private lateinit var chipRow: LinearLayout
    private lateinit var iconFrom: TextView
    private lateinit var iconTo: TextView
    private lateinit var textFrom: TextView
    private lateinit var textTo: TextView
    private lateinit var pendingText: TextView

    /** 金额输入缓冲："12.5" */
    private val amountBuffer = StringBuilder()
    /** 简单加减：点 + / - 时把当前值挂起 */
    private var pendingValue: Double? = null
    private var pendingOp: Char = ' '

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_edit)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = if (intent.getStringExtra(EXTRA_BILL_ID) != null) "编辑账单" else "记一笔"

        categoryGrid = findViewById(R.id.category_grid)
        categoryAdapter = CategoryGridAdapter { c ->
            selectedCategoryId = c.id
            categoryAdapter.submit(visibleCategories(), selectedCategoryId)
        }
        categoryGrid.layoutManager = GridLayoutManager(this, 5)
        categoryGrid.adapter = categoryAdapter
        // 网格嵌在外层 ScrollView 里：关掉自己的滚动，高度按内容撑开，
        // 否则分类一多（支出 28 项）会只显示两三行
        categoryGrid.isNestedScrollingEnabled = false

        amountText = findViewById(R.id.input_amount)
        remarkInput = findViewById(R.id.input_remark)
        chipRow = findViewById(R.id.chip_row)
        iconFrom = findViewById(R.id.icon_from_account)
        iconTo = findViewById(R.id.icon_to_account)
        textFrom = findViewById(R.id.row_from_account)
        textTo = findViewById(R.id.row_to_account)
        pendingText = findViewById(R.id.text_pending)

        findViewById<View>(R.id.tab_pay).setOnClickListener { setType(0) }
        findViewById<View>(R.id.tab_income).setOnClickListener { setType(1) }
        findViewById<View>(R.id.tab_transfer).setOnClickListener { setType(2) }
        findViewById<View>(R.id.card_from_account).setOnClickListener { pickAccount(1) }
        findViewById<View>(R.id.card_to_account).setOnClickListener { pickAccount(2) }

        buildKeypad()
        renderAmount()

        editingId = intent.getStringExtra(EXTRA_BILL_ID)
        val presetType = intent.getIntExtra(EXTRA_TYPE, -1)
        val presetAssetId = intent.getStringExtra(EXTRA_ASSET_ID) ?: ""

        lifecycleScope.launch {
            bookId = currentBookId()
            bookName = db.bookDao().getAll().firstOrNull { it.id == bookId }?.name ?: "个人账本"
            assets = db.assetDao().getByBook(bookId)
            if (editingId != null) {
                loadExistingSuspend(editingId!!)
            } else {
                if (presetAssetId.isNotBlank()) selectedAssetId = presetAssetId
                setType(if (presetType >= 0) presetType else 0)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------- 顶部 tab ----------------

    private fun setType(t: Int) {
        billType = t
        lifecycleScope.launch {
            categories = db.categoryDao().getByType(if (t == 1) 1 else 0)
            val visible = visibleCategories()
            selectedCategoryId = visible.firstOrNull()?.id ?: ""
            applyTypeUI()
            categoryAdapter.submit(visible, selectedCategoryId)
            updateChips()
        }
    }

    private fun applyTypeUI() {
        val on = getColor(R.color.primary)
        val off = getColor(R.color.text_secondary)
        findViewById<TextView>(R.id.tab_pay).setTextColor(if (billType == 0) on else off)
        findViewById<TextView>(R.id.tab_income).setTextColor(if (billType == 1) on else off)
        findViewById<TextView>(R.id.tab_transfer).setTextColor(if (billType == 2) on else off)
        findViewById<View>(R.id.ind_pay).visibility = if (billType == 0) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.ind_income).visibility = if (billType == 1) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.ind_transfer).visibility = if (billType == 2) View.VISIBLE else View.INVISIBLE

        categoryGrid.visibility = if (billType == 2) View.GONE else View.VISIBLE
        findViewById<View>(R.id.text_income_loan_hint).visibility =
            if (billType == 2) View.GONE else View.VISIBLE
        findViewById<View>(R.id.row_transfer_container).visibility =
            if (billType == 2) View.VISIBLE else View.GONE
        updateAccountCards()
    }

    /**
     * 可选的分类。
     *
     * 借出 / 借入 / 收回借款 / 还款都不再走「记一笔」：这些操作要挂到具体某个人、某一笔上，
     * 还得支持部分还款，只能到「资产 → 总借出 / 总借入」里做。留在这里反而误导——
     * 记了也不会进「总借出」，统计里又被当成不计收支，等于记了个寂寞。
     */
    private fun visibleCategories(): List<Category> {
        if (billType == 2) return emptyList()
        val list = categories.filter { CategoryIcons.loanDirection(it.name) == null }
        // 编辑老账单时它挂的可能是已被隐藏的借贷分类，补回去，不然选不上也看不见
        val current = categories.firstOrNull { it.id == selectedCategoryId }
        return if (current != null && list.none { it.id == current.id }) list + current else list
    }

    /** 转账两个卡片：未选账户时显示提示 + 问号 */
    private fun updateAccountCards() {
        fun render(assetId: String, icon: TextView, label: TextView, hint: String) {
            val a = assets.firstOrNull { it.id == assetId }
            if (a == null) {
                icon.text = "?"
                icon.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F0F1F3"))
                }
                icon.setTextColor(Color.parseColor("#9A9AA0"))
                icon.textSize = 15f
                label.text = hint
                label.setTextColor(Color.parseColor("#9A9AA0"))
            } else {
                AssetTypes.bindIcon(icon, a.name, a.subType, a.icon)
                label.text = a.name
                label.setTextColor(getColor(R.color.text_primary))
            }
        }
        render(fromAssetId, iconFrom, textFrom, "转出账户")
        render(toAssetId, iconTo, textTo, "转入账户")
    }

    // ---------------- 底部 chips ----------------

    private fun updateChips() {
        chipRow.removeAllViews()
        addChip(dateChipText()) { pickDate() }
        if (billType != 2) {
            val acc = assets.firstOrNull { it.id == selectedAssetId }?.name ?: "请选择账户"
            addChip(acc) { pickAccount(0) }
        }
        addChip(bookName) { pickBook() }
        if (billType == 2) {
            addChip(if (fee > 0) "手续费 ${Money.fmt(fee)}" else "手续费") { editNumber("手续费") { fee = it } }
            addChip(if (discount > 0) "优惠 ${Money.fmt(discount)}" else "优惠") { editNumber("优惠") { discount = it } }
        }
    }

    private var fee = 0.0
    private var discount = 0.0

    private fun addChip(text: String, onClick: () -> Unit) {
        chipRow.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#4A4A50"))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F2F3F5"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun editNumber(title: String, onDone: (Double) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "请输入$title"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                onDone(Money.parse(input.text.toString()) ?: 0.0)
                updateChips()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dateChipText(): String {
        val cal = Calendar.getInstance().also { it.timeInMillis = this.time }
        val today = Calendar.getInstance()
        val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYest = cal.get(Calendar.YEAR) == yest.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == yest.get(Calendar.DAY_OF_YEAR)
        val prefix = when {
            sameDay -> "今天"
            isYest -> "昨天"
            else -> "${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }
        return "%s %02d:%02d".format(
            prefix, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = this@BillEditActivity.time }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val c = Calendar.getInstance()
                c.clear()
                c.set(y, m, d, h, min, 0)
                time = c.timeInMillis
                updateChips()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickBook() {
        lifecycleScope.launch {
            val books = db.bookDao().getAll()
            if (books.isEmpty()) return@launch
            val names = books.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@BillEditActivity)
                .setTitle("选择账本")
                .setItems(names) { _, i ->
                    bookId = books[i].id
                    bookName = books[i].name
                    updateChips()
                }
                .show()
        }
    }

    // ---------------- 数字键盘 ----------------

    private fun buildKeypad() {
        val keypad = findViewById<GridLayout>(R.id.keypad)
        val keys = listOf(
            "1", "2", "3", "⌫",
            "4", "5", "6", "-",
            "7", "8", "9", "+",
            "再记", "0", ".", "完成"
        )
        for (k in keys) {
            val tv = TextView(this).apply {
                text = k
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                includeFontPadding = false
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(50)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.WHITE)
                }
                when (k) {
                    "完成" -> {
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(Color.parseColor("#1E6FD9"))
                        }
                    }
                    "再记" -> {
                        textSize = 14f
                        setTextColor(Color.parseColor("#5A5A60"))
                    }
                    else -> {
                        textSize = 20f
                        setTextColor(Color.parseColor("#26262B"))
                        typeface = Typeface.DEFAULT_BOLD
                    }
                }
                setOnClickListener { onKey(k) }
            }
            keypad.addView(tv)
        }
    }

    private fun onKey(k: String) {
        when (k) {
            "完成" -> { save(false); return }
            "再记" -> { save(true); return }
            "⌫" -> if (amountBuffer.isNotEmpty()) amountBuffer.deleteCharAt(amountBuffer.length - 1)
            "." -> if (!amountBuffer.contains(".")) {
                if (amountBuffer.isEmpty()) amountBuffer.append("0")
                amountBuffer.append(".")
            }
            "+", "-" -> {
                val cur = currentAmount()
                val base = pendingValue
                if (pendingOp != ' ' && base != null) {
                    pendingValue = if (pendingOp == '+') base + cur else base - cur
                } else {
                    pendingValue = cur
                }
                pendingOp = k[0]
                amountBuffer.clear()
                pendingText.visibility = View.VISIBLE
                pendingText.text = "${Money.fmt(pendingValue ?: 0.0)} ${pendingOp}"
                renderAmount()
                return
            }
            else -> {
                if (amountBuffer.contains(".")) {
                    if (amountBuffer.toString().substringAfter(".").length >= 2) return
                } else if (amountBuffer.length >= 8) {
                    return
                }
                if (amountBuffer.toString() == "0") amountBuffer.clear()
                amountBuffer.append(k)
            }
        }
        renderAmount()
    }

    private fun renderAmount() {
        amountText.text = if (amountBuffer.isEmpty()) "0.00" else amountBuffer.toString()
    }

    private fun currentAmount(): Double = Money.parse(amountBuffer.toString()) ?: 0.0

    /** 应用挂起的加减，得到最终金额 */
    private fun settledAmount(): Double {
        val cur = currentAmount()
        val base = pendingValue ?: return cur
        return when (pendingOp) {
            '+' -> base + cur
            '-' -> base - cur
            else -> cur
        }
    }

    // ---------------- 账户选择弹窗（带官方 logo） ----------------

    private fun pickAccount(which: Int) {
        if (assets.isEmpty()) {
            Toast.makeText(this, "还没有资产账户，请先去【资产】页添加", Toast.LENGTH_LONG).show()
            return
        }
        // 标题固定在顶部，列表在固定高度里滚动（账户多的时候不会把弹窗撑满屏）
        val sheet = SheetList.create(this, "选择账户", assets.size, 56)
        assets.forEach { a ->
            val icon = SheetList.iconView(this, 32).also {
                AssetTypes.bindIcon(it, a.name, a.subType, a.icon)
            }
            sheet.list.addView(
                SheetList.row(
                    this,
                    title = a.name,
                    heightDp = 56,
                    icon = icon,
                    subtitle = AssetTypes.groupOf(a.subType, a.type, a.name).let { g ->
                        if (g == AssetTypes.GROUP_CREDIT) "信贷 · 欠款" else null
                    }
                ) {
                    when (which) {
                        0 -> selectedAssetId = a.id
                        1 -> fromAssetId = a.id
                        2 -> toAssetId = a.id
                    }
                    updateAccountCards()
                    updateChips()
                    sheet.dialog.dismiss()
                }
            )
        }
        sheet.dialog.show()
    }

    // ---------------- 保存 ----------------

    private suspend fun loadExistingSuspend(id: String) {
        val b = db.billDao().getById(id) ?: return
        billType = b.type
        time = b.time
        selectedCategoryId = b.categoryId
        selectedAssetId = b.assetId
        fromAssetId = b.fromAssetId
        toAssetId = b.toAssetId
        if (b.bookId.isNotBlank()) {
            bookId = b.bookId
            bookName = db.bookDao().getAll().firstOrNull { it.id == b.bookId }?.name ?: bookName
        }
        fee = b.serviceCharge
        discount = b.discount
        categories = db.categoryDao().getByType(b.type)
        if (b.amount > 0) {
            amountBuffer.clear()
            amountBuffer.append(Money.fmt(b.amount))
        }
        renderAmount()
        remarkInput.setText(b.remark)
        applyTypeUI()
        categoryAdapter.submit(visibleCategories(), selectedCategoryId)
        updateChips()
    }

    private fun save(andContinue: Boolean) {
        val amount = settledAmount()
        if (amount <= 0.0) {
            Toast.makeText(this, "请输入正确的金额", Toast.LENGTH_SHORT).show()
            return
        }
        if (billType != 2) {
            if (selectedAssetId.isBlank()) {
                Toast.makeText(this, "请选择账户", Toast.LENGTH_SHORT).show()
                return
            }
            if (selectedCategoryId.isBlank() && categories.isNotEmpty()) {
                Toast.makeText(this, "请选择分类", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (fromAssetId.isBlank() || toAssetId.isBlank()) {
                Toast.makeText(this, "请选择转出/转入账户", Toast.LENGTH_SHORT).show()
                return
            }
            if (fromAssetId == toAssetId) {
                Toast.makeText(this, "转出和转入账户不能相同", Toast.LENGTH_SHORT).show()
                return
            }
        }

        lifecycleScope.launch {
            if (bookId.isBlank()) bookId = currentBookId()
            if (editingId != null) {
                val existing = db.billDao().getById(editingId!!)
                if (existing != null) {
                    com.mine.myjizhang.data.Reconcile.reverseBill(
                        db, existing, com.mine.myjizhang.data.entity.Adjust.KIND_BILL_EDIT,
                        "修改账单：冲正原记录 ¥${Money.fmt(existing.amount)}"
                    )
                }
            }
            val bill = Bill(
                id = editingId ?: AppDatabase.newId(),
                bookId = bookId,
                type = billType,
                amount = amount,
                time = time,
                remark = remarkInput.text.toString().trim(),
                categoryId = if (billType == 2) "" else selectedCategoryId,
                assetId = if (billType == 2) "" else selectedAssetId,
                fromAssetId = if (billType == 2) fromAssetId else "",
                toAssetId = if (billType == 2) toAssetId else "",
                serviceCharge = fee,
                discount = discount,
                recordType = 1,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis()
            )
            db.billDao().insert(bill)
            if (editingId != null) {
                com.mine.myjizhang.data.Reconcile.applyBill(
                    db, bill, com.mine.myjizhang.data.entity.Adjust.KIND_BILL_EDIT,
                    "修改账单：计入新记录 ¥${Money.fmt(bill.amount)}"
                )
            } else {
                // 新增账单：余额变化由账单本身解释，不需要额外的对账调整
                for (id in com.mine.myjizhang.data.Reconcile.billAssets(bill)) {
                    com.mine.myjizhang.data.Reconcile.changeBalance(
                        db, id, com.mine.myjizhang.data.Reconcile.billEffect(bill, id)
                    )
                }
            }

            if (andContinue) {
                Toast.makeText(this@BillEditActivity, "已保存，继续记下一笔", Toast.LENGTH_SHORT).show()
                amountBuffer.clear()
                pendingValue = null
                pendingOp = ' '
                pendingText.visibility = View.GONE
                renderAmount()
                remarkInput.setText("")
            } else {
                Toast.makeText(this@BillEditActivity, "记账成功", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private suspend fun currentBookId(): String {
        var id = Pref.currentBookId(this)
        if (id.isBlank()) {
            val book = db.bookDao().getAll().firstOrNull()
            book?.let {
                id = it.id
                Pref.setCurrentBookId(this, it.id)
            }
        }
        return id
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 菜单 ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bill_edit, menu)
        menu.findItem(R.id.action_delete)?.isVisible = editingId != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_delete -> {
            confirmDelete()
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDelete() {
        val id = editingId ?: return
        AlertDialog.Builder(this)
            .setTitle("删除账单")
            .setMessage("删除后该笔账单的账户余额变化会一并撤销。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    db.billDao().getById(id)?.let { b ->
                        com.mine.myjizhang.data.Reconcile.reverseBill(
                            db, b, com.mine.myjizhang.data.entity.Adjust.KIND_BILL_DELETE,
                            "删除账单：冲正 ¥${Money.fmt(b.amount)}"
                        )
                        db.billDao().delete(b)
                    }
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val EXTRA_BILL_ID = "bill_id"
        private const val EXTRA_TYPE = "bill_type"
        private const val EXTRA_ASSET_ID = "bill_asset_id"

        fun start(context: Context, billId: String?) {
            context.startActivity(Intent(context, BillEditActivity::class.java).apply {
                putExtra(EXTRA_BILL_ID, billId)
            })
        }

        /** 快捷记账：预选类型（0支出 1收入 2转账），可预选账户 */
        fun startNew(context: Context, type: Int, assetId: String = "") {
            context.startActivity(Intent(context, BillEditActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_ASSET_ID, assetId)
            })
        }
    }
}
