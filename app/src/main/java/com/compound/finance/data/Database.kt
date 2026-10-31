package com.compound.finance.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TxDao {
    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun between(from: Long, to: Long): Flow<List<TxEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp ASC")
    suspend fun since(from: Long): List<TxEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TxEntity?

    @Query("SELECT * FROM transactions WHERE merchantKey = :key")
    suspend fun byMerchant(key: String): List<TxEntity>

    @Query("SELECT * FROM transactions WHERE syncedToCompound = 0 AND excluded = 0 ORDER BY timestamp ASC")
    suspend fun unsynced(): List<TxEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: TxEntity): Long

    @Update suspend fun update(tx: TxEntity)
    @Delete suspend fun delete(tx: TxEntity)

    @Query("UPDATE transactions SET syncedToCompound = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM transactions ORDER BY timestamp ASC")
    suspend fun all(): List<TxEntity>

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface SmsDao {
    @Query("SELECT * FROM sms WHERE status = 'REVIEW' ORDER BY timestamp DESC")
    fun review(): Flow<List<SmsEntity>>

    @Query("SELECT COUNT(*) FROM sms WHERE status = 'REVIEW'")
    fun reviewCount(): Flow<Int>

    @Query("SELECT `key` FROM sms")
    suspend fun keys(): List<String>

    @Query("SELECT MAX(timestamp) FROM sms")
    suspend fun latest(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: SmsEntity): Long

    @Query("UPDATE sms SET status = :status, txId = :txId WHERE `key` = :key")
    suspend fun setStatus(key: String, status: String, txId: Long?)

    @Query("UPDATE sms SET status = 'DISMISSED' WHERE status = 'REVIEW' AND sender = :sender")
    suspend fun dismissSender(sender: String)

    @Query("DELETE FROM sms")
    suspend fun clear()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules") suspend fun all(): List<RuleEntity>
    @Query("SELECT * FROM rules ORDER BY pattern") fun observe(): Flow<List<RuleEntity>>
    @Upsert suspend fun upsert(r: RuleEntity)
    @Query("DELETE FROM rules WHERE pattern = :pattern") suspend fun delete(pattern: String)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets ORDER BY category") fun observe(): Flow<List<BudgetEntity>>
    @Query("SELECT * FROM budgets") suspend fun all(): List<BudgetEntity>
    @Upsert suspend fun upsert(b: BudgetEntity)
    @Query("DELETE FROM budgets WHERE category = :category") suspend fun delete(category: String)
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts WHERE active = 1 ORDER BY type, name") fun observe(): Flow<List<DebtEntity>>
    @Query("SELECT * FROM debts WHERE active = 1") suspend fun all(): List<DebtEntity>
    @Upsert suspend fun upsert(d: DebtEntity): Long
    @Delete suspend fun delete(d: DebtEntity)
}

@Dao
interface BillDao {
    @Query("SELECT * FROM bills WHERE active = 1 ORDER BY nextDue") fun observe(): Flow<List<BillEntity>>
    @Query("SELECT * FROM bills WHERE active = 1") suspend fun all(): List<BillEntity>
    @Upsert suspend fun upsert(b: BillEntity): Long
    @Delete suspend fun delete(b: BillEntity)
}

@Database(
    entities = [TxEntity::class, SmsEntity::class, RuleEntity::class, BudgetEntity::class, DebtEntity::class, BillEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tx(): TxDao
    abstract fun sms(): SmsDao
    abstract fun rules(): RuleDao
    abstract fun budgets(): BudgetDao
    abstract fun debts(): DebtDao
    abstract fun bills(): BillDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "compound.db")
                .build().also { instance = it }
        }
    }
}
