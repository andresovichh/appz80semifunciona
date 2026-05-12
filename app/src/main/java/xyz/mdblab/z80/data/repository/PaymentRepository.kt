package xyz.mdblab.z80.data.repository

import android.util.Log
import xyz.mdblab.z80.data.api.RetrofitClient
import xyz.mdblab.z80.data.api.models.*
import kotlinx.coroutines.delay

class PaymentRepository {

    private val api = RetrofitClient.instance
    // User provided API Key
    private val apiKey = "d87ac804-f3d1-4a1d-a36a-3fd9b34f9692" 

    suspend fun initiateCardPayment(amount: Double, selection: String, machineId: String): PaymentResponse? {
        return try {
            val item = PaymentItem(
                selection = selection,
                description = "Vending Product $selection",
                price = amount
            )
            val req = PaymentRequest(
                items = listOf(item)
            )
            val response = api.createPayment(apiKey, req)
            Log.i("PaymentRepo", "═══════════════════════════════════════════════")
            Log.i("PaymentRepo", "💳 CARD PAYMENT CREATED")
            Log.i("PaymentRepo", "   Payment ID: ${response.id}")
            Log.i("PaymentRepo", "   POS ID: ${response.posId ?: "NOT PROVIDED BY BACKEND"}")
            Log.i("PaymentRepo", "   Status: ${response.status}")
            Log.i("PaymentRepo", "   Timeout: ${response.timeoutSeconds}s")
            Log.i("PaymentRepo", "═══════════════════════════════════════════════")
            response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error initiateCardPayment", e)
            null
        }
    }

