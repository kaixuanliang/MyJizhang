package com.mine.myjizhang.excel

import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.ui.assets.AssetTypes
import com.mine.myjizhang.util.Money

/**
 * 导出为与一飞记账完全一致的 xlsx 结构（支出/收入/转账 三个 sheet），
 * 保证两个 App 之间可互相迁移。
 */
object BillExporter {

    private val EXPENSE_HEADER = listOf(
        "所属账本", "支出时间", "支出分类", "支出金额", "支出账户", "备注", "账单图片",
        "记录时间", "记录方式", "退款总额", "待报销", "报销金额", "报销入账账户", "报销时间"
    )
    private val INCOME_HEADER = listOf(
        "所属账本", "收入时间", "收入分类", "收入金额", "收入账户", "备注", "账单图片",
        "记录时间", "记录方式", "退款总额"
    )
    private val TRANSFER_HEADER = listOf(
        "所属账本", "转账时间", "转账金额", "转出账户", "转入账户", "转账手续费", "优惠",
        "备注", "账单图片", "记录时间", "记录方式"
    )

    /** 账户快照：导入时用它精确还原每个账户的余额与属性 */
    private val ACCOUNT_HEADER = listOf(
        "所属账本", "账户名称", "账户类型", "所属分组", "当前余额", "计入净资产",
        "总额度", "出账日", "还款日", "备注", "期初余额"
    )

    /** 对账流水：无法由账单推导的余额调整（改余额 / 借款还清等） */
    private val ADJUST_HEADER = listOf(
        "所属账本", "时间", "账户名称", "调整金额", "调整类型", "说明", "调整后余额"
    )

    /** bookId = null 导出全部账本；start/end = null 导出全部时间 */
    suspend fun export(db: AppDatabase, bookId: String?, start: Long?, end: Long?): LinkedHashMap<String, List<List<String?>>> {
        val books = db.bookDao().getAll().associateBy { it.id }
        val categories = db.categoryDao().getAll().associateBy { it.id }
        val assets = HashMap<String, com.mine.myjizhang.data.entity.Asset>()
        books.keys.forEach { bookAssets ->
            db.assetDao().getByBook(bookAssets).forEach { assets[it.id] = it }
        }

        val allBills: List<Bill> = if (bookId != null) {
            val s = start ?: 0L
            val e = end ?: Long.MAX_VALUE
            db.billDao().getByRange(bookId, s, e)
        } else {
            var list = db.billDao().getAll()
            if (start != null && end != null) list = list.filter { it.time in start until end }
            list
        }
        val bills = allBills.sortedBy { it.time }

        val expense = mutableListOf<List<String?>>(EXPENSE_HEADER)
        val income = mutableListOf<List<String?>>(INCOME_HEADER)
        val transfer = mutableListOf<List<String?>>(TRANSFER_HEADER)

        fun bookName(b: Bill) = books[b.bookId]?.name ?: ""
        fun catName(b: Bill) = categories[b.categoryId]?.name ?: ""
        fun assetName(id: String) = if (id.isBlank()) "" else assets[id]?.name ?: "已删除账户"

        for (b in bills) {
            val time = XlsxWriter.fmtTime(b.time)
            val method = if (b.recordType == 1) "导入" else "手动记账"
            when (b.type) {
                0 -> expense.add(listOf(
                    bookName(b), time, catName(b), Money.fmt(b.amount), assetName(b.assetId),
                    b.remark, "", time, method,
                    if (b.refundAmount > 0) Money.fmt(b.refundAmount) else null,
                    if (b.shouldReimburse) "是" else null,
                    if (b.reimburseAmount > 0) Money.fmt(b.reimburseAmount) else null,
                    "", ""
                ))
                1 -> income.add(listOf(
                    bookName(b), time, catName(b), Money.fmt(b.amount), assetName(b.assetId),
                    b.remark, "", time, method,
                    if (b.refundAmount > 0) Money.fmt(b.refundAmount) else null
                ))
                2 -> transfer.add(listOf(
                    bookName(b), time, Money.fmt(b.amount), assetName(b.fromAssetId),
                    assetName(b.toAssetId),
                    if (b.serviceCharge > 0) Money.fmt(b.serviceCharge) else null,
                    if (b.discount > 0) Money.fmt(b.discount) else null,
                    b.remark, "", time, method
                ))
            }
        }

        val sheets = LinkedHashMap<String, List<List<String?>>>()
        sheets["支出"] = expense
        sheets["收入"] = income
        sheets["转账"] = transfer

        // 「账户」「对账」：保证导出 → 导入后账户余额严格一致
        val accountRows = mutableListOf<List<String?>>(ACCOUNT_HEADER)
        val adjustRows = mutableListOf<List<String?>>(ADJUST_HEADER)
        val targetBooks = if (bookId != null) listOf(bookId) else books.keys.toList()
        for (bid in targetBooks) {
            val bName = books[bid]?.name ?: ""
            db.assetDao().getByBook(bid).forEach { a ->
                accountRows.add(
                    listOf(
                        bName, a.name, a.subType, AssetTypes.groupOf(a.subType, a.type, a.name),
                        Money.fmt(a.amount), if (a.inTotalAsset) "是" else "否",
                        if (a.totalQuota > 0) Money.fmt(a.totalQuota) else null,
                        if (a.chargeOffDate in 1..31) "${a.chargeOffDate}" else null,
                        if (a.repaymentDate in 1..31) "${a.repaymentDate}" else null,
                        a.remark,
                        Money.fmt(a.openingBalance)
                    )
                )
            }
            db.adjustDao().getByBook(bid).forEach { ad ->
                adjustRows.add(
                    listOf(
                        bName, XlsxWriter.fmtTime(ad.time), ad.assetName,
                        Money.fmt(ad.amount), com.mine.myjizhang.data.entity.Adjust.kindName(ad.kind),
                        ad.note, Money.fmt(ad.balanceAfter)
                    )
                )
            }
        }
        sheets["账户"] = accountRows
        sheets["对账"] = adjustRows
        return sheets
    }
}
