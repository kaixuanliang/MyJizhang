package com.mine.myjizhang.ui.settings

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.mine.myjizhang.R
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.AccountBook
import com.mine.myjizhang.excel.BillExporter
import com.mine.myjizhang.excel.BillImporter
import com.mine.myjizhang.excel.XlsxWriter
import com.mine.myjizhang.sync.GitHubSyncer
import com.mine.myjizhang.sync.SyncConfig
import com.mine.myjizhang.sync.SyncResult
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SettingsFragment : Fragment() {

    private val db by lazy { AppDatabase.get(requireContext()) }

    private var exportBookId: String? = null   // null=全部账本
    private var exportRange: Int = 0           // 0全部 1本月 2今年

    // 导出：让用户选择保存位置
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> if (uri != null) doExport(uri) }

    // 导入：选择 xlsx 文件
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doImport(uri) }

    // 导入迁移向导：同一个选文件入口，但走四步迁移流程
    private val wizardLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            com.mine.myjizhang.ui.importwizard.ImportWizardActivity.start(requireContext(), uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.row_book).setOnClickListener { manageBooks() }
        view.findViewById<View>(R.id.row_book_edit).setOnClickListener { renameOrDeleteBook() }
        view.findViewById<View>(R.id.row_export).setOnClickListener { showExportDialog() }
        view.findViewById<View>(R.id.row_import).setOnClickListener {
            importLauncher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
            ))
        }
        view.findViewById<View>(R.id.row_import_wizard).setOnClickListener {
            wizardLauncher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
            ))
        }
        view.findViewById<View>(R.id.row_sync).setOnClickListener { showSyncDialog() }
        view.findViewById<View>(R.id.row_adjust).setOnClickListener { showAdjustLog() }
        view.findViewById<View>(R.id.row_about).setOnClickListener { showAbout() }
    }

    override fun onResume() {
        super.onResume()
        updateCurrentBookLabel()
        updateSyncLabel()
    }

    private fun updateSyncLabel() {
        lifecycleScope.launch {
            val cfg = Pref.syncConfig(requireContext())
            view?.findViewById<TextView>(R.id.text_sync_state)?.text = if (cfg.lastSyncTime > 0) {
                "上次${cfg.lastSyncAction}：" +
                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(java.util.Date(cfg.lastSyncTime))
            } else {
                "GitHub 仓库加密备份"
            }
        }
    }

    // ---------- 进度对话框 ----------

    private var progressDialog: AlertDialog? = null

    /** 显示带横向进度条的对话框，返回 (对话框, 进度条, 文本) */
    private fun showProgress(title: String): Triple<AlertDialog, ProgressBar, TextView> {
        val ctx = requireContext()
        val container = FrameLayout(ctx).apply { setPadding(dp(28), dp(20), dp(28), dp(8)) }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        val label = TextView(ctx).apply {
            text = "准备中…"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar)
                addView(label)
            }
        )
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .show()
        return Triple(dialog, bar, label)
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun updateCurrentBookLabel() {
        lifecycleScope.launch {
            val id = Pref.currentBookId(requireContext())
            val book = db.bookDao().getAll().firstOrNull { it.id == id }
                ?: db.bookDao().getAll().firstOrNull()
            view?.findViewById<TextView>(R.id.text_current_book)?.text =
                book?.name ?: "未设置"
        }
    }

    // ---------- 账本管理 ----------

    private fun manageBooks() {
        lifecycleScope.launch {
            val books = db.bookDao().getAll()
            val current = Pref.currentBookId(requireContext())
            val names = books.map { (if (it.id == current) "✅ " else "　") + it.name }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("账本管理（点选切换，长按改名/删除）")
                .setItems(names) { _, i ->
                    Pref.setCurrentBookId(requireContext(), books[i].id)
                    Toast.makeText(requireContext(), "已切换到「${books[i].name}」", Toast.LENGTH_SHORT).show()
                    updateCurrentBookLabel()
                }
                .setPositiveButton("新增账本") { _, _ -> addBook() }
                .setNeutralButton("关闭", null)
                .show()

            // 用第二个对话框处理改名/删除（长按在 AlertDialog 里不好拦截，改用输入式管理）
        }
    }

    private fun addBook() {
        val input = EditText(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("新增账本")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val count = db.bookDao().count()
                    db.bookDao().insert(AccountBook(AppDatabase.newId(), name, count, System.currentTimeMillis()))
                    Toast.makeText(requireContext(), "已新增「$name」", Toast.LENGTH_SHORT).show()
                    updateCurrentBookLabel()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameOrDeleteBook() {
        lifecycleScope.launch {
            val books = db.bookDao().getAll()
            if (books.isEmpty()) return@launch
            val names = books.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择要修改的账本")
                .setItems(names) { _, i -> editBook(books[i]) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun editBook(book: AccountBook) {
        val options = arrayOf("重命名", "删除账本（连同账单）")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(book.name)
            .setItems(options) { _, i ->
                when (i) {
                    0 -> {
                        val input = EditText(requireContext())
                        input.setText(book.name)
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("重命名账本")
                            .setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    lifecycleScope.launch {
                                        book.name = name
                                        db.bookDao().update(book)
                                        updateCurrentBookLabel()
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("删除账本")
                            .setMessage("将同时删除「${book.name}」下的所有账单，无法恢复。确定吗？")
                            .setPositiveButton("删除") { _, _ ->
                                lifecycleScope.launch {
                                    db.billDao().deleteByBook(book.id)
                                    db.assetDao().getByBook(book.id).forEach { db.assetDao().delete(it) }
                                    db.bookDao().delete(book)
                                    if (Pref.currentBookId(requireContext()) == book.id) {
                                        Pref.setCurrentBookId(
                                            requireContext(),
                                            db.bookDao().getAll().firstOrNull()?.id ?: ""
                                        )
                                    }
                                    updateCurrentBookLabel()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 账单导出 ----------

    private fun showExportDialog() {
        val scopeOptions = arrayOf("当前账本", "全部账本")
        val rangeOptions = arrayOf("全部时间", "本月", "今年")
        var scopeSel = 0
        var rangeSel = exportRange

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(TextView(requireContext()).apply { text = "导出范围" })
            addView(TextView(requireContext()).apply {
                id = View.generateViewId()
                text = scopeOptions[scopeSel]
                setOnClickListener { _ -> }
            })
        }
        // 简化实现：两次单选对话框
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择导出范围")
            .setSingleChoiceItems(scopeOptions, 0) { _, i -> scopeSel = i }
            .setPositiveButton("下一步") { _, _ ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("选择时间范围")
                    .setSingleChoiceItems(rangeOptions, rangeSel) { _, i -> rangeSel = i }
                    .setPositiveButton("导出") { _, _ ->
                        exportBookId = if (scopeSel == 0) Pref.currentBookId(requireContext()) else null
                        exportRange = rangeSel
                        val name = "记账本_" +
                            (if (scopeSel == 0) "当前账本" else "全部账本") + "_" +
                            when (rangeSel) {
                                1 -> SimpleDateFormat("yyyy-MM", Locale.CHINA).format(java.util.Date()) + "_账单.xlsx"
                                2 -> Calendar.getInstance().get(Calendar.YEAR).toString() + "_账单.xlsx"
                                else -> "所有时间账单.xlsx"
                            }
                        exportLauncher.launch(name)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport(uri: Uri) {
        val (dialog, bar, label) = showProgress("正在导出账单")
        progressDialog = dialog
        lifecycleScope.launch {
            try {
                val sheets = withContext(Dispatchers.IO) {
                    val startEnd = when (exportRange) {
                        1 -> {
                            val cal = Calendar.getInstance()
                            Pair(
                                DateTimes.monthStart(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1),
                                DateTimes.monthEnd(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                            )
                        }
                        2 -> {
                            val y = Calendar.getInstance().get(Calendar.YEAR)
                            Pair(DateTimes.monthStart(y, 1), DateTimes.monthEnd(y, 12))
                        }
                        else -> null
                    }
                    withContext(Dispatchers.Main) {
                        label.text = "正在收集账单…"
                        bar.isIndeterminate = true
                    }
                    val data = BillExporter.export(db, exportBookId, startEnd?.first, startEnd?.second)
                    withContext(Dispatchers.Main) {
                        label.text = "正在生成 Excel…"
                    }
                    val bytes = ByteArrayOutputStream().also { XlsxWriter.write(it, data) }.toByteArray()
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    }
                    data
                }
                dismissProgress()
                val total = sheets.values.sumOf { s -> (s.size - 1).coerceAtLeast(0) }
                Toast.makeText(requireContext(), "导出成功：$total 笔", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                dismissProgress()
                Toast.makeText(requireContext(), "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 账单导入 ----------

    private fun doImport(uri: Uri) {
        val (dialog, bar, label) = showProgress("正在导入账单")
        progressDialog = dialog
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: throw IllegalArgumentException("无法读取文件")
                    BillImporter.import(requireContext(), bytes) { done, total, stage ->
                        withContext(Dispatchers.Main) {
                            if (total > 0) {
                                bar.isIndeterminate = false
                                bar.max = total
                                bar.progress = done
                                label.text = "$stage（${done * 100 / total}%）"
                            } else {
                                bar.isIndeterminate = true
                                label.text = stage
                            }
                        }
                    }
                }
                dismissProgress()
                val msg = StringBuilder("导入 ${result.bills} 笔账单")
                if (result.skipped > 0) msg.append("，跳过 ${result.skipped} 条无效行")
                if (result.newBooks.isNotEmpty()) msg.append("\n新增账本：${result.newBooks.joinToString("、")}")
                if (result.newAssets.isNotEmpty()) msg.append("\n新增账户：${result.newAssets.joinToString("、")}")
                if (result.newCategories.isNotEmpty()) msg.append("\n新增分类：${result.newCategories.joinToString("、")}")
                if (result.loans > 0) msg.append("\n识别借入/借出：${result.loans} 笔")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("导入完成")
                    .setMessage(msg.toString())
                    .setPositiveButton("好的", null)
                    .show()
            } catch (e: Exception) {
                dismissProgress()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("导入失败")
                    .setMessage(e.message ?: "未知错误")
                    .setPositiveButton("好的", null)
                    .show()
            }
        }
    }

    // ---------- GitHub 同步 ----------

    private fun showSyncDialog() {
        val cfg = Pref.syncConfig(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_sync, null)
        val inputRepo = view.findViewById<EditText>(R.id.input_repo)
        val inputBranch = view.findViewById<EditText>(R.id.input_branch)
        val inputToken = view.findViewById<EditText>(R.id.input_token)
        val inputPassword = view.findViewById<EditText>(R.id.input_password)
        val switchEncrypt = view.findViewById<MaterialSwitch>(R.id.switch_encrypt)
        val textHint = view.findViewById<TextView>(R.id.text_sync_hint)

        inputRepo.setText(cfg.repo)
        inputBranch.setText(cfg.branch.ifBlank { "main" })
        inputToken.setText(cfg.token)
        switchEncrypt.isChecked = cfg.password.isNotBlank()
        inputPassword.visibility = if (cfg.password.isNotBlank()) View.VISIBLE else View.GONE
        inputPassword.setText(cfg.password)
        switchEncrypt.setOnCheckedChangeListener { _, checked ->
            inputPassword.visibility = if (checked) View.VISIBLE else View.GONE
        }
        if (cfg.lastSyncTime > 0) {
            textHint.text = "上次${cfg.lastSyncAction}：" +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date(cfg.lastSyncTime))
        }

        fun collect(): SyncConfig {
            val c = cfg.copy(
                repo = inputRepo.text.toString().trim().removePrefix("https://github.com/").trimEnd('/'),
                branch = inputBranch.text.toString().trim().ifBlank { "main" },
                token = inputToken.text.toString().trim(),
                password = if (switchEncrypt.isChecked) inputPassword.text.toString() else ""
            )
            Pref.saveSyncConfig(requireContext(), c)
            return c
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("同步到 GitHub 仓库")
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        view.findViewById<View>(R.id.btn_test).setOnClickListener {
            val c = collect()
            lifecycleScope.launch {
                textHint.text = "测试中…"
                when (val r = GitHubSyncer.testConnection(c)) {
                    is SyncResult.Success -> textHint.text = r.message
                    is SyncResult.Failure -> textHint.text = "✗ " + r.message
                }
            }
        }
        view.findViewById<View>(R.id.btn_upload).setOnClickListener {
            val c = collect()
            val (dialog, bar, label) = showProgress("正在上传备份")
            progressDialog = dialog
            lifecycleScope.launch {
                when (val r = GitHubSyncer.upload(c, db)) {
                    is SyncResult.Success -> {
                        c.lastSyncTime = System.currentTimeMillis()
                        c.lastSyncAction = "上传"
                        Pref.saveSyncConfig(requireContext(), c)
                        dismissProgress()
                        Toast.makeText(requireContext(), "✓ " + r.message, Toast.LENGTH_LONG).show()
                        updateSyncLabel()
                    }
                    is SyncResult.Failure -> {
                        dismissProgress()
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("上传失败")
                            .setMessage(r.message)
                            .setPositiveButton("好的", null)
                            .show()
                    }
                }
            }
        }
        view.findViewById<View>(R.id.btn_restore).setOnClickListener {
            val c = collect()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("恢复备份")
                .setMessage("将用仓库里的备份覆盖本地全部数据（账本/分类/账户/账单）。确定继续吗？")
                .setPositiveButton("恢复") { _, _ ->
                    val (dialog, bar, label) = showProgress("正在恢复备份")
                    progressDialog = dialog
                    lifecycleScope.launch {
                        when (val r = GitHubSyncer.downloadAndRestore(c, db)) {
                            is SyncResult.Success -> {
                                c.lastSyncTime = System.currentTimeMillis()
                                c.lastSyncAction = "恢复"
                                Pref.saveSyncConfig(requireContext(), c)
                                dismissProgress()
                                Toast.makeText(requireContext(), "✓ " + r.message, Toast.LENGTH_LONG).show()
                                updateCurrentBookLabel()
                                updateSyncLabel()
                            }
                            is SyncResult.Failure -> {
                                dismissProgress()
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("恢复失败")
                                    .setMessage(r.message)
                                    .setPositiveButton("好的", null)
                                    .show()
                            }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        dialog.show()
    }

    // ---------- 关于 ----------

    private fun showAdjustLog() {
        lifecycleScope.launch {
            val bookId = Pref.currentBookId(requireContext())
            val list = if (bookId.isNotBlank()) db.adjustDao().getByBook(bookId) else db.adjustDao().getAll()
            if (list.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("对账记录")
                    .setMessage(
                        "还没有对账记录。\n\n" +
                            "以下操作会自动留下对账流水：\n" +
                            "· 修改账户余额 / 账户信息\n" +
                            "· 删除或修改账单\n" +
                            "· 借入借出标记已还\n\n" +
                            "导出 xlsx 时会一并导出「账户」「对账」两个 sheet，再导入就能精确还原余额。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val text = list.take(300).joinToString("\n") { a ->
                val amt = when {
                    Math.abs(a.amount) < 0.005 -> "备忘"
                    a.amount > 0 -> "+" + Money.fmt(a.amount)
                    else -> "-" + Money.fmt(Math.abs(a.amount))
                }
                val tail = if (a.note.isNotBlank()) "  ${a.note}" else ""
                "${fmt.format(java.util.Date(a.time))}  ${a.assetName}  $amt  ${com.mine.myjizhang.data.entity.Adjust.kindName(a.kind)}$tail"
            }
            val shown = if (list.size > 300) "\n\n（仅显示最近 300 条，共 ${list.size} 条）" else ""
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("对账记录（${list.size} 条）")
                .setMessage(text + shown)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun showAbout() {
        val ver = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("关于")
            .setMessage(
                "记账本 v$ver\n\n" +
                    "本地记账应用，数据存储在设备本地，无注册登录。\n\n" +
                    "· 支持导入/导出一飞记账格式的 xlsx 账单\n" +
                    "· 支持通过 GitHub 私有仓库加密备份与恢复\n\n" +
                    "提示：删除 App 或清除数据会丢失所有记录，请定期使用「同步」或「账单导出」备份。"
            )
            .setPositiveButton("好的", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
