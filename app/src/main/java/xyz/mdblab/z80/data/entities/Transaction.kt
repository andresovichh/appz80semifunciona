package xyz.mdblab.z80.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey val id: String, // UUID
    
    @ColumnInfo(name = "amount") val amount: Double,
    @ColumnInfo(name = "currency") val currency: String = "UYU",
    @ColumnInfo(name = "item_selection") val itemSelection: String,
    @ColumnInfo(name = "item_description") val itemDescription: String,
    
    @ColumnInfo(name = "payment_method") val paymentMethod: String, // "card", "qr"
    @ColumnInfo(name = "status") val status: String, // "pending", "approved", "dispensed", "failed"
    
    @ColumnInfo(name = "pos_id") val posId: String?,
    @ColumnInfo(name = "device_id") val deviceId: String?, // RaspberryID equivalent
    
    @ColumnInfo(name = "request_sent_at") val requestSentAt: Long?,
    @ColumnInfo(name = "dispensed_at") val dispensedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
