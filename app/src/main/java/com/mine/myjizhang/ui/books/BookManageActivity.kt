package com.mine.myjizhang.ui.books

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.DataBus
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.AccountBook
import kotlinx.coroutines.launch

/**
 * 账本管理页：切换账本、新增账本、重命名 / 删除账本。
 * 入口有两个：设置页的「账本管理」，以及账单页右上角的账本名。
 */
class BookManageActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, BookManageActivity::class.java))
        }
    }

    private val db by lazy { AppDatabase.get(this) }
    private lateinit var adapter: BookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_manage)

        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.recycler_books)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = BookAdapter(onSwitch = ::switchTo, onMore = ::showBookMenu)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btn_add_book).setOnClickListener { addBook() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val books = db.bookDao().getAll()
            var current = Pref.currentBookId(this@BookManageActivity)
            // 当前账本被删掉 / 从没设过 / 数据被恢复过，都回落到第一个账本
            if (books.isNotEmpty() && books.none { it.id == current }) {
                current = books.first().id
                Pref.setCurrentBookId(this@BookManageActivity, current)
                DataBus.bump()
            }
            val rows = books.map {
                BookRow(
                    book = it,
                    billCount = db.billDao().countByBook(it.id),
                    assetCount = db.assetDao().countByBook(it.id),
                    isCurrent = it.id == current
                )
            }
            adapter.submit(rows)
        }
    }

    // ---------- 切换 ----------

    private fun switchTo(book: AccountBook) {
        if (Pref.currentBookId(this) == book.id) {
            Toast.makeText(this, "已经是「${book.name}」了", Toast.LENGTH_SHORT).show()
            return
        }
        Pref.setCurrentBookId(this, book.id)
        DataBus.bump()
        reload()
        Toast.makeText(this, "已切换到「${book.name}」", Toast.LENGTH_SHORT).show()
    }

    // ---------- 新增 ----------

    private fun addBook() {
        val input = EditText(this).apply { hint = "例如：家庭账本 / 副业账本" }
        MaterialAlertDialogBuilder(this)
            .setTitle("新增账本")
            .setView(wrap(input))
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "账本名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val count = db.bookDao().count()
                    val book = AccountBook(AppDatabase.newId(), name, count, System.currentTimeMillis())
                    db.bookDao().insert(book)
                    // 新建后直接切过去，符合「刚建就想用」的预期
                    Pref.setCurrentBookId(this@BookManageActivity, book.id)
                    DataBus.bump()
                    reload()
                    Toast.makeText(
                        this@BookManageActivity, "已新增并切换到「$name」", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 重命名 / 删除 ----------

    private fun showBookMenu(book: AccountBook) {
        val options = arrayOf("重命名", "删除账本（连同账单）")
        MaterialAlertDialogBuilder(this)
            .setTitle(book.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rename(book)
                    1 -> confirmDelete(book)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rename(book: AccountBook) {
        val input = EditText(this).apply {
            setText(book.name)
            setSelection(book.name.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名账本")
            .setView(wrap(input))
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "账本名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    book.name = name
                    db.bookDao().update(book)
                    DataBus.bump()
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(book: AccountBook) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除账本")
            .setMessage("将删除「${book.name}」下的所有账单、账户、借贷与对账记录，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    db.billDao().deleteByBook(book.id)
                    db.adjustDao().deleteByBook(book.id)
                    db.loanDao().deleteByBook(book.id)
                    db.budgetDao().deleteByBook(book.id)
                    db.assetDao().getByBook(book.id).forEach { db.assetDao().delete(it) }
                    db.bookDao().delete(book)

                    val rest = db.bookDao().getAll()
                    if (Pref.currentBookId(this@BookManageActivity) == book.id) {
                        Pref.setCurrentBookId(
                            this@BookManageActivity, rest.firstOrNull()?.id ?: ""
                        )
                    }
                    DataBus.bump()
                    reload()
                    Toast.makeText(
                        this@BookManageActivity, "已删除「${book.name}」", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun wrap(v: android.view.View): android.view.View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v)
        }
    }
}
