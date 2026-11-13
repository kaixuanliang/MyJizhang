package com.mine.myjizhang.excel

import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.util.DateTimes
import com.mine.myjizhang.util.Money

/** 预扫描出来的账户（真实余额由用户在向导里填） */
data class PreviewAccount(
    val name: String,
    /** 账单流水累计（正=净流入，负=净流出） */
    val flow: Double,
    val count: Int,
    var realBalance: Double = flow
) {
    /** 反推的期初余额 */
    val opening: Double get() = realBalance - flow
}

/** 预扫描出来的分类（带笔数、金额、可编辑的性质与大类） */
data class PreviewCategory(
    val name: String,
    val type: Int,
    var count: Int,
    var amount: Double,
    var nature: Int = Category.NATURE_FLEX,
    var parent: String = ""
)

/** 导入前的体检报告 */
data class ImportPreview(
    val billCount: Int,
    val skipped: Int,
    val minTime: Long,
    val maxTime: Long,
    val books: List<String>,
    val accounts: MutableList<PreviewAccount>,
    val categories: MutableList<PreviewCategory>
) {
    val days: Int
        get() = if (minTime <= 0 || maxTime <= 0) 0
        else ((maxTime - minTime) / 86400000L).toInt() + 1

    val expenseTotal: Double
        get() = categories.filter { it.type == 0 }.sumOf { it.amount }

    val incomeTotal: Double
        get() = categories.filter { it.type == 1 }.sumOf { it.amount }
}

/**
 * 只读预扫描：把 xlsx 读一遍算出「账单数 / 时间跨度 / 账户流水 / 分类明细」，
 * 不写数据库。向导的第 1 步和第 2 步都基于它，用户确认后才真正落库。
 */
object ImportScanner {

    private const val TYPE_EXPENSE = 0
    private const val TYPE_INCOME = 1
    private const val TYPE_TRANSFER = 2

    fun scan(bytes: ByteArray): ImportPreview {
        val sheets = XlsxReader.read(bytes.inputStream())
        var billCount = 0
        var skipped = 0
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        val books = LinkedHashSet<String>()
        val accountFlow = LinkedHashMap<String, Double>()
        val accountCount = LinkedHashMap<String, Int>()
        val categories = LinkedHashMap<String, PreviewCategory>()

        for ((sheetName, rows) in sheets) {
            if (rows.size < 2) continue
            val type = when {
                sheetName.contains("支出") -> TYPE_EXPENSE
                sheetName.contains("收入") -> TYPE_INCOME
                sheetName.contains("转账") -> TYPE_TRANSFER
                else -> continue
            }
            val header = rows[0]
            val map = HashMap<String, Int>()
            header.forEachIndexed { i, h -> if (h != null && h.isNotBlank()) map[h.trim()] = i }
            fun cell(row: List<String?>, key: String): String? =
                map[key]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

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

            for (r in 1 until rows.size) {
                val row = rows[r]
                if (row.isEmpty() || row.all { it.isNullOrBlank() }) continue
                val timeStr = cell(row, timeKey)
                val amount = Money.parse(cell(row, amountKey))
                if (timeStr == null || amount == null || amount <= 0.0) {
                    skipped++
                    continue
                }
                val time = DateTimes.parse(timeStr)
                if (time == null) {
                    skipped++
                    continue
                }
                billCount++
                if (time < minTime) minTime = time
                if (time > maxTime) maxTime = time
                cell(row, "所属账本")?.let { books.add(it) }

                fun addFlow(name: String?, delta: Double) {
                    if (name.isNullOrBlank()) return
                    accountFlow[name] = (accountFlow[name] ?: 0.0) + delta
                    accountCount[name] = (accountCount[name] ?: 0) + 1
                }

                when (type) {
                    TYPE_EXPENSE, TYPE_INCOME -> {
                        val delta = if (type == TYPE_EXPENSE) -amount else amount
                        addFlow(
                            cell(row, if (type == TYPE_EXPENSE) "支出账户" else "收入账户"),
                            delta
                        )
                        val catName = cell(row, if (type == TYPE_EXPENSE) "支出分类" else "收入分类")
                            ?: "其他"
                        val key = "$type|$catName"
                        val c = categories[key]
                        if (c == null) {
                            categories[key] = PreviewCategory(
                                name = catName,
                                type = type,
                                count = 1,
                                amount = amount
                            )
                        } else {
                            c.count++
                            c.amount += amount
                        }
                    }
                    else -> {
                        val fee = Money.parse(cell(row, "转账手续费")) ?: 0.0
                        addFlow(cell(row, "转出账户"), -(amount + fee))
                        addFlow(cell(row, "转入账户"), amount)
                    }
                }
            }
        }

        val accounts = accountFlow.map { (name, flow) ->
            PreviewAccount(name = name, flow = flow, count = accountCount[name] ?: 0)
        }.sortedByDescending { Math.abs(it.flow) }.toMutableList()

        val cats = categories.values
            .sortedWith(compareBy({ it.type }, { -it.amount }))
            .toMutableList()

        return ImportPreview(
            billCount = billCount,
            skipped = skipped,
            minTime = if (minTime == Long.MAX_VALUE) 0L else minTime,
            maxTime = if (maxTime == Long.MIN_VALUE) 0L else maxTime,
            books = books.toList(),
            accounts = accounts,
            categories = cats
        )
    }
}
