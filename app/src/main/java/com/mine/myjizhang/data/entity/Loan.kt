package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 借入/借出记录：direction 0=借入（别人借给我）1=借出（我借给别人） */
@Entity(tableName = "Loan")
data class Loan(
    @PrimaryKey val id: String,
    val bookId: String,
    var direction: Int,          // 0 借入 1 借出
    var person: String,          // 对方（谁借钱给你 / 你借钱给谁）
    var amount: Double,
    var remark: String = "",
    var borrowDate: Long = 0,    // 借款日期
    var repayDate: Long = 0,     // 还款日期（0=选填未填）
    var assetId: String = "",    // 关联账户（借入累加余额 / 借出扣除余额）
    var repaid: Boolean = false, // 是否已还清
    var photoPath: String = "",  // 借据照片（可选）
    /** 由账单导入生成（余额已由账单流水计算，不再二次联动余额） */
    var imported: Boolean = false,
    /** 已归还金额（支持部分还款；总借入/总借出按 amount - repaidAmount 统计） */
    var repaidAmount: Double = 0.0,
    /** 最近一次还款用的账户（撤销还款时按它回退余额，空串表示沿用 assetId） */
    var repayAssetId: String = "",
    val createTime: Long = 0
) {
    /** 尚未归还的金额 */
    val outstanding: Double get() = (amount - repaidAmount).coerceAtLeast(0.0)
}
