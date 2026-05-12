package xyz.mdblab.z80.data.api.models

import com.google.gson.annotations.SerializedName

// --- CARD PAYMENT ---
data class PaymentRequest(
    @SerializedName("currency") val currency: String = "UYU",
    @SerializedName("items") val items: List<PaymentItem>
)

data class PaymentResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("transaction_timeout_seconds") val timeoutSeconds: Int?,
    @SerializedName("pos_id") val posId: String? = null  // POSLink POS ID used for this transaction
)

// --- QR PAYMENT ---
data class QrPaymentRequest(
    @SerializedName("currency") val currency: String = "UYU",
    @SerializedName("items") val items: List<PaymentItem>,
    @SerializedName("machine_id") val machineId: String
)

data class PaymentItem(
    @SerializedName("selection") val selection: String,
    @SerializedName("description") val description: String,
    @SerializedName("price") val price: Double,
    @SerializedName("quantity") val quantity: Int = 1
)

data class QrPaymentResponse(
    @SerializedName("id") val id: String,
    @SerializedName("qr_data") val qrData: String,
    @SerializedName("status") val status: String,
    @SerializedName("polling_interval_seconds") val pollingInterval: Int?,
    @SerializedName("transaction_timeout_seconds") val timeoutSeconds: Int?
)

data class QrStatusResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String, // "pending", "approved", "rejected"
    @SerializedName("display_message") val displayMessage: String?
)

// --- HEARTBEAT ---
data class HeartbeatPayload(
    @SerializedName("machine_id") val machineId: String,
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String = "1.0.0 (Android)",
    @SerializedName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerializedName("local_ip") val localIp: String = "127.0.0.1", // Placeholder
    @SerializedName("adb_port") val adbPort: Int? = null
)

data class HeartbeatResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("should_clear_pending") val shouldClearPending: Boolean = false,
    @SerializedName("raspberry_uuid") val raspberryUuid: String? = null,
    @SerializedName("config") val config: Map<String, String>? = null,
    @SerializedName("inventory") val inventory: List<ApiSlot>? = null
)

// --- CARD PAYMENT STATUS (Polling) ---
data class CardPaymentStatusResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String, // "pending", "processing", "success", "failed", "cancelled"
    @SerializedName("vending_action") val vendingAction: String?, // "wait", "dispense", "cancel"
    @SerializedName("display_message") val displayMessage: String?,
    @SerializedName("polling_interval_seconds") val pollingIntervalSeconds: Int?
)

// --- DISPENSE RESULT REPORTING ---
data class DispenseResultRequest(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error_message") val errorMessage: String? = null
)

data class DispenseResultResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("needs_void") val needsVoid: Boolean = false,
    @SerializedName("needs_credit_note") val needsCreditNote: Boolean = false
)

// --- CURRENT PAYMENT REQUEST (Recovery) ---
data class CurrentPaymentResponse(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("vending_action") val vendingAction: String?,
    @SerializedName("display_message") val displayMessage: String?,
    @SerializedName("amount") val amount: Double?,
    @SerializedName("selection") val selection: String?
)

// --- EMPLOYEE PIN VALIDATION ---
data class ValidatePinRequest(
    @SerializedName("pin") val pin: String,
    @SerializedName("machine_id") val machineId: String
)

data class ValidatePinResponse(
    @SerializedName("valid") val valid: Boolean,
    @SerializedName("employee_name") val employeeName: String?,
    @SerializedName("discount_percent") val discountPercent: Int?,
    @SerializedName("message") val message: String?
)

// --- VOID/REFUND ---
data class VoidInitiateResponse(
    @SerializedName("void_id") val voidId: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?
)

data class VoidStatusResponse(
    @SerializedName("void_id") val voidId: String,
    @SerializedName("status") val status: String, // "initiated", "polling", "completed", "error"
    @SerializedName("success") val success: Boolean,
    @SerializedName("poll_count") val pollCount: Int?,
    @SerializedName("error_message") val errorMessage: String?,
    @SerializedName("completed_at") val completedAt: String?
)
