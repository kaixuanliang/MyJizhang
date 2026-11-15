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
        supportActionBar?.title = "预算与分类"
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
            text = "右侧金额是每月预算上限，留空或填 0 表示不单独设限"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(4))
        })
        // 分类性质以前只能靠名称关键字在后台猜，现在这里可以直接点选
        container.addView(TextView(this).apply {
            text = "左侧标签是分类性质：统计页的「固定支出」＝ 标成固定的分类在本月实际花掉的钱，" +
                "不是这里填的预算金额"
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
            row.addView(natureTag(c))
            val amount = existing.firstOrNull { it.categoryId == c.id && it.month.isBlank() }?.amount ?: 0.0
            val input = numberInput(amount)
            row.addView(input, LinearLayout.LayoutParams(dp(110), -2).apply {
                marginStart = dp(8)
            })
            catInputs.add(c.id to input)
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1))
                setBackgroundColor(getColor(R.color.divider))
            })
        }
    }

    /** 分类性质标签：点一下弹出「固定 / 弹性 / 不计收支 / 未设定」，改完只刷新标签本身 */
    private fun natureTag(c: Category): TextView {
        val tag = TextView(this).apply {
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
        }
        renderNatureTag(tag, c.nature)
        tag.setOnClickListener { showNaturePicker(c, tag) }
        return tag
    }

    private fun renderNatureTag(tag: TextView, nature: Int) {
        val (label, bg, fg) = when (nature) {
            Category.NATURE_FIXED -> Triple("固定", "#FFF1E6", "#D97706")
            Category.NATURE_FLEX -> Triple("弹性", "#E8F1FE", "#1E6FD9")
            Category.NATURE_EXCLUDED -> Triple("不计", "#F0F1F3", "#8A8A90")
            else -> Triple("未设", "#F5F5F7", "#9A9AA0")
        }
        tag.text = label
        tag.setTextColor(android.graphics.Color.parseColor(fg))
        tag.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(android.graphics.Color.parseColor(bg))
        }
    }

    private fun showNaturePicker(c: Category, tag: TextView) {
        val options = arrayOf("固定支出", "弹性支出", "不计入收支", "未设定")
        val current = when (c.nature) {
            Category.NATURE_FIXED -> 0
            Category.NATURE_FLEX -> 1
            Category.NATURE_EXCLUDED -> 2
            else -> 3
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("「${c.name}」属于哪类支出？")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val nature = when (which) {
                    0 -> Category.NATURE_FIXED
                    1 -> Category.NATURE_FLEX
                    2 -> Category.NATURE_EXCLUDED
                    else -> Category.NATURE_NONE
                }
                dialog.dismiss()
                lifecycleScope.launch {
                    db.categoryDao().update(c.copy(nature = nature))
                    c.nature = nature
                    renderNatureTag(tag, nature)
                    if (nature == Category.NATURE_EXCLUDED) {
                        Toast.makeText(
                            this@BudgetActivity,
                            "「${c.name}」已不计入收支统计",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
