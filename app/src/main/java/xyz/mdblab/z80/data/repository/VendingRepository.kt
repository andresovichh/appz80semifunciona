package xyz.mdblab.z80.data.repository

import xyz.mdblab.z80.data.dao.VendingDao
import xyz.mdblab.z80.data.entities.Slot
import xyz.mdblab.z80.data.entities.Transaction

import java.util.UUID

class VendingRepository(private val vendingDao: VendingDao) {

    // --- Inventory ---
    suspend fun getAllSlots(): List<Slot> {
        return vendingDao.getAllSlots()
    }

    suspend fun getSlot(code: String): Slot? {
        return vendingDao.getSlot(code)
    }

    suspend fun processSyncPayload(json: String) {
        if (json.isBlank()) return
        val response = com.google.gson.Gson().fromJson(json, xyz.mdblab.z80.data.api.models.SyncResponse::class.java)
        if (response != null) {
            applySyncResponse(response)
        }
    }

    private suspend fun applySyncResponse(response: xyz.mdblab.z80.data.api.models.SyncResponse) {
        // Update Slots from 'inventory'
        if (response.slots.isNotEmpty()) {
            response.slots.forEach { apiSlot ->
                val slot = Slot(
                    slotCode = apiSlot.slotCode,
                    productId = apiSlot.productId, 
                    productName = apiSlot.productName,
                    price = apiSlot.price,
                    stock = apiSlot.stock,
                    enabled = apiSlot.enabled
                )
                vendingDao.insertSlot(slot) // Assumes Insert OnConflict = REPLACE
            }
        }
        
        // Update Config
        response.config?.forEach { (k, v) ->
            vendingDao.insertConfig(xyz.mdblab.z80.data.entities.Config(k, v))
        }
    }

    // --- Transactions ---
    suspend fun createPendingTransaction(
        amount: Double,
        selection: String,
        method: String,
        description: String
    ): String {
        val id = UUID.randomUUID().toString()
        val tx = Transaction(
            id = id,
            amount = amount,
            itemSelection = selection,
            itemDescription = description,
            paymentMethod = method,
            status = "pending",
            posId = null,
            deviceId = null, // TODO: Get from Config
            requestSentAt = System.currentTimeMillis(),
            dispensedAt = null
        )
        vendingDao.insertTransaction(tx)
        return id
    }

    suspend fun markTransactionDispensed(id: String) {
        vendingDao.markDispensed(id, "dispensed")
    }
    
    suspend fun markTransactionFailed(id: String) {
        vendingDao.markDispensed(id, "failed") // We reuse the query but just update status really
    }

    // --- Apply config + inventory from heartbeat response ---
    suspend fun applyConfigAndInventory(config: Map<String, String>?, inventory: List<xyz.mdblab.z80.data.api.models.ApiSlot>?) {
        val response = xyz.mdblab.z80.data.api.models.SyncResponse(
            slots = inventory ?: emptyList(),
            config = config
        )
        applySyncResponse(response)
    }

    // --- Config ---
    suspend fun getMachineUUID(): String? {
        return vendingDao.getConfigValue("machine_uuid")
    }
}
