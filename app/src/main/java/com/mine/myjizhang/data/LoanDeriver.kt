package com.mine.myjizhang.data

import com.mine.myjizhang.data.entity.Loan

/**
 * 从账单流水推导「借入 / 借出」记录，并把还款按 FIFO 冲销掉，得到未还净额。
 *
 * 一飞记账的债务类分类有两种用法：
 *  - 支出 + 债务-借出  → 我借钱给别人（借出）
 *  - 收入 + 债务-借出  → 对方还钱给我（冲销借出）
 *  - 收入 + 债务-借入  → 我向别人借钱（借入）
 *  - 支出 + 债务-借入  → 我还钱给别人（冲销借入）
 * 只有把还款冲销掉，「总借入 / 总借出」才是真正还没还的部分。
 */
object LoanDeriver {

    data class Event(
        val time: Long,
        val type: Int,          // 0 支出 1 收入 2 转账
        val catName: String,
        val person: String,
        val amount: Double,
        val assetId: String
    )

    fun build(bookId: String, events: List<Event>): List<Loan> {
        val now = System.currentTimeMillis()
        val loans = mutableListOf<Loan>()
        for (e in events.sortedBy { it.time }) {
            val dir = CategoryIcons.loanDirection(e.catName) ?: continue
            val isRepay = when (dir) {
                1 -> e.type == 1     // 借出：收入即收回
                else -> e.type == 0  // 借入：支出即还款
            }
            if (!isRepay) {
                loans.add(
                    Loan(
                        id = AppDatabase.newId(),
                        bookId = bookId,
                        direction = dir,
                        person = e.person,
                        amount = e.amount,
                        remark = e.catName,
                        borrowDate = e.time,
                        assetId = e.assetId,
                        repaid = false,
                        imported = true,
                        repaidAmount = 0.0,
                        createTime = now
                    )
                )
            } else {
                var left = e.amount
                val pool = loans
                    .filter { it.direction == dir && it.outstanding > 0.005 }
                    .sortedBy { it.borrowDate }
                // 只冲销「同一个人」名下的借款。
                // 以前这里写成「同人优先，其次任意人」，导致多还的钱（如借款 2 万还了 2.05 万）
                // 被顺着时间顺序摊到别人的借出上，出现「孟维一 部分已还 500」这类假数据。
                val targets = pool.filter { samePerson(it.person, e.person) }
                for (t in targets) {
                    if (left <= 0.005) break
                    val take = minOf(left, t.outstanding)
                    t.repaidAmount += take
                    left -= take
                    if (t.repaidAmount >= t.amount - 0.005) t.repaid = true
                }
                if (left > 0.005) {
                    // 这个人名下没有可冲的借款（或还款金额大于借款）：单独留痕，净额 0，不影响总额
                    loans.add(
                        Loan(
                            id = AppDatabase.newId(),
                            bookId = bookId,
                            direction = dir,
                            person = e.person,
                            amount = left,
                            remark = e.catName + "·未匹配到借款",
                            borrowDate = e.time,
                            assetId = e.assetId,
                            repaid = true,
                            imported = true,
                            repaidAmount = left,
                            createTime = now
                        )
                    )
                }
            }
        }
        loans.forEach { it.repaid = it.repaidAmount >= it.amount - 0.005 }
        return loans
    }

    /** 备注里可能写成「借给张三」「还张三」，做一次宽松比对 */
    private fun samePerson(a: String, b: String): Boolean {
        val x = clean(a)
        val y = clean(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun clean(s: String): String {
        var v = s.replace(" ", "").replace("　", "")
        for (kw in listOf("借给", "还给", "还了", "借", "还", "还款", "收款", "借款")) {
            v = v.replace(kw, "")
        }
        return v
    }
}
