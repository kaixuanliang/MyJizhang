package com.mine.myjizhang.excel

import android.content.Context
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.BalanceEngine
import com.mine.myjizhang.data.CategoryIcons
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.data.entity.AccountBook
import com.mine.myjizhang.data.entity.Adjust
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.ui.assets.AssetTypes
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money

/** 导入结果统计 */
data class ImportResult(
    var bills: Int = 0,
    val newBooks: MutableList<String> = mutableListOf(),
    val newAssets: MutableList<String> = mutableListOf(),
    val newCategories: MutableList<String> = mutableListOf(),
    val newAssetIds: MutableSet<String> = mutableSetOf(),
    var skipped: Int = 0,
    /** 由账单推导出的借入/借出记录条数 */
    var loans: Int = 0,
    /** 因已存在而跳过的重复账单条数（防止同一份文件导入两次导致金额翻倍） */
    var duplicates: Int = 0,
    /** 被校准到真实余额的账户数 */
    var reconciled: Int = 0
)

/**
 * xlsx 账单导入器。
 * 支持三个 sheet：支出 / 收入 / 转账，表头按名称匹配，列顺序无关。
 */
object BillImporter {

    private const val TYPE_EXPENSE = 0
    private const val TYPE_INCOME = 1
    private const val TYPE_TRANSFER = 2

    /** 去重指纹：金额换算成「分」避免浮点误差 */
    private fun billKey(b: Bill): String =
        "${b.type}|${b.time}|${Math.round(b.amount * 100)}|" +
            "${b.assetId}|${b.fromAssetId}|${b.toAssetId}|${b.remark}"

