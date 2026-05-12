package xyz.mdblab.z80.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import xyz.mdblab.z80.data.AppDatabase
import xyz.mdblab.z80.data.entities.Slot
import xyz.mdblab.z80.data.repository.VendingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: VendingRepository

    // Payment Repo (moved up to avoid NPE in init)
    private val paymentRepository = xyz.mdblab.z80.data.repository.PaymentRepository()

    // --- Global Timeout Configuration (in milliseconds) ---
    // These can be updated from backend config in the future
    object TimeoutConfig {
        var productSelectedMs = 30_000L   // 30s to select payment method
        var voidPendingMs = 60_000L       // 60s to initiate void
        var successDisplayMs = 5_000L     // 5s to show success before idle
        var failedDisplayMs = 8_000L      // 8s to show error before idle
        var countdownUpdateIntervalMs = 100L  // Update countdown every 100ms
    }

    // Current timeout job (cancellable)
    private var timeoutJob: Job? = null

    // Countdown state for UI (0.0 to 1.0 progress, remaining seconds)
    data class CountdownState(
        val progress: Float = 1f,      // 1.0 = full, 0.0 = expired
        val remainingSeconds: Int = 0,  // Seconds remaining
        val isActive: Boolean = false
    )

    private val _countdownState = MutableLiveData(CountdownState())
    val countdownState: LiveData<CountdownState> = _countdownState

    // UI State
    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    private val _currentSlot = MutableLiveData<Slot?>()
    val currentSlot: LiveData<Slot?> = _currentSlot

    // --- State Machine ---
    sealed class UiState {
        object Idle : UiState() // Playing Video
        data class ProductSelected(val slot: Slot) : UiState() // Show Product + Payment Options
        data class EmployeeLogin(val slot: Slot) : UiState()
        object ProcessingPayment : UiState() // Legacy - use ShowingCardTap instead
        data class PressButtonOnPOS(val slot: Slot) : UiState() // Tell user to press a button on POS to start
        data class ShowingCardTap(val slot: Slot, val timeoutSeconds: Int) : UiState() // Waiting for card tap on POS
        data class ShowingQR(val qrData: String, val slot: Slot) : UiState() // Displaying QR for payment
        // Dispensing state - waiting for VMC to confirm dispense
        data class Dispensing(val slot: Slot) : UiState() // Payment approved, waiting for VMC dispense confirmation
        data class DispenseSuccess(val slot: Slot) : UiState() // Product dispensed successfully
        data class DispenseFailed(val slot: Slot, val message: String) : UiState() // Dispense failed, need void
        // Void/Refund states
        data class RefundRequired(val slot: Slot, val paymentId: String, val message: String) : UiState() // Show refund button
        data class RefundInstructions(val paymentId: String) : UiState() // Show green button animation
        data class VoidPending(val paymentId: String) : UiState() // Waiting for user to initiate void
        data class VoidProcessing(val paymentId: String) : UiState() // Void in progress
        data class VoidSuccess(val message: String) : UiState() // Void completed
        data class VoidFailed(val message: String, val canRetry: Boolean) : UiState() // Void failed
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    // Connectivity tracking — updated by heartbeat
    @Volatile private var isOnline = false
    private var consecutiveHeartbeatFailures = 0
    private val MAX_FAILURES_BEFORE_OFFLINE = 2

    private val _isConnected = MutableLiveData(true)
    val isConnected: LiveData<Boolean> = _isConnected

    // Employee discount — applied after PIN validation, cleared on resetToIdle
    private var employeeDiscountPercent: Int = 0
    private var employeeName: String? = null

    private val _employeeStatus = MutableLiveData<String?>()
    val employeeStatus: LiveData<String?> = _employeeStatus

    // Promo video URL from backend
    private var currentPromoVideoUrl: String? = null
    private val _promoVideoPath = MutableLiveData<String?>()
    val promoVideoPath: LiveData<String?> = _promoVideoPath

    // Exposed Inventory List
    private val _slots = MutableLiveData<List<Slot>>()
    val slots: LiveData<List<Slot>> = _slots

    init {
        val dao = AppDatabase.getDatabase(application).vendingDao()
        repository = VendingRepository(dao)
        _statusText.value = "Initializing..."
        loadInitialInventory()

        // Check for pending transactions on startup
        checkPendingTransactionsOnStartup()

        // Start Heartbeat
        viewModelScope.launch {
            while (true) {
                // TODO: Get real machine ID
                val hbResponse = paymentRepository.sendHeartbeat("test-machine-01")

                if (hbResponse != null) {
                    // Heartbeat succeeded — we're online
                    if (!isOnline) {
                        android.util.Log.i("MainViewModel", "Back online after $consecutiveHeartbeatFailures failures")
                    }
                    isOnline = true
                    consecutiveHeartbeatFailures = 0
                    _isConnected.postValue(true)

                    // Check if backend wants us to clear pending state
                    if (hbResponse.shouldClearPending) {
                        android.util.Log.i("MainViewModel", "[RECOVERY] Backend says clear pending - resetting state")
                        val currentState = _uiState.value
                        if (currentState == UiState.ProcessingPayment) {
                            resetToIdle()
                        }
                    }

                    // Apply config and inventory directly from heartbeat response
                    if (hbResponse.config != null || hbResponse.inventory != null) {
                        repository.applyConfigAndInventory(hbResponse.config, hbResponse.inventory)
                        val dbSlots = repository.getAllSlots()
                        if (dbSlots.isNotEmpty()) {
                            _slots.postValue(dbSlots)
                            if (_uiState.value == UiState.Idle) {
                                _statusText.postValue("Ready. ${dbSlots.size} products loaded.")
                            }
                        } else {
                            _statusText.postValue("No inventory configured.")
                        }
                    }

                    // Check for promo video update
                    val videoUrl = hbResponse.config?.get("promo_video_url")
                    if (videoUrl != null && videoUrl != currentPromoVideoUrl) {
                        currentPromoVideoUrl = videoUrl
                        downloadPromoVideo(videoUrl)
                    }
                } else {
                    // Heartbeat failed
                    consecutiveHeartbeatFailures++
                    if (consecutiveHeartbeatFailures >= MAX_FAILURES_BEFORE_OFFLINE) {
                        isOnline = false
                        _isConnected.postValue(false)
                        android.util.Log.w("MainViewModel", "OFFLINE: $consecutiveHeartbeatFailures consecutive heartbeat failures")
                        if (_uiState.value == UiState.Idle) {
                            _statusText.postValue("Sin conexión a internet. Ventas deshabilitadas.")
                        }
                    }
                }

                delay(30000) // 30 seconds
            }
        }
    }

    private fun loadInitialInventory() {
        viewModelScope.launch {
            _statusText.value = "Loading inventory..."

            // Load from local DB (heartbeat will sync from backend)
            val dbSlots = repository.getAllSlots()

            if (dbSlots.isEmpty()) {
                _statusText.value = "Waiting for heartbeat sync..."
            } else {
                _statusText.value = "Ready. ${dbSlots.size} products loaded."
                _slots.postValue(dbSlots)
            }
        }
    }

    // Temporary: Seed DB for testing without backend sync
    private suspend fun seedTestInventory() {
        val appDb = AppDatabase.getDatabase(getApplication())
        val dao = appDb.vendingDao()
        dao.insertSlot(Slot("10", "prod_1", "Coca Cola", 50.0, 10, true))
        dao.insertSlot(Slot("11", "prod_2", "Water", 40.0, 10, true))
        dao.insertSlot(Slot("12", "prod_3", "Snickers", 65.0, 5, true))
        dao.insertSlot(Slot("32", "prod_32", "Coca Cola Zero", 10.0, 10, true))
        _statusText.postValue("Seeded Test Inventory")
    }

    /**
     * Check for pending transactions on app startup
     * If there's an active payment in the backend, resume polling
     */
    private fun checkPendingTransactionsOnStartup() {
        viewModelScope.launch {
            android.util.Log.i("MainViewModel", "[RECOVERY] Checking for pending transactions...")

            val activePayment = paymentRepository.getCurrentPaymentRequest()

            if (activePayment == null) {
                android.util.Log.i("MainViewModel", "[RECOVERY] No active payment found - clean state")
                return@launch
            }

            android.util.Log.i("MainViewModel", "[RECOVERY] Found active payment: ${activePayment.id}, status: ${activePayment.status}")

            // Check if payment is in terminal state - these should be ignored
            val terminalStates = listOf("qr_paid", "cancelled", "failed", "completed", "success", "approved", "rejected", "expired")
            if (terminalStates.contains(activePayment.status)) {
                android.util.Log.i("MainViewModel", "[RECOVERY] Payment is in terminal state '${activePayment.status}' - ignoring")
                return@launch
            }

            // Save payment ID for dispense reporting
            currentPaymentId = activePayment.id
            isPaymentInProgress = true

            // Handle based on status/action
            when (activePayment.vendingAction) {
                "dispense" -> {
                    // Payment was approved but dispense wasn't confirmed
                    android.util.Log.i("MainViewModel", "[RECOVERY] Payment approved, waiting for dispense")
                    _statusText.postValue("Resuming... Payment approved, dispense pending")
                    awaitingDispenseResult = true
                    // Don't send APPROVE again - MDB might have already processed it
                    // Just wait for MDB message
                }
                "cancel" -> {
                    // Payment was rejected - just reset
                    android.util.Log.i("MainViewModel", "[RECOVERY] Payment was cancelled")
                    resetToIdle()
                }
                "wait", null -> {
                    // Payment is still pending - resume polling
                    android.util.Log.i("MainViewModel", "[RECOVERY] Payment still pending, resuming polling")
                    _statusText.postValue("Resuming pending payment...")
                    _uiState.postValue(UiState.ProcessingPayment)

                    // Resume polling
                    val pollResult = paymentRepository.pollCardPaymentStatus(
                        paymentId = activePayment.id,
                        timeoutSeconds = 60, // Shorter timeout for recovery
                        pollingIntervalMs = 3000
                    )

                    when (pollResult) {
                        "approved" -> {
                            _statusText.postValue("Payment Approved! Waiting for dispense...")
                            awaitingDispenseResult = true
                            sendMdbCommand("APPROVE")
                        }
                        "rejected" -> {
                            _statusText.postValue("Payment Rejected")
                            sendMdbCommand("DENY")
                            delay(3000)
                            resetToIdle()
                        }
                        "timeout" -> {
                            _statusText.postValue("Payment Timeout")
                            sendMdbCommand("DENY")
                            delay(3000)
                            resetToIdle()
                        }
                    }
                }
            }
        }
    }

    fun selectSlot(slot: Slot) {
        _currentSlot.value = slot
        _statusText.value = "Selected: ${slot.productName} - $${slot.price}"
    }

    // Called when MDB Service broadcasts messages (VEND_REQUEST, VEND_SUCCESS, VEND_FAILURE, etc.)
    fun onMdbMessage(message: String) {
        android.util.Log.d("MainViewModel", "MDB Message: $message")

        when {
            message.startsWith("VEND_REQUEST") -> handleVendRequest(message)
            message.startsWith("VEND_SUCCESS") -> onMdbDispenseResult(true)
            message.startsWith("VEND_FAILURE") -> onMdbDispenseResult(false)
            message.startsWith("SESSION_COMPLETE") -> {
                // Session ended by VMC, reset if not already
                if (_uiState.value != UiState.Idle) {
                    resetToIdle()
                }
            }
            message.startsWith("VMC_STUCK") -> {
                android.util.Log.e("MainViewModel", "🚨 VMC STUCK detected! Recovering...")
                _statusText.value = "VMC trabada - recuperando..."
                // Cancel any ongoing payment
                cancelPayment()
            }
            message.startsWith("VMC_RECOVERED") -> {
                android.util.Log.i("MainViewModel", "✅ VMC recovered from stuck state")
                _statusText.value = "VMC recuperada"
                resetToIdle()
            }
            else -> _statusText.value = message
        }
    }

    private fun handleVendRequest(message: String) {
        android.util.Log.i("MainViewModel", "=== handleVendRequest START: $message (isOnline=$isOnline) ===")
        // Reject if no internet connection
        if (!isOnline) {
            android.util.Log.w("MainViewModel", "VEND_REQUEST denied - no internet connection")
            _statusText.value = "Sin conexión. Venta no disponible."
            sendMdbCommand("DENY")
            viewModelScope.launch {
                delay(3000)
                if (_uiState.value == UiState.Idle) {
                    _statusText.postValue("Sin conexión a internet. Ventas deshabilitadas.")
                }
            }
            return
        }

        // Reject if there's already a payment in progress
        if (isPaymentInProgress) {
            android.util.Log.w("MainViewModel", "VEND_REQUEST ignored - payment already in progress, sending DENY")
            _statusText.value = "Payment in progress..."
            sendMdbCommand("DENY")
            return
        }

        // Message format: "VEND_REQUEST: Item:32,Price:1000"
        val itemPart = message.substringAfter("Item:", "-1").substringBefore(",")
        val itemId = itemPart.toIntOrNull() ?: -1

        android.util.Log.i("MainViewModel", "Parsed itemId: $itemId")

        if (itemId != -1) {
            val slotCode = itemId.toString()
            viewModelScope.launch {
                val found = repository.getSlot(slotCode)
                android.util.Log.i("MainViewModel", "Lookup slot '$slotCode': found=$found")

                if (found != null && found.stock > 0) {
                    android.util.Log.i("MainViewModel", "Slot is valid and has stock. Moving to ProductSelected.")
                    _currentSlot.postValue(found)
                    _uiState.postValue(UiState.ProductSelected(found))
                    _statusText.postValue("${found.productName} - $${found.price}")

                    // Start timeout for payment method selection
                    startTimeoutWithCountdown(TimeoutConfig.productSelectedMs) {
                        android.util.Log.i("MainViewModel", "ProductSelected timeout - returning to idle")
                        _statusText.postValue("Session timeout")
                        sendMdbCommand("DENY")
                        delay(1500)
                        resetToIdle()
                    }
                } else {
                    _statusText.postValue("Slot $slotCode not found or empty")
                    sendMdbCommand("DENY")
                    delay(2000)
                    _uiState.postValue(UiState.Idle)
                }
            }
        }
    }

    /**
     * Start a cancellable timeout with visual countdown that executes action after delayMs
     */
    private fun startTimeoutWithCountdown(delayMs: Long, action: suspend () -> Unit) {
        cancelTimeout()

        val startTime = System.currentTimeMillis()
        val endTime = startTime + delayMs

        timeoutJob = viewModelScope.launch {
            // Update countdown while waiting
            while (System.currentTimeMillis() < endTime) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = delayMs - elapsed
                val progress = remaining.toFloat() / delayMs.toFloat()
                val remainingSeconds = (remaining / 1000).toInt() + 1

                _countdownState.postValue(CountdownState(
                    progress = progress.coerceIn(0f, 1f),
                    remainingSeconds = remainingSeconds.coerceAtLeast(0),
                    isActive = true
                ))

                delay(TimeoutConfig.countdownUpdateIntervalMs)
            }

            // Timeout expired - execute action
            _countdownState.postValue(CountdownState(progress = 0f, remainingSeconds = 0, isActive = false))
            action()
        }
    }

    /**
     * Cancel any active timeout and hide countdown
     */
    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
        // Use postValue to ensure UI thread updates
        _countdownState.postValue(CountdownState(progress = 1f, remainingSeconds = 0, isActive = false))
    }

    // Track current payment for dispense reporting
    private var currentPaymentId: String? = null
    private var awaitingDispenseResult = false
    private var isPaymentInProgress = false  // Prevent concurrent payments
    private var paymentJob: kotlinx.coroutines.Job? = null  // Track payment coroutine for cancellation

    // User Actions
    fun onPaymentMethodSelected(method: String) {
        val currentSlot = _currentSlot.value
        if (currentSlot == null) return

        // Prevent concurrent payments
        if (isPaymentInProgress) {
            android.util.Log.w("MainViewModel", "Payment already in progress, ignoring")
            _statusText.value = "Payment already in progress"
            return
        }

        // Cancel selection timeout - user made a choice
        cancelTimeout()

        isPaymentInProgress = true
        _uiState.value = UiState.ProcessingPayment

        paymentJob = viewModelScope.launch {
            val machineId = "test-machine-01" // TODO: Get from Config

            if (method == "card") {
                processCardPayment(currentSlot, machineId)
            } else if (method == "qr") {
                processQrPayment(currentSlot, machineId)
            }
        }
    }

    /**
     * Complete Card Payment Flow:
     * 1. Create payment request
     * 2. Show card tap screen with countdown
     * 3. Poll for status until approved/rejected/timeout
     * 4. Send MDB APPROVE or DENY
     * 5. Wait for dispense result from MDB
     * 6. Report result to backend
     */
    private suspend fun processCardPayment(slot: Slot, machineId: String) {
        // 0. Show "Press a button on POS" instruction
        _uiState.postValue(UiState.PressButtonOnPOS(slot))
        _statusText.postValue("Presioná una tecla en el POS...")

        // Wait for user to press button on POS (the backend call takes a moment anyway)
        delay(7000)

        _statusText.postValue("Iniciando pago con tarjeta...")

        // 1. Apply employee discount if active
        val finalPrice = if (employeeDiscountPercent > 0) {
            val discounted = slot.price * (1 - employeeDiscountPercent / 100.0)
            kotlin.math.round(discounted * 100) / 100.0
        } else {
            slot.price
        }
        if (employeeDiscountPercent > 0) {
            android.util.Log.i("MainViewModel", "Applying ${employeeDiscountPercent}% discount: ${slot.price} -> $finalPrice")
        }

        // 2. Create payment
        val response = paymentRepository.initiateCardPayment(finalPrice, slot.slotCode, machineId)

        if (response == null) {
            _statusText.postValue("Payment Init Failed")
            delay(2000)
            cancelPayment()
            return
        }

        currentPaymentId = response.id
        // Card-tap window: backend value if reasonable, else floor at 246s. Anything shorter
        // doesn't give the user enough time to walk to the POS and tap; cancel button is the
        // intended way out before this expires.
        val timeoutSeconds = (response.timeoutSeconds ?: 246).coerceAtLeast(246)

        // 2. Show card tap screen with countdown
        _uiState.postValue(UiState.ShowingCardTap(slot, timeoutSeconds))
        _statusText.postValue("Tap your card on the POS")
        android.util.Log.i("MainViewModel", "Card Payment Created: ${response.id}, timeout: ${timeoutSeconds}s")

        // Start countdown for card tap
        startTimeoutWithCountdown((timeoutSeconds * 1000).toLong()) {
            // This will be cancelled when payment completes
            android.util.Log.w("MainViewModel", "Card tap countdown finished (handled by polling)")
        }

        // 3. Poll for status (this blocks until terminal state)
        val pollResult = paymentRepository.pollCardPaymentStatus(
            paymentId = response.id,
            timeoutSeconds = timeoutSeconds,
            pollingIntervalMs = 3000
        )

        // Cancel countdown - payment finished
        cancelTimeout()

        // 3. Handle result
        when (pollResult) {
            "approved" -> {
                _statusText.postValue("Payment Approved! Dispensing...")
                awaitingDispenseResult = true

                // Transition to Dispensing state with animation
                _uiState.postValue(UiState.Dispensing(slot))

                // Send APPROVE to MDB
                sendMdbCommand("APPROVE")
                android.util.Log.i("MainViewModel", "Payment approved, sent APPROVE, waiting for VMC dispense result...")

                // Start timeout for VMC response (30 seconds)
                startTimeoutWithCountdown(30_000L) {
                    android.util.Log.w("MainViewModel", "VMC dispense timeout - no VEND_SUCCESS/FAILURE received")
                    if (awaitingDispenseResult) {
                        // VMC didn't respond - assume failure and initiate void
                        awaitingDispenseResult = false
                        val paymentId = currentPaymentId ?: ""

                        viewModelScope.launch {
                            _statusText.postValue("Dispense timeout - initiating refund...")
                            _uiState.postValue(UiState.DispenseFailed(slot, "VMC did not respond"))

                            // Try to report failure to backend (may fail with HTTP 400 if status is "approved")
                            android.util.Log.i("MainViewModel", "Attempting to report dispense failure for payment: $paymentId")
                            val result = paymentRepository.reportDispenseResult(paymentId, false, "VMC timeout - no response")

                            if (result == null) {
                                // Report failed (likely HTTP 400 due to status mismatch), but continue with void anyway
                                android.util.Log.w("MainViewModel", "Failed to report dispense failure (likely status mismatch), continuing with void initiation")
                            } else {
                                android.util.Log.i("MainViewModel", "Dispense failure reported successfully, needsVoid: ${result.needsVoid}")
                            }

                            // Show refund required screen - wait for user action
                            delay(2000) // Brief pause to show error
                            android.util.Log.i("MainViewModel", "Dispense failed for payment: $paymentId - showing refund button")

                            _uiState.postValue(UiState.RefundRequired(slot, paymentId, "El producto no se pudo dispensar"))
                            _statusText.postValue("Dispense failed - refund available")

                            // Start timeout for refund screen (same as product selection timeout)
                            startTimeoutWithCountdown(30_000L) {
                                android.util.Log.w("MainViewModel", "Refund button timeout - returning to idle")
                                resetToIdle()
                            }
                            return@launch // Wait for user to press refund button
                        }
                    }
                }
            }
            "rejected" -> {
                _statusText.postValue("Payment Rejected")
                sendMdbCommand("DENY")
                delay(3000)
                resetToIdle()
            }
            "timeout" -> {
                _statusText.postValue("Payment Timeout - Try Again")
                sendMdbCommand("DENY")
                delay(3000)
                resetToIdle()
            }
        }
    }

    /**
     * User pressed the refund button - initiate void process
     */
    fun initiateRefund() {
        val currentState = _uiState.value
        if (currentState !is UiState.RefundRequired) {
            android.util.Log.w("MainViewModel", "initiateRefund called but not in RefundRequired state")
            return
        }

        val paymentId = currentState.paymentId
        cancelTimeout() // Cancel the refund screen timeout

        viewModelScope.launch {
            android.util.Log.i("MainViewModel", "User initiated refund for payment: $paymentId")

            // Show instruction to press green button on POS
            _uiState.postValue(UiState.RefundInstructions(paymentId))
            _statusText.postValue("Presioná el botón verde del POS...")
            delay(8000) // Give user time to read and press the button

            // Now proceed with void
            _uiState.postValue(UiState.VoidProcessing(paymentId))
            _statusText.postValue("Procesando reintegro...")

            // Initiate void with backend
            val voidResponse = paymentRepository.initiateVoid()

                            if (voidResponse == null) {
                                android.util.Log.e("MainViewModel", "Failed to initiate void after timeout")
                                _uiState.postValue(UiState.VoidFailed("Error al iniciar reintegro", canRetry = false))
                                _statusText.postValue("Error en reintegro - contacte soporte")
                                delay(8000)
                                resetToIdle()
                                return@launch
                            }

                            android.util.Log.i("MainViewModel", "Void initiated: ${voidResponse.voidId}, status: ${voidResponse.status}")

                            // Check if void already errored
                            if (voidResponse.status == "error") {
                                _uiState.postValue(UiState.VoidFailed(voidResponse.error ?: "Error en reintegro", canRetry = false))
                                _statusText.postValue("Error en reintegro - contacte soporte")
                                delay(8000)
                                resetToIdle()
                                return@launch
                            }

                            // Poll for void completion
                            val voidResult = paymentRepository.pollVoidStatus(voidResponse.voidId, timeoutSeconds = 120)

                            when (voidResult) {
                                "completed" -> {
                                    _uiState.postValue(UiState.VoidSuccess("¡Reintegro exitoso!"))
                                    _statusText.postValue("¡Reintegro completado!")
                                    android.util.Log.i("MainViewModel", "Void completed successfully after timeout")
                                    delay(5000)
                                    resetToIdle()
                                }
                                "error" -> {
                                    _uiState.postValue(UiState.VoidFailed("Error en reintegro. Contacte soporte.", canRetry = false))
                                    _statusText.postValue("Error en reintegro - contacte soporte")
                                    android.util.Log.e("MainViewModel", "Void failed with error after timeout")
                                    delay(8000)
                                    resetToIdle()
                                }
                                "timeout" -> {
                                    _uiState.postValue(UiState.VoidFailed("Timeout en reintegro. Contacte soporte.", canRetry = false))
                                    _statusText.postValue("Timeout en reintegro - contacte soporte")
                                    android.util.Log.e("MainViewModel", "Void timeout after dispense timeout")
                                    delay(8000)
                                    resetToIdle()
                                }
                            }
        }
    }

    /**
     * QR Payment Flow with polling
     */
    private suspend fun processQrPayment(slot: Slot, machineId: String) {
        _statusText.value = "Generating QR Code..."

        val finalPrice = if (employeeDiscountPercent > 0) {
            val discounted = slot.price * (1 - employeeDiscountPercent / 100.0)
            kotlin.math.round(discounted * 100) / 100.0
        } else {
            slot.price
        }
        if (employeeDiscountPercent > 0) {
            android.util.Log.i("MainViewModel", "QR: Applying ${employeeDiscountPercent}% discount: ${slot.price} -> $finalPrice")
        }

        val qrResp = paymentRepository.initiateQrPayment(finalPrice, slot.slotCode, machineId)

        if (qrResp == null) {
            _statusText.value = "QR Generation Failed"
            delay(2000)
            cancelPayment()
            return
        }

        currentPaymentId = qrResp.id

        // Show QR code on screen
        _uiState.postValue(UiState.ShowingQR(qrResp.qrData, slot))
        _statusText.postValue("Scan QR with MercadoPago App")

        android.util.Log.i("MainViewModel", "QR Payment Created: ${qrResp.id}, showing QR code")

        // Poll for status
        val timeoutSeconds = qrResp.timeoutSeconds ?: 120
        val finalStatus = paymentRepository.pollQrStatus(qrResp.id, timeoutSeconds)

        when (finalStatus) {
            "approved" -> {
                _statusText.postValue("Payment Approved! Dispensing...")
                _uiState.postValue(UiState.Dispensing(slot)) // Change UI to show dispensing animation
                awaitingDispenseResult = true
                sendMdbCommand("APPROVE")
                android.util.Log.i("MainViewModel", "QR approved, sent APPROVE, waiting for VMC dispense result...")

                // Start timeout for VMC response (30s). If VMC never sends VEND_SUCCESS/FAILURE
                // (e.g. empty slot, motor stuck), force-fail and start refund flow — same path
                // as the card flow uses.
                startTimeoutWithCountdown(30_000L) {
                    android.util.Log.w("MainViewModel", "QR: VMC dispense timeout - no VEND_SUCCESS/FAILURE received")
                    if (awaitingDispenseResult) {
                        awaitingDispenseResult = false
                        val paymentId = currentPaymentId ?: ""

                        viewModelScope.launch {
                            _statusText.postValue("Dispense timeout - initiating refund...")
                            _uiState.postValue(UiState.VoidProcessing(paymentId))

                            // Push VMC out of the stuck VEND state by emitting END_SESSION (POLL 0x04).
                            // MDB only allows the master (VMC) to issue RESET; the best the slave can
                            // do is close the session, after which VMC normally returns to idle / sends
                            // a fresh BEGIN_SESSION cycle on next READER/ENABLE.
                            android.util.Log.i("MainViewModel", "QR: Sending CANCEL_SESSION to unstick VMC after dispense timeout")
                            sendMdbCommand("CANCEL_SESSION")

                            // QR refund is fully server-side: POST /qr-pay-requests/{id}/dispense-result
                            // with success=false triggers qrService.HandleDispenseResult which reverses
                            // the MercadoPago payment via the saga. We only need to call this and read
                            // the resulting message.
                            android.util.Log.i("MainViewModel", "QR: Reporting dispense failure for $paymentId — backend will auto-refund MP")
                            val result = paymentRepository.reportQrDispenseResult(paymentId, false, "VMC timeout - no response")
                            if (result == null) {
                                _uiState.postValue(UiState.VoidFailed("Error al reportar dispense failure", canRetry = false))
                                _statusText.postValue("Error en reintegro - contacte soporte")
                                delay(8000); resetToIdle(); return@launch
                            }

                            android.util.Log.i("MainViewModel", "QR: dispense-result response status=${result.status} message=${result.message}")
                            // saga_state="reversed" = refund completed; otherwise saga is in progress
                            _uiState.postValue(UiState.VoidSuccess("Reintegro iniciado. ${result.message ?: ""}".trim()))
                            _statusText.postValue(result.message ?: "Reintegro de QR iniciado")
                            delay(6000); resetToIdle()
                        }
                    }
                }
            }
            else -> {
                _statusText.postValue("QR Payment Failed: $finalStatus")
                sendMdbCommand("DENY")
                delay(3000)
                resetToIdle()
            }
        }
    }

    /**
     * Called when MDB reports dispense result (VEND_SUCCESS or VEND_FAILURE)
     */
    fun onMdbDispenseResult(success: Boolean) {
        if (!awaitingDispenseResult) return
        awaitingDispenseResult = false

        // Cancel the dispense timeout since VMC responded
        cancelTimeout()

        val paymentId = currentPaymentId ?: return
        val slot = _currentSlot.value

        viewModelScope.launch {
            if (success) {
                android.util.Log.i("MainViewModel", "Dispense SUCCESS for payment: $paymentId")

                // Show success state with animation
                _uiState.postValue(slot?.let { UiState.DispenseSuccess(it) } ?: UiState.Idle)
                _statusText.postValue("¡Producto dispensado!")

                // Report success to backend
                paymentRepository.reportDispenseResult(paymentId, true)

                // Show success for 5 seconds then return to idle
                delay(TimeoutConfig.successDisplayMs)
                resetToIdle()
            } else {
                android.util.Log.e("MainViewModel", "Dispense FAILED for payment: $paymentId")

                // Show failure state
                _uiState.postValue(slot?.let { UiState.DispenseFailed(it, "Dispense failed") } ?: UiState.Idle)
                _statusText.postValue("Error al dispensar")

                // Report failure to backend (triggers void/refund saga)
                val result = paymentRepository.reportDispenseResult(paymentId, false, "MDB dispense failure")

                if (result?.needsVoid == true) {
                    // Automatically initiate void/refund (card payment)
                    delay(2000) // Brief pause to show error
                    android.util.Log.i("MainViewModel", "Void required for payment: $paymentId - initiating automatically")

                    _uiState.postValue(UiState.VoidProcessing(paymentId))
                    _statusText.postValue("Iniciando reintegro... Acerque su tarjeta")

                    // Initiate void with backend
                    val voidResponse = paymentRepository.initiateVoid()

                    if (voidResponse == null) {
                        android.util.Log.e("MainViewModel", "Failed to initiate void")
                        _uiState.postValue(UiState.VoidFailed("Error al iniciar reintegro", canRetry = false))
                        _statusText.postValue("Error en reintegro - contacte soporte")
                        delay(8000)
                        resetToIdle()
                        return@launch
                    }

                    android.util.Log.i("MainViewModel", "Void initiated: ${voidResponse.voidId}, status: ${voidResponse.status}")

                    // Check if void already errored
                    if (voidResponse.status == "error") {
                        _uiState.postValue(UiState.VoidFailed(voidResponse.error ?: "Error en reintegro", canRetry = false))
                        _statusText.postValue("Error en reintegro - contacte soporte")
                        delay(8000)
                        resetToIdle()
                        return@launch
                    }

                    // Poll for void completion
                    val voidResult = paymentRepository.pollVoidStatus(voidResponse.voidId, timeoutSeconds = 120)

                    when (voidResult) {
                        "completed" -> {
                            _uiState.postValue(UiState.VoidSuccess("¡Reintegro exitoso!"))
                            _statusText.postValue("¡Reintegro completado!")
                            android.util.Log.i("MainViewModel", "Void completed successfully")
                            delay(5000)
                            resetToIdle()
                        }
                        "error" -> {
                            _uiState.postValue(UiState.VoidFailed("Error en reintegro. Contacte soporte.", canRetry = false))
                            _statusText.postValue("Error en reintegro - contacte soporte")
                            android.util.Log.e("MainViewModel", "Void failed with error")
                            delay(8000)
                            resetToIdle()
                        }
                        "timeout" -> {
                            _uiState.postValue(UiState.VoidFailed("Timeout en reintegro. Contacte soporte.", canRetry = false))
                            _statusText.postValue("Timeout en reintegro - contacte soporte")
                            android.util.Log.e("MainViewModel", "Void timeout")
                            delay(8000)
                            resetToIdle()
                        }
                    }
                } else {
                    // No void needed (QR payment or backend handled it)
                    _statusText.postValue("Procesando reintegro...")
                    delay(5000)
                    resetToIdle()
                }
            }
        }
    }

    /**
     * User initiated void/refund from the void pending screen
     */
    fun onInitiateVoid() {
        val paymentId = currentPaymentId ?: return

        // Cancel void pending timeout - user initiated action
        cancelTimeout()

        viewModelScope.launch {
            _uiState.postValue(UiState.VoidProcessing(paymentId))
            _statusText.postValue("Processing refund... Tap your card")

            // Initiate void with backend
            val voidResponse = paymentRepository.initiateVoid()

            if (voidResponse == null) {
                android.util.Log.e("MainViewModel", "Failed to initiate void")
                _uiState.postValue(UiState.VoidFailed("Failed to start refund", canRetry = true))
                _statusText.postValue("Refund failed - try again")
                return@launch
            }

            android.util.Log.i("MainViewModel", "Void initiated: ${voidResponse.voidId}, status: ${voidResponse.status}")

            // Check if void already errored
            if (voidResponse.status == "error") {
                _uiState.postValue(UiState.VoidFailed(voidResponse.error ?: "Refund failed", canRetry = true))
                _statusText.postValue("Refund failed - try again")
                return@launch
            }

            // Poll for void completion
            val voidResult = paymentRepository.pollVoidStatus(voidResponse.voidId, timeoutSeconds = 120)

            when (voidResult) {
                "completed" -> {
                    _uiState.postValue(UiState.VoidSuccess("Refund successful!"))
                    _statusText.postValue("Refund completed!")
                    android.util.Log.i("MainViewModel", "Void completed successfully")
                    delay(5000)
                    resetToIdle()
                }
                "error" -> {
                    _uiState.postValue(UiState.VoidFailed("Refund failed. Contact support.", canRetry = false))
                    _statusText.postValue("Refund failed - contact support")
                    android.util.Log.e("MainViewModel", "Void failed with error")
                    delay(8000)
                    resetToIdle()
                }
                "timeout" -> {
                    _uiState.postValue(UiState.VoidFailed("Refund timed out. Try again.", canRetry = true))
                    _statusText.postValue("Refund timed out")
                    android.util.Log.w("MainViewModel", "Void timed out")
                }
            }
        }
    }

    /**
     * Cancel the void pending screen and return to idle
     */
    fun onVoidCancelled() {
        android.util.Log.i("MainViewModel", "Void cancelled by user")
        cancelTimeout()
        resetToIdle()
    }

    private fun resetToIdle() {
        cancelTimeout()
        currentPaymentId = null
        awaitingDispenseResult = false
        isPaymentInProgress = false
        paymentJob = null
        _currentSlot.value = null
        employeeDiscountPercent = 0
        employeeName = null
        _employeeStatus.postValue(null)
        _uiState.value = UiState.Idle
        _statusText.value = "Ready"
    }

    private fun cancelPayment() {
        cancelTimeout()
        _statusText.value = "Payment cancelled"
        sendMdbCommand("DENY")
        resetToIdle()
    }

    fun onPaymentCancelled() {
        val paymentId = currentPaymentId  // Capture before reset
        paymentJob?.cancel()              // Cancel polling coroutine
        paymentJob = null
        cancelTimeout()
        _statusText.value = "Payment cancelled"
        sendMdbCommand("DENY")

        // Notify backend to cancel POSLink transaction (fire-and-forget)
        if (paymentId != null) {
            viewModelScope.launch {
                paymentRepository.cancelCardPayment(paymentId)
            }
        }

        resetToIdle()
    }

    private fun sendMdbCommand(command: String) {
        val intent = Intent("xyz.mdblab.z80.VM_COMMAND")
        intent.setPackage("xyz.mdblab.z80")
        if (command == "APPROVE") {
            // Scale price to MDB unit using current config (scale + decimal places).
            // VMC max-min-prices arrived earlier; we use the slot price as-is for now and let
            // MDB layer handle scaling. The service falls back to slave.session's stored amount
            // if 0, so this is best-effort.
            val priceScaled = _currentSlot.value?.price ?: 0
            intent.putExtra("command", "TRADE_RESULT")
            intent.putExtra("approved", true)
            intent.putExtra("amount", priceScaled)
        } else if (command == "DENY") {
            intent.putExtra("command", "TRADE_RESULT")
            intent.putExtra("approved", false)
        } else {
            intent.putExtra("command", command)
        }
        getApplication<Application>().sendBroadcast(intent)
        android.util.Log.i("MainViewModel", "Sent MDB Command: $command")
    }

    fun onEmployeeLoginClicked() {
        val currentState = _uiState.value
        if (currentState is UiState.ProductSelected) {
            _uiState.value = UiState.EmployeeLogin(currentState.slot)
        }
    }

    fun submitEmployeePIN(pin: String) {
        val currentState = _uiState.value
        if (currentState !is UiState.EmployeeLogin) return
        val slot = currentState.slot

        viewModelScope.launch {
            _employeeStatus.postValue("Validando...")
            val response = paymentRepository.validateEmployeePIN(pin, "test-machine-01")

            if (response == null) {
                _employeeStatus.postValue("Error de conexión")
                kotlinx.coroutines.delay(2000)
                _employeeStatus.postValue(null)
                return@launch
            }

            if (!response.valid) {
                _employeeStatus.postValue(response.message ?: "PIN inválido")
                kotlinx.coroutines.delay(2000)
                _employeeStatus.postValue(null)
                return@launch
            }

            // PIN valid — store discount and go back to ProductSelected
            employeeDiscountPercent = response.discountPercent ?: 0
            employeeName = response.employeeName
            _employeeStatus.postValue(null)

            android.util.Log.i("MainViewModel", "Employee ${response.employeeName} logged in. Discount: ${employeeDiscountPercent}%")

            // Return to ProductSelected with discount applied
            _uiState.postValue(UiState.ProductSelected(slot))
        }
    }

    fun getActiveDiscount(): Int = employeeDiscountPercent
    fun getEmployeeName(): String? = employeeName
    
    fun onTimeout() {
        cancelTimeout()
        // If we were in ProductSelected state (waiting for payment), deny the vend
        if (_uiState.value is UiState.ProductSelected) {
            sendMdbCommand("DENY")
        }
        resetToIdle()
    }

    private fun downloadPromoVideo(url: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                android.util.Log.i("MainViewModel", "Downloading promo video: $url")
                val videoFile = java.io.File(getApplication<android.app.Application>().filesDir, "promo_video.mp4")

                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.connect()

                if (connection.responseCode == 200) {
                    val tmpFile = java.io.File(videoFile.parent, "promo_video.tmp")
                    connection.inputStream.use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tmpFile.renameTo(videoFile)
                    android.util.Log.i("MainViewModel", "Promo video downloaded: ${videoFile.length()} bytes")
                    _promoVideoPath.postValue(videoFile.absolutePath)
                } else {
                    android.util.Log.e("MainViewModel", "Promo video download failed: ${connection.responseCode}")
                }
                connection.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Promo video download error", e)
            }
        }
    }
}

