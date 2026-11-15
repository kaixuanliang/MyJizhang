package com.mine.myjizhang.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mine.myjizhang.data.entity.AccountBook
import com.mine.myjizhang.data.entity.Adjust
import com.mine.myjizhang.data.entity.Asset
import com.mine.myjizhang.data.entity.Bill
import com.mine.myjizhang.data.entity.Budget
import com.mine.myjizhang.data.entity.Category
import com.mine.myjizhang.data.entity.Loan
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM AccountBook ORDER BY positionWeight")
    fun observeAll(): Flow<List<AccountBook>>

    @Query("SELECT * FROM AccountBook ORDER BY positionWeight")
    suspend fun getAll(): List<AccountBook>

    @Query("SELECT * FROM AccountBook WHERE id = :id")
    suspend fun getById(id: String): AccountBook?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: AccountBook)

    @Update
    suspend fun update(book: AccountBook)

    @Delete
    suspend fun delete(book: AccountBook)

    @Query("SELECT COUNT(*) FROM AccountBook")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM Category WHERE hide = 0 ORDER BY positionWeight")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM Category WHERE type = :type AND hide = 0 ORDER BY positionWeight")
    suspend fun getByType(type: Int): List<Category>

    @Query("SELECT * FROM Category")
    suspend fun getAll(): List<Category>

    @Query("SELECT * FROM Category WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByName(name: String, type: Int): Category?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM Asset WHERE bookId = :bookId ORDER BY positionWeight")
    fun observeByBook(bookId: String): Flow<List<Asset>>

    @Query("SELECT * FROM Asset WHERE bookId = :bookId ORDER BY positionWeight")
    suspend fun getByBook(bookId: String): List<Asset>

    @Query("SELECT COUNT(*) FROM Asset WHERE bookId = :bookId")
    suspend fun countByBook(bookId: String): Int

    @Query("UPDATE Asset SET amount = 0, openingBalance = 0 WHERE bookId = :bookId")
    suspend fun resetBalanceByBook(bookId: String)

    @Query("SELECT * FROM Asset WHERE id = :id")
    suspend fun getById(id: String): Asset?

    @Query("SELECT * FROM Asset WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Asset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: Asset)

    @Update
    suspend fun update(asset: Asset)

    @Delete
    suspend fun delete(asset: Asset)
}

@Dao
interface BillDao {
    @Query(
        "SELECT * FROM Bill WHERE bookId = :bookId AND time >= :start AND time < :end " +
            "ORDER BY time DESC"
    )
    fun observeByMonth(bookId: String, start: Long, end: Long): Flow<List<Bill>>

    @Query(
        "SELECT * FROM Bill WHERE bookId = :bookId AND time >= :start AND time < :end " +
            "ORDER BY time DESC"
    )
    suspend fun getByRange(bookId: String, start: Long, end: Long): List<Bill>

    @Query(
        "SELECT * FROM Bill WHERE bookId = :bookId AND time >= :start AND time < :end " +
            "ORDER BY time DESC"
    )
    fun observeByRange(bookId: String, start: Long, end: Long): Flow<List<Bill>>

    @Query("SELECT * FROM Bill WHERE id = :id")
    suspend fun getById(id: String): Bill?

    @Query(
        "SELECT * FROM Bill WHERE bookId = :bookId AND " +
            "(assetId = :assetId OR fromAssetId = :assetId OR toAssetId = :assetId) " +
            "ORDER BY time DESC"
    )
    fun observeByAsset(bookId: String, assetId: String): Flow<List<Bill>>

    @Query("SELECT * FROM Bill ORDER BY time DESC")
    suspend fun getAll(): List<Bill>

    @Query("SELECT COUNT(*) FROM Bill")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM Bill WHERE bookId = :bookId")
    suspend fun countByBook(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bill: Bill)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Bill>)

    @Update
    suspend fun update(bill: Bill)

    @Delete
    suspend fun delete(bill: Bill)

    @Query("DELETE FROM Bill")
    suspend fun deleteAll()

    @Query("DELETE FROM Bill WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    /** 分类合并用：把挂在旧分类上的账单改挂到保留分类 */
    @Query("UPDATE Bill SET categoryId = :to WHERE categoryId = :from")
    suspend fun remapCategory(from: String, to: String)
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM Loan WHERE bookId = :bookId ORDER BY createTime DESC")
    fun observeByBook(bookId: String): Flow<List<Loan>>

    @Query("SELECT * FROM Loan WHERE bookId = :bookId ORDER BY createTime DESC")
    suspend fun getByBook(bookId: String): List<Loan>

    @Query("SELECT * FROM Loan WHERE id = :id")
    suspend fun getById(id: String): Loan?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(loan: Loan)

    @Update
    suspend fun update(loan: Loan)

    @Delete
    suspend fun delete(loan: Loan)

    @Query("DELETE FROM Loan WHERE bookId = :bookId AND imported = 1")
    suspend fun deleteImportedByBook(bookId: String)

    @Query("DELETE FROM Loan WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface AdjustDao {
    @Query("SELECT * FROM Adjust WHERE bookId = :bookId ORDER BY time DESC")
    fun observeByBook(bookId: String): Flow<List<Adjust>>

    @Query("SELECT * FROM Adjust WHERE bookId = :bookId ORDER BY time DESC")
    suspend fun getByBook(bookId: String): List<Adjust>

    @Query("SELECT * FROM Adjust WHERE bookId = :bookId AND assetId = :assetId ORDER BY time DESC")
    suspend fun getByAsset(bookId: String, assetId: String): List<Adjust>

    /** 某个业务对象（如一笔借款）写下的全部余额调整，撤销时按它精确回退 */
    @Query("SELECT * FROM Adjust WHERE refType = :refType AND refId = :refId AND kind = :kind ORDER BY time")
    suspend fun getByRef(refType: String, refId: String, kind: Int): List<Adjust>

    @Query("SELECT * FROM Adjust ORDER BY time DESC")
    suspend fun getAll(): List<Adjust>

    @Query("SELECT COUNT(*) FROM Adjust WHERE bookId = :bookId")
    suspend fun count(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(adjust: Adjust)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Adjust>)

    @Query("DELETE FROM Adjust WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM Adjust WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM Budget WHERE bookId = :bookId")
    fun observeByBook(bookId: String): Flow<List<Budget>>

    @Query("SELECT * FROM Budget WHERE bookId = :bookId")
    suspend fun getByBook(bookId: String): List<Budget>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget)

    @Update
    suspend fun update(budget: Budget)

    @Delete
    suspend fun delete(budget: Budget)

    @Query("DELETE FROM Budget WHERE bookId = :bookId AND categoryId = :categoryId AND month = :month")
    suspend fun deleteOne(bookId: String, categoryId: String, month: String)

    @Query("DELETE FROM Budget WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    /** 分类合并用：把旧分类的预算改挂到保留分类 */
    @Query("UPDATE Budget SET categoryId = :to WHERE categoryId = :from")
    suspend fun remapCategory(from: String, to: String)
}
