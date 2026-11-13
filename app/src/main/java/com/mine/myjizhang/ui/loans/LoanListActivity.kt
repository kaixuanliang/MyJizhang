package com.mine.myjizhang.ui.loans

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import com.mine.myjizhang.data.entity.Loan
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
        findViewById<TextView>(R.id.label_outstanding).text = if (direction == 0) "未还金额（我欠的）" else "未还金额（别人欠的）"

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        adapter.onClick = { LoanEditActivity.start(this, it.id) }
        adapter.onLongClick = { showActions(it) }

        lifecycleScope.launch {
            val bookId = currentBookId()
            db.loanDao().observeByBook(bookId).collect { loans ->
                val list = loans.filter { it.direction == direction }
                adapter.submit(list)
                findViewById<TextView>(R.id.text_count).text = "${list.size} 笔"
                // 未还金额 = 借款金额 - 已归还（含部分归还）
                val outstanding = list.sumOf { it.outstanding }
                findViewById<TextView>(R.id.text_outstanding).text = Money.fmt(outstanding)
            }
        }
    }

    /** 点击条目：标记已还/未还、删除 */
    private fun showActions(loan: Loan) {
        val items = arrayOf(
            if (loan.repaid) "标记为未还" else "标记为已还",
            "删除"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(loan.person)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleRepaid(loan)
                    1 -> confirmDelete(loan)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleRepaid(loan: Loan) {
        lifecycleScope.launch {
            val before = loan.outstanding
            val markRepaid = !loan.repaid
            loan.repaidAmount = if (markRepaid) loan.amount else 0.0
            loan.repaid = markRepaid
            db.loanDao().update(loan)
            // 余额联动：借入还清→余额减少；借出收回→余额增加
            // 由账单导入生成的记录余额已由流水计算，不再二次联动
            if (!loan.imported && loan.assetId.isNotBlank() && before > 0.005) {
                val delta = when (loan.direction) {
                    0 -> if (markRepaid) -before else before
                    else -> if (markRepaid) before else -before
                }
                com.mine.myjizhang.data.Reconcile.apply(
                    db, loan.assetId, delta,
                    com.mine.myjizhang.data.entity.Adjust.KIND_LOAN_REPAY,
                    "loan", loan.id,
                    if (markRepaid) "借款已还清（${loan.person}）" else "撤销已还（${loan.person}）"
                )
            }
            Toast.makeText(
                this@LoanListActivity,
                if (markRepaid) "已标记为已还，账户余额已同步" else "已标记为未还",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

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
                            com.mine.myjizhang.data.entity.Adjust.KIND_LOAN_DELETE,
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
            holder.remark.visibility = if (l.remark.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            holder.remark.text = l.remark
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
