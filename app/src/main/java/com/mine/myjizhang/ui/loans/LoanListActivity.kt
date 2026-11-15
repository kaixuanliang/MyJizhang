package com.mine.myjizhang.ui.loans

import android.app.DatePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Adjust
import com.mine.myjizhang.data.entity.Loan
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 借入/借出列表页（direction：0=借入，1=借出） */
class LoanListActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var direction = 1
    private val adapter = LoanAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loan_list)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        direction = intent.getIntExtra(EXTRA_DIRECTION, 1)
        supportActionBar?.title = if (direction == 0) "借入" else "借出"
        findViewById<TextView>(R.id.label_outstanding).text =
            if (direction == 0) "未还金额（我欠的）" else "未还金额（别人欠的）"

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        adapter.onClick = { LoanEditActivity.start(this, it.id) }
        adapter.onLongClick = { showActions(it) }
        adapter.onRepay = { showRepayDialog(it) }
        adapter.onMore = { showActions(it) }

        lifecycleScope.launch {
            val bookId = currentBookId()
            db.loanDao().observeByBook(bookId).collect { loans ->
                // 排序：还欠着钱的（未还 / 部分已还）排前面，已还清的沉到最后；
                // 同一档里按借款日期倒序，最近借的在上。
                // 以前只按 createTime 排，而 Excel 导入推导出来的记录 createTime 全是同一秒，
                // 顺序完全随机，已还清的老账会混在最上面。
                // 未还和部分已还不拆成两档，是为了让「记一笔还款」之后这一行不会突然跳位。
                val list = loans.filter { it.direction == direction }
                    .sortedWith(
                        compareBy<Loan> { if (it.outstanding > 0.005) 0 else 1 }
                            .thenByDescending { it.borrowDate }
                            .thenByDescending { it.createTime }
                    )
                adapter.submit(list)
                val unpaidCount = list.count { it.outstanding > 0.005 }
                findViewById<TextView>(R.id.text_count).text = "${list.size} 笔"
                findViewById<TextView>(R.id.label_count).text =
                    if (unpaidCount == 0 || unpaidCount == list.size) "笔数"
                    else "笔数 · 未还清 $unpaidCount"
                findViewById<View>(R.id.text_empty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.text_empty).text = if (direction == 0) {
                    "还没有借入记录\n点右上角「＋」添加；还款时可以点条目上的「记录还款」，支持只还一部分"
                } else {
                    "还没有借出记录\n点右上角「＋」添加；收回时可以点条目上的「收回借款」，支持只还一部分"
                }
                // 未还金额 = 借款金额 - 已归还（含部分归还）
                val outstanding = list.sumOf { it.outstanding }
                findViewById<TextView>(R.id.text_outstanding).text = Money.fmt(outstanding)
            }
        }
    }

    /** 更多操作：还款 / 一键还清 / 撤销已记录还款 / 修改 / 删除 */
    private fun showActions(loan: Loan) {
        val isIn = loan.direction == 0
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (loan.outstanding > 0.005) {
            actions += (if (isIn) "记录还款（可部分）" else "收回借款（可部分）") to { showRepayDialog(loan) }
            actions += "一键还清" to { markFullyRepaid(loan) }
        }
        if (loan.repaidAmount > 0.005) {
            actions += "撤销已记录的还款" to { confirmUndoRepay(loan) }
        }
        actions += "修改" to { LoanEditActivity.start(this, loan.id) }
        actions += "删除" to { confirmDelete(loan) }

        MaterialAlertDialogBuilder(this)
            .setTitle(loan.person)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 还款 ----------------

    /**
     * 记录一笔还款（支持部分还款）。
     * 借入 = 我还钱给别人，账户余额减少；借出 = 别人还钱给我，账户余额增加。
     */
    private fun showRepayDialog(loan: Loan) {
        if (loan.outstanding <= 0.005) {
            Toast.makeText(this, "这笔已经全部还清了", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val assets = db.assetDao().getByBook(currentBookId())
            val isIn = loan.direction == 0
            val root = layoutInflater.inflate(R.layout.dialog_loan_repay, null)
            val inputAmount = root.findViewById<EditText>(R.id.input_amount)
            val inputNote = root.findViewById<EditText>(R.id.input_note)
            val textInfo = root.findViewById<TextView>(R.id.text_loan_info)
            val textDate = root.findViewById<TextView>(R.id.text_date)
            val textAccount = root.findViewById<TextView>(R.id.text_account)
            val labelAccount = root.findViewById<TextView>(R.id.label_account)

            textInfo.text = buildString {
                append(if (isIn) "借入 " else "借出 ")
                append("¥").append(Money.fmt(loan.amount))
                if (loan.repaidAmount > 0.005) {
                    append(" · 已").append(if (isIn) "还 " else "收回 ")
                    append("¥").append(Money.fmt(loan.repaidAmount))
                }
                append(" · 剩余 ¥").append(Money.fmt(loan.outstanding))
                if (loan.imported) {
                    // 这类记录是从账单推导出来的，本金那笔钱已经由账单动过；
                    // 但这次还款是新的现金流动，会按下面选的账户调整余额
                    append("\n这笔由 Excel 导入的账单推导生成：还款会同步调整你选的账户余额。")
                    append("若这笔还款已经记过账单，就不要再在这里重复记录，避免加两次")
                }
            }
            labelAccount.text = if (isIn) "付款账户" else "收到账户"

            var payDate = System.currentTimeMillis()
            // 默认沿用借款时的账户，用户可以改成实际收付的那个
            var assetId = loan.repayAssetId.ifBlank { loan.assetId }
            if (assetId.isBlank()) assetId = assets.firstOrNull()?.id ?: ""

            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            fun renderDate() {
                textDate.text = fmt.format(payDate)
            }
            fun renderAccount() {
                val name = assets.firstOrNull { it.id == assetId }?.name
                textAccount.text = name ?: "请选择"
                textAccount.setTextColor(
                    getColor(if (name != null) R.color.primary else R.color.text_secondary)
                )
            }
            inputAmount.setText(Money.fmt(loan.outstanding))
            inputAmount.setSelection(inputAmount.text.length)
            renderDate()
            renderAccount()

            root.findViewById<View>(R.id.btn_all).setOnClickListener {
                inputAmount.setText(Money.fmt(loan.outstanding))
                inputAmount.setSelection(inputAmount.text.length)
            }
            root.findViewById<View>(R.id.row_date).setOnClickListener {
                val cal = Calendar.getInstance().apply { timeInMillis = payDate }
                DatePickerDialog(this@LoanListActivity, { _, y, m, d ->
                    val c = Calendar.getInstance()
                    c.clear()
                    c.set(y, m, d, 12, 0, 0)
                    payDate = c.timeInMillis
                    renderDate()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            root.findViewById<View>(R.id.row_account).setOnClickListener {
                if (assets.isEmpty()) {
                    Toast.makeText(this@LoanListActivity, "还没有资产账户，请先到【资产】页添加", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                MaterialAlertDialogBuilder(this@LoanListActivity)
                    .setTitle(if (isIn) "从哪个账户还款" else "收到哪个账户")
                    .setItems(assets.map { it.name }.toTypedArray()) { _, i ->
                        assetId = assets[i].id
                        renderAccount()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            val dialog = MaterialAlertDialogBuilder(this@LoanListActivity)
                .setTitle(if (isIn) "记录还款" else "收回借款")
                .setView(root)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val pay = Money.parse(inputAmount.text.toString())
                    if (pay == null || pay <= 0) {
                        Toast.makeText(this@LoanListActivity, "请输入正确的金额", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (pay > loan.outstanding + 0.005) {
                        Toast.makeText(
                            this@LoanListActivity,
                            "超过剩余未还 ¥${Money.fmt(loan.outstanding)}",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    doRepay(loan, pay, payDate, assetId, inputNote.text.toString().trim())
                }
            }
            dialog.show()
        }
    }

    private fun doRepay(loan: Loan, pay: Double, payDate: Long, assetId: String, note: String) {
        if (assetId.isBlank()) {
            Toast.makeText(this, "请先选择${if (loan.direction == 0) "付款" else "收款"}账户", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            loan.repaidAmount = (loan.repaidAmount + pay).coerceAtMost(loan.amount)
            val cleared = loan.repaidAmount >= loan.amount - 0.005
            loan.repaid = cleared
            if (cleared) loan.repayDate = payDate
            loan.repayAssetId = assetId
            db.loanDao().update(loan)

            // 钱是真的动了：不管这笔借款是手工录的还是账单推导出来的，
            // 都要按用户选的账户调整余额（账单推导的那笔本金本来就由账单动过，
            // 而这次还款是一笔新的现金流动，不能再跳过）。
            val delta = if (loan.direction == 0) -pay else pay
            val what = if (loan.direction == 0) "还款" else "收回借款"
            com.mine.myjizhang.data.Reconcile.apply(
                db, assetId, delta, Adjust.KIND_LOAN_REPAY, "loan", loan.id,
                "$what ¥${Money.fmt(pay)}（${loan.person}）" + if (note.isNotBlank()) " · $note" else ""
            )
            val acc = db.assetDao().getById(assetId)
            val sync = if (acc == null) {
                "所选账户已不存在，余额未同步"
            } else {
                "${acc.name} ${if (delta > 0) "+" else "-"}${Money.fmt(Math.abs(delta))} → ¥${Money.fmt(acc.amount)}"
            }
            val msg = if (cleared) {
                "已全部${if (loan.direction == 0) "还清" else "收回"} · $sync"
            } else {
                "已记录 ¥${Money.fmt(pay)}，剩余 ¥${Money.fmt(loan.outstanding)} · $sync"
            }
            Toast.makeText(this@LoanListActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 一键还清：把剩余部分一次性记为已还，并同步动账户余额 */
    private fun markFullyRepaid(loan: Loan) {
        val aid = loan.repayAssetId.ifBlank { loan.assetId }
        if (aid.isBlank()) {
            // 没有可用的收款账户时不猜，走正常的还款弹窗让用户自己选
            showRepayDialog(loan)
            return
        }
        lifecycleScope.launch {
            val accName = db.assetDao().getById(aid)?.name ?: "原账户"
            val sign = if (loan.direction == 0) "-" else "+"
            MaterialAlertDialogBuilder(this@LoanListActivity)
                .setTitle("一键还清")
                .setMessage(
                    "把剩余未还的 ¥${Money.fmt(loan.outstanding)} 一次性记为已${if (loan.direction == 0) "还" else "收回"}？\n" +
                        "$accName 余额 $sign${Money.fmt(loan.outstanding)}"
                )
                .setPositiveButton("确认") { _, _ ->
                    doRepay(loan, loan.outstanding, System.currentTimeMillis(), aid, "")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmUndoRepay(loan: Loan) {
        MaterialAlertDialogBuilder(this)
            .setTitle("撤销已记录的还款")
            .setMessage(
                "把这笔「已${if (loan.direction == 0) "还" else "收回"} ¥${Money.fmt(loan.repaidAmount)}」清零，" +
                    "并按当初记账的账户把余额回退。"
            )
            .setPositiveButton("撤销") { _, _ -> undoRepay(loan) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 撤销还款：把这笔借款在「对账流水」里写下的余额调整按账户原路退回，
     * 再把已还金额减掉这些钱。
     *
     * 之所以按流水回退、而不是按 repaidAmount 一刀清零：由账单推导出来的借款，
     * 已还金额里有一部分是账单流水冲销的（那笔钱在账户里已经动过），
     * 在这里再退一次就会凭空多出钱。
     */
    private fun undoRepay(loan: Loan) {
        lifecycleScope.launch {
            val reverted = com.mine.myjizhang.data.Reconcile.revertByRef(
                db, "loan", loan.id, Adjust.KIND_LOAN_REPAY
            )
            when {
                reverted > 0.005 -> {
                    loan.repaidAmount = (loan.repaidAmount - reverted).coerceAtLeast(0.0)
                    finishUndo(loan, reverted)
                }
                loan.repaidAmount > 0.005 -> {
                    // 已还金额找不到对应的余额调整流水：多是旧版本在「账单推导的借款」上
                    // 记过还款（当时没动余额），也可能这笔还款本来就由账单流水冲销。
                    // 到底要不要退钱，交给用户判断，别替他猜。
                    askUndoWithoutLog(loan)
                }
                else -> toast("这笔没有可撤销的还款")
            }
        }
    }

    /** 已还金额没有对应余额流水时的撤销方式：只清零 / 清零并退钱 */
    private fun askUndoWithoutLog(loan: Loan) {
        val aid = loan.repayAssetId.ifBlank { loan.assetId }
        lifecycleScope.launch {
            val accName = db.assetDao().getById(aid)?.name ?: "原账户"
            val back = loan.repaidAmount
            MaterialAlertDialogBuilder(this@LoanListActivity)
                .setTitle("撤销已记录的还款")
                .setMessage(
                    "这笔「已${if (loan.direction == 0) "还" else "收回"} ¥${Money.fmt(back)}」没有对应的余额调整记录" +
                        "（可能是旧版本记的，或这笔钱本来就走账单）。\n\n" +
                        "· 只清零：只把这条借款改回未还，账户余额不动\n" +
                        "· 清零并退余额：$accName ${if (loan.direction == 0) "+" else "-"}${Money.fmt(back)}"
                )
                .setPositiveButton("只清零") { _, _ ->
                    lifecycleScope.launch {
                        loan.repaidAmount = 0.0
                        finishUndo(loan, 0.0)
                    }
                }
                .setNeutralButton("清零并退余额") { _, _ ->
                    lifecycleScope.launch {
                        if (aid.isNotBlank()) {
                            // 当初退回本金时的方向：借入 -back / 借出 +back，撤销取反
                            val delta = if (loan.direction == 0) back else -back
                            com.mine.myjizhang.data.Reconcile.apply(
                                db, aid, delta, Adjust.KIND_LOAN_REPAY, "loan", loan.id,
                                "撤销${if (loan.direction == 0) "还款" else "收回借款"} ¥${Money.fmt(back)}（${loan.person}）"
                            )
                        }
                        loan.repaidAmount = 0.0
                        finishUndo(loan, back)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private suspend fun finishUndo(loan: Loan, reverted: Double) {
        loan.repaid = loan.repaidAmount >= loan.amount - 0.005
        loan.repayAssetId = ""
        db.loanDao().update(loan)
        toast(
            if (reverted > 0.005) "已撤销，账户余额已回退 ¥${Money.fmt(Math.abs(reverted))}"
            else "已撤销，账户余额未变动"
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun confirmDelete(loan: Loan) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除记录")
            .setMessage("删除「${loan.person}」的这笔${if (loan.direction == 0) "借入" else "借出"}吗？未还清的记录会同时恢复账户余额。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val remain = loan.outstanding
                    if (remain > 0.005 && !loan.imported && loan.assetId.isNotBlank()) {
                        val delta = if (loan.direction == 0) -remain else remain
                        com.mine.myjizhang.data.Reconcile.apply(
                            db, loan.assetId, delta,
                            Adjust.KIND_LOAN_DELETE,
                            "loan", loan.id, "删除未还借款（${loan.person}）"
                        )
                    }
                    db.loanDao().delete(loan)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_loan_add, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add -> { LoanEditActivity.startNew(this, direction); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
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

    class LoanAdapter : RecyclerView.Adapter<LoanAdapter.Holder>() {

        var onClick: (Loan) -> Unit = {}
        var onLongClick: (Loan) -> Unit = {}
        var onRepay: (Loan) -> Unit = {}
        var onMore: (Loan) -> Unit = {}
        private var list: List<Loan> = emptyList()

        fun submit(list: List<Loan>) {
            this.list = list
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val person: TextView = view.findViewById(R.id.text_person)
            val amount: TextView = view.findViewById(R.id.text_amount)
            val status: TextView = view.findViewById(R.id.text_status)
            val dates: TextView = view.findViewById(R.id.text_dates)
            val remark: TextView = view.findViewById(R.id.text_remark)
            val repay: TextView = view.findViewById(R.id.btn_repay)
            val more: TextView = view.findViewById(R.id.btn_more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_loan, parent, false))

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val l = list[position]
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            holder.person.text = l.person
            holder.amount.text = (if (l.direction == 0) "+" else "-") + Money.fmt(l.amount)
            holder.amount.setTextColor(
                holder.itemView.context.getColor(if (l.direction == 0) R.color.income_green else R.color.expense_red)
            )
            holder.status.text = when {
                l.repaid -> "已还"
                l.repaidAmount > 0.005 -> "部分已还"
                else -> "未还"
            }
            holder.status.setTextColor(
                holder.itemView.context.getColor(
                    if (l.repaid) R.color.income_green else R.color.expense_red
                )
            )
            holder.status.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f
                setColor(
                    applyAlpha(
                        holder.itemView.context.getColor(
                            if (l.repaid) R.color.income_green else R.color.expense_red
                        ),
                        0x22
                    )
                )
            }
            holder.dates.text = buildString {
                append("借款 ")
                append(fmt.format(l.borrowDate))
                if (l.repayDate > 0) {
                    append(" · 还款 ")
                    append(fmt.format(l.repayDate))
                }
                if (!l.repaid && l.repaidAmount > 0.005) {
                    append(" · 已还 ¥")
                    append(Money.fmt(l.repaidAmount))
                    append(" · 剩余 ¥")
                    append(Money.fmt(l.outstanding))
                }
            }
            holder.remark.visibility = if (l.remark.isBlank()) View.GONE else View.VISIBLE
            holder.remark.text = l.remark

            // 还清后不再显示还款按钮，只留「更多」（可撤销/删除）
            holder.repay.visibility = if (l.outstanding > 0.005) View.VISIBLE else View.GONE
            holder.repay.text = if (l.direction == 0) "记录还款" else "收回借款"
            holder.repay.setOnClickListener { onRepay(l) }
            holder.more.setOnClickListener { onMore(l) }

            holder.itemView.setOnClickListener { onClick(l) }
            holder.itemView.setOnLongClickListener { onLongClick(l); true }
        }

        private fun applyAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {
        private const val EXTRA_DIRECTION = "loan_direction"
        fun start(context: Context, direction: Int) {
            context.startActivity(Intent(context, LoanListActivity::class.java).apply {
                putExtra(EXTRA_DIRECTION, direction)
            })
        }
    }
}
