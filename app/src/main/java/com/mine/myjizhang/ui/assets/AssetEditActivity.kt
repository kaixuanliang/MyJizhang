package com.mine.myjizhang.ui.assets

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.launch

class AssetEditActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var editingId: String? = null
    private var subType: String = ""
    private var icon: String = ""
    private var isCreditAccount = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_edit)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        editingId = intent.getStringExtra(EXTRA_ID)
        setTitle(if (editingId != null) "修改账户" else "添加账户")
        subType = intent.getStringExtra(EXTRA_SUBTYPE) ?: ""
        icon = intent.getStringExtra(EXTRA_ICON) ?: ""
        val prefillName = intent.getStringExtra(EXTRA_NAME) ?: ""

        isCreditAccount = AssetTypes.isCredit(subType)
        applyCreditUI()

        if (prefillName.isNotBlank() && editingId == null) {
            findViewById<EditText>(R.id.input_name).setText(prefillName)
        }

        if (editingId != null) {
            lifecycleScope.launch {
                val a = db.assetDao().getById(editingId!!) ?: return@launch
                subType = a.subType
                icon = a.icon
                isCreditAccount = a.type == 1 || AssetTypes.isCredit(subType)
                applyCreditUI()
                findViewById<EditText>(R.id.input_name).setText(a.name)
                // 信贷账户显示当前欠款（正数），普通账户显示当前余额
                findViewById<EditText>(R.id.input_amount).setText(
                    if (isCreditAccount) Money.fmt(Math.abs(a.amount.coerceAtMost(0.0)))
                    else Money.fmt(a.amount)
                )
                findViewById<EditText>(R.id.input_quota).setText(Money.fmt(a.totalQuota))
                findViewById<TextView>(R.id.text_charge_off).text = dayLabel(a.chargeOffDate)
                findViewById<TextView>(R.id.text_repayment).text = dayLabel(a.repaymentDate)
                findViewById<EditText>(R.id.input_remark).setText(a.remark)
                findViewById<MaterialSwitch>(R.id.switch_in_total).isChecked = a.inTotalAsset
            }
        }

        findViewById<View>(R.id.row_charge_off).setOnClickListener {
            pickDay(findViewById<TextView>(R.id.text_charge_off))
        }
        findViewById<View>(R.id.row_repayment).setOnClickListener {
            pickDay(findViewById<TextView>(R.id.text_repayment))
        }
    }

    private fun dayLabel(day: Int): String = if (day in 1..31) "每月${day}号" else "选填"

    private fun applyCreditUI() {
        findViewById<View>(R.id.row_quota).visibility =
            if (isCreditAccount) View.VISIBLE else View.GONE
        findViewById<View>(R.id.card_dates).visibility =
            if (isCreditAccount) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.label_amount).text =
            if (isCreditAccount) "当前欠款（欠款正数，存款负数）" else "当前余额"
    }

    /** 1-31 号选择 */
    private fun pickDay(target: TextView) {
        val dialog = Dialog(this)
        dialog.setTitle("选择几号")
        val picker = NumberPicker(this)
        picker.minValue = 1
        picker.maxValue = 31
        val cur = target.text.toString()
        picker.value = Regex("每月(\\d+)号").find(cur)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        dialog.setContentView(picker)
        picker.setOnValueChangedListener { _, _, newVal ->
            target.text = "每月${newVal}号"
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_asset_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_finish -> { save(); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun save() {
        val name = findViewById<EditText>(R.id.input_name).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入账户名称", Toast.LENGTH_SHORT).show()
            return
        }
        val rawAmount = Money.parse(findViewById<EditText>(R.id.input_amount).text.toString()) ?: 0.0
        val quota = if (isCreditAccount) Money.parse(findViewById<EditText>(R.id.input_quota).text.toString()) ?: 0.0 else 0.0
        val chargeOff = Regex("每月(\\d+)号")
            .find(findViewById<TextView>(R.id.text_charge_off).text.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val repayment = Regex("每月(\\d+)号")
            .find(findViewById<TextView>(R.id.text_repayment).text.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val inTotal = findViewById<MaterialSwitch>(R.id.switch_in_total).isChecked
        val remark = findViewById<EditText>(R.id.input_remark).text.toString().trim()

        // 信贷账户：欠款输入正数 → 余额存负数；输入负数（存款）→ 余额存正数
        val amount = if (isCreditAccount) -rawAmount else rawAmount

        lifecycleScope.launch {
            val existing = editingId?.let { db.assetDao().getById(it) }
            val asset = existing ?: Asset(
                id = AppDatabase.newId(),
                bookId = currentBookId(),
                name = name,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis()
            )
            val oldName = asset.name
            val oldQuota = asset.totalQuota
            val oldChargeOff = asset.chargeOffDate
            val oldRepay = asset.repaymentDate
            val oldInTotal = asset.inTotalAsset
            asset.name = name
            asset.type = if (isCreditAccount) 1 else 0
            asset.subType = subType.ifBlank {
                AssetTypes.match(name).name
            }
            asset.icon = icon.ifBlank {
                AssetTypes.bankOf(name)?.short ?: AssetTypes.match(name).emoji
            }
            asset.totalQuota = quota
            asset.chargeOffDate = chargeOff
            asset.repaymentDate = repayment
            asset.inTotalAsset = inTotal
            asset.remark = remark
            asset.updateTime = System.currentTimeMillis()

            if (existing == null) {
                // 新建：先以 0 落库，再用对账流水写入初始余额，保证导出后可原样还原
                asset.amount = 0.0
                db.assetDao().insert(asset)
                if (amount != 0.0) {
                    com.mine.myjizhang.data.Reconcile.apply(
                        db, asset.id, amount,
                        com.mine.myjizhang.data.entity.Adjust.KIND_ASSET_BALANCE,
                        "asset", asset.id, "新建账户初始余额"
                    )
                }
            } else {
                // 先落库账户信息（amount 仍为旧值），再由对账流水把余额调整到目标值
                db.assetDao().insert(asset)
                // 余额变化 → 记「余额调整」对账流水（导出/导入可还原）
                com.mine.myjizhang.data.Reconcile.setBalance(
                    db, asset.id, amount, "asset", asset.id, "修改账户余额"
                )
                // 关键信息变化 → 记一条「账户信息变更」，方便日后对账追查
                val changes = mutableListOf<String>()
                if (oldName != name) changes.add("名称 $oldName → $name")
                if (Math.abs(oldQuota - quota) > 0.005) changes.add("额度 ${Money.fmt(oldQuota)} → ${Money.fmt(quota)}")
                if (oldChargeOff != chargeOff) changes.add("出账日 ${oldChargeOff} → ${chargeOff}")
                if (oldRepay != repayment) changes.add("还款日 ${oldRepay} → ${repayment}")
                if (oldInTotal != inTotal) changes.add("计入净资产 ${oldInTotal} → ${inTotal}")
                if (changes.isNotEmpty()) {
                    com.mine.myjizhang.data.Reconcile.logInfo(
                        db, asset.id, "asset", asset.id, changes.joinToString("；")
                    )
                }
            }
            Toast.makeText(this@AssetEditActivity, "已保存", Toast.LENGTH_SHORT).show()
            finish()
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

    companion object {
        private const val EXTRA_ID = "asset_id"
        private const val EXTRA_SUBTYPE = "asset_subtype"
        private const val EXTRA_ICON = "asset_icon"
        private const val EXTRA_NAME = "asset_name"

        fun start(context: Context, assetId: String?) {
            context.startActivity(Intent(context, AssetEditActivity::class.java).apply {
                putExtra(EXTRA_ID, assetId)
            })
        }

        /** 新增：带资产类型（来自类型选择弹窗） */
        fun startNew(
            context: Context,
            subType: String,
            prefillName: String = "",
            credit: Boolean = false,
            icon: String = ""
        ) {
            context.startActivity(Intent(context, AssetEditActivity::class.java).apply {
                putExtra(EXTRA_SUBTYPE, subType)
                putExtra(EXTRA_NAME, prefillName)
                putExtra(EXTRA_ICON, icon)
            })
        }
    }
}
