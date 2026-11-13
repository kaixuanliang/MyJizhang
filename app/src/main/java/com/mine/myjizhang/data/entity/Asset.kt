package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 资产账户 type: 0=资金/充值/理财账户 1=信贷账户(信用卡/花呗等) */
@Entity(tableName = "Asset", indices = [Index("bookId")])
data class Asset(
    @PrimaryKey var id: String,
    var bookId: String,
    var name: String,
    var type: Int = 0,
    var amount: Double = 0.0,
    var totalQuota: Double = 0.0,      // 信用额度（信用卡）
    var chargeOffDate: Int = 1,        // 出账日（几号）
    var repaymentDate: Int = 10,       // 还款日（几号）
    var inTotalAsset: Boolean = true,  // 计入总资产
    var subType: String = "",          // 资产类型名：现金/微信/储蓄卡/信用卡/花呗/公积金/股票…见 AssetTypes
    var icon: String = "",             // 图标：emoji 或银行缩写字（配合 subType 取色）
    var remark: String = "",
    var positionWeight: Int = 0,
    var createTime: Long = 0L,
    var updateTime: Long = 0L,
    /**
     * 期初余额：开始记账那一刻账户里已有的钱。
     * 余额口径 = 期初余额 + 账单流水 + 对账调整，缺了它算出来只是「净流水」。
     */
    var openingBalance: Double = 0.0,
    /** 期初日期（毫秒，0 表示未设定，取该账户第一笔账单之前） */
    var openingDate: Long = 0L
)
