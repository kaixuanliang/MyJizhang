package com.mine.myjizhang.data

import com.mine.myjizhang.data.entity.Adjust
import com.mine.myjizhang.data.entity.Bill

/**
 * 对账中心：把「账户余额」与「账单流水」之间的差额，以及所有增删改动作，都记成对账流水。
 *
 * 核心不变式（导出 → 导入严格一致的关键）：
 *     账户余额 = Σ 账单对余额的影响 + Σ 对账调整金额
 *
 * 由此分成两类：
 *  1) 可直接由账单推导的余额变化（新增 / 修改 / 删除账单）→ 只改余额，对账流水记 amount = 0 的「备忘」，
 *     仅作审计痕迹，不影响上面的等式（因为账单本身已经带上了这笔影响，重复计入就会算重）。
 *  2) 无法由账单推导的余额变化（手工改余额、新建账户初始余额、借款还清）→ 改余额 + 记 amount ≠ 0 的调整，
 *     导出时随「对账」sheet 一起带走，导入时回放，余额才不会丢。
 */
object Reconcile {

    /** 真正的余额调整：改余额并写入一条 amount ≠ 0 的对账流水（导出/导入会回放） */
    suspend fun apply(
        db: AppDatabase,
        assetId: String,
        delta: Double,
        kind: Int,
        refType: String = "",
        refId: String = "",
        note: String = ""
    ) {
        val a = db.assetDao().getById(assetId) ?: return
        val ts = System.currentTimeMillis()
        if (delta != 0.0) {
            a.amount += delta
            a.updateTime = ts
            db.assetDao().update(a)
        }
        db.adjustDao().insert(
            Adjust(
                id = AppDatabase.newId(),
                bookId = a.bookId,
                assetId = a.id,
                assetName = a.name,
                amount = delta,
                kind = kind,
                refType = refType,
                refId = refId,
                note = note,
                balanceAfter = a.amount,
                time = ts
            )
        )
    }

    /** 只记账不做余额调整的备忘（删除/修改账单、改账户信息），保证审计有痕但等式不失衡 */
    suspend fun memo(
        db: AppDatabase,
        assetId: String,
        kind: Int,
        refType: String = "",
        refId: String = "",
        note: String = ""
    ) {
        if (assetId.isBlank()) return
        val a = db.assetDao().getById(assetId) ?: return
        db.adjustDao().insert(
            Adjust(
                id = AppDatabase.newId(),
                bookId = a.bookId,
                assetId = a.id,
                assetName = a.name,
                amount = 0.0,
                kind = kind,
                refType = refType,
                refId = refId,
                note = note,
                balanceAfter = a.amount,
                time = System.currentTimeMillis()
            )
        )
    }

    /** 纯余额变动，不写调整流水（账单本身已经能解释这笔变化） */
    suspend fun changeBalance(db: AppDatabase, assetId: String, delta: Double) {
        if (assetId.isBlank() || delta == 0.0) return
        val a = db.assetDao().getById(assetId) ?: return
        a.amount += delta
        a.updateTime = System.currentTimeMillis()
        db.assetDao().update(a)
    }

    /** 只记录账户信息变更（额度、还款日、名称等），不影响余额 */
    suspend fun logInfo(
        db: AppDatabase,
        assetId: String,
        refType: String = "asset",
        refId: String = "",
        note: String = ""
    ) = memo(db, assetId, Adjust.KIND_ASSET_INFO, refType, refId, note)

    /** 账单对余额的影响（正数=账户增加） */
    fun billEffect(bill: Bill, assetId: String): Double = when {
        bill.type == 0 && bill.assetId == assetId -> -bill.amount
        bill.type == 1 && bill.assetId == assetId -> bill.amount
        bill.type == 2 && bill.fromAssetId == assetId -> -(bill.amount + bill.serviceCharge)
        bill.type == 2 && bill.toAssetId == assetId -> bill.amount
        else -> 0.0
    }

    /** 账单涉及的所有账户 */
    fun billAssets(bill: Bill): List<String> = when (bill.type) {
        2 -> listOf(bill.fromAssetId, bill.toAssetId).filter { it.isNotBlank() }
        else -> listOf(bill.assetId).filter { it.isNotBlank() }
    }

    /** 按账单计入余额（新增账单 / 撤销删除），并留一条备忘 */
    suspend fun applyBill(db: AppDatabase, bill: Bill, kind: Int, note: String) {
        for (id in billAssets(bill)) changeBalance(db, id, billEffect(bill, id))
        val first = billAssets(bill).firstOrNull() ?: return
        memo(db, first, kind, "bill", bill.id, note)
    }

    /** 冲正账单对余额的影响（删除账单 / 修改账单前），并留一条备忘 */
    suspend fun reverseBill(db: AppDatabase, bill: Bill, kind: Int, note: String) {
        for (id in billAssets(bill)) changeBalance(db, id, -billEffect(bill, id))
        val first = billAssets(bill).firstOrNull() ?: return
        memo(db, first, kind, "bill", bill.id, note)
    }

    /** 把账户余额直接设为目标值（手工改余额），差额记一条对账流水 */
    suspend fun setBalance(
        db: AppDatabase,
        assetId: String,
        target: Double,
        refType: String = "asset",
        refId: String = "",
        note: String = "手工修改账户余额"
    ) {
        val a = db.assetDao().getById(assetId) ?: return
        apply(db, assetId, target - a.amount, Adjust.KIND_ASSET_BALANCE, refType, refId, note)
    }
}
