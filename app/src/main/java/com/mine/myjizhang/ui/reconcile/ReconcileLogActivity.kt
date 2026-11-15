package com.mine.myjizhang.ui.reconcile

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.launch

/**
 * 对账记录页：逐条展示「余额调整 / 账单冲正 / 借款冲销 / 账户信息变更」流水。
 * 不再用弹窗一把梭，改成可滚动的列表页按天分组。
 */
class ReconcileLogActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private val adapter = AdjustAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reconcile_log)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "对账记录"

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ReconcileLogActivity)
            adapter = this@ReconcileLogActivity.adapter
        }

        lifecycleScope.launch {
            val bookId = currentBookId()
            val list = if (bookId.isNotBlank()) {
                db.adjustDao().getByBook(bookId)
            } else {
                db.adjustDao().getAll()
            }
            adapter.submit(list)

            findViewById<TextView>(R.id.text_count).text = "${list.size} 条"
            val net = list.sumOf { it.amount }
            findViewById<TextView>(R.id.text_net_adjust).apply {
                text = when {
                    Math.abs(net) < 0.005 -> Money.fmt(0.0)
                    net < 0 -> "-" + Money.fmt(net)
                    else -> "+" + Money.fmt(net)
                }
                setTextColor(getColor(if (net < 0) R.color.expense_red else R.color.income_green))
            }
            findViewById<TextView>(R.id.text_asset_count).text =
                "${list.map { it.assetId }.filter { it.isNotBlank() }.distinct().size} 个"

            findViewById<View>(R.id.empty_view).visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
}
