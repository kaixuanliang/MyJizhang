package com.mine.myjizhang.ui.budget

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.Budget
import com.mine.myjizhang.data.entity.Category
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 预算设置：账本总预算 + 各支出分类预算（按月循环）。
 * 统计页会展示总预算执行进度，超支变红。
 */
class BudgetActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private lateinit var container: LinearLayout
    private var bookId = ""
    private var existing: List<Budget> = emptyList()

    private lateinit var totalInput: EditText
    private val catInputs = mutableListOf<Pair<String, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget)
        container = findViewById(R.id.container)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "预算设置"
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.btn_save).setOnClickListener { save() }

        lifecycleScope.launch {
            bookId = currentBookId()
            existing = db.budgetDao().getByBook(bookId)
            bind()
        }
    }

    private suspend fun bind() {
        container.removeAllViews()
        catInputs.clear()

        container.addView(TextView(this).apply {
            text = "账本总预算"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_primary))
        })
        container.addView(TextView(this).apply {
            text = "每月循环生效；填 0 表示不设预算"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(3), 0, dp(8))
        })

        val total = existing.firstOrNull { it.categoryId.isBlank() && it.month.isBlank() }?.amount ?: 0.0
        totalInput = numberInput(total)
        container.addView(totalInput, LinearLayout.LayoutParams(-1, -2))

        container.addView(TextView(this).apply {
            text = "分类预算"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(18), 0, dp(4))
        })
        container.addView(TextView(this).apply {
            text = "按分类控制支出，留空或填 0 表示不单独设限"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
        })

        val cats = db.categoryDao().getAll()
            .filter { it.type == 0 && !it.hide && it.nature != Category.NATURE_EXCLUDED }
        for (c in cats) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = "${c.icon}  ${c.name}"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val amount = existing.firstOrNull { it.categoryId == c.id && it.month.isBlank() }?.amount ?: 0.0
            val input = numberInput(amount)
            row.addView(input, LinearLayout.LayoutParams(dp(120), -2))
            catInputs.add(c.id to input)
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1))
                setBackgroundColor(getColor(R.color.divider))
            })
        }
    }

    private fun numberInput(value: Double): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        textSize = 15f
        hint = "0.00"
        setText(String.format(Locale.CHINA, "%.2f", value))
        setSelectAllOnFocus(true)
    }

    private fun save() {
        val total = totalInput.text.toString().trim().toDoubleOrNull() ?: 0.0
        val catValues = catInputs.map { (id, input) ->
            id to (input.text.toString().trim().toDoubleOrNull() ?: 0.0)
        }
        lifecycleScope.launch {
            upsert("", total)
            for ((id, v) in catValues) upsert(id, v)
            Toast.makeText(this@BudgetActivity, "预算已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun upsert(categoryId: String, amount: Double) {
        val current = existing.firstOrNull { it.categoryId == categoryId && it.month.isBlank() }
        if (amount <= 0.0) {
            if (current != null) db.budgetDao().delete(current)
            return
        }
        db.budgetDao().insert(
            Budget(
                id = current?.id ?: AppDatabase.newId(),
                bookId = bookId,
                categoryId = categoryId,
                month = "",
                amount = amount,
                createTime = current?.createTime ?: System.currentTimeMillis()
            )
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private suspend fun currentBookId(): String {
        var id = Pref.currentBookId(this)
        if (id.isBlank()) {
            db.bookDao().getAll().firstOrNull()?.let {
                id = it.id
                Pref.setCurrentBookId(this, it.id)
            }
        }
        return id
    }
}
