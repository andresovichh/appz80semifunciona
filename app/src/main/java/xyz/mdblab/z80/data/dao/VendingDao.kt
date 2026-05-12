package xyz.mdblab.z80.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.mdblab.z80.data.entities.Config
import xyz.mdblab.z80.data.entities.Slot
import xyz.mdblab.z80.data.entities.Transaction

@Dao
interface VendingDao {
    // --- Slots ---
    @Query("SELECT * FROM slots WHERE slot_code = :code")
    suspend fun getSlot(code: String): Slot?

    @Query("SELECT * FROM slots ORDER BY slot_code ASC")
    suspend fun getAllSlots(): List<Slot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlot(slot: Slot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlots(slots: List<Slot>)

    // --- Transactions ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: Transaction)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransaction(id: String): Transaction?

    @Query("UPDATE transactions SET status = :status, dispensed_at = :dispensedAt WHERE id = :id")
    suspend fun markDispensed(id: String, status: String, dispensedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM transactions WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingTransactions(): List<Transaction>

    // --- Config ---
    @Query("SELECT value FROM config WHERE key = :key")
    suspend fun getConfigValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: Config)
}
