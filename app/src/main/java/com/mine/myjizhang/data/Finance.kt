package com.mine.myjizhang.data

import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Category

/**
 * 财务口径：全 App 统一的「钱怎么算」。
 *
 * 之前统计页直接 sum(amount)，导致：
 *  - 退款 / 已报销没从支出里扣掉 → 支出虚高、结余率虚低；
 *  - 转账手续费没算进支出 → 账户减少的金额大于支出合计，月末对不上；
 *  - 标成「不计收支」的分类（借款、还款）仍被计入 → 重复计算。
 * 所有报表都必须经过这里，避免各页面各算一套。
 */
object Finance {

    /** 支出净额：金额 − 退款 − 已报销（垫付后报销的钱不是自己的消费） */
    fun netExpense(b: Bill): Double {
        if (b.type != 0) return 0.0
        val net = b.amount - b.refundAmount - b.reimburseAmount
        return if (net < 0) 0.0 else net
    }

    fun income(b: Bill): Double = if (b.type == 1) b.amount else 0.0

    /** 转账手续费也是支出（真实流出，只是没有分类） */
    fun fee(b: Bill): Double = b.serviceCharge

    fun transferAmount(b: Bill): Double = if (b.type == 2) b.amount else 0.0

    /** 是否计入收支统计：转账只是账户间搬家，标了「不计收支」的分类（借款/还款）也不能算 */
    fun countsInStats(b: Bill, cat: Category?): Boolean =
        b.type != 2 && cat?.nature != Category.NATURE_EXCLUDED

    fun isFixed(b: Bill, cat: Category?): Boolean =
        b.type == 0 && cat?.nature == Category.NATURE_FIXED

    fun isFlex(b: Bill, cat: Category?): Boolean =
        b.type == 0 &&
            cat?.nature != Category.NATURE_FIXED &&
            cat?.nature != Category.NATURE_EXCLUDED

    /** 结余率 = 结余 / 收入；收入为 0 时返回 0 */
    fun savingRate(income: Double, expense: Double): Double =
        if (income <= 0.0) 0.0 else (income - expense) / income

    /** 单笔账单在列表中展示的金额（支出显示净额，转账显示金额） */
    fun displayAmount(b: Bill): Double = when (b.type) {
        0 -> netExpense(b)
        else -> b.amount
    }
}
