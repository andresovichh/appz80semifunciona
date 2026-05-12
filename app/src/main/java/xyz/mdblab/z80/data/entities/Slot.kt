package xyz.mdblab.z80.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "slots")
data class Slot(
    @PrimaryKey
    @ColumnInfo(name = "slot_code") val slotCode: String,
    
    @ColumnInfo(name = "product_id") val productId: String?,
    @ColumnInfo(name = "product_name") val productName: String?,
    @ColumnInfo(name = "price") val price: Double,
    @ColumnInfo(name = "stock") val stock: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
