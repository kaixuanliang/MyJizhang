package com.mine.myjizhang.ui.loans

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Loan
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 添加/修改借入借出（direction：0=借入，1=借出）。
 * 保存时联动账户余额：借入 → 账户余额累加；借出 → 账户余额扣减；标记已还时反向恢复。
 */
class LoanEditActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private var direction = 1          // 0 借入 1 借出
    private var editingId: String? = null
    private var borrowDate: Long = System.currentTimeMillis()
    private var repayDate: Long = 0
    private var assetId: String = ""
    private var photoPath: String = ""
    private var assets: List<Asset> = emptyList()

    private lateinit var inputPerson: EditText
    private lateinit var inputAmount: EditText
    private lateinit var inputRemark: EditText
    private lateinit var textBorrowDate: TextView
    private lateinit var textRepayDate: TextView
    private lateinit var textAsset: TextView
    private lateinit var textPhoto: TextView

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { copyPhoto(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loan_edit)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        direction = intent.getIntExtra(EXTRA_DIRECTION, 1)
        editingId = intent.getStringExtra(EXTRA_ID)

        inputPerson = findViewById(R.id.input_person)
        inputAmount = findViewById(R.id.input_amount)
        inputRemark = findViewById(R.id.input_remark)
        textBorrowDate = findViewById(R.id.text_borrow_date)
        textRepayDate = findViewById(R.id.text_repay_date)
        textAsset = findViewById(R.id.text_asset)
        textPhoto = findViewById(R.id.text_photo)

        val isIn = direction == 0
        supportActionBar?.title = if (editingId != null) "修改借${if (isIn) "入" else "出"}" else "添加借${if (isIn) "入" else "出"}"
        findViewById<TextView>(R.id.label_person).text = if (isIn) "谁借钱给你" else "你借钱给谁"
        findViewById<TextView>(R.id.label_asset).text = if (isIn) "借入账户" else "借出账户"
        findViewById<TextView>(R.id.label_asset_hint).text =
            if (isIn) "会将金额累加到该账户" else "会从此账户扣除金额"
        inputAmount.hint = if (isIn) "借入金额" else "借出金额"

        findViewById<View>(R.id.row_borrow_date).setOnClickListener { pickDate(false) }
        findViewById<View>(R.id.row_repay_date).setOnClickListener { pickDate(true) }
        findViewById<View>(R.id.row_asset).setOnClickListener { pickAsset() }
        findViewById<View>(R.id.row_photo).setOnClickListener { photoPicker.launch("image/*") }
        findViewById<View>(R.id.row_photo).setOnLongClickListener { clearPhoto(); true }

        updateDateTexts()

        lifecycleScope.launch {
            assets = db.assetDao().getByBook(currentBookId())
            editingId?.let { loadExisting(it) }
        }
    }

    private suspend fun loadExisting(id: String) {
        val l = db.loanDao().getById(id) ?: return
        direction = l.direction
        borrowDate = l.borrowDate
        repayDate = l.repayDate
        assetId = l.assetId
        photoPath = l.photoPath
        inputPerson.setText(l.person)
        inputAmount.setText(Money.fmt(l.amount))
        inputRemark.setText(l.remark)
        updateDateTexts()
        updateAssetText()
        updatePhotoText()
    }

    private fun pickDate(isRepay: Boolean) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = if (isRepay && repayDate > 0) repayDate else borrowDate
        }
        DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, m, d, 12, 0, 0)
            if (isRepay) {
                repayDate = c.timeInMillis
                updateDateTexts()
            } else {
                borrowDate = c.timeInMillis
                updateDateTexts()
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickAsset() {
        if (assets.isEmpty()) {
            Toast.makeText(this, "还没有资产账户，请先到【资产】页添加", Toast.LENGTH_LONG).show()
            return
        }
        val names = assets.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(if (direction == 0) "借入账户" else "借出账户")
            .setItems(names) { _, i ->
                assetId = assets[i].id
                updateAssetText()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyPhoto(uri: Uri) {
        lifecycleScope.launch {
            try {
                val dir = File(filesDir, "loan_photos").apply { mkdirs() }
                val f = File(dir, "${AppDatabase.newId()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
                photoPath = f.absolutePath
                updatePhotoText()
            } catch (e: Exception) {
                Toast.makeText(this@LoanEditActivity, "照片保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearPhoto() {
        if (photoPath.isNotBlank()) {
            File(photoPath).delete()
            photoPath = ""
            updatePhotoText()
            Toast.makeText(this, "已清除照片", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDateTexts() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        textBorrowDate.text = fmt.format(borrowDate)
        textRepayDate.text = if (repayDate > 0) fmt.format(repayDate) else "选填"
        textRepayDate.setTextColor(getColor(if (repayDate > 0) R.color.text_primary else R.color.text_secondary))
    }

    private fun updateAssetText() {
        val name = assets.firstOrNull { it.id == assetId }?.name
        textAsset.text = name ?: "请选择"
        textAsset.setTextColor(getColor(if (name != null) R.color.text_primary else R.color.text_secondary))
    }

    private fun updatePhotoText() {
        textPhoto.text = if (photoPath.isNotBlank()) "已添加照片（长按清除）" else "选填"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_loan_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_finish -> { save(); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private suspend fun applyBalance(assetId: String, direction: Int, amount: Double, addBack: Boolean) {
        if (assetId.isBlank() || amount <= 0) return
        val a = db.assetDao().getById(assetId) ?: return
        // 借入 → 余额累加；借出 → 余额扣减。addBack=true 表示撤销/还清，取反方向
        val delta = when (direction) {
            0 -> if (addBack) -amount else amount
            else -> if (addBack) amount else -amount
        }
        a.amount += delta
        a.updateTime = System.currentTimeMillis()
        db.assetDao().update(a)
    }

    private fun save() {
        val person = inputPerson.text.toString().trim()
        if (person.isEmpty()) {
            Toast.makeText(this, if (direction == 0) "请填写谁借钱给你" else "请填写你借钱给谁", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = Money.parse(inputAmount.text.toString())
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "请输入正确的金额", Toast.LENGTH_SHORT).show()
            return
        }
        if (assetId.isBlank()) {
            Toast.makeText(this, "请选择账户", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val old = editingId?.let { db.loanDao().getById(it) }
            // 撤销旧记录对余额的影响（未还清的才影响余额）
            if (old != null && !old.repaid) {
                applyBalance(old.assetId, old.direction, old.amount, addBack = true)
            }
            val loan = old ?: Loan(
                id = AppDatabase.newId(),
                bookId = currentBookId(),
                direction = direction,
                person = person,
                amount = amount,
                createTime = System.currentTimeMillis()
            )
            loan.person = person
            loan.amount = amount
            loan.remark = inputRemark.text.toString().trim()
            loan.borrowDate = borrowDate
            loan.repayDate = repayDate
            loan.assetId = assetId
            loan.photoPath = photoPath
            loan.direction = direction
            db.loanDao().insert(loan)
            // 应用新记录对余额的影响
            applyBalance(loan.assetId, loan.direction, loan.amount, addBack = false)
            Toast.makeText(this@LoanEditActivity, "已保存", Toast.LENGTH_SHORT).show()
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
        private const val EXTRA_DIRECTION = "loan_direction"
        private const val EXTRA_ID = "loan_id"

        fun startNew(context: Context, direction: Int) {
            context.startActivity(Intent(context, LoanEditActivity::class.java).apply {
                putExtra(EXTRA_DIRECTION, direction)
            })
        }

        fun start(context: Context, loanId: String) {
            context.startActivity(Intent(context, LoanEditActivity::class.java).apply {
                putExtra(EXTRA_ID, loanId)
            })
        }
    }
}
