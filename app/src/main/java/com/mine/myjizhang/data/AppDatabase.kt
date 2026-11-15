package com.mine.myjizhang.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mine.myjizhang.data.dao.AdjustDao
import com.mine.myjizhang.data.dao.AssetDao
import com.mine.myjizhang.data.dao.BillDao
import com.mine.myjizhang.data.dao.BookDao
import com.mine.myjizhang.data.dao.BudgetDao
import com.mine.myjizhang.data.dao.CategoryDao
import com.mine.myjizhang.data.dao.LoanDao
import com.mine.myjizhang.data.entity.AccountBook
import com.mine.myjizhang.data.entity.Adjust
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Budget
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.data.entity.Loan
import java.util.UUID

@Database(
    entities = [
        AccountBook::class, Category::class, Asset::class, Bill::class,
        Loan::class, Adjust::class, Budget::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun categoryDao(): CategoryDao
    abstract fun assetDao(): AssetDao
    abstract fun billDao(): BillDao
    abstract fun loanDao(): LoanDao
    abstract fun adjustDao(): AdjustDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 -> v2：Asset 增加 subType / icon 列（保留已有数据） */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Asset ADD COLUMN subType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE Asset ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 -> v3：新增 Loan（借入/借出）表 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Loan` (" +
                        "`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `direction` INTEGER NOT NULL, " +
                        "`person` TEXT NOT NULL, `amount` REAL NOT NULL, `remark` TEXT NOT NULL, " +
                        "`borrowDate` INTEGER NOT NULL, `repayDate` INTEGER NOT NULL, " +
                        "`assetId` TEXT NOT NULL, `repaid` INTEGER NOT NULL, " +
                        "`photoPath` TEXT NOT NULL, `createTime` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v3 -> v4：Loan 增加 imported 列（区分由账单导入生成的记录） */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Loan ADD COLUMN imported INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 -> v5：新增对账流水 Adjust 表；Loan 增加已还金额；清掉旧的导入借贷记录（重建时会按净额重新生成） */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Loan` ADD COLUMN `repaidAmount` REAL NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Adjust` (" +
                        "`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `assetId` TEXT NOT NULL, " +
                        "`assetName` TEXT NOT NULL, `amount` REAL NOT NULL, `kind` INTEGER NOT NULL, " +
                        "`refType` TEXT NOT NULL, `refId` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`balanceAfter` REAL NOT NULL, `time` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                // 旧版导入的借贷记录没有冲销逻辑，删掉让启动时按新规则重建
                db.execSQL("DELETE FROM `Loan` WHERE `imported` = 1")
            }
        }

        /**
         * v5 -> v6：账户增加「期初余额」，分类增加「层级 + 支出性质」，新增 Budget 预算表。
         * 老数据的 openingBalance 默认为 0，此时 余额 = 流水 + 调整，行为与升级前一致；
         * 用户可通过「导入迁移向导」填入真实余额来反推期初，把账彻底对平。
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Asset` ADD COLUMN `openingBalance` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `Asset` ADD COLUMN `openingDate` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `Category` ADD COLUMN `parentId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `Category` ADD COLUMN `nature` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Budget` (" +
                        "`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, " +
                        "`month` TEXT NOT NULL, `amount` REAL NOT NULL, `createTime` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                // 索引必须与实体 @Entity(indices=...) 完全一致，否则 Room 迁移校验会抛
                // IllegalStateException("Migration didn't properly handle: Budget") 导致启动即崩。
                // 同时兜底补齐更早版本可能缺失的索引（老库若在声明索引前创建过，这里一并补上）。
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Budget_bookId` ON `Budget`(`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Asset_bookId` ON `Asset`(`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Bill_bookId` ON `Bill`(`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Bill_time` ON `Bill`(`time`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Bill_categoryId` ON `Bill`(`categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Bill_assetId` ON `Bill`(`assetId`)")
            }
        }

        /**
         * v6 -> v7：Loan 增加 repayAssetId（记录还款打到了哪个账户）。
         * 部分还款要能撤销，就必须知道当时动的是哪个账户的余额，否则回退会打到错的地方。
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Loan` ADD COLUMN `repayAssetId` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jizhang.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                    )
                    .build()
                    .also { INSTANCE = it }
            }

        /** 首次启动的种子数据：默认账本 + 常用分类 */
        suspend fun seed(context: Context) {
            val db = get(context)
            val now = System.currentTimeMillis()
            if (db.bookDao().count() == 0) {
                db.bookDao().insert(AccountBook(newId(), "个人账本", 0, now))
            }
            if (db.categoryDao().getAll().isEmpty()) {
                val cats = mutableListOf<Category>()
                for (type in 0..1) {
                    CategoryIcons.defaults(type).forEachIndexed { i, (n, ic) ->
                        cats.add(Category(newId(), n, type, ic, i, false))
                    }
                }
                db.categoryDao().insertAll(cats)
            }

            // 历史数据修复：图标与分类名不符 / 同类内重复的，重新分配正确图标
            repairCategoryIcons(db)

            // 合并历史版本的重复分类（餐饮→三餐、日用品→日用…）
            mergeDuplicateCategories(db)

            // 分类顺序按新版的「高频优先」重排（只调 positionWeight，不动名称和账单）
            reorderCategories(db)

            // 分类支出性质：老分类 nature 未设定时按名称推断固定 / 弹性 / 不计收支
            repairCategoryNatures(db)

            // 老数据规范化：账户类型写「其他」或为空时，按账户名推断（股票/基金/虚拟币 → 投资理财账户）
            normalizeAssets(db)

            // 老数据补全 / 修复借入、借出记录。
            // v2 算法之前会把「多还的钱」跨人摊到别人的借出上，升级后需强制重建一次修正。
            if (Pref.loanAlgoVersion(context) < 2) {
                for (book in db.bookDao().getAll()) rebuildImportedLoansForce(db, book.id)
                Pref.setLoanAlgoVersion(context, 2)
            } else {
                rebuildImportedLoans(db)
            }
        }

        /**
         * 从已有账单里推导借入/借出记录（幂等：某账本已有导入记录就跳过）。
         * 还款会按 FIFO 冲销对应借款，因此「总借入 / 总借出」是未还净额。
         */
        private suspend fun rebuildImportedLoans(db: AppDatabase) {
            for (book in db.bookDao().getAll()) rebuildImportedLoans(db, book.id)
        }

        /** 重建指定账本的导入借贷记录（导入完成后也会调用，保证净额正确） */
        suspend fun rebuildImportedLoans(db: AppDatabase, bookId: String) {
            val existing = db.loanDao().getByBook(bookId)
            if (existing.any { it.imported }) return
            val bills = db.billDao().getByRange(bookId, 0L, Long.MAX_VALUE)
            if (bills.isEmpty()) return
            val cats = db.categoryDao().getAll().associateBy { it.id }
            val events = bills.mapNotNull { b ->
                val catName = cats[b.categoryId]?.name ?: return@mapNotNull null
                if (CategoryIcons.loanDirection(catName) == null) return@mapNotNull null
                LoanDeriver.Event(
                    time = b.time,
                    type = b.type,
                    catName = catName,
                    person = b.remark.ifBlank { catName },
                    amount = b.amount,
                    assetId = b.assetId
                )
            }
            if (events.isEmpty()) return
            for (loan in LoanDeriver.build(bookId, events)) db.loanDao().insert(loan)
        }

        /** 重建指定账本的导入借贷记录（先清后建，用于重新导入 / 数据修复） */
        suspend fun rebuildImportedLoansForce(db: AppDatabase, bookId: String) {
            // 先记住手工记过的还款：这类还款是真的动了账户余额（有对账流水为证），
            // 重建时如果跟着旧记录一起被删掉，就会出现「账户已经加过钱、借款却显示未还」。
            val manual = LinkedHashMap<String, Double>()
            val repoint = mutableListOf<Pair<List<Adjust>, Loan>>()
            for (l in db.loanDao().getByBook(bookId).filter { it.imported }) {
                val rows = db.adjustDao().getByRef("loan", l.id, Adjust.KIND_LOAN_REPAY)
                if (rows.isEmpty()) continue
                val sum = Math.abs(rows.sumOf { it.amount })
                if (sum <= 0.004) continue
                manual[loanKey(l)] = (manual[loanKey(l)] ?: 0.0) + sum
                repoint += rows to l
            }
            db.loanDao().deleteImportedByBook(bookId)
            rebuildImportedLoans(db, bookId)
            if (manual.isEmpty()) return

            // 把手工还款按「方向 + 对方 + 金额 + 借款日」匹配回新建的记录
            val fresh = db.loanDao().getByBook(bookId).filter { it.imported }
            for (loan in fresh) {
                val add = manual[loanKey(loan)] ?: continue
                if (add <= 0.004) continue
                loan.repaidAmount = (loan.repaidAmount + add).coerceAtMost(loan.amount)
                loan.repaid = loan.repaidAmount >= loan.amount - 0.005
                db.loanDao().update(loan)
                manual.remove(loanKey(loan))
                // 对账流水的 refId 指向新记录，之后「撤销还款」还能找到它
                for ((rows, old) in repoint) {
                    if (loanKey(old) != loanKey(loan)) continue
                    for (r in rows) db.adjustDao().insert(r.copy(refId = loan.id))
                }
            }
        }

        /** 借款匹配键：同一个人、同方向、同金额、同借款日，基本可以唯一确定一笔 */
        private fun loanKey(l: Loan): String =
            listOf(l.direction, l.person.replace(" ", ""), Math.round(l.amount * 100), l.borrowDate).joinToString("|")

        /** 重建指定账本的导入借贷记录（幂等：某账本已有导入记录就跳过）。 */

        /** 老账户类型规范化：subType 为空 / 其他 / 与名称语义不符时按名称重新推断 */
        private suspend fun normalizeAssets(db: AppDatabase) {
            for (book in db.bookDao().getAll()) {
                for (a in db.assetDao().getByBook(book.id)) {
                    val fixed = com.mine.myjizhang.ui.assets.AssetTypes
                        .normalizeSubType(a.subType, a.type, a.name)
                    if (fixed != a.subType) {
                        a.subType = fixed
                        a.updateTime = System.currentTimeMillis()
                        db.assetDao().update(a)
                    }
                }
            }
        }

        /** 固定支出关键词：房租、水电、订阅这类每月必须发生、不太能压缩的支出 */
        private val FIXED_WORDS = listOf(
            "房租", "房贷", "物业", "水费", "电费", "燃气", "宽带", "话费", "月租",
            "保险", "学费", "订阅", "会员费", "停车", "车位", "赡养", "托育"
        )

        /** 不计入收支的关键词：还款、借贷、内部资金转移，计入会重复计算 */
        private val EXCLUDED_WORDS = listOf(
            "转账", "还款", "借款", "借入", "借出", "债务", "内部划转", "信用卡还"
        )

        private fun natureOf(name: String, type: Int): Int {
            val n = name.replace(" ", "")
            if (EXCLUDED_WORDS.any { n.contains(it) }) return Category.NATURE_EXCLUDED
            if (type != 0) return Category.NATURE_NONE
            if (FIXED_WORDS.any { n.contains(it) }) return Category.NATURE_FIXED
            return Category.NATURE_FLEX
        }

        private suspend fun repairCategoryNatures(db: AppDatabase) {
            for (c in db.categoryDao().getAll()) {
                if (c.nature != Category.NATURE_NONE) continue
                val n = natureOf(c.name, c.type)
                if (n != Category.NATURE_NONE) {
                    c.nature = n
                    db.categoryDao().update(c)
                }
            }
        }

        private suspend fun repairCategoryIcons(db: AppDatabase) {            for (type in 0..1) {
                val cats = db.categoryDao().getByType(type).sortedBy { it.positionWeight }
                val used = mutableSetOf<String>()
                for (c in cats) {
                    val exact = CategoryIcons.iconOf(c.name, type)
                    val icon = when {
                        exact != null && exact == c.icon -> exact          // 已经正确
                        exact != null && exact !in used -> exact            // 纠正为语义正确的图标
                        c.icon.isNotBlank() && c.icon !in used -> c.icon    // 保留不重复的自定义图标
                        else -> CategoryIcons.pick(c.name, type, used)       // 兜底，保证唯一
                    }
                    if (icon != c.icon) {
                        c.icon = icon
                        db.categoryDao().update(c)
                    }
                    used.add(icon)
                }
            }
        }

        /**
         * 合并重复分类。两种情形：
         * ① 保留项存在 → 把账单 / 预算改挂过去，删掉多余的分类；
         * ② 只有老旧写法（比如只有「餐饮」）→ 直接改名成保留项，账单不用动。
         * 幂等：第二次启动找不到旧名字就什么也不做。
         */
        private suspend fun mergeDuplicateCategories(db: AppDatabase) {
            val books = db.bookDao().getAll()
            for ((from, to) in CategoryIcons.mergeAlias) {
                val old = db.categoryDao().findByName(from, 0) ?: continue
                val keep = db.categoryDao().findByName(to, 0)
                if (keep == null) {
                    // 只有旧写法：改名即可，bill.categoryId 指向的是 id，不受影响
                    old.name = to
                    old.icon = CategoryIcons.iconOf(to, 0) ?: old.icon
                    db.categoryDao().update(old)
                    continue
                }
                db.billDao().remapCategory(old.id, keep.id)
                // 预算可能冲突：保留项已有预算就把旧分类那条删掉，否则改挂
                for (book in books) {
                    val budgets = db.budgetDao().getByBook(book.id)
                    val oldBudget = budgets.filter { it.categoryId == old.id }
                    if (oldBudget.isEmpty()) continue
                    val keepHas = budgets.any { it.categoryId == keep.id }
                    if (keepHas) {
                        oldBudget.forEach { db.budgetDao().delete(it) }
                    } else {
                        db.budgetDao().remapCategory(old.id, keep.id)
                    }
                }
                db.categoryDao().delete(old)
            }
        }

        /**
         * 按新版默认顺序重排分类：命中的按 order 的索引排，未命中的（用户自建、导入的）
         * 统一排到后面并保持它们彼此原有的相对顺序。
         */
        private suspend fun reorderCategories(db: AppDatabase) {
            for (type in 0..1) {
                val order = if (type == 1) CategoryIcons.incomeOrder else CategoryIcons.expenseOrder
                val idx = order.withIndex().associate { it.value to it.index }
                val cats = db.categoryDao().getAll()
                    .filter { it.type == type }
                    .sortedBy { it.positionWeight }
                val known = cats.filter { idx.containsKey(it.name) }.sortedBy { idx[it.name] }
                val unknown = cats.filter { !idx.containsKey(it.name) }
                (known + unknown).forEachIndexed { i, c ->
                    if (c.positionWeight != i) {
                        c.positionWeight = i
                        db.categoryDao().update(c)
                    }
                }
            }
        }

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}