    suspend fun initiateQrPayment(amount: Double, selection: String, machineId: String): QrPaymentResponse? {
        return try {
            val item = PaymentItem(
                selection = selection,
                description = "Product $selection",
                price = amount
            )
            val req = QrPaymentRequest(
                items = listOf(item),
                machineId = machineId
            )
            val response = api.createQrPayment(apiKey, req)
            Log.i("PaymentRepo", "QR Payment Created: ${response.id}")
            response
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 409) {
                // Conflict: Active payment already exists - cancel it and retry
                Log.w("PaymentRepo", "409 Conflict detected - attempting to cancel existing payment")
                return handleQrConflictAndRetry(amount, selection, machineId)
            } else {
                Log.e("PaymentRepo", "HTTP Error ${e.code()} initiateQrPayment", e)
                null
            }
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error initiateQrPayment", e)
            null
        }
    }

    private suspend fun handleQrConflictAndRetry(amount: Double, selection: String, machineId: String): QrPaymentResponse? {
        try {
            // 1. Get current active payment
            val activePayment = getCurrentPaymentRequest()
            if (activePayment == null) {
                Log.w("PaymentRepo", "409 but no active payment found - retrying directly")
                delay(1000)
                // Retry once more
                return tryCreateQrPayment(amount, selection, machineId)
            }

            Log.i("PaymentRepo", "Cancelling active payment: ${activePayment.id}")

            // 2. Cancel the active payment
            try {
                api.cancelQrPayment(apiKey, activePayment.id)
                Log.i("PaymentRepo", "Successfully cancelled payment ${activePayment.id}")
            } catch (e: Exception) {
                Log.w("PaymentRepo", "Failed to cancel payment, continuing anyway: ${e.message}")
            }

            // 3. Wait a bit for backend to process
            delay(1000)

            // 4. Retry creating new payment
            return tryCreateQrPayment(amount, selection, machineId)

        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error handling QR conflict", e)
            return null
        }
    }

    private suspend fun tryCreateQrPayment(amount: Double, selection: String, machineId: String): QrPaymentResponse? {
        return try {
            val item = PaymentItem(
                selection = selection,
                description = "Product $selection",
                price = amount
            )
            val req = QrPaymentRequest(
                items = listOf(item),
                machineId = machineId
            )
            val response = api.createQrPayment(apiKey, req)
            Log.i("PaymentRepo", "QR Payment Created (retry): ${response.id}")
            response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Retry failed", e)
            null
        }
    }

    suspend fun pollQrStatus(qrId: String, timeoutSeconds: Int = 60): String {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val statusResp = api.getQrStatus(apiKey, qrId)
                Log.d("PaymentRepo", "QR Status: ${statusResp.status}")

                if (statusResp.status == "approved") return "approved"
                if (statusResp.status == "rejected" || statusResp.status == "cancelled") return "rejected"

                delay(2000) // Poll every 2s
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancel button pressed (or viewmodel scope torn down) — abort polling cleanly
                throw e
            } catch (e: Exception) {
                Log.w("PaymentRepo", "Poll error", e)
                delay(2000)
            }
        }
        return "timeout"
    }

    /**
     * Poll card payment status until terminal state or timeout
     * Returns: "approved", "rejected", or "timeout"
     */
    suspend fun pollCardPaymentStatus(paymentId: String, timeoutSeconds: Int = 120, pollingIntervalMs: Long = 3000): String {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        Log.i("PaymentRepo", "Starting card payment polling for $paymentId (timeout: ${timeoutSeconds}s)")

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val statusResp = api.getCardPaymentStatus(apiKey, paymentId)
                Log.d("PaymentRepo", "Card Payment Status: ${statusResp.status} (action: ${statusResp.vendingAction})")

                // Check vending_action first (more reliable)
                when (statusResp.vendingAction) {
                    "dispense" -> {
                        Log.i("PaymentRepo", "✓ Card payment APPROVED - dispense")
                        return "approved"
                    }
                    "cancel" -> {
                        Log.i("PaymentRepo", "✗ Card payment DENIED - cancel")
                        return "rejected"
                    }
                }

                // Fallback to status check
                when (statusResp.status) {
                    "success", "approved", "pos_paid" -> {
                        Log.i("PaymentRepo", "✓ Card payment APPROVED (status: ${statusResp.status})")
                        return "approved"
                    }
                    "failed", "rejected", "error", "cancelled", "expired" -> {
                        Log.i("PaymentRepo", "✗ Card payment REJECTED (status: ${statusResp.status})")
                        return "rejected"
                    }
                }

                // Still pending/processing - continue polling
                delay(pollingIntervalMs)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancel button pressed (or viewmodel scope torn down) — abort polling cleanly
                throw e
            } catch (e: Exception) {
                Log.w("PaymentRepo", "Card poll error: ${e.message}")
                delay(pollingIntervalMs)
            }
        }

        Log.w("PaymentRepo", "⏱ Card payment TIMEOUT after ${timeoutSeconds}s")
        return "timeout"
    }

    /**
     * Report dispense result to backend
     */
    suspend fun reportDispenseResult(paymentId: String, success: Boolean, errorMessage: String? = null): DispenseResultResponse? {
        return try {
            val request = DispenseResultRequest(
                success = success,
                errorMessage = errorMessage
            )
            val response = api.reportDispenseResult(apiKey, paymentId, request)
            Log.i("PaymentRepo", "Dispense result reported: success=$success, needsVoid=${response.needsVoid}")
            response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error reporting dispense result", e)
            null
        }
    }

    /**
     * Report QR dispense result to backend — backend's qrService.HandleDispenseResult
     * reverses the MercadoPago payment automatically when success=false.
     */
    suspend fun reportQrDispenseResult(qrPaymentId: String, success: Boolean, errorMessage: String? = null): DispenseResultResponse? {
        return try {
            val request = DispenseResultRequest(success = success, errorMessage = errorMessage)
            val response = api.reportQrDispenseResult(apiKey, qrPaymentId, request)
            Log.i("PaymentRepo", "QR dispense result reported: success=$success, status=${response.status}, msg=${response.message}")
            response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error reporting QR dispense result", e)
            null
        }
    }

    /**
     * Cancel a card payment request that is still being processed (POS waiting for card tap)
     */
    suspend fun cancelCardPayment(paymentId: String): Boolean {
        return try {
            api.cancelCardPayment(apiKey, paymentId)
            Log.i("PaymentRepo", "Card payment cancelled: $paymentId")
            true
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error cancelling card payment $paymentId", e)
            false
        }
    }

    /**
     * Initiate a void/refund for the last successful transaction
     */
    suspend fun initiateVoid(): VoidInitiateResponse? {
        return try {
            val response = api.initiateVoid(apiKey)
            Log.i("PaymentRepo", "Void initiated: ${response.voidId}, status: ${response.status}")
            response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error initiating void", e)
            null
        }
    }

    /**
     * Check void status
     */
    suspend fun getVoidStatus(voidId: String): VoidStatusResponse? {
        return try {
            val response = api.getVoidStatus(apiKey, voidId)
            Log.d("PaymentRepo", "Void status for $voidId: ${response.status}, success: ${response.success}")
            response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error checking void status", e)
            null
        }
    }

    /**
     * Poll void status until completed or failed
     * Returns: "completed", "error", or "timeout"
     */
    suspend fun pollVoidStatus(voidId: String, timeoutSeconds: Int = 120, pollingIntervalMs: Long = 2000): String {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        Log.i("PaymentRepo", "Starting void polling for $voidId (timeout: ${timeoutSeconds}s)")

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val statusResp = api.getVoidStatus(apiKey, voidId)
                Log.d("PaymentRepo", "Void Status: ${statusResp.status} (success: ${statusResp.success})")

                // Check if void completed successfully
                if (statusResp.success) {
                    Log.i("PaymentRepo", "Void completed successfully!")
                    return "completed"
                }

                // Check terminal states
                when (statusResp.status) {
                    "completed" -> {
                        Log.i("PaymentRepo", "Void completed (status: completed)")
                        return "completed"
                    }
                    "error" -> {
                        Log.w("PaymentRepo", "Void failed: ${statusResp.errorMessage}")
                        return "error"
                    }
                }

                // Still polling - continue
                delay(pollingIntervalMs)

            } catch (e: Exception) {
                Log.w("PaymentRepo", "Void poll error: ${e.message}")
                delay(pollingIntervalMs)
            }
        }

        Log.w("PaymentRepo", "Void polling TIMEOUT after ${timeoutSeconds}s")
        return "timeout"
    }

    /**
     * Get current active payment request (for recovery on startup)
     * Returns null if no active payment exists (404 from backend)
     */
    suspend fun getCurrentPaymentRequest(): CurrentPaymentResponse? {
        return try {
            val response = api.getCurrentPaymentRequest(apiKey)
            Log.i("PaymentRepo", "Active payment found: ${response.id}, status: ${response.status}")
            response
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) {
                Log.d("PaymentRepo", "No active payment request found")
                null
            } else {
                Log.e("PaymentRepo", "Error checking current payment", e)
                null
            }
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Error checking current payment", e)
            null
        }
    }

    suspend fun validateEmployeePIN(pin: String, machineId: String): ValidatePinResponse? {
        try {
            val request = ValidatePinRequest(pin = pin, machineId = machineId)
            val response = api.validateEmployeePIN(apiKey, request)
            Log.d("PaymentRepo", "PIN validation: valid=${response.valid}, name=${response.employeeName}, discount=${response.discountPercent}%")
            return response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "PIN validation failed", e)
            return null
        }
    }

    suspend fun sendHeartbeat(machineId: String): HeartbeatResponse? {
        try {
            val ip = getTailscaleOrLocalIp()
            val adbPort = getAdbWirelessPort()
            val payload = HeartbeatPayload(
                machineId = machineId,
                status = "online",
                uptimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000,
                localIp = ip,
                adbPort = adbPort
            )
            val response = api.sendHeartbeat(apiKey, payload)
            Log.d("PaymentRepo", "Heartbeat sent (IP: $ip, adb_port: $adbPort). Response: $response")
            return response
        } catch (e: Exception) {
            Log.e("PaymentRepo", "Heartbeat failed", e)
            return null
        }
    }

    /**
     * Returns the ADB wireless port the device is currently listening on, or null if it
     * couldn't be determined. Source order:
     *   1. SharedPreferences "adb_config".adb_port — set by UsbAutoGrantService when it
     *      scrapes the Wireless Debugging subscreen.
     *   2. Fallback: parse `getprop` for service.adb.tcp.port / service.adb.tls.port.
     */
    private fun getAdbWirelessPort(): Int? {
        try {
            val svc = xyz.mdblab.z80.services.UsbAutoGrantService.instance
            if (svc != null) {
                val prefs = svc.getSharedPreferences("adb_config", 0)
                val port = prefs.getInt("adb_port", 0)
                if (port > 0) return port
            }
        } catch (_: Exception) {}

        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("getprop"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Regex("""\[service\.adb\.(tcp|tls)\.port\]: \[(\d+)\]""").find(output)
                ?.groupValues?.get(2)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun getTailscaleOrLocalIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val sAddr = addr.hostAddress
                        // Prefer Tailscale IP
                        if (sAddr != null && sAddr.startsWith("100.")) {
                            return sAddr
                        }
                    }
                }
            }
            // Fallback: Return any non-loopback or default
            return "127.0.0.1"
        } catch (ex: Exception) {
            return "127.0.0.1"
        }
    }
}