    suspend fun import(
        context: Context,
        bytes: ByteArray,
        onProgress: suspend (done: Int, total: Int, stage: String) -> Unit = { _, _, _ -> }
    ): ImportResult {
        val db = AppDatabase.get(context)
        val result = ImportResult()

        onProgress(0, 0, "正在解析文件…")
        val sheets = XlsxReader.read(bytes.inputStream())
        if (sheets.isEmpty()) throw IllegalArgumentException("文件里没有工作表")

        val bills = mutableListOf<Bill>()
        val affectedBooks = mutableSetOf<String>()

        // 去重：同一账本内「类型 + 时间 + 金额 + 账户 + 备注」完全相同，视为同一笔。
        // 防止同一份文件导入两次导致金额翻倍（这是最容易把账搞坏的操作）。
        val knownKeys = HashMap<String, MutableSet<String>>()
        val seenKeys = HashSet<String>()
        suspend fun loadKeys(bookId: String): MutableSet<String> = knownKeys.getOrPut(bookId) {
            db.billDao().getByRange(bookId, Long.MIN_VALUE, Long.MAX_VALUE)
                .map { billKey(it) }.toMutableSet()
        }

        // 总行数（用于进度条）
        val totalRows = sheets.values.sumOf { (it.size - 1).coerceAtLeast(0) }
        var doneRows = 0

        for ((sheetName, rows) in sheets) {
            if (rows.isEmpty()) continue
            val header = rows[0]
            val type = when {
                sheetName.contains("支出") -> TYPE_EXPENSE
                sheetName.contains("收入") -> TYPE_INCOME
                sheetName.contains("转账") -> TYPE_TRANSFER
                else -> continue
            }
            val map = HashMap<String, Int>()
            header.forEachIndexed { i, h -> if (h != null && h.isNotBlank()) map[h.trim()] = i }

            fun cell(row: List<String?>, key: String): String? =
                map[key]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            for (r in 1 until rows.size) {
                val row = rows[r]
                doneRows++
                if (doneRows % 20 == 0 || doneRows == totalRows) {
                    onProgress(doneRows, totalRows, "正在解析账单 $doneRows/$totalRows")
                }
                if (row.isEmpty() || row.all { it.isNullOrBlank() }) continue

                val timeKey = when (type) {
                    TYPE_EXPENSE -> "支出时间"
                    TYPE_INCOME -> "收入时间"
                    else -> "转账时间"
                }
                val amountKey = when (type) {
                    TYPE_EXPENSE -> "支出金额"
                    TYPE_INCOME -> "收入金额"
                    else -> "转账金额"
                }
                val timeStr = cell(row, timeKey)
                val amount = Money.parse(cell(row, amountKey))
                if (timeStr == null || amount == null || amount <= 0.0) {
                    result.skipped++
                    continue
                }
                val time = DateTimes.parse(timeStr)
                if (time == null) {
                    result.skipped++
                    continue
                }

                // 账本
                val bookName = cell(row, "所属账本") ?: "默认账本"
                val bookId = ensureBook(db, bookName, result)

                val now = System.currentTimeMillis()
                val bill = Bill(
                    id = AppDatabase.newId(),
                    bookId = bookId,
                    type = type,
                    amount = amount,
                    time = time,
                    remark = cell(row, "备注") ?: "",
                    recordType = 1,
                    createTime = now,
                    updateTime = now
                )

                when (type) {
                    TYPE_EXPENSE, TYPE_INCOME -> {
                        val catName = cell(row, if (type == TYPE_EXPENSE) "支出分类" else "收入分类") ?: "其他"
                        bill.categoryId = ensureCategory(db, catName, type, result)
                        val accName = cell(row, if (type == TYPE_EXPENSE) "支出账户" else "收入账户")
                        if (accName != null) bill.assetId = ensureAsset(db, accName, bookId, result)
                        bill.refundAmount = Money.parse(cell(row, "退款总额")) ?: 0.0
                        // 报销字段（支出 sheet）
                        val reim = Money.parse(cell(row, "报销金额"))
                        if (reim != null && reim > 0) {
                            bill.shouldReimburse = false
                            bill.reimburseAmount = reim
                        } else if ("是" == cell(row, "待报销") || "待报销" == cell(row, "待报销")) {
                            bill.shouldReimburse = true
                        }

                        // 借入 / 借出：分类名即语义（债务-借出 / 债务-借入 / 借款…），
                        // 记录在账单写库后统一按净额（含还款冲销）重建
                        if (CategoryIcons.loanDirection(catName) != null) {
                            affectedBooks.add(bookId)
                        }
                    }
                    else -> {
                        val from = cell(row, "转出账户")
                        val to = cell(row, "转入账户")
                        if (from != null) bill.fromAssetId = ensureAsset(db, from, bookId, result)
                        if (to != null) bill.toAssetId = ensureAsset(db, to, bookId, result)
                        bill.serviceCharge = Money.parse(cell(row, "转账手续费")) ?: 0.0
                        bill.discount = Money.parse(cell(row, "优惠")) ?: 0.0
                    }
                }
                val key = billKey(bill)
                if (!seenKeys.add(key) || loadKeys(bookId).contains(key)) {
                    result.duplicates++
                    continue
                }
                bills.add(bill)
            }
        }

        if (bills.isNotEmpty()) {
            onProgress(totalRows, totalRows, "正在写入数据库…")
            db.billDao().insertAll(bills)
        }
        result.bills = bills.size

        // 借入/借出：按账本全量账单重建，还款自动冲销，保证「总借入/总借出」为未还净额
        if (affectedBooks.isNotEmpty()) {
            onProgress(totalRows, totalRows, "正在汇总借入借出…")
            for (bookId in affectedBooks) {
                AppDatabase.rebuildImportedLoansForce(db, bookId)
                result.loans += db.loanDao().getByBook(bookId).count { it.imported }
            }
        }

        // 新建账户的余额统一走余额引擎：期初 + 流水 + 调整（期初为 0 时等价于净流水）
        if (result.newAssetIds.isNotEmpty()) {
            onProgress(totalRows, totalRows, "正在计算账户余额…")
            for (id in result.newAssetIds) BalanceEngine.recalc(db, id)
        }

        // 「账户」「对账」sheet：把账户余额与对账流水一起带回，保证导出 → 导入后余额严格一致
        onProgress(totalRows, totalRows, "正在还原账户余额…")
        val accountSheet = sheets.entries.firstOrNull {
            it.key.trim() == "账户" || (it.key.contains("账户") && !it.key.contains("对账"))
        }?.value
        val adjustSheet = sheets.entries.firstOrNull { it.key.contains("对账") }?.value
        val realBalances = LinkedHashMap<String, Double>()
        if (accountSheet != null && accountSheet.size > 1) {
            restoreAccounts(db, accountSheet, result, realBalances)
        }
        if (adjustSheet != null && adjustSheet.size > 1) {
            // 有「账户」sheet 时余额已经校准，这里只补审计记录；没有时按对账流水回放
            restoreAdjusts(db, adjustSheet, replay = accountSheet == null, result = result)
        }
        // 校准：把账户余额设成导出的真实余额，并把差额折进期初，保证
        // 「期初 + 流水 + 调整 = 余额」这条等式在导入后依然成立
        if (realBalances.isNotEmpty()) {
            onProgress(totalRows, totalRows, "正在校准账户余额…")
            for ((assetId, real) in realBalances) {
                BalanceEngine.calibrate(db, assetId, real)
                result.reconciled++
            }
        }

        // 把导入到的第一个账本设为当前账本
        if (result.bills > 0) {
            val firstBook = db.bookDao().getAll().firstOrNull { it.name == "个人账本" }
            val current = Pref.currentBookId(context)
            if (current.isBlank()) {
                val target = firstBook ?: db.bookDao().getAll().firstOrNull()
                target?.let { Pref.setCurrentBookId(context, it.id) }
            }
        }
        return result
    }

