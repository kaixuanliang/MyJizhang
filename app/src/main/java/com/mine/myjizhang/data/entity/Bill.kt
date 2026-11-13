package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账单 type: 0=支出 1=收入 2=转账
 * recordType: 0=手动 1=导入
 */
@Entity(
    tableName = "Bill",
    indices = [Index("bookId"), Index("time"), Index("categoryId"), Index("assetId")]
)
data class Bill(
    @PrimaryKey var id: String,
    var bookId: String,
    var type: Int,
    var amount: Double,
    var categoryId: String = "",
    var assetId: String = "",        // 支出/收入账户
    var fromAssetId: String = "",    // 转出账户
    var toAssetId: String = "",      // 转入账户
    var serviceCharge: Double = 0.0, // 转账手续费
    var discount: Double = 0.0,      // 转账优惠
    var remark: String = "",
    var time: Long,
    var refundAmount: Double = 0.0,  // 退款总额
    var shouldReimburse: Boolean = false, // 待报销
    var reimburseAmount: Double = 0.0,    // 已报销金额
    var recordType: Int = 0,
    var createTime: Long = 0L,
    var updateTime: Long = 0L
)
