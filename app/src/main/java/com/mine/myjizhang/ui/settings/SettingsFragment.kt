package com.mine.myjizhang.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
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
import com.mine.myjizhang.data.DataBus
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.AccountBook
import com.mine.myjizhang.excel.BillExporter
import com.mine.myjizhang.excel.BillImporter
import com.mine.myjizhang.excel.XlsxWriter
import com.mine.myjizhang.ui.TabRefreshable
import com.mine.myjizhang.sync.GitHubSyncer
import com.mine.myjizhang.sync.SyncScheduler
import com.mine.myjizhang.sync.SyncConfig
import com.mine.myjizhang.sync.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class SettingsFragment : Fragment(), TabRefreshable {

    private val db by lazy { AppDatabase.get(requireContext()) }

    // 导出：让用户选择保存位置
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> if (uri != null) doExport(uri) }

    // 导入：选择 xlsx 文件
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doImport(uri) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.row_book).setOnClickListener { openBookManage() }
        view.findViewById<View>(R.id.row_clear_book).setOnClickListener { confirmClearBook() }
        view.findViewById<View>(R.id.row_export).setOnClickListener { startExport() }
        view.findViewById<View>(R.id.row_import).setOnClickListener {
            importLauncher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
            ))
        }
        view.findViewById<View>(R.id.row_sync).setOnClickListener { showSyncDialog() }
        view.findViewById<View>(R.id.row_adjust).setOnClickListener {
            startActivity(
                android.content.Intent(
                    requireContext(),
                    com.mine.myjizhang.ui.reconcile.ReconcileLogActivity::class.java
                )
            )
        }
        view.findViewById<View>(R.id.row_about).setOnClickListener { showAbout() }
    }

    override fun onResume() {
        super.onResume()
        updateCurrentBookLabel()
        updateSyncLabel()
    }

    override fun refreshTab() {
        updateCurrentBookLabel()
        updateSyncLabel()
    }

    private fun updateSyncLabel() {
        lifecycleScope.launch {
            val cfg = Pref.syncConfig(requireContext())
            val auto = if (cfg.autoSyncDays > 0) " · ${Pref.autoSyncLabel(cfg.autoSyncDays)}自动同步" else ""
            view?.findViewById<TextView>(R.id.text_sync_state)?.text = when {
                cfg.repo.isBlank() || cfg.token.isBlank() -> "未配置，点击设置 GitHub 仓库"
                cfg.lastSyncTime > 0 ->
                    "${cfg.repo} · 上次${cfg.lastSyncAction} " +
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(java.util.Date(cfg.lastSyncTime)) + auto
                else -> "${cfg.repo} · 尚未同步$auto"
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

    // ---------- 账本管理 / 清空账本 ----------

    /** 账本管理是独立页面：切换、新增、重命名、删除都在那里 */
    private fun openBookManage() {
        com.mine.myjizhang.ui.books.BookManageActivity.start(requireContext())
    }

    /** 清空当前账本：保留账本与账户本身，清掉全部流水并把余额归零 */
    private fun confirmClearBook() {
        lifecycleScope.launch {
            val books = db.bookDao().getAll()
            val currentId = Pref.currentBookId(requireContext())
            val book = books.firstOrNull { it.id == currentId } ?: books.firstOrNull()
            if (book == null) {
                Toast.makeText(requireContext(), "还没有账本", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val billCount = db.billDao().countByBook(book.id)
            val assetCount = db.assetDao().countByBook(book.id)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空账本")
                .setMessage(
                    "是否清空当前账本——「${book.name}」？\n\n" +
                        "将删除该账本下 $billCount 笔账单、$assetCount 个账户的全部记录" +
                        "（账单 / 对账流水 / 借贷 / 预算），账户余额归零；" +
                        "账本和账户本身会保留。\n\n此操作无法撤销，建议先导出或同步备份。"
                )
                .setPositiveButton("确认清空") { _, _ -> doClearBook(book.id, book.name) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doClearBook(bookId: String, bookName: String) {
        val (dialog, _, label) = showProgress("正在清空账本")
        progressDialog = dialog
        label.text = "正在删除「$bookName」的记录…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.billDao().deleteByBook(bookId)
                    db.adjustDao().deleteByBook(bookId)
                    db.loanDao().deleteByBook(bookId)
                    db.budgetDao().deleteByBook(bookId)
                    db.assetDao().resetBalanceByBook(bookId)
                }
                dismissProgress()
                DataBus.bump()
                updateCurrentBookLabel()
                Toast.makeText(
                    requireContext(), "已清空「$bookName」", Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                dismissProgress()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("清空失败")
                    .setMessage(e.message ?: "未知错误")
                    .setPositiveButton("好的", null)
                    .show()
            }
        }
    }

    // ---------- 账单导出 ----------

    /** 点击即导出：全部账本、全部时间，不再询问范围 */
    private fun startExport() {
        val name = "记账本_全部时间_" +
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(java.util.Date()) + ".xlsx"
        exportLauncher.launch(name)
    }

    private fun doExport(uri: Uri) {
        val (dialog, bar, label) = showProgress("正在导出账单")
        progressDialog = dialog
        bar.isIndeterminate = false
        bar.progress = 5
        label.text = "正在收集账单…"
        lifecycleScope.launch {
            try {
                val sheets = withContext(Dispatchers.IO) {
                    val data = BillExporter.export(db, null, null, null)
                    withContext(Dispatchers.Main) {
                        bar.progress = 55
                        label.text = "正在生成 Excel（${data.size} 个工作表）…"
                    }
                    val bytes = ByteArrayOutputStream().also { XlsxWriter.write(it, data) }.toByteArray()
                    withContext(Dispatchers.Main) {
                        bar.progress = 85
                        label.text = "正在写入文件（${bytes.size / 1024} KB）…"
                    }
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    }
                    withContext(Dispatchers.Main) { bar.progress = 100 }
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
                // 导入会新建账本 / 账户，账单页必须重新查库（hide 的 Fragment 不会自己刷新）
                DataBus.bump()
                updateCurrentBookLabel()
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

    /** 自动同步可选间隔：文案与天数一一对应 */
    private val autoLabels = arrayOf("关闭", "每天", "每星期", "每月")
    private val autoDays = intArrayOf(0, 1, 7, 30)

    private fun autoDesc(days: Int): String = when (days) {
        1 -> "每天自动上传一次"
        7 -> "每星期自动上传一次"
        30 -> "每月自动上传一次"
        else -> "关闭（只能手动点「同步」）"
    }

    /** 弹系统电池优化白名单，华为/EMUI 上能明显提高后台任务存活率 */
    private fun requestBackgroundPermission() {
        val ctx = requireContext()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            })
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                )
                Toast.makeText(ctx, "请到「耗电管理」里允许后台运行", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(ctx, "请到系统设置的电池管理中允许本应用后台运行", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSyncDialog() {
        val ctx = requireContext()
        var cfg = Pref.syncConfig(ctx)
        val view = layoutInflater.inflate(R.layout.dialog_sync, null)

        val sectionConfig = view.findViewById<View>(R.id.section_config)
        val sectionReady = view.findViewById<View>(R.id.section_ready)
        val inputRepo = view.findViewById<EditText>(R.id.input_repo)
        val inputBranch = view.findViewById<EditText>(R.id.input_branch)
        val inputToken = view.findViewById<EditText>(R.id.input_token)
        val inputPassword = view.findViewById<EditText>(R.id.input_password)
        val switchEncrypt = view.findViewById<MaterialSwitch>(R.id.switch_encrypt)
        val textConfigHint = view.findViewById<TextView>(R.id.text_config_hint)
        val textRepo = view.findViewById<TextView>(R.id.text_repo)
        val textRepoMeta = view.findViewById<TextView>(R.id.text_repo_meta)
        val textLastSync = view.findViewById<TextView>(R.id.text_last_sync)

        val textAutoValue = view.findViewById<TextView>(R.id.text_auto_config_value)
        val textAutoHint = view.findViewById<TextView>(R.id.text_auto_hint)
        val btnBattery = view.findViewById<View>(R.id.btn_battery)
        val textAutoReady = view.findViewById<TextView>(R.id.text_auto_ready)
        val textAutoReadyState = view.findViewById<TextView>(R.id.text_auto_ready_state)
        val btnBatteryReady = view.findViewById<View>(R.id.btn_battery_ready)

        // 用已保存的配置填充表单
        inputRepo.setText(cfg.repo)
        inputBranch.setText(cfg.branch.ifBlank { "main" })
        inputToken.setText(cfg.token)
        inputPassword.setText(cfg.password)
        switchEncrypt.isChecked = cfg.password.isNotBlank()
        inputPassword.visibility = if (switchEncrypt.isChecked) View.VISIBLE else View.GONE
        switchEncrypt.setOnCheckedChangeListener { _, checked ->
            inputPassword.visibility = if (checked) View.VISIBLE else View.GONE
        }

        fun readForm(): SyncConfig = cfg.copy(
            repo = inputRepo.text.toString().trim()
                .removePrefix("https://github.com/").removeSuffix(".git").trimEnd('/'),
            branch = inputBranch.text.toString().trim().ifBlank { "main" },
            token = inputToken.text.toString().trim(),
            password = if (switchEncrypt.isChecked) inputPassword.text.toString() else ""
        )

        fun isReady(c: SyncConfig) = c.repo.isNotBlank() && c.token.isNotBlank()

        val fmtTime = { t: Long ->
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(java.util.Date(t))
        }

        fun render(c: SyncConfig) {
            val ready = isReady(c)
            sectionConfig.visibility = if (ready) View.GONE else View.VISIBLE
            sectionReady.visibility = if (ready) View.VISIBLE else View.GONE

            // 自动同步：配置区
            val on = c.autoSyncDays > 0
            textAutoValue.text = autoDesc(c.autoSyncDays)
            textAutoHint.visibility = if (on) View.VISIBLE else View.GONE
            textAutoHint.text = "间隔已保存后，由系统在后台择机上传；省电策略可能让实际时间有几个小时偏差，长期不用 App 时建议点下面按钮放开后台限制。"
            btnBattery.visibility = if (on) View.VISIBLE else View.GONE

            if (!ready) return

            textRepo.text = c.repo
            textRepoMeta.text = "分支 ${c.branch.ifBlank { "main" }} · " +
                if (c.password.isNotBlank()) "已加密" else "未加密"
            textLastSync.text = if (c.lastSyncTime > 0) {
                "上次${c.lastSyncAction}：${fmtTime(c.lastSyncTime)}"
            } else {
                "还没有同步过，点「同步」把本地账本推送到仓库"
            }

            // 自动同步：已配置区
            textAutoReady.text = "自动同步：" + Pref.autoSyncLabel(c.autoSyncDays)
            val autoTime = Pref.autoSyncLastTime(ctx)
            val autoMsg = Pref.autoSyncLastMsg(ctx)
            textAutoReadyState.text = when {
                !on -> "点这里开启定时备份"
                autoTime <= 0L -> "已开启，等待系统首次后台执行"
                autoMsg.startsWith("失败") -> "上次自动同步失败（${fmtTime(autoTime)}）：${autoMsg.removePrefix("失败：")}"
                else -> "上次自动同步：${fmtTime(autoTime)}"
            }
            btnBatteryReady.visibility = if (on) View.VISIBLE else View.GONE
        }

        /** 选间隔：配置区只改内存，已配置区立刻落盘并重建任务 */
        fun chooseInterval(persistNow: Boolean) {
            val sel = autoDays.indexOf(cfg.autoSyncDays).coerceAtLeast(0)
            MaterialAlertDialogBuilder(ctx)
                .setTitle("自动同步间隔")
                .setSingleChoiceItems(autoLabels, sel) { d, which ->
                    cfg = cfg.copy(autoSyncDays = autoDays[which])
                    if (persistNow) {
                        Pref.saveSyncConfig(ctx, cfg)
                        SyncScheduler.apply(ctx, replace = true)
                        updateSyncLabel()
                    }
                    render(cfg)
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("GitHub 同步")
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        view.findViewById<View>(R.id.row_auto_config).setOnClickListener { chooseInterval(false) }
        view.findViewById<View>(R.id.row_auto_ready).setOnClickListener { chooseInterval(true) }
        btnBattery.setOnClickListener { requestBackgroundPermission() }
        btnBatteryReady.setOnClickListener { requestBackgroundPermission() }

        // 保存配置
        view.findViewById<View>(R.id.btn_save).setOnClickListener {
            val c = readForm()
            if (c.repo.isBlank()) {
                textConfigHint.text = "✗ 请填写仓库（格式 owner/repo）"
                return@setOnClickListener
            }
            if (c.token.isBlank()) {
                textConfigHint.text = "✗ 请填写 Personal Access Token"
                return@setOnClickListener
            }
            Pref.saveSyncConfig(ctx, c)
            cfg = c
            SyncScheduler.apply(ctx, replace = true)
            textConfigHint.text = ""
            render(c)
            updateSyncLabel()
            Toast.makeText(
                ctx,
                if (c.autoSyncDays > 0) "配置已保存，" + Pref.autoSyncLabel(c.autoSyncDays) + "自动同步" else "配置已保存",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 测试连接
        view.findViewById<View>(R.id.btn_test).setOnClickListener {
            val c = readForm()
            lifecycleScope.launch {
                textConfigHint.text = "测试中…"
                when (val r = GitHubSyncer.testConnection(c)) {
                    is SyncResult.Success -> textConfigHint.text = "✓ " + r.message
                    is SyncResult.Failure -> textConfigHint.text = "✗ " + r.message
                }
            }
        }

        // 已配置状态下，点击「同步」直接上传
        view.findViewById<View>(R.id.btn_sync).setOnClickListener {
            val c = readForm().let {
                Pref.saveSyncConfig(ctx, it)
                it
            }
            cfg = c
            SyncScheduler.apply(ctx, replace = true)
            val (progress, _, _) = showProgress("正在同步到 GitHub")
            progressDialog = progress
            lifecycleScope.launch {
                when (val r = GitHubSyncer.upload(c, db)) {
                    is SyncResult.Success -> {
                        cfg = Pref.syncConfig(ctx).copy(
                            lastSyncTime = System.currentTimeMillis(),
                            lastSyncAction = "同步"
                        )
                        Pref.saveSyncConfig(ctx, cfg)
                        dismissProgress()
                        render(cfg)
                        updateSyncLabel()
                        Toast.makeText(ctx, "✓ " + r.message, Toast.LENGTH_LONG).show()
                    }
                    is SyncResult.Failure -> {
                        dismissProgress()
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("同步失败")
                            .setMessage(r.message)
                            .setPositiveButton("好的", null)
                            .show()
                    }
                }
            }
        }

        // 从仓库恢复
        view.findViewById<View>(R.id.btn_restore).setOnClickListener {
            val c = readForm()
            MaterialAlertDialogBuilder(ctx)
                .setTitle("恢复备份")
                .setMessage("将用仓库里的备份覆盖本地全部数据（账本/分类/账户/账单）。确定继续吗？")
                .setPositiveButton("恢复") { _, _ ->
                    val (progress, _, _) = showProgress("正在恢复备份")
                    progressDialog = progress
                    lifecycleScope.launch {
                        when (val r = GitHubSyncer.downloadAndRestore(c, db)) {
                            is SyncResult.Success -> {
                                cfg = Pref.syncConfig(ctx).copy(
                                    lastSyncTime = System.currentTimeMillis(),
                                    lastSyncAction = "恢复"
                                )
                                Pref.saveSyncConfig(ctx, cfg)
                                dismissProgress()
                                DataBus.bump()
                                render(cfg)
                                updateCurrentBookLabel()
                                updateSyncLabel()
                                Toast.makeText(ctx, "✓ " + r.message, Toast.LENGTH_LONG).show()
                            }
                            is SyncResult.Failure -> {
                                dismissProgress()
                                MaterialAlertDialogBuilder(ctx)
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

        // 修改配置 → 回到表单
        view.findViewById<View>(R.id.btn_edit_config).setOnClickListener {
            inputRepo.setText(cfg.repo)
            inputBranch.setText(cfg.branch.ifBlank { "main" })
            inputToken.setText(cfg.token)
            inputPassword.setText(cfg.password)
            switchEncrypt.isChecked = cfg.password.isNotBlank()
            inputPassword.visibility = if (switchEncrypt.isChecked) View.VISIBLE else View.GONE
            sectionReady.visibility = View.GONE
            sectionConfig.visibility = View.VISIBLE
        }

        render(cfg)
        dialog.show()
    }

    // ---------- 关于 ----------

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
                    "· 支持导入 / 导出 xlsx 账单（含账户与对账流水，可精确还原余额）\n" +
                    "· 支持通过 GitHub 私有仓库加密备份与恢复\n\n" +
                    "提示：删除 App 或清除数据会丢失所有记录，请定期使用「同步」或「账单导出」备份。"
            )
            .setPositiveButton("好的", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