    private suspend fun currentBookId(context: Context): String {
        var id = Pref.currentBookId(context)
        if (id.isBlank()) {
            val book = AppDatabase.get(context).bookDao().getAll().firstOrNull()
            book?.let {
                id = it.id
                Pref.setCurrentBookId(context, it.id)
            }
        }
        return id
    }

    /** 按「账户」sheet 还原账户：不存在则新建，存在则校正类型 / 余额 / 额度 / 日期 */
    private suspend fun restoreAccounts(
        db: AppDatabase,
        rows: List<List<String?>>,
        result: ImportResult,
        realBalances: MutableMap<String, Double>
    ) {
        val header = rows[0]
        val map = HashMap<String, Int>()
        header.forEachIndexed { i, h -> if (h != null && h.isNotBlank()) map[h.trim()] = i }
        fun cell(row: List<String?>, key: String): String? =
            map[key]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        // 旧版导出把金额取了绝对值（负号丢失），信贷账户的欠款会变成正数。
        // 这里判断文件是否属于旧格式：所有信贷账户余额都 >= 0 且没有负数出现 → 视为旧格式，欠款还原成负数。
        val legacyUnsigned = rows.drop(1).none { r ->
            val n = cell(r, "账户名称")
            if (n == null) false else {
                val t = cell(r, "账户类型") ?: AssetTypes.match(n).name
                val b = Money.parse(cell(r, "当前余额")) ?: 0.0
                AssetTypes.isCredit(t) && b < 0
            }
        }

        for (r in 1 until rows.size) {
            val row = rows[r]
            val name = cell(row, "账户名称") ?: continue
            val bookName = cell(row, "所属账本") ?: "默认账本"
            val bookId = ensureBook(db, bookName, result)
            val typeName = cell(row, "账户类型") ?: AssetTypes.match(name).name
            val credit = AssetTypes.isCredit(typeName)
            val rawBalance = Money.parse(cell(row, "当前余额")) ?: 0.0
            val balance = if (credit && legacyUnsigned && rawBalance > 0) -rawBalance else rawBalance
            val inTotal = cell(row, "计入净资产") != "否"
            val quota = Money.parse(cell(row, "总额度")) ?: 0.0
            val chargeOff = cell(row, "出账日")?.toIntOrNull() ?: 1
            val repay = cell(row, "还款日")?.toIntOrNull() ?: 10
            val remark = cell(row, "备注") ?: ""
            val rawOpening = Money.parse(cell(row, "期初余额")) ?: 0.0
            val opening = if (credit && legacyUnsigned && rawOpening > 0) -rawOpening else rawOpening

            val list = db.assetDao().getByBook(bookId)
            val existing = list.firstOrNull { it.name == name }
            if (existing == null) {
                val created = Asset(
                    id = AppDatabase.newId(),
                    bookId = bookId,
                    name = name,
                    type = if (credit) 1 else 0,
                    amount = balance,
                    totalQuota = quota,
                    chargeOffDate = chargeOff,
                    repaymentDate = repay,
                    inTotalAsset = inTotal,
                    subType = typeName,
                    icon = AssetTypes.bankOf(name)?.short ?: AssetTypes.match(name).emoji,
                    remark = remark,
                    positionWeight = list.size,
                    createTime = System.currentTimeMillis(),
                    updateTime = System.currentTimeMillis(),
                    openingBalance = opening
                )
                db.assetDao().insert(created)
                realBalances[created.id] = balance
                result.newAssets.add(name)
            } else {
                existing.type = if (credit) 1 else 0
                existing.subType = typeName
                existing.amount = balance
                existing.totalQuota = quota
                existing.chargeOffDate = chargeOff
                existing.repaymentDate = repay
                existing.inTotalAsset = inTotal
                existing.openingBalance = opening
                if (remark.isNotBlank()) existing.remark = remark
                existing.updateTime = System.currentTimeMillis()
                db.assetDao().update(existing)
                realBalances[existing.id] = balance
            }
        }
    }

