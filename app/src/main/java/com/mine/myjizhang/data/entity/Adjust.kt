package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对账流水（Adjust）：记录「不由账单流水直接解释、却改变了账户余额」的每一笔调整。
 *
 * 有了它，账户余额 = 账单流水累计 + 对账调整累计，导出时把两者一起导出，
 * 再导入就能精确还原余额，不会因为删账单 / 改余额 / 改借款状态而对不上。
 */
@Entity(tableName = "Adjust")
data class Adjust(
    @PrimaryKey val id: String,
    val bookId: String,
    val assetId: String,
    /** 账户名快照（账户被删后导出仍能对上） */
    val assetName: String = "",
    /** 余额变化量，正数加、负数减 */
    val amount: Double = 0.0,
    /** 1 账户余额调整 / 2 账单删除冲正 / 3 账单修改冲正 / 4 借款还款 / 5 借款删除 / 6 账户信息变更 / 7 账单恢复 */
    val kind: Int = 1,
    /** 关联对象类型：asset / bill / loan / "" */
    val refType: String = "",
    val refId: String = "",
    val note: String = "",
    /** 调整后的账户余额快照 */
    val balanceAfter: Double = 0.0,
    val time: Long = 0
) {
    companion object {
        const val KIND_ASSET_BALANCE = 1
        const val KIND_BILL_DELETE = 2
        const val KIND_BILL_EDIT = 3
        const val KIND_LOAN_REPAY = 4
        const val KIND_LOAN_DELETE = 5
        const val KIND_ASSET_INFO = 6
        const val KIND_BILL_RESTORE = 7

        fun kindName(kind: Int): String = when (kind) {
            1 -> "余额调整"
            2 -> "账单删除冲正"
            3 -> "账单修改冲正"
            4 -> "借款冲销"
            5 -> "借款删除冲正"
            6 -> "账户信息变更"
            7 -> "账单恢复"
            else -> "调整"
        }
    }
}
