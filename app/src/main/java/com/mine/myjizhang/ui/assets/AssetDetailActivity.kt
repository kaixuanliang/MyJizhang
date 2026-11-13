package com.mine.myjizhang.ui.assets

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.ui.bills.BillAdapter
import com.mine.myjizhang.ui.edit.BillEditActivity
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssetDetailActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var assetId: String = ""
    private var asset: Asset? = null
    private val adapter = BillAdapter(
        onClick = { bill -> BillEditActivity.start(this, bill.id) },
        onLongClick = { }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_detail)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        assetId = intent.getStringExtra(EXTRA_ID) ?: ""

        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.btn_quick_pay).setOnClickListener {
            asset?.let { BillEditActivity.startNew(this, 0, it.id) }
        }
        findViewById<View>(R.id.btn_quick_income).setOnClickListener {
            asset?.let { BillEditActivity.startNew(this, 1, it.id) }
        }
        findViewById<View>(R.id.btn_quick_transfer).setOnClickListener {
            asset?.let { BillEditActivity.startNew(this, 2, it.id) }
        }

        lifecycleScope.launch {
            val a = db.assetDao().getById(assetId)
            asset = a
            title = "资产 - " + (a?.name ?: "详情")
            findViewById<TextView>(R.id.text_balance).text =
                (if ((a?.amount ?: 0.0) < 0) "-¥" else "¥") + Money.fmt(a?.amount ?: 0.0)
            if (a?.type == 1) {
                findViewById<TextView>(R.id.text_quota).text =
                    "额度 ¥${Money.fmt(a!!.totalQuota)} · 出账日${a.chargeOffDate}日 还款日${a.repaymentDate}日"
                findViewById<TextView>(R.id.text_quota).visibility = android.view.View.VISIBLE
            }

            val categories = db.categoryDao().getAll()
            val catMap = categories.associateBy { it.id }
            adapter.categoryName = { id -> catMap[id]?.name ?: "" }
            adapter.categoryIcon = { id -> catMap[id]?.icon ?: "💡" }
            val allAssets = HashMap<String, String>()
            db.bookDao().getAll().forEach { book ->
                db.assetDao().getByBook(book.id).forEach { allAssets[it.id] = it.name }
            }
            adapter.assetName = { id -> allAssets[id] ?: "已删除账户" }

            a?.let {
                db.billDao().observeByAsset(it.bookId, it.id).collectLatest { bills ->
                    adapter.submitRows(bills.map { com.mine.myjizhang.ui.bills.Row.BillRow(it) })
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_asset_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_edit -> {
            AssetEditActivity.start(this, assetId)
            true
        }
        R.id.action_delete -> {
            confirmDelete()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除账户")
            .setMessage("删除账户不影响已有账单记录。确定删除「${asset?.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    asset?.let { db.assetDao().delete(it) }
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "asset_id"
        fun startIntent(context: Context, assetId: String) =
            Intent(context, AssetDetailActivity::class.java).apply { putExtra(EXTRA_ID, assetId) }
    }
}