    /** 还原对账流水：replay = true 时同时把这些调整额加回账户余额（旧版导出没有「账户」sheet 的情况） */
    private suspend fun restoreAdjusts(
        db: AppDatabase,
        rows: List<List<String?>>,
        replay: Boolean,
        result: ImportResult
    ) {
        val header = rows[0]
        val map = HashMap<String, Int>()
        header.forEachIndexed { i, h -> if (h != null && h.isNotBlank()) map[h.trim()] = i }
        fun cell(row: List<String?>, key: String): String? =
            map[key]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        for (r in 1 until rows.size) {
            val row = rows[r]
            val name = cell(row, "账户名称") ?: continue
            val bookName = cell(row, "所属账本") ?: "默认账本"
            val bookId = ensureBook(db, bookName, result)
            val asset = db.assetDao().getByBook(bookId).firstOrNull { it.name == name } ?: continue
            val delta = Money.parse(cell(row, "调整金额")) ?: 0.0
            val time = cell(row, "时间")?.let { DateTimes.parse(it) } ?: System.currentTimeMillis()
            val kindName = cell(row, "调整类型") ?: ""
            val kind = when (kindName) {
                "余额调整" -> Adjust.KIND_ASSET_BALANCE
                "账单删除冲正" -> Adjust.KIND_BILL_DELETE
                "账单修改冲正" -> Adjust.KIND_BILL_EDIT
                "借款冲销" -> Adjust.KIND_LOAN_REPAY
                "借款删除冲正" -> Adjust.KIND_LOAN_DELETE
                "账户信息变更" -> Adjust.KIND_ASSET_INFO
                "账单恢复" -> Adjust.KIND_BILL_RESTORE
                else -> Adjust.KIND_ASSET_BALANCE
            }
            // 幂等：同账本 + 同账户 + 同时间 + 同金额的调整不重复写入
            val exists = db.adjustDao().getByAsset(bookId, asset.id).any {
                it.time == time && Math.abs(it.amount - delta) < 0.005
            }
            if (exists) continue
            if (replay && delta != 0.0) {
                asset.amount += delta
                asset.updateTime = System.currentTimeMillis()
                db.assetDao().update(asset)
            }
            db.adjustDao().insert(
                Adjust(
                    id = AppDatabase.newId(),
                    bookId = bookId,
                    assetId = asset.id,
                    assetName = asset.name,
                    amount = delta,
                    kind = kind,
                    refType = "",
                    refId = "",
                    note = cell(row, "说明") ?: "",
                    balanceAfter = Money.parse(cell(row, "调整后余额")) ?: asset.amount,
                    time = time
                )
            )
        }
    }

    private suspend fun ensureBook(db: AppDatabase, name: String, result: ImportResult): String {        val all = db.bookDao().getAll()
        all.firstOrNull { it.name == name }?.let { return it.id }
        val book = AccountBook(AppDatabase.newId(), name, all.size, System.currentTimeMillis())
        db.bookDao().insert(book)
        result.newBooks.add(name)
        return book.id
    }

    private suspend fun ensureCategory(db: AppDatabase, name: String, type: Int, result: ImportResult): String {
        db.categoryDao().findByName(name, type)?.let { return it.id }
        val existing = db.categoryDao().getByType(type)
        val used = existing.map { it.icon }.toSet()
        val cat = Category(
            AppDatabase.newId(), name, type,
            icon = CategoryIcons.pick(name, type, used),
            positionWeight = existing.size
        )
        db.categoryDao().insert(cat)
        result.newCategories.add(name)
        return cat.id
    }

    private suspend fun ensureAsset(db: AppDatabase, name: String, bookId: String, result: ImportResult): String {
        db.assetDao().findByName(name)?.let { return it.id }
        // 按名称推断资产类型与图标（对齐资产页展示）
        val info = com.mine.myjizhang.ui.assets.AssetTypes.match(name)
        val bank = com.mine.myjizhang.ui.assets.AssetTypes.bankOf(name)
        val isCredit = info.credit
        val existing = db.assetDao().getByBook(bookId)
        val asset = Asset(
            id = AppDatabase.newId(),
            bookId = bookId,
            name = name,
            type = if (isCredit) 1 else 0,
            amount = 0.0,
            inTotalAsset = !isCredit,
            subType = info.name,
            icon = bank?.short ?: info.emoji,
            positionWeight = existing.size,
            createTime = System.currentTimeMillis(),
            updateTime = System.currentTimeMillis()
        )
        db.assetDao().insert(asset)
        result.newAssetIds.add(asset.id)
        result.newAssets.add(name)
        return asset.id
    }
}
