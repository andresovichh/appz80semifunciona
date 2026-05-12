package xyz.mdblab.z80.data.api.models

import com.google.gson.annotations.SerializedName

data class SyncResponse(
    @SerializedName("inventory") val slots: List<ApiSlot>,
    @SerializedName("config") val config: Map<String, String>?
)

data class ApiSlot(
    @SerializedName("slot_code") val slotCode: String,
    @SerializedName("product_id") val productId: String,
    @SerializedName("product_name") val productName: String,
    @SerializedName("price") val price: Double,
    @SerializedName("stock") val stock: Int,
    @SerializedName("enabled") val enabled: Boolean
)
