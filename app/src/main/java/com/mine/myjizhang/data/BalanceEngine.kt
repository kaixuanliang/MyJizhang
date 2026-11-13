package com.mine.myjizhang.data

import com.mine.myjizhang.data.entity.Bill

/**
 * 余额引擎：全 App 唯一的余额口径。
 *
 *     账户余额 = 期初余额 + Σ 账单流水影响 + Σ 对账调整
 *
 * 之前各处（导入器、记账页、删除、借款联动）各写一套累加逻辑，口径容易漂；
 * 现在统一从这里算，任何改动账户余额的动作最后都调 [recalc] 一次即可。
 */
object BalanceEngine {

    /** 单笔账单对指定账户的余额影响：正数=账户增加，负数=账户减少 */
    fun impactOf(bill: Bill, assetId: String): Double {
        if (assetId.isBlank()) return 0.0
        return when (bill.type) {
            0 -> if (bill.assetId == assetId) -bill.amount else 0.0
            1 -> if (bill.assetId == assetId) bill.amount else 0.0
            else -> {
                var d = 0.0
                if (bill.fromAssetId == assetId) d -= (bill.amount + bill.serviceCharge)
                if (bill.toAssetId == assetId) d += bill.amount
                d
            }
        }
    }

    fun flowOf(bills: List<Bill>, assetId: String): Double {
        var sum = 0.0
        for (b in bills) sum += impactOf(b, assetId)
        return sum
    }

    suspend fun adjustOf(db: AppDatabase, bookId: String, assetId: String): Double =
        db.adjustDao().getByAsset(bookId, assetId).sumOf { it.amount }

    /** 按「期初 + 流水 + 调整」重算并写回，返回新余额 */
    suspend fun recalc(db: AppDatabase, assetId: String): Double {
        val a = db.assetDao().getById(assetId) ?: return 0.0
        val bills = db.billDao().getByRange(a.bookId, Long.MIN_VALUE, Long.MAX_VALUE)
        val balance = a.openingBalance +
            flowOf(bills, assetId) +
            adjustOf(db, a.bookId, assetId)
        if (Math.abs(balance - a.amount) > 0.004) {
            a.amount = balance
            a.updateTime = System.currentTimeMillis()
            db.assetDao().update(a)
        }
        return balance
    }

    suspend fun recalcBook(db: AppDatabase, bookId: String) {
        for (a in db.assetDao().getByBook(bookId)) recalc(db, a.id)
    }

    /**
     * 反推期初余额：已知「今天的真实余额」，倒推记账起点时账户里有多少钱。
     * 迁移向导与「对账」入口都用它把账一次性对平。
     */
    suspend fun deriveOpening(db: AppDatabase, assetId: String, realBalance: Double): Double {
        val a = db.assetDao().getById(assetId) ?: return 0.0
        val bills = db.billDao().getByRange(a.bookId, Long.MIN_VALUE, Long.MAX_VALUE)
        return realBalance - flowOf(bills, assetId) - adjustOf(db, a.bookId, assetId)
    }

    /** 直接把账户校准到真实余额（差额进期初，不产生对账噪声） */
    suspend fun calibrate(db: AppDatabase, assetId: String, realBalance: Double): Double {
        val a = db.assetDao().getById(assetId) ?: return 0.0
        a.openingBalance = deriveOpening(db, assetId, realBalance)
        a.amount = realBalance
        a.updateTime = System.currentTimeMillis()
        db.assetDao().update(a)
        return realBalance
    }
}
